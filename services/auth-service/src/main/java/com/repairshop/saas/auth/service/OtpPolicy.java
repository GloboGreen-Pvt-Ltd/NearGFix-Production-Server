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

    public OtpPolicy(@Value("${app.otp.dev-mode:false}") boolean dev) {
        devMode = dev;
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
