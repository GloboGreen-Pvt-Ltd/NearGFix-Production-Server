package com.repairshop.saas.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production OTP policy. With app.otp.dev-mode=false (the default) the fixed
 * default mobile OTP "123456" is never accepted and no OTP value (devOtp /
 * defaultOtp) is ever returned in an API response. Dev/test environments opt
 * in with OTP_DEV_MODE=true. Mobile OTP in production needs a real SMS provider.
 */
@Component
public class OtpPolicy {

    public static final String DEFAULT_OTP = "123456";
    private static volatile boolean devMode = false;

    // ---- Administrator-controlled test numbers (OTP_TEST_NUMBERS) -------------
    // Only numbers listed in OTP_TEST_NUMBERS get a random, single-use, 5-minute
    // OTP. The code is never logged and never returned by the public OTP APIs; an
    // active ADMIN / SUPER_ADMIN reads it via GET /auth/admin/test-otp (audited).
    // Unset OTP_TEST_NUMBERS to disable entirely.
    // No universal code and no bypass for any other number.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OtpPolicy.class);
    static final long TEST_TTL_MS = 5 * 60 * 1000L;
    static final int TEST_MAX_ATTEMPTS = 10;
    /** A repeat send within this window reuses the live code (double taps, owner+shop lookups). */
    static final long TEST_REUSE_MS = 30 * 1000L;
    static final int TEST_MAX_ISSUES = 3;
    static final long TEST_ISSUE_WINDOW_MS = 15 * 60 * 1000L;
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private static volatile java.util.Set<String> testNumbers = java.util.Set.of();
    // OTP_FIXED_TEST_CODE_ENABLED=true: the issued test OTP for an allow-listed number is
    // the fixed DEFAULT_OTP instead of a random one. Same single-use / TTL / attempt /
    // issue-rate limits; numbers outside OTP_TEST_NUMBERS are unaffected.
    private static volatile boolean fixedTestCode = false;
    private static final java.util.Map<String, TestOtp> TEST_OTPS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, java.util.Deque<Long>> TEST_ISSUES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class TestOtp {
        final String code; final long issued; final long exp; int attempts; boolean used;
        TestOtp(String code, long issued) { this.code = code; this.issued = issued; this.exp = issued + TEST_TTL_MS; }
    }

    public OtpPolicy(boolean dev) { this(dev, ""); }

    public OtpPolicy(boolean dev, String numbers) { this(dev, numbers, false); }

