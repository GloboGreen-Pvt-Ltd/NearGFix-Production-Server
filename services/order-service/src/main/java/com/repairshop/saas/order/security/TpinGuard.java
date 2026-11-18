package com.repairshop.saas.order.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Consumes a one-time TPIN authorization issued by auth-service
 * (/auth/security/tpin/authorize, migration 123). Call inside the same
 * transaction as the protected write so consumption is atomic with it.
 * Header: X-TPIN-Authorization. Enforced when app.security.tpin-enforced=true.
 */
@Component
@RequiredArgsConstructor
public class TpinGuard {

    public static final String HEADER = "X-TPIN-Authorization";

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    @Value("${app.security.tpin-enforced:false}")
    private boolean enforced;

    /** Returns the consumed authorization id, or null when not enforced and no header was sent. */
    public UUID consume(HttpServletRequest req, String actionType, String targetId, String contextHash) {
        String auth = req.getHeader(HEADER);
        if (auth == null || auth.isBlank()) {
            if (enforced) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TPIN_REQUIRED");
            return null;
        }
        String bearer = req.getHeader("Authorization");
        String sid = null;
        if (bearer != null && bearer.startsWith("Bearer ")) {
            try { Claims c = jwtService.parseClaims(bearer.substring(7)); sid = c.get("sid", String.class); }
            catch (Exception ignored) { }
        }
        if (sid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        List<UUID> ids = jdbc.queryForList("""
                UPDATE auth_transaction_authorizations a SET consumed_at = now()
                 WHERE a.auth_token_hash = ? AND a.session_id = ? AND a.action_type = ?
                   AND a.context_hash = ? AND a.target_id IS NOT DISTINCT FROM ?
                   AND a.consumed_at IS NULL AND a.revoked_at IS NULL AND a.expires_at > now()
                   AND EXISTS (SELECT 1 FROM auth_device_sessions s WHERE s.id = a.session_id
                               AND s.revoked_at IS NULL AND s.otp_expires_at > now())
                   AND (a.actor_type <> 'EMPLOYEE' OR a.action_type IN ('CHECK_IN','CHECK_OUT') OR EXISTS (
                        SELECT 1 FROM technician_attendance ta JOIN technicians t ON t.id = ta.technician_id
                         WHERE t.user_id = a.actor_id AND ta.check_in_at IS NOT NULL AND ta.check_out_at IS NULL
                           AND ta.check_in_at > now() - interval '20 hours'))
                   AND a.credential_version = (SELECT c.credential_version FROM auth_pin_credentials c
                        WHERE c.principal_type = a.actor_type AND c.principal_id = a.actor_id AND c.pin_type = 'TPIN'
                        ORDER BY c.updated_at DESC LIMIT 1)
                RETURNING a.id""", UUID.class, sha256(auth.trim()), UUID.fromString(sid), actionType, contextHash, targetId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TPIN authorization invalid, expired or already used");
        jdbc.update("""
                INSERT INTO auth_security_audit_logs (principal_type, principal_id, session_id, event_type, action_type, target_id, result)
                SELECT actor_type, actor_id, session_id, 'TPIN_CONSUMED', action_type, target_id, 'SUCCESS'
                  FROM auth_transaction_authorizations WHERE id = ?""", ids.get(0));
        return ids.get(0);
    }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
