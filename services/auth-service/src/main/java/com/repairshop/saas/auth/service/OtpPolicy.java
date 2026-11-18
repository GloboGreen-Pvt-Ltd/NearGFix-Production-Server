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
    // OTP written to the server audit log (never to an API response). Unset the
    // variable to disable. No universal code and no bypass for any other number.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OtpPolicy.class);
    static final long TEST_TTL_MS = 5 * 60 * 1000L;
    static final int TEST_MAX_ATTEMPTS = 5;
    static final int TEST_MAX_ISSUES = 3;
    static final long TEST_ISSUE_WINDOW_MS = 15 * 60 * 1000L;
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private static volatile java.util.Set<String> testNumbers = java.util.Set.of();
    private static final java.util.Map<String, TestOtp> TEST_OTPS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, java.util.Deque<Long>> TEST_ISSUES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class TestOtp {
        final String code; final long exp; int attempts;
        TestOtp(String code, long exp) { this.code = code; this.exp = exp; }
    }

    public OtpPolicy(boolean dev) { this(dev, ""); }

    @org.springframework.beans.factory.annotation.Autowired
    public OtpPolicy(@Value("${app.otp.dev-mode:false}") boolean dev,
                     @Value("${app.otp.test-numbers:}") String numbers) {
        devMode = dev;
        java.util.Set<String> set = new java.util.HashSet<>();
        if (numbers != null) for (String n : numbers.split(",")) { String k = norm(n); if (k != null) set.add(k); }
        testNumbers = java.util.Set.copyOf(set);
        TEST_OTPS.clear();
        TEST_ISSUES.clear();
        if (!set.isEmpty())
            log.warn("[AUDIT] OTP test numbers ENABLED for {} number(s); unset OTP_TEST_NUMBERS after testing", set.size());
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

    /** Issues a test OTP for an allow-listed number (no-op otherwise); the code goes to the audit log only. */
    public static void issueTestOtp(String mobile) {
        if (!isTestNumber(mobile)) return;
        String k = norm(mobile);
        long now = System.currentTimeMillis();
        java.util.Deque<Long> q = TEST_ISSUES.computeIfAbsent(k, x -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > TEST_ISSUE_WINDOW_MS) q.pollFirst();
            if (q.size() >= TEST_MAX_ISSUES) {
                log.warn("[AUDIT] TEST_OTP_RATE_LIMITED mobile={}", mask(k));
                throw new com.repairshop.saas.auth.exception.UnauthorizedException("Too many OTP requests. Try again later.");
            }
            q.addLast(now);
        }
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        TEST_OTPS.put(k, new TestOtp(code, now + TEST_TTL_MS));
        log.warn("[AUDIT] TEST_OTP_ISSUED mobile={} code={} ttlSeconds={}", mask(k), code, TEST_TTL_MS / 1000);
    }

    /** Single-use check of an issued test OTP; burns it on expiry or after too many wrong attempts. */
    public static boolean verifyTestOtp(String mobile, String entered) {
        if (entered == null || !isTestNumber(mobile)) return false;
        String k = norm(mobile);
        TestOtp t = TEST_OTPS.get(k);
        if (t == null) return false;
        synchronized (t) {
            if (System.currentTimeMillis() > t.exp) {
                TEST_OTPS.remove(k, t);
                log.warn("[AUDIT] TEST_OTP_EXPIRED mobile={}", mask(k));
                return false;
            }
            if (java.security.MessageDigest.isEqual(t.code.getBytes(), entered.trim().getBytes())) {
                if (!TEST_OTPS.remove(k, t)) return false;   // already used
                log.warn("[AUDIT] TEST_OTP_USED mobile={}", mask(k));
                return true;
            }
            if (++t.attempts >= TEST_MAX_ATTEMPTS) {
                TEST_OTPS.remove(k, t);
                log.warn("[AUDIT] TEST_OTP_LOCKED mobile={}", mask(k));
            } else log.warn("[AUDIT] TEST_OTP_MISMATCH mobile={} attempts={}", mask(k), t.attempts);
            return false;
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