    @org.springframework.beans.factory.annotation.Autowired
    public OtpPolicy(@Value("${app.otp.dev-mode:false}") boolean dev,
                     @Value("${app.otp.test-numbers:}") String numbers,
                     @Value("${app.otp.fixed-test-code-enabled:false}") boolean fixedCode) {
        devMode = dev;
        fixedTestCode = fixedCode;
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String n : splitNumbers(numbers)) { String k = norm(n); if (k != null) set.add(k); }
        testNumbers = java.util.Set.copyOf(set);
        TEST_OTPS.clear();
        TEST_ISSUES.clear();
        if (!set.isEmpty())
            log.warn("[AUDIT] OTP test numbers ENABLED for {} number(s) {}, fixedCode={}; unset OTP_TEST_NUMBERS after testing",
                    set.size(), set.stream().map(OtpPolicy::mask).sorted().toList(), fixedCode);
    }

    /**
     * OTP_TEST_NUMBERS entries may be separated by commas, semicolons, newlines or
     * plain spaces ("a b c" in an .env / secret used to collapse into ONE bogus
     * entry, so only the last number worked). A space inside a single number
     * ("+91 95008 24814") is kept together: whitespace splits only when every
     * part is itself a full 10+ digit number.
     */
    static java.util.List<String> splitNumbers(String numbers) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (numbers == null) return out;
        for (String tok : numbers.replace("\"", "").replace("'", "").split("[,;\\r\\n]+")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+");
            boolean allFull = parts.length > 1;
            for (String p : parts) if (p.replaceAll("\\D", "").length() < 10) { allFull = false; break; }
            if (allFull) out.addAll(java.util.Arrays.asList(parts)); else out.add(t);
        }
        return out;
    }

    static String norm(String mobile) {
        if (mobile == null || mobile.contains("@")) return null;
        String d = mobile.replaceAll("\\D", "");
        return d.length() < 10 ? null : d.substring(d.length() - 10);
    }

    private static String mask(String k) { return "******" + k.substring(6); }

    public static boolean isTestNumber(String mobile) {
        String k = norm(mobile);
        return k != null && testNumbers.contains(k);
    }

    /** Issues a test OTP for an allow-listed number (no-op otherwise). No send-rate limit. */
    public static void issueTestOtp(String mobile) {
        if (!isTestNumber(mobile)) return;
        String k = norm(mobile);
        long now = System.currentTimeMillis();
        TestOtp live = TEST_OTPS.get(k);
        if (live != null && !live.used && live.attempts == 0 && now - live.issued < TEST_REUSE_MS) return; // duplicate send
        // No send-rate limit (partner / employee / customer): every request issues a fresh OTP.
        String code = fixedTestCode ? DEFAULT_OTP : String.format("%06d", RNG.nextInt(1_000_000));
        TEST_OTPS.put(k, new TestOtp(code, now));
        log.warn("[AUDIT] TEST_OTP_ISSUED mobile={} ttlSeconds={}", mask(k), TEST_TTL_MS / 1000);
    }

    /**
     * The live (unused, unexpired, not locked) test OTP for an allow-listed number, for the
     * admin-only lookup endpoint. Empty for any other number. Never logs the code.
     */
    public static java.util.Optional<java.util.Map<String, Object>> activeTestOtp(String mobile) {
        if (!isTestNumber(mobile)) return java.util.Optional.empty();
        String k = norm(mobile);
        TestOtp t = TEST_OTPS.get(k);
        if (t == null) return java.util.Optional.empty();
        synchronized (t) {
            long left = (t.exp - System.currentTimeMillis()) / 1000;
            if (t.used || left <= 0 || t.attempts >= TEST_MAX_ATTEMPTS) return java.util.Optional.empty();
            return java.util.Optional.of(java.util.Map.of("mobile", mask(k), "code", t.code,
                    "expiresInSeconds", left, "attemptsLeft", TEST_MAX_ATTEMPTS - t.attempts));
        }
    }

    static String masked(String mobile) { String k = norm(mobile); return k == null ? "?" : mask(k); }

    private static com.repairshop.saas.auth.exception.UnauthorizedException otpError(String msg) {
        return new com.repairshop.saas.auth.exception.UnauthorizedException(msg);
    }

    /**
     * Single-use check of an issued test OTP (allow-listed numbers only; false otherwise).
     * Wrong / expired / used codes throw 401 with a specific message; the 10th wrong
     * attempt invalidates the code.
     */
    public static boolean verifyTestOtp(String mobile, String entered) {
        if (entered == null || !isTestNumber(mobile)) return false;
        String k = norm(mobile);
        TestOtp t = TEST_OTPS.get(k);
        if (t == null) throw otpError("OTP expired or not requested. Please request a new OTP.");
        synchronized (t) {
            if (t.used) throw otpError("This OTP has already been used. Please request a new OTP.");
            if (System.currentTimeMillis() > t.exp) {
                TEST_OTPS.remove(k, t);
                log.warn("[AUDIT] TEST_OTP_EXPIRED mobile={}", mask(k));
                throw otpError("OTP expired. Please request a new OTP.");
            }
            if (t.attempts >= TEST_MAX_ATTEMPTS) throw otpError("Too many incorrect attempts. Please request a new OTP.");
            if (java.security.MessageDigest.isEqual(t.code.getBytes(), entered.trim().getBytes())) {
                t.used = true;
                log.warn("[AUDIT] TEST_OTP_USED mobile={}", mask(k));
                return true;
            }
            int left = TEST_MAX_ATTEMPTS - (++t.attempts);
            if (left <= 0) {
                TEST_OTPS.remove(k, t);
                log.warn("[AUDIT] TEST_OTP_LOCKED mobile={}", mask(k));
                throw otpError("Too many incorrect attempts. Please request a new OTP.");
            }
            log.warn("[AUDIT] TEST_OTP_MISMATCH mobile={} attempts={}", mask(k), t.attempts);
            throw otpError("Incorrect OTP. " + left + (left == 1 ? " attempt" : " attempts") + " left.");
        }
    }

    /** Mobile OTP check: the stored/static code (dev-mode rules) or, for test numbers, the issued test OTP. */
    public static boolean mobileMatch(String mobile, String stored, String entered) {
        return staticMatch(stored, entered) || verifyTestOtp(mobile, entered);
    }

    public static boolean devMode() { return devMode; }

    /** Static per-account / default code check: the fixed default only counts in dev mode. */
    public static boolean staticMatch(String stored, String entered) {
        if (stored == null || entered == null) return false;
        String s = stored.trim();
        if (!s.equals(entered.trim())) return false;
        return devMode || !DEFAULT_OTP.equals(s);
    }

    /** Puts a dev-only OTP hint into a response map, only in dev mode. */
    public static void devHint(java.util.Map<String, Object> res, String key, Object value) {
        if (devMode && res != null) res.put(key, value);
    }
}
