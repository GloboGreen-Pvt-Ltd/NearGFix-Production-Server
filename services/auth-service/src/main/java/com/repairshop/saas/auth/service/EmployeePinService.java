package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Employee app PIN: one bcrypt hash (salted per hash) per user id. A PIN is only
 * ever checked against the caller's own row, so two employees may pick the same
 * digits without either unlocking the other — and nothing reveals that they did.
 * PIN values are never logged or returned.
 */
@Service
@RequiredArgsConstructor
public class EmployeePinService {

    static final int MAX_FAILS = 5;
    static final long LOCK_MINUTES = 15;
    private static final Set<String> COMMON = Set.of(
            "123456", "654321", "000000", "111111", "121212", "123123", "112233", "123321",
            "696969", "666666", "159753", "147258", "789456", "102030", "202020", "101010");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    // Prod deploy runs no migrations; the table is tiny and self-contained.
    @PostConstruct
    void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS employee_pins ("
                + "user_id UUID PRIMARY KEY, pin_hash VARCHAR(100) NOT NULL, failed_attempts INT NOT NULL DEFAULT 0, "
                + "locked_until TIMESTAMP NULL, updated_at TIMESTAMP NOT NULL DEFAULT now())");
    }

    /** 6 digits, not all-same, not a straight run, not a common pick. */
    public static String weakReason(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) return "PIN must be exactly 6 digits.";
        if (pin.chars().distinct().count() == 1) return "PIN is too easy. Avoid repeated digits.";
        boolean up = true, down = true;
        for (int i = 1; i < 6; i++) {
            int d = pin.charAt(i) - pin.charAt(i - 1);
            if (d != 1) up = false;
            if (d != -1) down = false;
        }
        if (up || down || COMMON.contains(pin)) return "PIN is too easy to guess. Choose another.";
        return null;
    }

    private Map<String, Object> row(UUID userId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT pin_hash, failed_attempts, locked_until FROM employee_pins WHERE user_id = ?", userId);
        return r.isEmpty() ? null : r.get(0);
    }

    private static Instant lockedUntil(Map<String, Object> r) {
        Object t = r == null ? null : r.get("locked_until");
        Instant until = t instanceof Timestamp ts ? ts.toInstant() : null;
        return until != null && until.isAfter(Instant.now()) ? until : null;
    }

    public Map<String, Object> status(UUID userId) {
        Map<String, Object> r = row(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pinSet", r != null);
        Instant until = lockedUntil(r);
        out.put("locked", until != null);
        out.put("lockedUntil", until == null ? null : until.toString());
        out.put("attemptsLeft", r == null ? MAX_FAILS : Math.max(0, MAX_FAILS - ((Number) r.get("failed_attempts")).intValue()));
        return out;
    }

    /** Checks the caller's own PIN; counts failures and locks after MAX_FAILS. */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> verify(UUID userId, String pin) {
        Map<String, Object> r = row(userId);
        if (r == null) throw new BadRequestException("No PIN set. Create your PIN first.");
        Instant until = lockedUntil(r);
        if (until != null) throw new ForbiddenException("Too many wrong PINs. Try again after " + until + " or reset your PIN.");
        if (pin != null && passwordEncoder.matches(pin, (String) r.get("pin_hash"))) {
            jdbc.update("UPDATE employee_pins SET failed_attempts = 0, locked_until = NULL WHERE user_id = ?", userId);
            return Map.of("verified", true);
        }
        int fails = ((Number) r.get("failed_attempts")).intValue() + 1;
        if (fails >= MAX_FAILS) {
            jdbc.update("UPDATE employee_pins SET failed_attempts = 0, locked_until = ? WHERE user_id = ?",
                    Timestamp.from(Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES)), userId);
            throw new ForbiddenException("Too many wrong PINs. PIN locked for " + LOCK_MINUTES + " minutes.");
        }
        jdbc.update("UPDATE employee_pins SET failed_attempts = ? WHERE user_id = ?", fails, userId);
        throw new UnauthorizedException("Wrong PIN. " + (MAX_FAILS - fails) + " attempt(s) left.");
    }

    /** First PIN, or a change (needs the current PIN). */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public Map<String, Object> set(UUID userId, String currentPin, String newPin) {
        String weak = weakReason(newPin);
        if (weak != null) throw new BadRequestException(weak);
        if (row(userId) != null) verify(userId, currentPin);
        save(userId, newPin);
        return status(userId);
    }

    /** Forgotten PIN: re-verify the account with the login OTP, then set a new one. */
    @Transactional
    public Map<String, Object> reset(UUID userId, String identifier, String otp, String newPin) {
        String weak = weakReason(newPin);
        if (weak != null) throw new BadRequestException(weak);
        if (!authService.verifyAccountOtp(userId, identifier, otp))
            throw new UnauthorizedException("Invalid or expired OTP.");
        save(userId, newPin);
        return status(userId);
    }

    private void save(UUID userId, String pin) {
        jdbc.update("INSERT INTO employee_pins (user_id, pin_hash, failed_attempts, locked_until, updated_at) "
                + "VALUES (?, ?, 0, NULL, now()) ON CONFLICT (user_id) DO UPDATE SET pin_hash = EXCLUDED.pin_hash, "
                + "failed_attempts = 0, locked_until = NULL, updated_at = now()", userId, passwordEncoder.encode(pin));
    }
}
