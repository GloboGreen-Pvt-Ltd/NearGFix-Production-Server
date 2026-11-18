package com.repairshop.saas.auth.controller;

import com.repairshop.saas.auth.entity.Roles;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.repository.UserRepository;
import com.repairshop.saas.auth.security.JwtService;
import com.repairshop.saas.auth.service.OtpPolicy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Test-only OTP delivery: an active ADMIN / SUPER_ADMIN reads the live test OTP of an
 * OTP_TEST_NUMBERS number (no SMS provider yet). Every other number → 404. The code is
 * returned only here (no-store), never logged; each lookup is audited without the code.
 */
@RestController
@RequiredArgsConstructor
public class TestOtpController {

    private static final Logger log = LoggerFactory.getLogger(TestOtpController.class);
    private final JwtService jwtService;
    private final UserRepository userRepository;

    @GetMapping("/auth/admin/test-otp")
    public org.springframework.http.ResponseEntity<Map<String, Object>> testOtp(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam("mobile") String mobile) {
        UUID adminId = requireActiveAdmin(auth);
        if (!OtpPolicy.isTestNumber(mobile))
            throw new com.repairshop.saas.auth.exception.BadRequestException("Not an authorized test number.");
        var otp = OtpPolicy.activeTestOtp(mobile);
        log.warn("[AUDIT] TEST_OTP_LOOKUP admin={} mobile={} found={}", adminId, otp.map(m -> m.get("mobile")).orElse("-"), otp.isPresent());
        if (otp.isEmpty())
            throw new com.repairshop.saas.auth.exception.BadRequestException("No active OTP. Request a new OTP in the app first.");
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(otp.get());
    }

    private UUID requireActiveAdmin(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new UnauthorizedException("Authentication required");
        UUID userId;
        try { userId = jwtService.getUserId(auth.substring(7).trim()); }
        catch (RuntimeException e) { throw new UnauthorizedException("Invalid or expired token"); }
        var user = userRepository.findById(userId).orElseThrow(() -> new ForbiddenException("Admin only"));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !Roles.isAdmin(user.getRole()))
            throw new ForbiddenException("Admin only");
        return userId;
    }
}
