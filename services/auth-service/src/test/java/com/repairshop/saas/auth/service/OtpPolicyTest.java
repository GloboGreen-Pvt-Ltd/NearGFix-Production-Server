package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.exception.TooManyRequestsException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtpPolicyTest {

    static final String M = "9876543210";

    @AfterEach
    void reset() { new OtpPolicy(false); }

    @SuppressWarnings("unchecked")
    static Object entry() throws Exception {
        var f = OtpPolicy.class.getDeclaredField("TEST_OTPS");
        f.setAccessible(true);
        return ((Map<String, Object>) f.get(null)).get(M);
    }

    static Object field(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void set(Object o, String name, Object v) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static String code() throws Exception { return (String) field(entry(), "code"); }

    /** Moves issue history back so a new issue is not treated as a duplicate send. */
    @SuppressWarnings("unchecked")
    static void age(long ms) throws Exception {
        var f = OtpPolicy.class.getDeclaredField("TEST_ISSUES");
        f.setAccessible(true);
        Deque<Long> q = ((Map<String, Deque<Long>>) f.get(null)).get(M);
        int n = q.size();
        for (int i = 0; i < n; i++) q.addLast(q.pollFirst() - ms);
        Object e = entry();
        if (e != null) set(e, "used", true);   // the live code is no longer a reusable duplicate
    }

    @Test
    void productionRejectsFixedDefaultOtpAndHidesHints() {
        new OtpPolicy(false);
        assertFalse(OtpPolicy.staticMatch("123456", "123456"));
        assertTrue(OtpPolicy.staticMatch("804512", " 804512 "));
        assertFalse(OtpPolicy.staticMatch("804512", "804513"));
        assertFalse(OtpPolicy.staticMatch(null, "123456"));
        Map<String, Object> res = new HashMap<>();
        OtpPolicy.devHint(res, "devOtp", "804512");
        assertTrue(res.isEmpty());
    }

    @Test
    void nonAllowListedNumbersGetNothing() {
        new OtpPolicy(false, M);
        OtpPolicy.issueTestOtp("9000000000");
        assertFalse(OtpPolicy.verifyTestOtp("9000000000", "000000"));
        assertFalse(OtpPolicy.mobileMatch("9000000000", "123456", "123456"));
        assertFalse(OtpPolicy.isTestNumber("user@x.com"));
        new OtpPolicy(false, "");                       // empty list = disabled
        OtpPolicy.issueTestOtp(M);
        assertFalse(OtpPolicy.verifyTestOtp(M, "123456"));
    }

    @Test
    void threeRequestsPer15MinutesThen429WithRetryAfter() throws Exception {
        new OtpPolicy(false, M);
        OtpPolicy.issueTestOtp(M);
        String first = code();
        OtpPolicy.issueTestOtp(M);                       // rapid duplicate: same code, not counted
        assertEquals(first, code());
        age(31_000);
        OtpPolicy.issueTestOtp(M);                       // 2nd
        age(31_000);
        OtpPolicy.issueTestOtp(M);                       // 3rd
        age(31_000);
        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class, () -> OtpPolicy.issueTestOtp(M));
        long expected = (15 * 60) - 93;                  // oldest request is 93 s old
        assertTrue(Math.abs(ex.getRetryAfterSeconds() - expected) <= 2, "retryAfter=" + ex.getRetryAfterSeconds());
    }

    @Test
    void tenWrongAttemptsInvalidateWithRemainingCount() throws Exception {
        new OtpPolicy(false, M);
        OtpPolicy.issueTestOtp(M);
        String good = code();
        String bad = good.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 9; i++) {
            UnauthorizedException e = assertThrows(UnauthorizedException.class, () -> OtpPolicy.verifyTestOtp(M, bad));
            assertEquals("Incorrect OTP. " + (10 - i) + ((10 - i) == 1 ? " attempt" : " attempts") + " left.", e.getMessage());
        }
        assertEquals("Too many incorrect attempts. Please request a new OTP.",
                assertThrows(UnauthorizedException.class, () -> OtpPolicy.verifyTestOtp(M, bad)).getMessage());
        assertThrows(UnauthorizedException.class, () -> OtpPolicy.verifyTestOtp(M, good));   // invalidated
    }

    @Test
    void validOtpIsSingleUseAndNoUniversalCode() throws Exception {
        new OtpPolicy(false, "+91 98765 43210");
        OtpPolicy.issueTestOtp(M);
        String good = code();
        if (!good.equals("123456"))
            assertThrows(UnauthorizedException.class, () -> OtpPolicy.mobileMatch(M, "123456", "123456"));
        assertTrue(OtpPolicy.mobileMatch("98765 43210", "123456", good));
        assertEquals("This OTP has already been used. Please request a new OTP.",
                assertThrows(UnauthorizedException.class, () -> OtpPolicy.verifyTestOtp(M, good)).getMessage());
    }

    @Test
    void expiredOtpRejected() throws Exception {
        new OtpPolicy(false, M);
        OtpPolicy.issueTestOtp(M);
        String good = code();
        set(entry(), "exp", System.currentTimeMillis() - 1);
        assertEquals("OTP expired. Please request a new OTP.",
                assertThrows(UnauthorizedException.class, () -> OtpPolicy.verifyTestOtp(M, good)).getMessage());
        assertEquals(5 * 60 * 1000L, OtpPolicy.TEST_TTL_MS);
    }

    @Test
    void devModeKeepsLegacyBehaviour() {
        new OtpPolicy(true);
        assertTrue(OtpPolicy.staticMatch("123456", "123456"));
        Map<String, Object> res = new HashMap<>();
        OtpPolicy.devHint(res, "defaultOtp", "123456");
        assertEquals("123456", res.get("defaultOtp"));
    }
}
