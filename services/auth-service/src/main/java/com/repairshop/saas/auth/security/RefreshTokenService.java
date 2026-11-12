package com.repairshop.saas.auth.security;

import com.repairshop.saas.auth.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Opaque, rotating refresh tokens (table auth_refresh_tokens, migration 116).
 * The client gets 256 random bits; only their SHA-256 is stored. Each refresh
 * revokes the presented token and issues a new one in the same family. A token
 * presented again after rotation (outside a short grace window for parallel
 * client requests) revokes the whole family — the session must log in again.
 *
 * Runs inside the caller's transaction (no REQUIRES_NEW: that would hold a
 * second pooled connection per login). If migration 116 has not been applied
 * yet, issuing is skipped (login still works, just without a refresh token).
 */
@Slf4j
@Service
public class RefreshTokenService {

    /** A rotated token replayed within this window is a client race, not theft. */
    private static final long REUSE_GRACE_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final long ttlMs;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(JdbcTemplate jdbc,
                               @Value("${app.jwt.refresh-expiry-ms:7776000000}") long ttlMs) {
        this.jdbc = jdbc;
        this.ttlMs = ttlMs;
    }

    public long getTtlMs() { return ttlMs; }

    /** What a refresh token is bound to; the access token is re-minted from this. */
    public record Grant(UUID userId, UUID shopId, String loginScope, UUID familyId) {}

    private volatile boolean tableReady;
    private volatile long tableCheckedAt;

    /** True once auth_refresh_tokens exists; re-checked at most every 5 minutes while missing. */
    private boolean tableReady() {
        if (tableReady) return true;
        long now = System.currentTimeMillis();
        if (now - tableCheckedAt < 300_000 && tableCheckedAt != 0) return false;
        tableCheckedAt = now;
        try {
            tableReady = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('auth_refresh_tokens') IS NOT NULL", Boolean.class));
        } catch (Exception e) {
            tableReady = false;
        }
        if (!tableReady) log.warn("[auth] auth_refresh_tokens missing (migration 116): refresh tokens disabled");
        return tableReady;
    }

    /** Returns null when refresh tokens are unavailable (table missing). */
    @Transactional
    public String issue(UUID userId, UUID shopId, String loginScope, UUID familyId) {
        if (!tableReady()) return null;
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO auth_refresh_tokens (id, token_hash, family_id, user_id, shop_id, login_scope, expires_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), hash(token), familyId != null ? familyId : UUID.randomUUID(), userId, shopId,
                loginScope != null ? loginScope : "OWNER", Timestamp.from(Instant.now().plusMillis(ttlMs)));
        // Housekeeping: drop this user's long-dead rows so the table stays small.
        jdbc.update("DELETE FROM auth_refresh_tokens WHERE user_id = ? AND expires_at < now() - interval '7 days'", userId);
        return token;
    }

    /** Validates and revokes the presented token; the caller issues its successor. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Grant consume(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Refresh token is required");
        if (!tableReady()) throw new UnauthorizedException("Invalid refresh token");
        List<Object[]> rows = jdbc.query(
                "SELECT user_id, shop_id, login_scope, family_id, expires_at, revoked_at FROM auth_refresh_tokens WHERE token_hash = ? FOR UPDATE",
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getTimestamp(5), rs.getTimestamp(6)},
                hash(token.trim()));
        if (rows.isEmpty()) throw new UnauthorizedException("Invalid refresh token");
        Object[] r = rows.get(0);
        UUID family = (UUID) r[3];
        Timestamp revokedAt = (Timestamp) r[5];
        if (((Timestamp) r[4]).toInstant().isBefore(Instant.now()))
            throw new UnauthorizedException("Refresh token expired");
        if (revokedAt != null) {
            // Within the grace window a replay is a lost response or a parallel
            // request: honour it (the family is still live) instead of logging out.
            boolean familyLive = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM auth_refresh_tokens WHERE family_id = ? AND revoked_at IS NULL)",
                    Boolean.class, family));
            if (familyLive && System.currentTimeMillis() - revokedAt.getTime() <= REUSE_GRACE_MS)
                return new Grant((UUID) r[0], (UUID) r[1], (String) r[2], family);
            if (familyLive) {
                log.warn("[auth] refresh token reuse detected; revoking family {}", family);
                revokeFamily(family);
            }
            throw new UnauthorizedException("Refresh token already used");
        }
        jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE token_hash = ?", hash(token.trim()));
        return new Grant((UUID) r[0], (UUID) r[1], (String) r[2], family);
    }

    /** Logout: revoke the presented token's whole family. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank() || !tableReady()) return;
        jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL AND family_id = "
                + "(SELECT family_id FROM auth_refresh_tokens WHERE token_hash = ?)", hash(token.trim()));
    }

    /** Password change / reset / deactivation: end every session of this user. */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        if (userId == null || !tableReady()) return;
        jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    /** Shop-mobile password change: end that shop's shop-login sessions. */
    @Transactional
    public void revokeShopScope(UUID shopId) {
        if (shopId == null || !tableReady()) return;
        jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE shop_id = ? AND login_scope = 'SHOP' AND revoked_at IS NULL", shopId);
    }

    private void revokeFamily(UUID family) {
        jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL", family);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
