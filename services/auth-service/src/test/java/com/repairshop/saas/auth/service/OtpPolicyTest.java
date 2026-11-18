package com.repairshop.saas.auth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtpPolicyTest {

    @AfterEach
    void reset() { new OtpPolicy(false); }

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
    void testNumbersGetSingleUseOtpOnlyForAllowListedMobiles() {
        new OtpPolicy(false, "+91 98765 43210");
        assertFalse(OtpPolicy.verifyTestOtp("9876543210", "123456"));      // nothing issued
        OtpPolicy.issueTestOtp("9876543210");
        assertFalse(OtpPolicy.mobileMatch("9876543210", "123456", "123456")); // no universal code
        OtpPolicy.issueTestOtp("9000000000");                                // not allow-listed: no-op
        assertFalse(OtpPolicy.verifyTestOtp("9000000000", "000000"));
        for (int i = 0; i < OtpPolicy.TEST_MAX_ATTEMPTS; i++) OtpPolicy.verifyTestOtp("9876543210", "x");
        assertFalse(OtpPolicy.isTestNumber("user@x.com"));
        // Rate limit: 3 issues per window (one already used above).
        OtpPolicy.issueTestOtp("9876543210");
        OtpPolicy.issueTestOtp("9876543210");
        assertThrows(RuntimeException.class, () -> OtpPolicy.issueTestOtp("9876543210"));
    }

    @Test
    void issuedTestOtpIsSingleUse() throws Exception {
        new OtpPolicy(false, "9876543210");
        OtpPolicy.issueTestOtp("9876543210");
        java.lang.reflect.Field f = OtpPolicy.class.getDeclaredField("TEST_OTPS");
        f.setAccessible(true);
        Object entry = ((Map<?, ?>) f.get(null)).get("9876543210");
        java.lang.reflect.Field c = entry.getClass().getDeclaredField("code");
        c.setAccessible(true);
        String code = (String) c.get(entry);
        assertTrue(OtpPolicy.mobileMatch("98765 43210", "123456", code));
        assertFalse(OtpPolicy.mobileMatch("9876543210", "123456", code));   // consumed
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
