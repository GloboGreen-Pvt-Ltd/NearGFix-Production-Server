package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repair booking PIN (Partner App "Submit Booking"): finds which ACTIVE member of
 * the caller's shop (or the signed-in owner) owns the typed PIN, reusing the
 * Employee App PIN hashes in employee_pins, and returns a short-lived signed
 * booking token. ticket-service records "Booked By" only from that token.
 *
 * The token is signed with a key derived from the JWT secret plus a fixed
 * suffix, so it can never be used as a login/access token (and vice versa).
 * PINs are never logged, stored or returned.
 */
@Service
public class BookingPinService {

    public static final String KEY_SUFFIX = ":booking-pin";
    static final long TOKEN_MINUTES = 5;
    static final int MAX_FAILS = 5;
    static final long LOCK_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey key;
    /** Wrong-PIN counter per signed-in caller: [fails, lockedUntilMillis]. */
    private final Map<UUID, long[]> fails = new ConcurrentHashMap<>();

    public BookingPinService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, @Value("${app.jwt.secret}") String secret) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.key = Keys.hmacShaKeyFor((secret + KEY_SUFFIX).getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> verify(UUID callerId, UUID shopId, List<String> callerRoles, String pin) {
        if (shopId == null) throw new ForbiddenException("Select a shop first.");
        if (pin == null || !pin.matches("\\d{6}")) throw new BadRequestException("Enter the 6-digit PIN.");
        long now = System.currentTimeMillis();
        long[] f = fails.get(callerId);
        if (f != null && f[1] > now) throw new ForbiddenException("Too many wrong PINs. Try again in a few minutes.");

        boolean callerIsOwner = callerRoles != null && callerRoles.stream().anyMatch(r -> r != null && r.toUpperCase().contains("OWNER"));
        // Active staff of THIS shop with a PIN; the owner only as themselves (signed in).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.name, p.pin_hash, p.locked_until FROM users u JOIN employee_pins p ON p.user_id = u.id "
                        + "WHERE u.is_active = TRUE AND ((u.shop_id = ? AND UPPER(COALESCE(u.role, '')) NOT IN ('CUSTOMER', 'SUPER_ADMIN')) "
                        + "OR (? AND u.id = ?))",
                shopId, callerIsOwner, callerId);
        List<Map<String, Object>> match = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (passwordEncoder.matches(pin, (String) r.get("pin_hash"))) match.add(r);
        }
        if (match.isEmpty()) {
            long[] next = (f == null || f[1] != 0) ? new long[]{0, 0} : f;
            next[0]++;
            if (next[0] >= MAX_FAILS) { next[0] = 0; next[1] = now + LOCK_MINUTES * 60_000; }
            fails.put(callerId, next);
            throw new UnauthorizedException("Invalid PIN or employee not active in this shop.");
        }
        if (match.size() > 1) {
            throw new BadRequestException("This PIN is not unique in the shop. Ask the employee to change their PIN in the Employee App.");
        }
        fails.remove(callerId);
        Map<String, Object> e = match.get(0);
        if (e.get("locked_until") instanceof Timestamp ts && ts.toInstant().isAfter(Instant.now()))
            throw new ForbiddenException("This employee's PIN is locked. Try again later.");

        UUID empId = (UUID) e.get("id");
        String name = e.get("name") == null ? "Employee" : String.valueOf(e.get("name"));
        String role = callerIsOwner && empId.equals(callerId) ? "OWNER" : "EMPLOYEE";
        Instant exp = Instant.now().plus(TOKEN_MINUTES, ChronoUnit.MINUTES);
        String token = Jwts.builder()
                .subject(empId.toString())
                .claim("typ", "booking_pin")
                .claim("shopId", shopId.toString())
                .claim("by", callerId.toString())
                .claim("name", name)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", true);
        out.put("employeeName", name);
        out.put("role", role);
        out.put("token", token);
        out.put("expiresAt", exp.toString());
        return out;
    }
}
