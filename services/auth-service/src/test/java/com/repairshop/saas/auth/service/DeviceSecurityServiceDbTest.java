package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.ConflictException;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for migration 123 + DeviceSecurityService against a real
 * PostgreSQL with all migrations applied. Runs only when TEST_PG_URL is set
 * (e.g. jdbc:postgresql://localhost:55432/ngf); skipped otherwise.
 */
class DeviceSecurityServiceDbTest {

    static JdbcTemplate jdbc;
    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    static final String PEPPER = "test-pepper-test-pepper-test-pepper-0123";
    JwtService jwt;
    DeviceSecurityService svc;
    UUID shop, owner, employee, tech, customer;

    @BeforeAll
    static void db() {
        String url = System.getenv("TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_PG_URL not set");
        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("TEST_PG_USER", "postgres"), System.getenv().getOrDefault("TEST_PG_PASSWORD", "test"));
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void fixtures() {
        jwt = new JwtService(SECRET, 86_400_000L);
        svc = new DeviceSecurityService(jdbc, jwt, PEPPER);
        shop = UUID.randomUUID(); owner = UUID.randomUUID(); employee = UUID.randomUUID();
        tech = UUID.randomUUID(); customer = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, 'T', ?)", shop, "t-" + shop);
        jdbc.update("INSERT INTO users (id, shop_id, email, role, name, is_active) VALUES (?,?,?,?,?,true)", owner, shop, owner + "@t.in", "SHOP_OWNER", "Owner A");
        jdbc.update("INSERT INTO users (id, shop_id, email, role, name, is_active) VALUES (?,?,?,?,?,true)", employee, shop, employee + "@t.in", "TECHNICIAN", "Emp B");
        jdbc.update("INSERT INTO technicians (id, shop_id, name, user_id) VALUES (?,?,?,?)", tech, shop, "Emp B", employee);
        jdbc.update("INSERT INTO customer_users (id, full_name, mobile) VALUES (?,?,?)", customer, "Cust C", String.valueOf(System.nanoTime()).substring(0, 10));
    }

    String ownerLogin() { return jwt.generateToken(owner, shop, owner + "@t.in", List.of("SHOP_OWNER"), "OWNER"); }
    String custLogin() { return jwt.issueCustomerToken(customer, List.of("CUSTOMER")); }
    String empLogin() { return jwt.generateToken(employee, shop, employee + "@t.in", List.of("TECHNICIAN")); }
    static String b(Object t) { return "Bearer " + t; }

    Map<String, Object> start(String login, String device, boolean replace) {
        return svc.startSession(login, device, "Phone " + device, "android", null, replace);
    }

    @Test
    void singleDeviceReplacementRevokesOldSession() {   // CUSTOMER app stays single-device
        Map<String, Object> a = start(custLogin(), "dev-a", false);
        assertEquals("ACTIVE", a.get("status"));
        assertNotNull(jwt.parseClaims((String) a.get("accessToken")).get("sid"));
        Map<String, Object> limit = start(custLogin(), "dev-b", false);
        assertEquals("DEVICE_LIMIT", limit.get("status"));
        assertEquals("Phone dev-a", limit.get("deviceName"));
        assertEquals("android", limit.get("platform"));
        svc.requireSession(b(a.get("accessToken")));   // showing the limit / Cancel keeps the old device signed in
        Map<String, Object> bSess = start(custLogin(), "dev-b", true);
        assertEquals("ACTIVE", bSess.get("status"));
        assertEquals("SESSION_REPLACED", assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(a.get("accessToken")))).getMessage());
        assertEquals("SESSION_REPLACED", assertThrows(UnauthorizedException.class, () -> svc.refresh((String) a.get("refreshToken"), (String) a.get("accessToken"))).getMessage());
        svc.requireSession(b(bSess.get("accessToken")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_device_sessions WHERE principal_id = ? AND revoked_at IS NULL", Integer.class, customer));
    }

    // ------------------------------------------------ multi-device TESTING allow-list

    @Test
    void allowListedEmployeeUsesSeveralPhonesWithoutOwnerApproval() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        svc.setMultiDeviceTesters("9000000999, " + m);
        try {
            Map<String, Object> a = empMpinLogin(m, "482916", "dev-a");
            Map<String, Object> bS = empMpinLogin(m, "482916", "dev-b");               // no DEVICE_APPROVAL_REQUIRED
            assertEquals("ACTIVE", bS.get("status"));
            svc.requireSession(b(a.get("accessToken")));                              // dev-a still signed in
            svc.requireSession(b(bS.get("accessToken")));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_device_approvals WHERE employee_id = ?", Integer.class, employee));
            Map<String, Object> r = svc.refresh((String) bS.get("refreshToken"), (String) bS.get("accessToken"));
            svc.requireSession(b(r.get("accessToken")));                              // refresh honours the exception
            Map<String, Object> a2 = empMpinLogin(m, "482916", "dev-a");             // same phone → replaces only itself
            assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(a.get("accessToken"))));
            svc.requireSession(b(a2.get("accessToken")));
            svc.requireSession(b(r.get("accessToken")));
            assertThrows(UnauthorizedException.class, () -> empMpinLogin(m, "000001", "dev-c")); // MPIN still checked
            svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "593027");            // security revocation still ends ALL
            assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(a2.get("accessToken"))));
            assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(r.get("accessToken"))));
        } finally {
            svc.setMultiDeviceTesters("");
        }
    }

    @Test
    void nonListedEmployeeAndCustomerStaySingleDevice() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        svc.setMultiDeviceTesters("9000000999");                                       // someone else is listed
        try {
            empMpinLogin(m, "482916", "dev-a");
            assertEquals("DEVICE_APPROVAL_REQUIRED", empMpinLogin(m, "482916", "dev-b").get("status"));
            start(custLogin(), "c-a", false);
            assertEquals("DEVICE_LIMIT", start(custLogin(), "c-b", false).get("status"));
        } finally {
            svc.setMultiDeviceTesters("");
        }
    }

    @Test
    void allowListedCustomerUsesSeveralPhonesAndLogoutEndsOnlyOne() {
        String mobile = jdbc.queryForObject("SELECT mobile FROM customer_users WHERE id = ?", String.class, customer);
        svc.setMultiDeviceTesters(mobile);
        try {
            Map<String, Object> a = start(custLogin(), "c-a", false);
            Map<String, Object> bS = start(custLogin(), "c-b", false);
            assertEquals("ACTIVE", bS.get("status"));                                   // no DEVICE_LIMIT
            svc.requireSession(b(a.get("accessToken")));
            svc.logout(b(bS.get("accessToken")));                                      // manual logout: only that phone
            assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(bS.get("accessToken"))));
            svc.requireSession(b(a.get("accessToken")));
        } finally {
            svc.setMultiDeviceTesters("");
        }
    }

    @Test
    void partnerOwnerCanBeSignedInOnSeveralDevices() {
        Map<String, Object> a = start(ownerLogin(), "dev-a", false);
        Map<String, Object> bSess = start(ownerLogin(), "dev-b", false);              // no DEVICE_LIMIT
        assertEquals("ACTIVE", bSess.get("status"));
        svc.requireSession(b(a.get("accessToken")));                                   // dev-a still signed in
        svc.requireSession(b(bSess.get("accessToken")));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM auth_device_sessions WHERE principal_id = ? AND revoked_at IS NULL", Integer.class, owner));
        Map<String, Object> a2 = start(ownerLogin(), "dev-a", false);                 // same phone re-login replaces only itself
        assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(a.get("accessToken"))));
        svc.requireSession(b(a2.get("accessToken")));
        svc.requireSession(b(bSess.get("accessToken")));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM auth_device_sessions WHERE principal_id = ? AND revoked_at IS NULL", Integer.class, owner));
        svc.logout(b(bSess.get("accessToken")));                                       // logout ends only that device
        assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(bSess.get("accessToken"))));
        svc.requireSession(b(a2.get("accessToken")));
        // Employee app is still single-device.
        start(empLogin(), "e-a", false);
        assertEquals("DEVICE_LIMIT", start(empLogin(), "e-b", false).get("status"));
    }
    @Test
    void refreshRotatesAndReuseRevokesSession() {
        Map<String, Object> s = start(ownerLogin(), "dev-a", false);
        Map<String, Object> r1 = svc.refresh((String) s.get("refreshToken"), (String) s.get("accessToken"));
        assertNotEquals(s.get("refreshToken"), r1.get("refreshToken"));
        UnauthorizedException reuse = assertThrows(UnauthorizedException.class,
                () -> svc.refresh((String) s.get("refreshToken"), (String) s.get("accessToken")));
        assertEquals("SESSION_REVOKED", reuse.getMessage());
        assertThrows(UnauthorizedException.class, () -> svc.refresh((String) r1.get("refreshToken"), (String) r1.get("accessToken")));
    }

    @Test
    void otpDeadlineCapsRefreshAndAccessToken() {
        Map<String, Object> s = start(ownerLogin(), "dev-a", false);
        Instant deadline = Instant.parse((String) s.get("otpExpiresAt"));
        long days = Duration.between(Instant.now(), deadline).toDays();
        assertTrue(days >= 14 && days <= 15, "partner OTP window is 15 days");
        jdbc.update("UPDATE auth_device_sessions SET otp_verified_at = now() - interval '16 days', otp_expires_at = now() - interval '1 day' WHERE id = ?",
                UUID.fromString(String.valueOf(s.get("sessionId"))));
        assertThrows(UnauthorizedException.class, () -> svc.refresh((String) s.get("refreshToken"), (String) s.get("accessToken")));
        assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(s.get("accessToken"))));
    }

    @Test
    void customerGets30DaysAndNoTpin() {
        Map<String, Object> s = svc.startSession(jwt.issueCustomerToken(customer, List.of("CUSTOMER")), "c-1", "P", "ios", null, false);
        long days = Duration.between(Instant.now(), Instant.parse((String) s.get("otpExpiresAt"))).toDays();
        assertTrue(days >= 29 && days <= 30);
        assertEquals(600, s.get("lockTimeoutSeconds"));
        assertThrows(ForbiddenException.class, () -> svc.createPin(b(s.get("accessToken")), "TPIN", "482916"));
        assertEquals(true, svc.createPin(b(s.get("accessToken")), "MPIN", "482916").get("created"));
    }

    @Test
    void otpProofRejectsRefreshMintedAndSessionTokens() {
        String minted = jwt.withoutOtpProof(this::ownerLogin);
        assertThrows(UnauthorizedException.class, () -> start(minted, "dev-a", false));
        Map<String, Object> s = start(ownerLogin(), "dev-a", false);
        assertThrows(UnauthorizedException.class, () -> start((String) s.get("accessToken"), "dev-b", true));
    }

    @Test
    void startRevokesLegacyRefreshTokens() {
        jdbc.update("INSERT INTO auth_refresh_tokens (id, token_hash, family_id, user_id, login_scope, expires_at) VALUES (?,?,?,?, 'OWNER', now() + interval '90 days')",
                UUID.randomUUID(), UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID(), owner);
        start(ownerLogin(), "dev-a", false);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, owner));
    }

    @Test
    void mpinCreateVerifyLockoutAndWeakPins() {
        String t = b(start(ownerLogin(), "dev-a", false).get("accessToken"));
        assertThrows(BadRequestException.class, () -> svc.createPin(t, "MPIN", "111111"));
        assertThrows(BadRequestException.class, () -> svc.createPin(t, "MPIN", "123456"));
        svc.createPin(t, "MPIN", "482916");
        assertThrows(ConflictException.class, () -> svc.createPin(t, "MPIN", "593827"));
        assertEquals(true, svc.verifyMpin(t, "482916").get("verified"));
        for (int i = 0; i < 4; i++) assertThrows(UnauthorizedException.class, () -> svc.verifyMpin(t, "000001"));
        UnauthorizedException locked = assertThrows(UnauthorizedException.class, () -> svc.verifyMpin(t, "000001"));
        assertEquals("PIN_LOCKED", locked.getMessage());
        assertThrows(ForbiddenException.class, () -> svc.verifyMpin(t, "482916"));
        String raw = jdbc.queryForObject("SELECT pin_hash FROM auth_pin_credentials WHERE principal_id = ? AND pin_type = 'MPIN'", String.class, owner);
        assertFalse(raw.contains("482916"));
        assertTrue(raw.startsWith("$argon2id$"));
    }

    @Test
    void employeeTpinNeedsCheckInAndIsSingleUse() {
        String t = b(start(empLogin(), "e-1", false).get("accessToken"));
        svc.createPin(t, "TPIN", "739154");
        ForbiddenException noShift = assertThrows(ForbiddenException.class,
                () -> svc.authorizeTransaction(t, "739154", "REPAIR_BOOKING", null, null, "ctx", null));
        assertEquals("Please check in first to use your TPIN.", noShift.getMessage());

        Map<String, Object> in = svc.authorizeTransaction(t, "739154", "CHECK_IN", null, null, "ctx-in", null);
        assertEquals("Emp B", in.get("actorName"));
        svc.consume(t, (String) in.get("authorizationToken"), "CHECK_IN", null, "ctx-in");
        assertThrows(ForbiddenException.class, () -> svc.consume(t, (String) in.get("authorizationToken"), "CHECK_IN", null, "ctx-in"));

        // ticket-service stamps the open punch on check-in
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date, now(), ?)", tech, shop);
        assertThrows(ConflictException.class, () -> svc.authorizeTransaction(t, "739154", "CHECK_IN", null, null, "x", null));

        Map<String, Object> ok = svc.authorizeTransaction(t, "739154", "REPAIR_BOOKING", "TICKET", "T-1", "ctx-1", "idem-1");
        assertThrows(ForbiddenException.class, () -> svc.consume(t, (String) ok.get("authorizationToken"), "REPAIR_BOOKING", "T-1", "other-ctx"));
        assertThrows(ForbiddenException.class, () -> svc.consume(t, (String) ok.get("authorizationToken"), "PAYMENT", "T-1", "ctx-1"));
        svc.consume(t, (String) ok.get("authorizationToken"), "REPAIR_BOOKING", "T-1", "ctx-1");
        assertThrows(ConflictException.class, () -> svc.authorizeTransaction(t, "739154", "REPAIR_BOOKING", "TICKET", "T-1", "ctx-1", "idem-1"));

        // after check-out no protected employee actions
        jdbc.update("UPDATE technician_attendance SET check_out_at = now() WHERE technician_id = ?", tech);
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(t, "739154", "PAYMENT", null, null, "c", null));
    }

    @Test
    void staleOpenShiftDoesNotCountAsCheckedIn() {
        String t = b(start(empLogin(), "e-1", false).get("accessToken"));
        svc.createPin(t, "TPIN", "739154");
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date - 2, now() - interval '30 hours', ?)", tech, shop);
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(t, "739154", "REPAIR_BOOKING", null, null, "c", null));
        assertNotNull(svc.authorizeTransaction(t, "739154", "CHECK_IN", null, null, "c", null).get("authorizationToken"));
    }

    @Test
    void tpinResetRequiresFreshOtpAndRevokesPendingAuthorizations() {
        String login = ownerLogin();
        String t = b(start(login, "dev-a", false).get("accessToken"));
        svc.createPin(t, "TPIN", "739154");
        Map<String, Object> pending = svc.authorizeTransaction(t, "739154", "PAYMENT", null, null, "ctx", null);
        assertThrows(UnauthorizedException.class, () -> svc.resetPin(t, "TPIN", jwt.withoutOtpProof(this::ownerLogin), "846291", "OTP"));
        String otherUser = jwt.generateToken(employee, shop, "e", List.of("TECHNICIAN"));
        assertThrows(ForbiddenException.class, () -> svc.resetPin(t, "TPIN", otherUser, "846291", "OTP"));
        svc.resetPin(t, "TPIN", ownerLogin(), "846291", "OTP");
        assertThrows(ForbiddenException.class, () -> svc.consume(t, (String) pending.get("authorizationToken"), "PAYMENT", null, "ctx"));
        assertThrows(UnauthorizedException.class, () -> svc.authorizeTransaction(t, "739154", "PAYMENT", null, null, "ctx", null));
        assertNotNull(svc.authorizeTransaction(t, "846291", "PAYMENT", null, null, "ctx", null).get("authorizationToken"));
        assertEquals(2, jdbc.queryForObject("SELECT credential_version FROM auth_pin_credentials WHERE principal_id = ? AND pin_type = 'TPIN'", Integer.class, owner));
    }

    @Test
    void logoutRevokesSessionAndAuditNeverStoresSecrets() {
        Map<String, Object> s = start(ownerLogin(), "dev-a", false);
        svc.createPin(b(s.get("accessToken")), "MPIN", "482916");
        svc.logout(b(s.get("accessToken")));
        assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(s.get("accessToken"))));
        Integer leaks = jdbc.queryForObject("""
                SELECT count(*) FROM auth_security_audit_logs WHERE principal_id = ?
                  AND (coalesce(reason_code,'') LIKE '%482916%' OR coalesce(target_id,'') LIKE '%482916%')""", Integer.class, owner);
        assertEquals(0, leaks);
        Timestamp revoked = jdbc.queryForObject("SELECT revoked_at FROM auth_device_sessions WHERE id = ?", Timestamp.class,
                UUID.fromString(String.valueOf(s.get("sessionId"))));
        assertNotNull(revoked);
    }

    @Test
    void missingPepperFailsClosed() {
        DeviceSecurityService noPepper = new DeviceSecurityService(jdbc, jwt, "");
        String t = b(start(ownerLogin(), "dev-a", false).get("accessToken"));
        assertThrows(IllegalStateException.class, () -> noPepper.createPin(t, "MPIN", "482916"));
    }

    // ------------------------------------------------ employee MPIN login

    String empMobile() {
        String m = "9" + String.valueOf(System.nanoTime()).substring(0, 9);
        jdbc.update("UPDATE users SET phone = ? WHERE id = ?", "+91 " + m, employee);
        jdbc.update("UPDATE shops SET owner_user_id = ? WHERE id = ?", owner, shop);
        return m;
    }

    Map<String, Object> empMpinLogin(String m, String pin, String dev) {
        return svc.employeeLogin(m, pin, dev, "Phone " + dev, "android");
    }

    @Test
    void ownerSetsEmployeeMpinAndEmployeeLogsInWithoutOtp() {
        String m = empMobile();
        assertEquals(false, svc.employeeMpinStatus(b(ownerLogin()), employee).get("mpinSet"));
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        assertEquals(true, svc.employeeMpinStatus(b(ownerLogin()), employee).get("mpinSet"));
        String hash = jdbc.queryForObject("SELECT pin_hash FROM auth_pin_credentials WHERE principal_id = ? AND pin_type = 'MPIN'", String.class, employee);
        assertFalse(hash.contains("482916"));
        assertTrue(hash.startsWith("$argon2id$"));
        Map<String, Object> s = empMpinLogin(m, "482916", "dev-a");
        assertEquals("ACTIVE", s.get("status"));
        assertEquals(120, s.get("lockTimeoutSeconds"));
        assertTrue(Instant.parse((String) s.get("otpExpiresAt")).isAfter(Instant.now().plus(Duration.ofDays(300))));   // no 15-day OTP
        svc.verifyMpin(b(s.get("accessToken")), "482916");                                                              // same MPIN unlocks
        assertEquals("ACTIVE", empMpinLogin(m, "482916", "dev-a").get("status"));                                     // same device relogin
    }

    @Test
    void onlyTheEmployeesOwnerCanSetMpin() {
        empMobile();
        UUID other = UUID.randomUUID(), otherShop = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, 'O', ?)", otherShop, "o-" + otherShop);
        jdbc.update("INSERT INTO users (id, shop_id, email, role, name, is_active) VALUES (?,?,?,?,?,true)", other, otherShop, other + "@t.in", "SHOP_OWNER", "Other");
        jdbc.update("UPDATE shops SET owner_user_id = ? WHERE id = ?", other, otherShop);
        String otherToken = b(jwt.generateToken(other, otherShop, other + "@t.in", List.of("SHOP_OWNER"), "OWNER"));
        assertThrows(ForbiddenException.class, () -> svc.ownerSetEmployeeMpin(otherToken, employee, "482916"));
        assertThrows(ForbiddenException.class, () -> svc.ownerSetEmployeeMpin(b(empLogin()), employee, "482916"));      // employee itself
        assertThrows(BadRequestException.class, () -> svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "111111"));  // too simple
    }

    @Test
    void wrongMpinLocksOutAndDisabledAccountsCannotLogIn() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        assertThrows(UnauthorizedException.class, () -> empMpinLogin("9000000001", "482916", "dev-a"));                // unknown mobile
        for (int i = 0; i < 4; i++) assertThrows(UnauthorizedException.class, () -> empMpinLogin(m, "000001", "dev-a"));
        assertEquals("PIN_LOCKED", assertThrows(UnauthorizedException.class, () -> empMpinLogin(m, "000001", "dev-a")).getMessage());
        assertThrows(ForbiddenException.class, () -> empMpinLogin(m, "482916", "dev-a"));                                // locked even with right MPIN
        jdbc.update("UPDATE auth_pin_credentials SET locked_until = NULL, failed_attempts = 0 WHERE principal_id = ?", employee);
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", employee);
        assertThrows(ForbiddenException.class, () -> empMpinLogin(m, "482916", "dev-a"));
        jdbc.update("UPDATE users SET is_active = true WHERE id = ?", employee);
        jdbc.update("UPDATE shops SET is_active = false WHERE id = ?", shop);
        assertThrows(ForbiddenException.class, () -> empMpinLogin(m, "482916", "dev-a"));
    }

    @Test
    void newDeviceNeedsOwnerApprovalAndApprovalIsSingleUse() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        Map<String, Object> a = empMpinLogin(m, "482916", "dev-a");
        Map<String, Object> pending = empMpinLogin(m, "482916", "dev-b");
        assertEquals("DEVICE_APPROVAL_REQUIRED", pending.get("status"));
        svc.requireSession(b(a.get("accessToken")));                                                   // old device untouched
        assertEquals(pending.get("requestId"), empMpinLogin(m, "482916", "dev-b").get("requestId"));  // no duplicate requests
        assertEquals(1, svc.pendingDeviceApprovals(b(ownerLogin())).size());
        assertEquals("DEVICE_LIMIT", start(empLogin(), "dev-c", true).get("status"));                 // OTP path can't replace either
        svc.decideDeviceApproval(b(ownerLogin()), (UUID) pending.get("requestId"), true);
        Map<String, Object> bSess = empMpinLogin(m, "482916", "dev-b");
        assertEquals("ACTIVE", bSess.get("status"));
        assertEquals("SESSION_REPLACED", assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(a.get("accessToken")))).getMessage());
        assertEquals("DEVICE_APPROVAL_REQUIRED", empMpinLogin(m, "482916", "dev-a").get("status"));   // approval was single-use
    }

    @Test
    void deniedRequestAndOwnerResetEndSessions() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        Map<String, Object> a = empMpinLogin(m, "482916", "dev-a");
        Map<String, Object> pending = empMpinLogin(m, "482916", "dev-b");
        svc.decideDeviceApproval(b(ownerLogin()), (UUID) pending.get("requestId"), false);
        assertThrows(BadRequestException.class, () -> svc.decideDeviceApproval(b(ownerLogin()), (UUID) pending.get("requestId"), true));
        assertEquals("DEVICE_APPROVAL_REQUIRED", empMpinLogin(m, "482916", "dev-b").get("status"));
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "593027");                                 // reset
        assertThrows(UnauthorizedException.class, () -> svc.requireSession(b(a.get("accessToken"))));   // sessions ended
        assertThrows(UnauthorizedException.class, () -> empMpinLogin(m, "482916", "dev-a"));           // old MPIN dead
        assertEquals("ACTIVE", empMpinLogin(m, "593027", "dev-b").get("status"));                      // no active device → allowed
    }

    @Test
    void employeeCannotCreateOrResetOwnLoginMpinButTpinStillWorks() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        String t = b(empMpinLogin(m, "482916", "dev-a").get("accessToken"));
        assertThrows(ForbiddenException.class, () -> svc.createPin(t, "MPIN", "593027"));
        assertThrows(ForbiddenException.class, () -> svc.resetPin(t, "MPIN", empLogin(), "593027", "OTP"));
        assertEquals(true, svc.createPin(t, "TPIN", "739154").get("created"));                         // TPIN separate, unchanged
        assertEquals("ACTIVE", empMpinLogin(m, "482916", "dev-a").get("status"));                     // own MPIN untouched
    }

    @Test
    void employeeChangesOwnMpinWithCurrentMpin() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        String t = b(empMpinLogin(m, "482916", "dev-a").get("accessToken"));
        // wrong current MPIN → counts toward lockout, nothing changes
        assertEquals("WRONG_PIN:4", assertThrows(UnauthorizedException.class, () -> svc.changeEmployeeMpin(t, "000001", "593027")).getMessage());
        assertThrows(BadRequestException.class, () -> svc.changeEmployeeMpin(t, "482916", "482916"));   // same as current
        assertThrows(BadRequestException.class, () -> svc.changeEmployeeMpin(t, "482916", "111111"));   // too simple
        assertThrows(BadRequestException.class, () -> svc.changeEmployeeMpin(t, "482916", "12ab"));     // not 6 digits
        int v = jdbc.queryForObject("SELECT credential_version FROM auth_pin_credentials WHERE principal_id = ? AND pin_type = 'MPIN'", Integer.class, employee);

        assertEquals(true, svc.changeEmployeeMpin(t, "482916", "593027").get("reloginRequired"));
        assertEquals(v + 1, jdbc.queryForObject("SELECT credential_version FROM auth_pin_credentials WHERE principal_id = ? AND pin_type = 'MPIN'", Integer.class, employee));
        assertThrows(UnauthorizedException.class, () -> svc.requireSession(t));                         // sessions revoked
        assertThrows(UnauthorizedException.class, () -> empMpinLogin(m, "482916", "dev-a"));            // old MPIN dead
        assertEquals("ACTIVE", empMpinLogin(m, "593027", "dev-a").get("status"));                       // new MPIN works
        String hash = jdbc.queryForObject("SELECT pin_hash FROM auth_pin_credentials WHERE principal_id = ? AND pin_type = 'MPIN'", String.class, employee);
        assertFalse(hash.contains("593027"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_security_audit_logs WHERE principal_id = ? AND (coalesce(reason_code,'') LIKE '%593027%' OR coalesce(target_id,'') LIKE '%593027%')", Integer.class, employee));

        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "604918");                                    // owner reset still works
        assertThrows(UnauthorizedException.class, () -> empMpinLogin(m, "593027", "dev-a"));
        assertEquals("ACTIVE", empMpinLogin(m, "604918", "dev-a").get("status"));
    }

    @Test
    void changeMpinLocksOutAndIsEmployeeOnly() {
        String m = empMobile();
        svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
        String t = b(empMpinLogin(m, "482916", "dev-a").get("accessToken"));
        for (int i = 0; i < 4; i++) assertThrows(UnauthorizedException.class, () -> svc.changeEmployeeMpin(t, "000001", "593027"));
        assertEquals("PIN_LOCKED", assertThrows(UnauthorizedException.class, () -> svc.changeEmployeeMpin(t, "000001", "593027")).getMessage());
        assertThrows(ForbiddenException.class, () -> svc.changeEmployeeMpin(t, "482916", "593027"));   // locked even with right MPIN
        String owner = b(start(ownerLogin(), "o-1", false).get("accessToken"));
        assertThrows(ForbiddenException.class, () -> svc.changeEmployeeMpin(owner, "482916", "593027"));
        assertThrows(UnauthorizedException.class, () -> svc.changeEmployeeMpin(b(empLogin()), "482916", "593027")); // no device session
    }

    @Test
    void ownerSessionApprovesWithEmployeesOwnTpin() {
        // Employee sets their own TPIN in the Employee app.
        String e = b(start(empLogin(), "e-1", false).get("accessToken"));
        svc.createPin(e, "TPIN", "252525");
        String o = b(start(ownerLogin(), "o-1", false).get("accessToken"));
        String emp = employee.toString();

        // Not checked in → refused (same attendance rule as the Employee app).
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", "TICKET", "T-1", "c1", null, emp));
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date, now(), ?)", tech, shop);

        // Wrong employee TPIN counts against the EMPLOYEE's lockout, not the owner's.
        assertEquals("WRONG_PIN:4", assertThrows(UnauthorizedException.class,
                () -> svc.authorizeTransaction(o, "111112", "REPAIR_BOOKING", "TICKET", "T-1", "c1", null, emp)).getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_pin_credentials WHERE principal_id = ? AND failed_attempts > 0", Integer.class, owner));

        Map<String, Object> a = svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", "TICKET", "T-1", "c1", null, emp);
        assertEquals("EMPLOYEE", a.get("actorType"));
        assertEquals("Emp B", a.get("actorName"));
        Map<String, Object> used = svc.consume(o, (String) a.get("authorizationToken"), "REPAIR_BOOKING", "T-1", "c1");
        assertEquals(employee, used.get("actorId"));                                        // recorded as who approved
        assertThrows(ForbiddenException.class, () -> svc.consume(o, (String) a.get("authorizationToken"), "REPAIR_BOOKING", "T-1", "c1"));

        // Bound to the owner session that requested it: another session can't consume it.
        Map<String, Object> a2 = svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", "TICKET", "T-2", "c2", null, emp);
        assertThrows(ForbiddenException.class, () -> svc.consume(e, (String) a2.get("authorizationToken"), "REPAIR_BOOKING", "T-2", "c2"));
    }

    @Test
    void employeeTpinOnPartnerIsRestricted() {
        String e = b(start(empLogin(), "e-1", false).get("accessToken"));
        svc.createPin(e, "TPIN", "252525");
        String o = b(start(ownerLogin(), "o-1", false).get("accessToken"));
        String emp = employee.toString();
        // Attendance actions stay in the Employee app.
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(o, "252525", "CHECK_IN", null, null, "c", null, emp));
        // An employee session can't act for another employee.
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(e, "252525", "REPAIR_BOOKING", null, null, "c", null, emp));
        // Employee of another shop / unknown → refused.
        UUID otherShop = UUID.randomUUID(), stranger = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, 'O', ?)", otherShop, "o-" + otherShop);
        jdbc.update("INSERT INTO users (id, shop_id, email, role, name, is_active) VALUES (?,?,?,?,?,true)", stranger, otherShop, stranger + "@t.in", "TECHNICIAN", "X");
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", null, null, "c", null, stranger.toString()));
        assertThrows(BadRequestException.class, () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", null, null, "c", null, "not-a-uuid"));
        // Inactive employee → refused.
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", employee);
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", null, null, "c", null, emp));
    }

    // ------------------------------------------------ automatic TPIN approver (Partner app)

    /** A second active employee of the same shop with their own TPIN; returns their id. */
    UUID secondEmployee(String tpin) {
        UUID id = UUID.randomUUID(), t2 = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, shop_id, email, role, name, is_active) VALUES (?,?,?,?,?,true)", id, shop, id + "@t.in", "STAFF", "Emp C");
        jdbc.update("INSERT INTO technicians (id, shop_id, name, user_id) VALUES (?,?,?,?)", t2, shop, "Emp C", id);
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date, now(), ?)", t2, shop);
        String tok = jwt.generateToken(id, shop, id + "@t.in", List.of("STAFF"));
        svc.createPin(b(start(tok, "c-" + id, false).get("accessToken")), "TPIN", tpin);
        return id;
    }

    @Test
    void autoApproverIdentifiesOwnerOrEmployeeFromPinAlone() {
        String o = b(start(ownerLogin(), "o-1", false).get("accessToken"));
        svc.createPin(o, "TPIN", "739154");
        String e = b(start(empLogin(), "e-1", false).get("accessToken"));
        svc.createPin(e, "TPIN", "252525");

        Map<String, Object> mine = svc.authorizeTransaction(o, "739154", "REPAIR_BOOKING", "TICKET", "T-1", "c1", null);
        assertEquals("SHOP_OWNER", mine.get("actorType"));
        assertEquals("Owner", mine.get("actorRole"));
        assertEquals("Owner A", mine.get("actorName"));

        // Employee PIN, not checked in → refused (correct PIN, attendance rule).
        assertEquals("Please check in first to use your TPIN.", assertThrows(ForbiddenException.class,
                () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", "TICKET", "T-2", "c2", null)).getMessage());
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date, now(), ?)", tech, shop);
        Map<String, Object> emp = svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", "TICKET", "T-2", "c2", null);
        assertEquals("EMPLOYEE", emp.get("actorType"));
        assertEquals(employee, emp.get("actorId"));
        assertEquals("Emp B", emp.get("actorName"));
        assertEquals("Technician", emp.get("actorRole"));
        assertEquals(employee, svc.consume(o, (String) emp.get("authorizationToken"), "REPAIR_BOOKING", "T-2", "c2").get("actorId"));

        // Employee PIN can't approve attendance actions from the Partner app.
        assertThrows(ForbiddenException.class, () -> svc.authorizeTransaction(o, "252525", "CHECK_IN", null, null, "c3", null));
        // Inactive employee's PIN is not a candidate.
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", employee);
        assertThrows(UnauthorizedException.class, () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", null, null, "c4", null));
    }

    @Test
    void autoApproverRefusesSharedPinWithoutRevealingWho() {
        String o = b(start(ownerLogin(), "o-1", false).get("accessToken"));
        svc.createPin(o, "TPIN", "739154");
        String e = b(start(empLogin(), "e-1", false).get("accessToken"));
        svc.createPin(e, "TPIN", "252525");
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date, now(), ?)", tech, shop);
        secondEmployee("252525");                                                   // same PIN as Emp B
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> svc.authorizeTransaction(o, "252525", "REPAIR_BOOKING", null, null, "c1", null));
        assertTrue(ex.getMessage().startsWith("TPIN_AMBIGUOUS"));
        assertFalse(ex.getMessage().contains("Emp B") || ex.getMessage().contains("Emp C"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_transaction_authorizations WHERE session_id IN (SELECT id FROM auth_device_sessions WHERE principal_id = ?)", Integer.class, owner));
        assertEquals("SHOP_OWNER", svc.authorizeTransaction(o, "739154", "REPAIR_BOOKING", null, null, "c2", null).get("actorType")); // unique PIN still fine
    }

    @Test
    void autoApproverShopWideLockoutAndLockedCredentialsSkipped() {
        String o = b(start(ownerLogin(), "o-1", false).get("accessToken"));
        svc.createPin(o, "TPIN", "739154");
        UUID c = secondEmployee("482916");
        // A locked credential is never matched.
        jdbc.update("UPDATE auth_pin_credentials SET locked_until = now() + interval '10 minutes' WHERE principal_id = ? AND pin_type = 'TPIN'", c);
        assertEquals("WRONG_PIN:4", assertThrows(UnauthorizedException.class,
                () -> svc.authorizeTransaction(o, "482916", "REPAIR_BOOKING", null, null, "c0", null)).getMessage());
        jdbc.update("UPDATE auth_pin_credentials SET locked_until = NULL WHERE principal_id = ? AND pin_type = 'TPIN'", c);
        for (int i = 3; i >= 1; i--) {
            int left = i;
            assertEquals("WRONG_PIN:" + left, assertThrows(UnauthorizedException.class,
                    () -> svc.authorizeTransaction(o, "000001", "REPAIR_BOOKING", null, null, "c" + left, null)).getMessage());
        }
        assertEquals("PIN_LOCKED", assertThrows(UnauthorizedException.class,
                () -> svc.authorizeTransaction(o, "000001", "REPAIR_BOOKING", null, null, "c9", null)).getMessage());
        // 5 failures for this shop → even a correct PIN is refused for 15 minutes.
        assertEquals("PIN_LOCKED", assertThrows(UnauthorizedException.class,
                () -> svc.authorizeTransaction(o, "739154", "REPAIR_BOOKING", null, null, "c10", null)).getMessage());
        // Wrong guesses never touched individual credentials (no per-person lock as side effect).
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_pin_credentials WHERE principal_id IN (?, ?) AND failed_attempts > 0", Integer.class, owner, c));
    }

    @Test
    void staffAndPickupRolesUseOwnerSetMpinLogin() {
        String m = empMobile();
        for (String role : new String[]{"STAFF", "PICKUP_PERSON"}) {
            jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, employee);
            svc.ownerSetEmployeeMpin(b(ownerLogin()), employee, "482916");
            Map<String, Object> s = empMpinLogin(m, "482916", "dev-a");
            assertEquals("ACTIVE", s.get("status"), role);
            assertEquals("EMPLOYEE", s.get("principalType"), role);
        }
    }
}
