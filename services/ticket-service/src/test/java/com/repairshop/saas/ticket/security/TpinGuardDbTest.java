package com.repairshop.saas.ticket.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** TPIN consumption in an executing service (migration 123). Needs TEST_PG_URL. */
class TpinGuardDbTest {

    static JdbcTemplate jdbc;
    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    TpinGuard guard;
    UUID shop, emp, tech, sid;
    String bearer;

    @BeforeAll
    static void db() {
        String url = System.getenv("TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_PG_URL not set");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", System.getenv().getOrDefault("TEST_PG_PASSWORD", "test")));
    }

    @BeforeEach
    void fixtures() {
        guard = new TpinGuard(jdbc, new JwtService(SECRET));
        shop = UUID.randomUUID(); emp = UUID.randomUUID(); tech = UUID.randomUUID(); sid = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, 'T', ?)", shop, "g-" + shop);
        jdbc.update("INSERT INTO users (id, shop_id, email, role, is_active) VALUES (?,?,?,'TECHNICIAN',true)", emp, shop, emp + "@t.in");
        jdbc.update("INSERT INTO technicians (id, shop_id, name, user_id) VALUES (?,?,'E',?)", tech, shop, emp);
        jdbc.update("""
                INSERT INTO auth_device_sessions (id, principal_type, principal_id, app_type, device_id_hash, otp_verified_at, otp_expires_at)
                VALUES (?, 'EMPLOYEE', ?, 'EMPLOYEE', 'h', now(), now() + interval '15 days')""", sid, emp);
        jdbc.update("INSERT INTO auth_pin_credentials (principal_type, principal_id, app_type, pin_type, pin_hash) VALUES ('EMPLOYEE', ?, 'ANY', 'TPIN', 'x')", emp);
        bearer = "Bearer " + Jwts.builder().subject(emp.toString()).claim("sid", sid.toString()).claim("shopId", shop.toString())
                .claim("roles", List.of("TECHNICIAN")).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 900_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    String authorize(String action, String ctx) {
        String secret = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO auth_transaction_authorizations (session_id, actor_type, actor_id, shop_id, action_type, context_hash,
                    auth_token_hash, credential_version, expires_at) VALUES (?, 'EMPLOYEE', ?, ?, ?, ?, ?, 1, now() + interval '2 minutes')""",
                sid, emp, shop, action, ctx, TpinInterceptor.sha256(secret));
        return secret;
    }

    MockHttpServletRequest req(String method, String uri, String tpin) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.addHeader("Authorization", bearer);
        if (tpin != null) r.addHeader(TpinGuard.HEADER, tpin);
        return r;
    }

    @Test
    void checkInConsumesOnceAndBindsToRequest() {
        String ctx = TpinInterceptor.sha256("POST /technicians/me/attendance/check-in");
        String t = authorize("CHECK_IN", ctx);
        assertThrows(ResponseStatusException.class, () -> guard.consume(req("POST", "/x", t), "CHECK_IN", null, "wrong"));
        assertNotNull(guard.consume(req("POST", "/x", t), "CHECK_IN", null, ctx));
        assertThrows(ResponseStatusException.class, () -> guard.consume(req("POST", "/x", t), "CHECK_IN", null, ctx));
    }

    @Test
    void businessActionRequiresActiveShiftAtExecution() {
        String t = authorize("PAYMENT", "c");
        assertThrows(ResponseStatusException.class, () -> guard.consume(req("POST", "/p", t), "PAYMENT", null, "c"));
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at) VALUES (?, current_date, now())", tech);
        assertNotNull(guard.consume(req("POST", "/p", t), "PAYMENT", null, "c"));
    }

    @Test
    void revokedSessionAndMissingHeaderAreRejected() {
        String t = authorize("CHECK_IN", "c");
        jdbc.update("UPDATE auth_device_sessions SET revoked_at = now() WHERE id = ?", sid);
        assertThrows(ResponseStatusException.class, () -> guard.consume(req("POST", "/x", t), "CHECK_IN", null, "c"));
        assertNull(guard.consume(req("POST", "/x", null), "CHECK_IN", null, "c"));
        ReflectionTestUtils.setField(guard, "enforced", true);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> guard.consume(req("POST", "/x", null), "CHECK_IN", null, "c"));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void interceptorMatchesConfiguredRoutesOnly() throws Exception {
        ReflectionTestUtils.setField(guard, "enforced", true);
        TpinInterceptor i = new TpinInterceptor(guard, List.of("POST /tickets=REPAIR_BOOKING", "PATCH /tickets/*/status=TICKET_STATUS"));
        assertTrue(i.preHandle(req("GET", "/tickets", null), new MockHttpServletResponse(), null));
        assertThrows(ResponseStatusException.class, () -> i.preHandle(req("PATCH", "/tickets/9/status", null), new MockHttpServletResponse(), null));
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at) VALUES (?, current_date, now())", tech);
        String t = authorize("REPAIR_BOOKING", TpinInterceptor.sha256("POST /tickets"));
        assertTrue(i.preHandle(req("POST", "/tickets", t), new MockHttpServletResponse(), null));
    }

    // ---- Partner app: TPIN on pickup receive-at-shop and return-delivery handover ----

    static final List<String> PARTNER_ROUTES = List.of(
            "POST /shop/pickup-bookings/*/receive-at-shop=PICKUP_DELIVERY",
            "POST /tickets/*/repair-delivery/release=PICKUP_DELIVERY");

    @Test
    void receiveAtShopAndReleaseRecordVerifiedApproverOnce() {
        jdbc.update("UPDATE users SET name = 'Nandha' WHERE id = ?", emp);
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at, shop_id) VALUES (?, current_date, now(), ?)", tech, shop);
        TpinInterceptor i = new TpinInterceptor(guard, PARTNER_ROUTES);
        for (String uri : List.of("/shop/pickup-bookings/" + UUID.randomUUID() + "/receive-at-shop",
                                  "/tickets/" + UUID.randomUUID() + "/repair-delivery/release")) {
            String t = authorize("PICKUP_DELIVERY", TpinInterceptor.sha256("POST " + uri));
            MockHttpServletRequest r = req("POST", uri, t);
            assertTrue(i.preHandle(r, new MockHttpServletResponse(), null));
            // Approver comes from the consumed authorization, never from the client.
            assertEquals(emp, guard.approver(r).userId());
            assertEquals("Nandha (Technician)", guard.approverLabel(r));
            // Same token again (double tap / replay) → refused, nothing recorded twice.
            assertThrows(ResponseStatusException.class, () -> i.preHandle(req("POST", uri, t), new MockHttpServletResponse(), null));
            // A token for one route can't be used on the other.
            String other = authorize("PICKUP_DELIVERY", TpinInterceptor.sha256("POST /tickets/x/repair-delivery/release"));
            assertThrows(ResponseStatusException.class, () -> i.preHandle(req("POST", uri, other), new MockHttpServletResponse(), null));
        }
    }

    @Test
    void ownerApproverLabelAndNoApproverWithoutTpin() {
        UUID owner = UUID.randomUUID(), osid = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, shop_id, email, role, name, is_active) VALUES (?,?,?,'SHOP_OWNER','Owner A',true)", owner, shop, owner + "@t.in");
        jdbc.update("""
                INSERT INTO auth_device_sessions (id, principal_type, principal_id, app_type, device_id_hash, otp_verified_at, otp_expires_at)
                VALUES (?, 'SHOP_OWNER', ?, 'PARTNER', 'ho', now(), now() + interval '15 days')""", osid, owner);
        jdbc.update("INSERT INTO auth_pin_credentials (principal_type, principal_id, app_type, pin_type, pin_hash) VALUES ('SHOP_OWNER', ?, 'ANY', 'TPIN', 'x')", owner);
        String uri = "/tickets/" + UUID.randomUUID() + "/repair-delivery/release";
        String secret = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO auth_transaction_authorizations (session_id, actor_type, actor_id, shop_id, action_type, context_hash,
                    auth_token_hash, credential_version, expires_at) VALUES (?, 'SHOP_OWNER', ?, ?, 'PICKUP_DELIVERY', ?, ?, 1, now() + interval '2 minutes')""",
                osid, owner, shop, TpinInterceptor.sha256("POST " + uri), TpinInterceptor.sha256(secret));
        String ob = "Bearer " + Jwts.builder().subject(owner.toString()).claim("sid", osid.toString()).claim("shopId", shop.toString())
                .claim("roles", List.of("SHOP_OWNER")).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 900_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
        r.addHeader("Authorization", ob);
        r.addHeader(TpinGuard.HEADER, secret);
        assertTrue(new TpinInterceptor(guard, PARTNER_ROUTES).preHandle(r, new MockHttpServletResponse(), null));
        assertEquals("Owner A (Owner)", guard.approverLabel(r));
        // Not enforced + no TPIN header → action proceeds, no approver recorded.
        MockHttpServletRequest plain = req("POST", uri, null);
        assertTrue(new TpinInterceptor(guard, PARTNER_ROUTES).preHandle(plain, new MockHttpServletResponse(), null));
        assertNull(guard.approver(plain));
        assertNull(guard.approverLabel(plain));
    }
}
