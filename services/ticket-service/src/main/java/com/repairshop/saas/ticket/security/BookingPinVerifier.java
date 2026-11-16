package com.repairshop.saas.ticket.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Checks the booking-PIN token from auth-service (POST /auth/booking-pin/verify)
 * at the final "Submit Booking": signature (derived key, never an access token),
 * expiry, same shop, issued to this signed-in caller, and the employee is still
 * active in the shop (or is the signed-in owner). "Booked By" comes only from
 * here, never from a client-supplied employee id.
 */
@Component
public class BookingPinVerifier {

    /** Must match auth-service BookingPinService.KEY_SUFFIX. */
    private static final String KEY_SUFFIX = ":booking-pin";

    private final SecretKey key;
    private final JdbcTemplate jdbc;

    public BookingPinVerifier(@Value("${app.jwt.secret}") String secret, JdbcTemplate jdbc) {
        this.key = Keys.hmacShaKeyFor((secret + KEY_SUFFIX).getBytes(StandardCharsets.UTF_8));
        this.jdbc = jdbc;
    }

    public record BookedBy(UUID userId, String name, String role) {}

    public BookedBy verify(String token, UUID shopId, UUID callerId) {
        Claims c;
        try {
            c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw deny("PIN verification expired. Verify the PIN again.");
        }
        if (!"booking_pin".equals(c.get("typ", String.class))) throw deny("Invalid PIN verification.");
        if (shopId == null || !shopId.toString().equals(c.get("shopId", String.class))) throw deny("PIN verification is for another shop.");
        if (callerId == null || !callerId.toString().equals(c.get("by", String.class))) throw deny("PIN verification is for another login.");
        UUID empId = UUID.fromString(c.getSubject());
        String role = "OWNER".equals(c.get("role", String.class)) ? "OWNER" : "EMPLOYEE";
        // Re-check at submit: still active, still in this shop (owner = the caller).
        List<String> names = jdbc.queryForList(
                "SELECT name FROM users WHERE id = ? AND is_active = TRUE AND (shop_id = ? OR (? AND id = ?))",
                String.class, empId, shopId, "OWNER".equals(role), callerId);
        if (names.isEmpty()) throw deny("This employee is no longer active in this shop.");
        String name = names.get(0) != null ? names.get(0) : c.get("name", String.class);
        return new BookedBy(empId, name, role);
    }

    private static ResponseStatusException deny(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
