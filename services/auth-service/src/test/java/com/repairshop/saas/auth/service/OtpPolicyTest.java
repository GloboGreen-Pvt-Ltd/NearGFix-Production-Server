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
    void devModeKeepsLegacyBehaviour() {
        new OtpPolicy(true);
        assertTrue(OtpPolicy.staticMatch("123456", "123456"));
        Map<String, Object> res = new HashMap<>();
        OtpPolicy.devHint(res, "defaultOtp", "123456");
        assertEquals("123456", res.get("defaultOtp"));
    }
}
