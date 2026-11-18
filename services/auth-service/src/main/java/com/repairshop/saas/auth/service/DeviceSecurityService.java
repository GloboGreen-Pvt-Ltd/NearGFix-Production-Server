package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ConflictException;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Banking-style session + PIN security (migration 123). Server-enforced:
 * single active device per account, refresh rotation capped at the OTP
 * deadline, Argon2id+pepper MPIN/TPIN, one-time TPIN transaction authorization,
 * employee attendance gating and security audit. Raw PINs/tokens are never stored or logged.
 */
@Service
public class DeviceSecurityService {

    public record Principal(String type, UUID id, String app, int otpDays, int lockSeconds, UUID shopId, Claims claims) {}
    public record Session(UUID id, Principal p) {}

    private static final int MAX_PIN_FAILS = 5;
    private static final Duration PIN_LOCK = Duration.ofMinutes(15);
    private static final Duration FRESH_OTP = Duration.ofMinutes(10);
    private static final Duration TXN_TTL = Duration.ofMinutes(2);
    private static final Set<String> ATTENDANCE_ACTIONS = Set.of("CHECK_IN", "CHECK_OUT");

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final String pepper;
    private final SecureRandom rng = new SecureRandom();
    private final Argon2PasswordEncoder argon = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public DeviceSecurityService(JdbcTemplate jdbc, JwtService jwt, @Value("${app.security.pin-pepper:}") String pepper) {
        this.jdbc = jdbc; this.jwt = jwt; this.pepper = pepper;
    }

    // ---------------------------------------------------------------- principal

    Principal principal(Claims c) {
        List<?> roles = c.get("roles", List.class);
        Set<String> r = new HashSet<>();
        if (roles != null) roles.forEach(x -> r.add(String.valueOf(x).toUpperCase().replace("ROLE_", "")));
        UUID id = UUID.fromString(c.getSubject());
        String shop = c.get("shopId", String.class);
        UUID shopId = shop == null ? null : UUID.fromString(shop);
        if (r.stream().anyMatch(x -> x.startsWith("CUSTOMER"))) return new Principal("CUSTOMER", id, "CUSTOMER", 30, 600, null, c);
        if ("SHOP".equals(c.get("loginScope", String.class))) return new Principal("SHOP_MOBILE", id, "PARTNER", 15, 300, shopId, c);
        if (r.contains("TECHNICIAN") || r.contains("STAFF") || r.contains("PICKUP_PERSON")) return new Principal("EMPLOYEE", id, "EMPLOYEE", 15, 120, shopId, c);
        return new Principal("SHOP_OWNER", id, "PARTNER", 15, 300, shopId, c);
    }

