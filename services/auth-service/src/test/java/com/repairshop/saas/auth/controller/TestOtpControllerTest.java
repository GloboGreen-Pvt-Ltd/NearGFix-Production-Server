package com.repairshop.saas.auth.controller;

import com.repairshop.saas.auth.entity.User;
import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.repository.UserRepository;
import com.repairshop.saas.auth.security.JwtService;
import com.repairshop.saas.auth.service.OtpPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestOtpControllerTest {

    static final String M = "9876543210";
    JwtService jwt = mock(JwtService.class);
    UserRepository users = mock(UserRepository.class);
    TestOtpController ctl = new TestOtpController(jwt, users);
    UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() { new OtpPolicy(false, M); }

    @AfterEach
    void reset() { new OtpPolicy(false); }

    void as(String role, boolean active) {
        when(jwt.getUserId("t")).thenReturn(id);
        User u = new User();
        u.setRole(role);
        u.setIsActive(active);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    @Test
    void activeAdminGetsLiveCodeNoStore() {
        as("SUPER_ADMIN", true);
        OtpPolicy.issueTestOtp(M);
        var res = ctl.testOtp("Bearer t", M);
        assertEquals("no-store", res.getHeaders().getCacheControl());
        assertNotNull(res.getBody().get("code"));
    }

    @Test
    void nonAdminsAndAnonymousAreRejected() {
        OtpPolicy.issueTestOtp(M);
        assertThrows(UnauthorizedException.class, () -> ctl.testOtp(null, M));
        as("SHOP_OWNER", true);
        assertThrows(ForbiddenException.class, () -> ctl.testOtp("Bearer t", M));
        as("SUPER_ADMIN", false);
        assertThrows(ForbiddenException.class, () -> ctl.testOtp("Bearer t", M));
        when(users.findById(id)).thenReturn(Optional.empty());               // e.g. a customer token
        assertThrows(ForbiddenException.class, () -> ctl.testOtp("Bearer t", M));
    }

    @Test
    void onlyAllowListedNumbersWithLiveCode() {
        as("ADMIN", true);
        assertThrows(BadRequestException.class, () -> ctl.testOtp("Bearer t", "9000000000"));
        assertThrows(BadRequestException.class, () -> ctl.testOtp("Bearer t", M));   // none issued
    }
}
