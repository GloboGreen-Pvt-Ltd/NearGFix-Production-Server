package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.dto.LoginRequest;
import com.repairshop.saas.auth.entity.Roles;
import com.repairshop.saas.auth.entity.User;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Employees (TECHNICIAN / STAFF / PICKUP_PERSON) sign in ONLY with mobile +
 * owner-set MPIN (/auth/security/employee/login). The legacy OTP / password
 * paths must refuse them before any credential check; owners keep OTP login.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeLegacyLoginBlockTest {

    @Mock UserRepository userRepository;
    @Mock OtpStore otpStore;
    @InjectMocks AuthService auth;

    private User user(String role) {
        User u = User.builder().id(UUID.randomUUID()).email("u@t.in").phone("9876543210")
                .role(role).otpCode("123456").isActive(true).build();
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByPhone(any())).thenReturn(Optional.of(u));
        return u;
    }

    private static LoginRequest otp(String code) {
        LoginRequest r = new LoginRequest();
        r.setEmail("9876543210");
        r.setOtp(code);
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"TECHNICIAN", "STAFF", "PICKUP_PERSON", "technician"})
    void employeeOtpLoginIsRejectedEvenWithTheCorrectOtp(String role) {
        user(role);
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> auth.login(otp("123456")));
        assertTrue(e.getMessage().startsWith("EMPLOYEE_MPIN_ONLY"));
        verifyNoInteractions(otpStore);   // rejected before any OTP check
    }

    @Test
    void employeePasswordLoginIsRejected() {
        user("STAFF");
        LoginRequest r = new LoginRequest();
        r.setEmail("9876543210");
        r.setPassword("whatever-123");
        assertThrows(ForbiddenException.class, () -> auth.login(r));
    }

    @Test
    void employeeCannotUseOtpForgotPasswordToGetAToken() {
        user("TECHNICIAN");
        assertThrows(ForbiddenException.class, () -> auth.resetPasswordWithOtp("9876543210", "123456", "new-password-1"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void allowListedEmployeeNumberNeverGetsOrUsesAnOtp() {
        new OtpPolicy(false, "9876543210", true);
        try {
            User u = user("PICKUP_PERSON");
            assertThrows(ForbiddenException.class, () -> auth.sendPasswordResetOtp("9876543210"));   // no OTP issued
            assertThrows(ForbiddenException.class, () -> auth.login(otp("123456")));
            assertFalse(auth.verifyAccountOtp(u.getId(), "9876543210", "123456"));
        } finally {
            new OtpPolicy(false);
        }
    }

    @Test
    void allowListedOwnerGetsFixedTestOtpAfterSend() {
        new OtpPolicy(false, "9876543210", true);
        try {
            user("SHOP_OWNER");
            auth.sendPasswordResetOtp("9876543210");
            // Reaches token issuance (mocks end there) only because the fixed OTP was accepted.
            RuntimeException e = assertThrows(RuntimeException.class, () -> auth.login(otp("123456")));
            assertFalse(e instanceof UnauthorizedException || e instanceof ForbiddenException, e.toString());
        } finally {
            new OtpPolicy(false);
        }
    }

    @Test
    void ownerOtpLoginIsStillProcessed() {
        user("SHOP_OWNER");
        // Not blocked as an employee: reaches the normal OTP check (wrong code → Invalid OTP).
        UnauthorizedException e = assertThrows(UnauthorizedException.class, () -> auth.login(otp("000000")));
        assertEquals("Invalid OTP", e.getMessage());
    }

    @Test
    void employeeRoleSet() {
        for (String r : new String[]{"TECHNICIAN", "STAFF", "PICKUP_PERSON", " staff "}) assertTrue(Roles.isEmployee(r), r);
        for (String r : new String[]{"SHOP_OWNER", "SUPER_ADMIN", "ADMIN", "MARKET_PERSON", "CUSTOMER", null}) assertFalse(Roles.isEmployee(r), String.valueOf(r));
    }
}