    private Claims parse(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) throw new UnauthorizedException("Missing or invalid Authorization header");
        try { return jwt.parseClaims(bearer.substring(7).trim()); }
        catch (JwtException | IllegalArgumentException e) { throw new UnauthorizedException("SESSION_EXPIRED"); }
    }

    /** Fresh OTP-login token (existing /auth/login, /auth/shop-login, /auth/customer-login) = OTP proof. */
    private Claims freshOtpClaims(String token) {
        Claims c;
        try { c = jwt.parseClaims(token); } catch (JwtException | IllegalArgumentException e) { throw new UnauthorizedException("OTP_REQUIRED"); }
        if (c.get("sid") != null || c.get("np") != null || c.getIssuedAt() == null
                || c.getIssuedAt().toInstant().isBefore(Instant.now().minus(FRESH_OTP)))
            throw new UnauthorizedException("OTP_REQUIRED");
        return c;
    }

    /** Validates a session-bound access token against the server session row. */
    @Transactional
    public Session requireSession(String bearer) {
        Claims c = parse(bearer);
        String sid = c.get("sid", String.class);
        if (sid == null) throw new UnauthorizedException("SESSION_REQUIRED");
        Principal p = principal(c);
        int n = jdbc.update("""
                UPDATE auth_device_sessions SET last_active_at = now()
                 WHERE id = ? AND principal_type = ? AND principal_id = ? AND revoked_at IS NULL AND otp_expires_at > now()""",
                UUID.fromString(sid), p.type(), p.id());
        if (n == 0) throw new UnauthorizedException(replaced(UUID.fromString(sid)) ? "SESSION_REPLACED" : "SESSION_EXPIRED");
        return new Session(UUID.fromString(sid), p);
    }

    // ---------------------------------------------------------------- sessions

    @Transactional
    public Map<String, Object> startSession(String otpLoginToken, String deviceId, String deviceName, String platform,
                                            String publicKey, boolean replace) {
        if (deviceId == null || deviceId.isBlank()) throw new BadRequestException("deviceId is required");
        Principal p = principal(freshOtpClaims(otpLoginToken));
        String devHash = sha256(p.type() + ":" + deviceId);

        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT id, device_id_hash, device_name, platform, last_active_at FROM auth_device_sessions
                 WHERE principal_type = ? AND principal_id = ? AND app_type = ? AND revoked_at IS NULL FOR UPDATE""",
                p.type(), p.id(), p.app());
        if (!active.isEmpty()) {
            Map<String, Object> old = active.get(0);
            boolean sameDevice = devHash.equals(old.get("device_id_hash"));
            // Employees never replace a trusted device on their own (owner approval via MPIN login).
            if (!sameDevice && (!replace || "EMPLOYEE".equals(p.type()))) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("status", "DEVICE_LIMIT");
                res.put("deviceName", old.get("device_name"));
                res.put("platform", old.get("platform"));
                res.put("lastActiveAt", old.get("last_active_at"));
                return res;
            }
            revokeSession((UUID) old.get("id"), sameDevice ? "RELOGIN" : "DEVICE_REPLACED");
            audit(p, (UUID) old.get("id"), sameDevice ? "SESSION_RELOGIN" : "DEVICE_REPLACED", null, null, null, "SUCCESS", null);
        }

        Instant now = Instant.now(), deadline = now.plus(Duration.ofDays(p.otpDays()));
        UUID sid = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO auth_device_sessions (id, principal_type, principal_id, app_type, device_id_hash,
                        device_public_key, device_name, platform, otp_verified_at, otp_expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    sid, p.type(), p.id(), p.app(), devHash, publicKey, trim(deviceName, 150), trim(platform, 20),
                    Timestamp.from(now), Timestamp.from(deadline));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Another login is in progress. Please retry.");
        }
        if (!"CUSTOMER".equals(p.type()))
            jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", p.id());
        String refresh = newRefreshToken(sid, null, deadline);
        audit(p, sid, "OTP_LOGIN", null, null, null, "SUCCESS", null);

        Map<String, Object> res = sessionBody(p, sid, deadline);
        res.put("status", "ACTIVE");
        res.put("accessToken", jwt.issueSessionAccessToken(p.claims(), sid, Date.from(deadline)));
        res.put("refreshToken", refresh);
        return res;
    }

    /** Rotates the refresh token. Reuse of a rotated token revokes the whole session. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Map<String, Object> refresh(String refreshToken, String expiredAccessToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new UnauthorizedException("SESSION_EXPIRED");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.session_id, t.rotated_at, t.revoked_at, t.expires_at, s.revoked_at AS s_revoked,
                       s.otp_expires_at, s.principal_type, s.principal_id
                  FROM auth_session_refresh_tokens t JOIN auth_device_sessions s ON s.id = t.session_id
                 WHERE t.token_hash = ? FOR UPDATE OF t, s""", sha256(refreshToken));
        if (rows.isEmpty()) throw new UnauthorizedException("SESSION_EXPIRED");
        Map<String, Object> t = rows.get(0);
        UUID sid = (UUID) t.get("session_id");
        if (t.get("s_revoked") != null && replaced(sid)) throw new UnauthorizedException("SESSION_REPLACED");
        if (t.get("rotated_at") != null || t.get("revoked_at") != null) {
            if (t.get("s_revoked") == null) revokeSession(sid, "REFRESH_REUSE");
            jdbc.update("""
                    INSERT INTO auth_security_audit_logs (principal_type, principal_id, session_id, event_type, result, reason_code)
                    VALUES (?,?,?,'REFRESH_REUSE','DENIED','TOKEN_REUSE')""", t.get("principal_type"), t.get("principal_id"), sid);
            throw new UnauthorizedException("SESSION_REVOKED");
        }
        Instant deadline = ((Timestamp) t.get("otp_expires_at")).toInstant();
        if (t.get("s_revoked") != null || !deadline.isAfter(Instant.now())
                || !((Timestamp) t.get("expires_at")).toInstant().isAfter(Instant.now()))
            throw new UnauthorizedException("SESSION_EXPIRED");

        Claims base = claimsAllowExpired(expiredAccessToken);
        Principal p = principal(base);
        if (!sid.toString().equals(base.get("sid", String.class)) || !p.id().equals(t.get("principal_id")))
            throw new UnauthorizedException("SESSION_EXPIRED");

        jdbc.update("UPDATE auth_session_refresh_tokens SET rotated_at = now() WHERE id = ?", t.get("id"));
        String next = newRefreshToken(sid, (UUID) t.get("id"), deadline);
        jdbc.update("UPDATE auth_device_sessions SET last_active_at = now() WHERE id = ?", sid);

        Map<String, Object> res = sessionBody(p, sid, deadline);
        res.put("accessToken", jwt.issueSessionAccessToken(base, sid, Date.from(deadline)));
        res.put("refreshToken", next);
        return res;
    }

    @Transactional
    public Map<String, Object> status(String bearer) {
        Session s = requireSession(bearer);
        Timestamp deadline = jdbc.queryForObject("SELECT otp_expires_at FROM auth_device_sessions WHERE id = ?", Timestamp.class, s.id());
        return sessionBody(s.p(), s.id(), deadline.toInstant());
    }

    @Transactional
    public void logout(String bearer) {
        Session s = requireSession(bearer);
        revokeSession(s.id(), "LOGOUT");
        audit(s.p(), s.id(), "LOGOUT", null, null, null, "SUCCESS", null);
    }

    @Transactional
    public void biometricPreference(String bearer, boolean enabled) {
        Session s = requireSession(bearer);
        audit(s.p(), s.id(), enabled ? "BIOMETRIC_ENABLED" : "BIOMETRIC_DISABLED", null, null, null, "SUCCESS", null);
    }

    // ---------------------------------------------------------------- PINs

    @Transactional
    public Map<String, Object> createPin(String bearer, String pinType, String pin) {
        Session s = requireSession(bearer);
        String type = pinType(pinType, s.p());
        validatePin(pin);
        try {
            jdbc.update("""
                    INSERT INTO auth_pin_credentials (principal_type, principal_id, app_type, pin_type, pin_hash)
                    VALUES (?,?,?,?,?)""", s.p().type(), s.p().id(), credApp(s.p(), type), type, hash(s.p(), type, pin));
        } catch (DuplicateKeyException e) {
            throw new ConflictException(type + " already set. Use Reset " + type + ".");
        }
        audit(s.p(), s.id(), type + "_SETUP", null, null, null, "SUCCESS", null);
        return Map.of("pinType", type, "created", true);
    }

    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> verifyMpin(String bearer, String pin) {
        Session s = requireSession(bearer);
        checkPin(s, "MPIN", pin);
        return Map.of("verified", true);
    }

    /**
     * Employee changes their OWN login MPIN (no OTP). Identity comes only from the
     * device-session bearer; the current MPIN is verified with the shared lockout
     * (5 wrong → 15 min). The new MPIN must be valid and differ from the current one.
     * On success: new Argon2 hash, credential_version+1, and every employee session
     * is revoked so the employee must log in again with the new MPIN.
     * Owner set/reset (ownerSetEmployeeMpin) is unchanged.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> changeEmployeeMpin(String bearer, String currentMpin, String newMpin) {
        Session s = requireSession(bearer);
        Principal p = s.p();
        if (!"EMPLOYEE".equals(p.type())) throw new ForbiddenException("Only employees can change their login MPIN here");
        validatePin(newMpin);
        checkPin(s, "MPIN", currentMpin);   // wrong → attempt logged, lockout applies
        if (newMpin.equals(currentMpin)) throw new BadRequestException("New MPIN must be different from your current MPIN");
        int n = jdbc.update("""
                UPDATE auth_pin_credentials SET pin_hash = ?, credential_version = credential_version + 1,
                       failed_attempts = 0, locked_until = NULL, reset_at = now(), updated_at = now()
                 WHERE principal_type = 'EMPLOYEE' AND principal_id = ? AND app_type = 'EMPLOYEE' AND pin_type = 'MPIN'""",
                hash(p, "MPIN", newMpin), p.id());
        if (n != 1) throw new BadRequestException("MPIN_NOT_SET");
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id FROM auth_device_sessions WHERE principal_type = 'EMPLOYEE' AND principal_id = ? AND revoked_at IS NULL", p.id()))
            revokeSession((UUID) r.get("id"), "MPIN_CHANGED");
        audit(p, s.id(), "MPIN_CHANGED_BY_EMPLOYEE", null, null, null, "SUCCESS", null);
        return Map.of("changed", true, "reloginRequired", true);
    }

    /** OTP-verified reset: otpLoginToken must be a fresh existing-OTP-login token for the same account. */
    @Transactional
    public Map<String, Object> resetPin(String bearer, String pinType, String otpLoginToken, String newPin,
                                        String verificationMethod) {
        Session s = requireSession(bearer);
        Principal otp = principal(freshOtpClaims(otpLoginToken));
        if (!otp.id().equals(s.p().id()) || !otp.type().equals(s.p().type())) throw new ForbiddenException("OTP account mismatch");
        String type = pinType(pinType, s.p());
        validatePin(newPin);
        String method = verificationMethod == null ? "OTP" : trim(verificationMethod.toUpperCase(), 50);
        int n = jdbc.update("""
                UPDATE auth_pin_credentials SET pin_hash = ?, credential_version = credential_version + 1,
                       failed_attempts = 0, locked_until = NULL, reset_at = now(), updated_at = now()
                 WHERE principal_type = ? AND principal_id = ? AND app_type = ? AND pin_type = ?""",
                hash(s.p(), type, newPin), s.p().type(), s.p().id(), credApp(s.p(), type), type);
        if (n == 0) throw new BadRequestException(type + " is not set yet");
        jdbc.update("""
                INSERT INTO auth_pin_reset_requests (principal_type, principal_id, app_type, pin_type, session_id,
                    verification_method, expires_at, consumed_at) VALUES (?,?,?,?,?,?, now(), now())""",
                s.p().type(), s.p().id(), s.p().app(), type, s.id(), method);
        if ("TPIN".equals(type))
            jdbc.update("""
                    UPDATE auth_transaction_authorizations SET revoked_at = now()
                     WHERE actor_type = ? AND actor_id = ? AND consumed_at IS NULL AND revoked_at IS NULL""",
                    s.p().type(), s.p().id());
        audit(s.p(), s.id(), type + "_RESET", null, null, null, "SUCCESS", method);
        return Map.of("pinType", type, "reset", true);
    }

    @Transactional
    public Map<String, Object> pinStatus(String bearer) {
        Session s = requireSession(bearer);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mpinSet", pinExists(s.p(), "MPIN"));
        res.put("tpinRequired", tpinAllowed(s.p()));
        res.put("tpinSet", tpinAllowed(s.p()) && pinExists(s.p(), "TPIN"));
        return res;
    }

    // ---------------------------------------------------------------- TPIN authorization

    /**
     * Verifies TPIN and issues a short-lived single-use authorization bound to
     * actor + session + shop + action + target + context. Employees need an
     * open attendance for business actions (and for CHECK_OUT); CHECK_IN needs none open.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> authorizeTransaction(String bearer, String pin, String actionType, String targetType,
                                                    String targetId, String contextHash, String idempotencyKey) {
        return authorizeTransaction(bearer, pin, actionType, targetType, targetId, contextHash, idempotencyKey, null);
    }

    /**
     * With {@code employeeId}: an owner / shop-login session (Partner app) lets one of
     * the shop's employees approve with the EMPLOYEE'S OWN TPIN (they don't know the
     * owner's / shop's). The employee must be an active employee of this session's shop
     * and checked in; the employee's TPIN lockout applies. CHECK_IN / CHECK_OUT stay
     * Employee-app only. The authorization stays bound to this session; its actor is
     * the employee (recorded as who approved).
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> authorizeTransaction(String bearer, String pin, String actionType, String targetType,
                                                    String targetId, String contextHash, String idempotencyKey,
                                                    String employeeId) {
        Session s = requireSession(bearer);
        Principal p = s.p();
        if (!tpinAllowed(p)) throw new ForbiddenException("TPIN is not available for this account");
        if (p.shopId() == null) throw new ForbiddenException("No shop on this session");
        String action = req(actionType, "actionType").toUpperCase();
        String ctx = req(contextHash, "contextHash");
        if (employeeId != null && !employeeId.isBlank()) {
            if (!"SHOP_OWNER".equals(p.type()) && !"SHOP_MOBILE".equals(p.type()))
                throw new ForbiddenException("Only the shop owner / shop login can use an employee's TPIN");
            if (ATTENDANCE_ACTIONS.contains(action))
                throw new ForbiddenException("Check-in / check-out must be done in the Employee app");
            UUID empId;
            try { empId = UUID.fromString(employeeId.trim()); } catch (IllegalArgumentException e) { throw new BadRequestException("Invalid employeeId"); }
            Integer ok = jdbc.queryForObject("""
                    SELECT count(*) FROM users WHERE id = ? AND shop_id = ? AND is_active = true
                       AND upper(role) IN ('TECHNICIAN','STAFF','PICKUP_PERSON')""", Integer.class, empId, p.shopId());
            if (ok == null || ok == 0) throw new ForbiddenException("Employee not found in this shop");
            p = employeePrincipal(empId, p.shopId(), null);
        }
        // Owner / shop session without employeeId: identify the approver from the PIN
        // itself (owner, shop or a checked-in employee of THIS shop) — server-side only.
        boolean auto = (employeeId == null || employeeId.isBlank())
                && ("SHOP_OWNER".equals(p.type()) || "SHOP_MOBILE".equals(p.type()));
        Integer autoVersion = null;
        if (auto) {
            Object[] m = autoApprover(s, p, action, pin);
            p = (Principal) m[0];
            autoVersion = (Integer) m[1];
        }
        UUID attendanceId = attendanceGate(p, action);
        int version = auto ? autoVersion : checkPin(p, s.id(), "TPIN", pin);

        String secret = randomToken();
        UUID id = UUID.randomUUID();
        Instant exp = Instant.now().plus(TXN_TTL);
        try {
            jdbc.update("""
                    INSERT INTO auth_transaction_authorizations (id, session_id, actor_type, actor_id, shop_id, action_type,
                        target_type, target_id, context_hash, auth_token_hash, idempotency_key, credential_version,
                        attendance_id, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    id, s.id(), p.type(), p.id(), p.shopId(), trim(action, 100), trim(targetType, 50), trim(targetId, 100),
                    trim(ctx, 255), sha256(secret), trim(idempotencyKey, 100), version, attendanceId, Timestamp.from(exp));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Duplicate request (idempotency key already used)");
        }
        audit(p, s.id(), "TPIN_AUTHORIZED", action, targetType, targetId, "SUCCESS", null);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("authorizationToken", secret);
        res.put("expiresAt", exp.toString());
        res.put("actorType", p.type());
        res.put("actorId", p.id());
        res.put("actorName", actorName(p));
        res.put("actorRole", actorRole(p));
        return res;
    }

    /**
     * Finds whose TPIN this is among the session shop's owner / shop login and its active
     * employees. Every candidate is checked server-side (Argon2 + pepper); locked
     * credentials are skipped. Exactly one match = the approver. Two or more = refused
     * (never guessed, and who else shares the PIN is never revealed). Because one entry
     * is tested against several credentials, failures are also limited per SHOP:
     * 5 failed auto-verifications in 15 min lock TPIN entry for that shop.
     * Returns {Principal, credential_version}.
     */
    private Object[] autoApprover(Session s, Principal caller, String action, String pin) {
        UUID shopId = caller.shopId();
        Integer recent = jdbc.queryForObject("""
                SELECT count(*) FROM auth_security_audit_logs
                 WHERE shop_id = ? AND event_type = 'TPIN_AUTO_VERIFY' AND result = 'DENIED'
                   AND created_at > now() - interval '15 minutes'""", Integer.class, shopId);
        int fails = recent == null ? 0 : recent;
        if (fails >= MAX_PIN_FAILS) throw new UnauthorizedException("PIN_LOCKED");

        List<Map<String, Object>> cands = pin != null && pin.matches("\\d{6}") ? jdbc.queryForList("""
                SELECT c.id, c.principal_type, c.principal_id, c.pin_hash, c.credential_version
                  FROM auth_pin_credentials c JOIN users u ON u.id = c.principal_id
                 WHERE c.pin_type = 'TPIN' AND c.app_type = 'ANY'
                   AND (c.locked_until IS NULL OR c.locked_until <= now())
                   AND ((c.principal_type IN ('SHOP_OWNER','SHOP_MOBILE')
                         AND (c.principal_id = ? OR c.principal_id = (SELECT owner_user_id FROM shops WHERE id = ?)))
                     OR (c.principal_type = 'EMPLOYEE' AND u.shop_id = ? AND u.is_active = true
                         AND upper(u.role) IN ('TECHNICIAN','STAFF','PICKUP_PERSON')))""",
                caller.id(), shopId, shopId) : List.of();
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> c : cands) {
            Principal cp = new Principal((String) c.get("principal_type"), (UUID) c.get("principal_id"), "ANY", 15, 120, shopId, null);
            if (argon.matches(pepper(cp, "TPIN", pin), (String) c.get("pin_hash"))) hits.add(c);
        }
        if (hits.size() != 1) {
            boolean dup = hits.size() > 1;
            audit(caller, s.id(), "TPIN_AUTO_VERIFY", action, null, null, "DENIED", dup ? "AMBIGUOUS_PIN" : "WRONG_PIN");
            if (dup) throw new ForbiddenException(   // 403 (not rolled back: the attempt stays counted)
                    "TPIN_AMBIGUOUS: This TPIN is used by more than one person. Ask the shop owner to reset one of them.");
            int left = MAX_PIN_FAILS - fails - 1;
            throw new UnauthorizedException(left <= 0 ? "PIN_LOCKED" : "WRONG_PIN:" + left);
        }
        Map<String, Object> c = hits.get(0);
        String type = (String) c.get("principal_type");
        UUID id = (UUID) c.get("principal_id");
        if ("EMPLOYEE".equals(type) && ATTENDANCE_ACTIONS.contains(action))
            throw new ForbiddenException("Check-in / check-out must be done in the Employee app");
        Principal approver = "EMPLOYEE".equals(type) ? employeePrincipal(id, shopId, null)
                : new Principal(type, id, caller.app(), caller.otpDays(), caller.lockSeconds(), shopId, caller.claims());
        attempt((UUID) c.get("id"), s.id(), true, null);
        audit(caller, s.id(), "TPIN_AUTO_VERIFY", action, type, id.toString(), "SUCCESS", null);
        return new Object[]{approver, ((Number) c.get("credential_version")).intValue()};
    }

    private String actorRole(Principal p) {
        if ("SHOP_OWNER".equals(p.type())) return "Owner";
        if ("SHOP_MOBILE".equals(p.type())) return "Shop";
        if (!"EMPLOYEE".equals(p.type())) return p.type();
        String r = jdbc.query("SELECT role FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, p.id());
        r = r == null ? "" : r.trim().toUpperCase();
        return switch (r) { case "STAFF" -> "Staff"; case "PICKUP_PERSON" -> "Pickup Person"; default -> "Technician"; };
    }

    /**
     * Atomically consumes an authorization at execution time (call from the
     * service that performs the action, forwarding the user's bearer token).
     * Re-checks session, credential version, shop and attendance.
     */
    @Transactional
    public Map<String, Object> consume(String bearer, String authorizationToken, String actionType, String targetId,
                                       String contextHash) {
        Session s = requireSession(bearer);
        String action = req(actionType, "actionType").toUpperCase();
        if (!ATTENDANCE_ACTIONS.contains(action)) attendanceGate(s.p(), action);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE auth_transaction_authorizations a SET consumed_at = now()
                 WHERE a.auth_token_hash = ? AND a.session_id = ?
                   AND (a.actor_id = ? OR (a.actor_type = 'EMPLOYEE' AND ? IN ('SHOP_OWNER','SHOP_MOBILE')))
                   AND a.action_type = ?
                   AND a.context_hash = ? AND a.target_id IS NOT DISTINCT FROM ?
                   AND a.consumed_at IS NULL AND a.revoked_at IS NULL AND a.expires_at > now()
                   AND a.credential_version = (SELECT c.credential_version FROM auth_pin_credentials c
                        WHERE c.principal_type = a.actor_type AND c.principal_id = a.actor_id AND c.pin_type = 'TPIN'
                        ORDER BY c.updated_at DESC LIMIT 1)
                RETURNING a.id, a.shop_id, a.attendance_id, a.actor_type, a.actor_id""",
                sha256(req(authorizationToken, "authorizationToken")), s.id(), s.p().id(), s.p().type(), action,
                req(contextHash, "contextHash"), targetId);
        if (rows.isEmpty()) {
            audit(s.p(), s.id(), "TPIN_CONSUME", action, null, targetId, "DENIED", "INVALID_OR_USED");
            throw new ForbiddenException("TPIN authorization invalid, expired or already used");
        }
        audit(s.p(), s.id(), "TPIN_CONSUMED", action, null, targetId, "SUCCESS", null);
        Map<String, Object> r = rows.get(0);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("authorizationId", r.get("id"));
        res.put("shopId", r.get("shop_id"));
        res.put("attendanceId", r.get("attendance_id"));
        // Who approved: the session holder, or the employee whose TPIN was used.
        res.put("actorType", r.get("actor_type"));
        res.put("actorId", r.get("actor_id"));
        return res;
    }

    // ---------------------------------------------------------------- helpers

    private UUID attendanceGate(Principal p, String action) {
        if (!"EMPLOYEE".equals(p.type())) return null;
        Boolean active = jdbc.query("SELECT is_active FROM users WHERE id = ? AND shop_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, p.id(), p.shopId());
        if (active == null || !active) throw new ForbiddenException("Employee is inactive or not assigned to this shop");
        List<UUID> open = jdbc.queryForList("""
                SELECT a.id FROM technician_attendance a JOIN technicians t ON t.id = a.technician_id
                 WHERE t.user_id = ? AND a.check_in_at IS NOT NULL AND a.check_out_at IS NULL
                   AND a.check_in_at > now() - interval '20 hours'
                   AND (a.shop_id IS NULL OR a.shop_id = ?) LIMIT 1""", UUID.class, p.id(), p.shopId());
        if ("CHECK_IN".equals(action)) {
            if (!open.isEmpty()) throw new ConflictException("Already checked in");
            return null;
        }
        if (open.isEmpty()) throw new ForbiddenException("Please check in first to use your TPIN.");
        return open.get(0);
    }

    /** Verifies a PIN with lockout; returns credential_version. */
    // ------------------------------------------------- employee MPIN login (no OTP)

    private static final Duration EMPLOYEE_SESSION = Duration.ofDays(365);
    private static final Duration APPROVAL_PENDING_TTL = Duration.ofMinutes(30);
    private static final Duration APPROVAL_USE_TTL = Duration.ofMinutes(15);
    private static final String EMP_MOBILE = "right(regexp_replace(coalesce(u.phone,''), '\\D', '', 'g'), 10)";

    private Principal employeePrincipal(UUID id, UUID shopId, Claims c) {
        return new Principal("EMPLOYEE", id, "EMPLOYEE", 365, 120, shopId, c);
    }

    /** Shop owner (or admin) behind this token, checked in the DB; returns the owner's user id. */
    private UUID requireOwner(String bearer) {
        Claims c = parse(bearer);
        if (c.get("sid") != null) requireSession(bearer);
        if ("SHOP".equals(c.get("loginScope", String.class))) throw new ForbiddenException("Only the shop owner can manage employee security");
        UUID id = UUID.fromString(c.getSubject());
        List<Map<String, Object>> u = jdbc.queryForList("SELECT role, is_active FROM users WHERE id = ?", id);
        if (u.isEmpty() || !Boolean.TRUE.equals(u.get(0).get("is_active"))) throw new ForbiddenException("Only the shop owner can manage employee security");
        String role = String.valueOf(u.get(0).get("role")).toUpperCase();
        if (!role.equals("SHOP_OWNER") && !com.repairshop.saas.auth.entity.Roles.isAdmin(role))
            throw new ForbiddenException("Only the shop owner can manage employee security");
        return id;
    }

    private boolean isAdmin(UUID userId) {
        String r = jdbc.query("SELECT role FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        return com.repairshop.saas.auth.entity.Roles.isAdmin(r);
    }

    /** Employee (TECHNICIAN / STAFF / PICKUP_PERSON) of one of the owner's shops; returns {id, shop_id}. */
    private Map<String, Object> ownedEmployee(UUID ownerId, UUID employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.shop_id, s.owner_user_id FROM users u JOIN shops s ON s.id = u.shop_id
                 WHERE u.id = ? AND upper(u.role) IN ('TECHNICIAN','STAFF','PICKUP_PERSON')""", employeeId);
        if (rows.isEmpty() || !(ownerId.equals(rows.get(0).get("owner_user_id")) || isAdmin(ownerId)))
            throw new ForbiddenException("Employee not found in your shops");
        return rows.get(0);
    }

    /**
     * Owner sets / resets an employee's 6-digit login MPIN (Partner app → Employee profile).
     * Argon2id + pepper; bumps the credential version, clears lockout and ends the
     * employee's device sessions so the old MPIN / sessions stop working.
     */
    @Transactional
    public Map<String, Object> ownerSetEmployeeMpin(String ownerBearer, UUID employeeId, String pin) {
        UUID ownerId = requireOwner(ownerBearer);
        Map<String, Object> emp = ownedEmployee(ownerId, employeeId);
        validatePin(pin);
        Principal p = employeePrincipal(employeeId, (UUID) emp.get("shop_id"), null);
        String h = hash(p, "MPIN", pin);
        int n = jdbc.update("""
                UPDATE auth_pin_credentials SET pin_hash = ?, credential_version = credential_version + 1,
                       failed_attempts = 0, locked_until = NULL, reset_at = now(), updated_at = now()
                 WHERE principal_type = 'EMPLOYEE' AND principal_id = ? AND app_type = 'EMPLOYEE' AND pin_type = 'MPIN'""", h, employeeId);
        if (n == 0)
            jdbc.update("""
                    INSERT INTO auth_pin_credentials (principal_type, principal_id, app_type, pin_type, pin_hash)
                    VALUES ('EMPLOYEE', ?, 'EMPLOYEE', 'MPIN', ?)""", employeeId, h);
        for (Map<String, Object> s : jdbc.queryForList(
                "SELECT id FROM auth_device_sessions WHERE principal_type = 'EMPLOYEE' AND principal_id = ? AND revoked_at IS NULL", employeeId))
            revokeSession((UUID) s.get("id"), "MPIN_RESET");
        jdbc.update("UPDATE auth_device_approvals SET status = 'EXPIRED' WHERE employee_id = ? AND status IN ('PENDING','APPROVED')", employeeId);
        audit(p, null, n == 0 ? "MPIN_SETUP_BY_OWNER" : "MPIN_RESET_BY_OWNER", null, "OWNER", ownerId.toString(), "SUCCESS", null);
        return Map.of("employeeId", employeeId, "mpinSet", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> employeeMpinStatus(String ownerBearer, UUID employeeId) {
        ownedEmployee(requireOwner(ownerBearer), employeeId);
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM auth_pin_credentials
                 WHERE principal_type = 'EMPLOYEE' AND principal_id = ? AND app_type = 'EMPLOYEE' AND pin_type = 'MPIN'""",
                Integer.class, employeeId);
        return Map.of("employeeId", employeeId, "mpinSet", n != null && n > 0);
    }

    /**
     * Employee app login: registered mobile + owner-set MPIN (no OTP). First device or the
     * same device → session. A different device while another is active → owner approval
     * (DEVICE_APPROVAL_REQUIRED); MPIN alone never replaces a trusted device.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> employeeLogin(String mobile, String pin, String deviceId, String deviceName, String platform) {
        if (deviceId == null || deviceId.isBlank()) throw new BadRequestException("deviceId is required");
        String m = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (m.length() < 10) throw new UnauthorizedException("Invalid mobile number or MPIN");
        m = m.substring(m.length() - 10);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.shop_id, u.email, u.name, u.role, u.is_active, s.is_active AS shop_active, s.name AS shop_name
                  FROM users u LEFT JOIN shops s ON s.id = u.shop_id
                 WHERE upper(u.role) IN ('TECHNICIAN','STAFF','PICKUP_PERSON') AND""" + " " + EMP_MOBILE + " = ?", m);
        if (rows.size() != 1) throw new UnauthorizedException("Invalid mobile number or MPIN");
        Map<String, Object> u = rows.get(0);
        UUID empId = (UUID) u.get("id");
        UUID shopId = (UUID) u.get("shop_id");
        if (!Boolean.TRUE.equals(u.get("is_active"))) throw new ForbiddenException("Your employee account is disabled. Contact your shop owner.");
        if (shopId == null || !Boolean.TRUE.equals(u.get("shop_active"))) throw new ForbiddenException("Your shop is inactive. Contact your shop owner.");

        Claims claims = jwt.parseClaims(jwt.generateToken(empId, shopId, (String) u.get("email"), List.of(String.valueOf(u.get("role")))));
        Principal p = employeePrincipal(empId, shopId, claims);
        checkPin(p, null, "MPIN", pin);   // lockout + attempt log + audit on failure

        String devHash = sha256(p.type() + ":" + deviceId);
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT id, device_id_hash, device_name, platform, last_active_at FROM auth_device_sessions
                 WHERE principal_type = 'EMPLOYEE' AND principal_id = ? AND app_type = 'EMPLOYEE' AND revoked_at IS NULL FOR UPDATE""", empId);
        if (!active.isEmpty()) {
            Map<String, Object> old = active.get(0);
            boolean sameDevice = devHash.equals(old.get("device_id_hash"));
            if (!sameDevice) {
                int used = jdbc.update("""
                        UPDATE auth_device_approvals SET status = 'USED'
                         WHERE employee_id = ? AND device_id_hash = ? AND status = 'APPROVED' AND expires_at > now()""", empId, devHash);
                if (used == 0) return approvalRequired(p, devHash, deviceName, platform, old);
            }
            revokeSession((UUID) old.get("id"), sameDevice ? "RELOGIN" : "DEVICE_REPLACED");
            audit(p, (UUID) old.get("id"), sameDevice ? "SESSION_RELOGIN" : "DEVICE_REPLACED", null, null, null, "SUCCESS", "OWNER_APPROVED");
        }

        Instant now = Instant.now(), deadline = now.plus(EMPLOYEE_SESSION);
        UUID sid = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO auth_device_sessions (id, principal_type, principal_id, app_type, device_id_hash,
                        device_name, platform, otp_verified_at, otp_expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                    sid, p.type(), p.id(), p.app(), devHash, trim(deviceName, 150), trim(platform, 20),
                    Timestamp.from(now), Timestamp.from(deadline));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Another login is in progress. Please retry.");
        }
        jdbc.update("UPDATE auth_refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", empId);
        String refresh = newRefreshToken(sid, null, deadline);
        audit(p, sid, "MPIN_LOGIN", null, null, null, "SUCCESS", null);

        Map<String, Object> res = sessionBody(p, sid, deadline);
        res.put("status", "ACTIVE");
        res.put("accessToken", jwt.issueSessionAccessToken(claims, sid, Date.from(deadline)));
        res.put("refreshToken", refresh);
        // Same profile fields as the /auth/login LoginResponse, so the app stores one session shape.
        res.put("tokenType", "Bearer");
        res.put("userId", empId.toString());
        res.put("shopId", shopId.toString());
        res.put("shopName", u.get("shop_name"));
        res.put("email", u.get("email"));
        res.put("name", u.get("name"));
        res.put("roles", List.of(String.valueOf(u.get("role"))));
        res.put("loginScope", "OWNER");
        res.put("loginType", "EMPLOYEE");
        res.put("deviceSessionId", sid.toString());
        return res;
    }

    private Map<String, Object> approvalRequired(Principal p, String devHash, String deviceName, String platform, Map<String, Object> old) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id FROM auth_device_approvals WHERE employee_id = ? AND device_id_hash = ? AND status = 'PENDING' AND expires_at > now()""",
                p.id(), devHash);
        UUID id;
        if (!open.isEmpty()) id = (UUID) open.get(0).get("id");
        else {
            jdbc.update("UPDATE auth_device_approvals SET status = 'EXPIRED' WHERE employee_id = ? AND device_id_hash = ? AND status IN ('PENDING','APPROVED')", p.id(), devHash);
            id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO auth_device_approvals (id, employee_id, shop_id, device_id_hash, device_name, platform, expires_at)
                    VALUES (?,?,?,?,?,?,?)""", id, p.id(), p.shopId(), devHash, trim(deviceName, 150), trim(platform, 20),
                    Timestamp.from(Instant.now().plus(APPROVAL_PENDING_TTL)));
            audit(p, null, "DEVICE_APPROVAL_REQUESTED", null, "DEVICE", id.toString(), "PENDING", null);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "DEVICE_APPROVAL_REQUIRED");
        res.put("requestId", id);
        res.put("deviceName", old.get("device_name"));
        res.put("platform", old.get("platform"));
        res.put("lastActiveAt", old.get("last_active_at"));
        return res;
    }

    /** Owner: pending new-device requests from employees of the owner's shops. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingDeviceApprovals(String ownerBearer) {
        UUID ownerId = requireOwner(ownerBearer);
        return jdbc.queryForList("""
                SELECT a.id, a.employee_id AS "employeeId", u.name AS "employeeName", a.device_name AS "deviceName",
                       a.platform, a.created_at AS "createdAt", a.expires_at AS "expiresAt"
                  FROM auth_device_approvals a JOIN users u ON u.id = a.employee_id JOIN shops s ON s.id = u.shop_id
                 WHERE a.status = 'PENDING' AND a.expires_at > now() AND (s.owner_user_id = ? OR ?)
                 ORDER BY a.created_at DESC""", ownerId, isAdmin(ownerId));
    }

    /** Owner approves / denies; an approval is single-use and valid for 15 minutes. */
    @Transactional
    public Map<String, Object> decideDeviceApproval(String ownerBearer, UUID requestId, boolean approve) {
        UUID ownerId = requireOwner(ownerBearer);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT employee_id, shop_id FROM auth_device_approvals WHERE id = ? AND status = 'PENDING' AND expires_at > now() FOR UPDATE", requestId);
        if (rows.isEmpty()) throw new BadRequestException("This request has expired or was already handled.");
        UUID empId = (UUID) rows.get(0).get("employee_id");
        ownedEmployee(ownerId, empId);
        jdbc.update("""
                UPDATE auth_device_approvals SET status = ?, decided_by = ?, decided_at = now(), expires_at = ?
                 WHERE id = ?""", approve ? "APPROVED" : "DENIED", ownerId,
                Timestamp.from(Instant.now().plus(APPROVAL_USE_TTL)), requestId);
        audit(employeePrincipal(empId, (UUID) rows.get(0).get("shop_id"), null), null,
                approve ? "DEVICE_APPROVED" : "DEVICE_DENIED", null, "OWNER", ownerId.toString(), "SUCCESS", null);
        return Map.of("requestId", requestId, "status", approve ? "APPROVED" : "DENIED");
    }

    private int checkPin(Session s, String type, String pin) { return checkPin(s.p(), s.id(), type, pin); }

    private int checkPin(Principal p, UUID sid, String type, String pin) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, pin_hash, credential_version, failed_attempts, locked_until FROM auth_pin_credentials
                 WHERE principal_type = ? AND principal_id = ? AND app_type = ? AND pin_type = ? FOR UPDATE""",
                p.type(), p.id(), credApp(p, type), type);
        if (rows.isEmpty()) throw new BadRequestException(type + "_NOT_SET");
        Map<String, Object> c = rows.get(0);
        UUID cid = (UUID) c.get("id");
        Timestamp locked = (Timestamp) c.get("locked_until");
        if (locked != null && locked.toInstant().isAfter(Instant.now())) {
            attempt(cid, sid, false, "LOCKED");
            throw new ForbiddenException("PIN_LOCKED until " + locked.toInstant());
        }
        boolean ok = pin != null && pin.matches("\\d{6}") && argon.matches(pepper(p, type, pin), (String) c.get("pin_hash"));
        if (!ok) {
            int fails = ((Number) c.get("failed_attempts")).intValue() + 1;
            boolean lock = fails >= MAX_PIN_FAILS;
            jdbc.update("UPDATE auth_pin_credentials SET failed_attempts = ?, locked_until = ?, updated_at = now() WHERE id = ?",
                    lock ? 0 : fails, lock ? Timestamp.from(Instant.now().plus(PIN_LOCK)) : null, cid);
            attempt(cid, sid, false, lock ? "LOCKED_OUT" : "WRONG_PIN");
            audit(p, sid, type + "_VERIFY", null, null, null, "DENIED", lock ? "LOCKED_OUT" : "WRONG_PIN");
            throw new UnauthorizedException(lock ? "PIN_LOCKED" : "WRONG_PIN:" + (MAX_PIN_FAILS - fails));
        }
        if (((Number) c.get("failed_attempts")).intValue() > 0 || locked != null)
            jdbc.update("UPDATE auth_pin_credentials SET failed_attempts = 0, locked_until = NULL WHERE id = ?", cid);
        attempt(cid, sid, true, null);
        return ((Number) c.get("credential_version")).intValue();
    }

    /** True when the session was ended because the account signed in on another device. */
    private boolean replaced(UUID sid) {
        return !jdbc.queryForList("SELECT 1 FROM auth_device_sessions WHERE id = ? AND revoke_reason = 'DEVICE_REPLACED'", sid).isEmpty();
    }

    private void revokeSession(UUID sid, String reason) {
        jdbc.update("UPDATE auth_device_sessions SET revoked_at = now(), revoke_reason = ? WHERE id = ? AND revoked_at IS NULL", reason, sid);
        jdbc.update("UPDATE auth_session_refresh_tokens SET revoked_at = now() WHERE session_id = ? AND revoked_at IS NULL", sid);
        jdbc.update("UPDATE auth_transaction_authorizations SET revoked_at = now() WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL", sid);
    }

    private String newRefreshToken(UUID sid, UUID previous, Instant deadline) {
        String token = randomToken();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_session_refresh_tokens (id, session_id, token_hash, expires_at) VALUES (?,?,?,?)",
                id, sid, sha256(token), Timestamp.from(deadline));
        if (previous != null) jdbc.update("UPDATE auth_session_refresh_tokens SET replaced_by_token_id = ? WHERE id = ?", id, previous);
        return token;
    }

    private Claims claimsAllowExpired(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("SESSION_EXPIRED");
        try { return jwt.parseClaims(token); }
        catch (ExpiredJwtException e) { return e.getClaims(); }
        catch (JwtException | IllegalArgumentException e) { throw new UnauthorizedException("SESSION_EXPIRED"); }
    }

    private Map<String, Object> sessionBody(Principal p, UUID sid, Instant deadline) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sessionId", sid);
        res.put("principalType", p.type());
        res.put("appType", p.app());
        res.put("otpExpiresAt", deadline.toString());
        res.put("lockTimeoutSeconds", p.lockSeconds());
        res.put("mpinSet", pinExists(p, "MPIN"));
        res.put("tpinRequired", tpinAllowed(p));
        res.put("tpinSet", tpinAllowed(p) && pinExists(p, "TPIN"));
        return res;
    }

    private boolean tpinAllowed(Principal p) { return !"CUSTOMER".equals(p.type()); }

    /** TPIN is bound to the person (not the app); MPIN to the app. */
    private String credApp(Principal p, String type) { return "TPIN".equals(type) ? "ANY" : p.app(); }

    private boolean pinExists(Principal p, String type) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM auth_pin_credentials WHERE principal_type = ? AND principal_id = ? AND app_type = ? AND pin_type = ?""",
                Integer.class, p.type(), p.id(), credApp(p, type), type);
        return n != null && n > 0;
    }

    private String pinType(String t, Principal p) {
        String type = t == null ? "" : t.toUpperCase();
        if (!type.equals("MPIN") && !type.equals("TPIN")) throw new BadRequestException("pinType must be MPIN or TPIN");
        if (type.equals("TPIN") && !tpinAllowed(p)) throw new ForbiddenException("TPIN is not available for this account");
        // Employee login MPIN is owner-managed (ownerSetEmployeeMpin) — never self-created / reset.
        if (type.equals("MPIN") && "EMPLOYEE".equals(p.type())) throw new ForbiddenException("Your MPIN is managed by your shop owner");
        return type;
    }

    private static void validatePin(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) throw new BadRequestException("PIN must be exactly 6 digits");
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin))
            throw new BadRequestException("PIN is too simple");
    }

    private String hash(Principal p, String type, String pin) { return argon.encode(pepper(p, type, pin)); }

    private String pepper(Principal p, String type, String pin) {
        if (pepper == null || pepper.length() < 32) throw new IllegalStateException("PIN_PEPPER (>=32 chars) is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((p.type() + ":" + p.id() + ":" + type + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String actorName(Principal p) {
        String sql = "CUSTOMER".equals(p.type()) ? "SELECT full_name FROM customer_users WHERE id = ?" : "SELECT name FROM users WHERE id = ?";
        return jdbc.query(sql, rs -> rs.next() ? rs.getString(1) : null, p.id());
    }

    private void attempt(UUID cid, UUID sid, boolean ok, String reason) {
        jdbc.update("INSERT INTO auth_pin_attempts (credential_id, session_id, success, failure_reason) VALUES (?,?,?,?)", cid, sid, ok, reason);
    }

    private void audit(Principal p, UUID sid, String event, String action, String targetType, String targetId, String result, String reason) {
        jdbc.update("""
                INSERT INTO auth_security_audit_logs (principal_type, principal_id, app_type, session_id, shop_id, event_type,
                    action_type, target_type, target_id, result, reason_code) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                p.type(), p.id(), p.app(), sid, p.shopId(), event, trim(action, 100), trim(targetType, 50), trim(targetId, 100), result, reason);
    }

    private String randomToken() {
        byte[] b = new byte[32];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String req(String v, String name) {
        if (v == null || v.isBlank()) throw new BadRequestException(name + " is required");
        return v.trim();
    }

    private static String trim(String v, int max) { return v == null ? null : (v.length() > max ? v.substring(0, max) : v); }
}
