package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.common.presence.PresenceAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Presence state machine on H2 (PostgreSQL mode) with the real migration DDL. */
class ShopPresenceControllerTest {

    static final double LAT = 11.75, LNG = 79.75;
    static final double M = 1 / 111_195.0; // degrees latitude per metre

    JdbcTemplate jdbc;
    ShopPresenceController c;
    UUID shopId, userId, techId, attId;

    @BeforeEach
    void setUp() throws Exception {
        // Default: in-memory H2 (PostgreSQL mode). Set PRESENCE_PG_URL (+ PRESENCE_PG_USER / PRESENCE_PG_PASSWORD)
        // to an EMPTY scratch PostgreSQL database to run the same checks on real PostgreSQL.
        String pg = System.getenv("PRESENCE_PG_URL");
        DriverManagerDataSource ds = pg != null
                ? new DriverManagerDataSource(pg, System.getenv("PRESENCE_PG_USER"), System.getenv("PRESENCE_PG_PASSWORD"))
                : new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "");
        jdbc = new JdbcTemplate(ds);
        if (pg != null) for (String t : List.of("employee_presence_events", "employee_presence_state", "shop_presence_settings", "shops", "technicians",
                "technician_attendance", "repair_bookings", "sell_orders", "marketplace_orders", "tickets")) jdbc.execute("DROP TABLE IF EXISTS " + t);
        jdbc.execute("CREATE TABLE shops (id UUID PRIMARY KEY, name VARCHAR(100), latitude DOUBLE PRECISION, longitude DOUBLE PRECISION)");
        jdbc.execute("CREATE TABLE technicians (id UUID PRIMARY KEY, shop_id UUID, user_id UUID, name VARCHAR(100), role_label VARCHAR(100))");
        jdbc.execute("CREATE TABLE technician_attendance (id UUID PRIMARY KEY, technician_id UUID, attendance_date DATE, check_in_time TIME, check_out_time TIME, status VARCHAR(50))");
        jdbc.execute("CREATE TABLE repair_bookings (booking_number VARCHAR(40), assigned_pickup_person_id UUID, status VARCHAR(60))");
        jdbc.execute("CREATE TABLE sell_orders (sell_number VARCHAR(40), assigned_pickup_person_id UUID, status VARCHAR(60))");
        jdbc.execute("CREATE TABLE marketplace_orders (id UUID, assigned_staff_user_id UUID, status VARCHAR(60))");
        String ddl = new String(getClass().getResourceAsStream("/db/120_shop_presence.sql").readAllBytes(), StandardCharsets.UTF_8)
                .replace("BEGIN;", "").replace("COMMIT;", "").replace("TIMESTAMPTZ", pg != null ? "TIMESTAMPTZ" : "TIMESTAMP WITH TIME ZONE");
        for (String stmt : ddl.replaceAll("(?m)--.*$", "").split(";")) if (!stmt.isBlank()) jdbc.execute(stmt);
        if (pg != null) jdbc.execute(ddl); // PostgreSQL: the whole file again in one call, as the startup initializer runs it (idempotent)

        shopId = UUID.randomUUID(); userId = UUID.randomUUID(); techId = UUID.randomUUID(); attId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops VALUES (?, 'Globo Green', ?, ?)", shopId, LAT, LNG);
        jdbc.update("INSERT INTO technicians VALUES (?, ?, ?, 'Nivetha', 'Technician')", techId, shopId, userId);
        jdbc.execute("CREATE TABLE tickets (id UUID, assigned_technician_id UUID, status VARCHAR(60))");
        c = new ShopPresenceController(jdbc, new DataSourceTransactionManager(ds), new PresenceAvailability(jdbc, false, 90));
    }

    void checkIn() {
        jdbc.update("INSERT INTO technician_attendance VALUES (?, ?, ?, ?, NULL, 'GENERAL')",
                attId, techId, Date.valueOf(LocalDate.now(ShopPresenceController.IST)), Time.valueOf(LocalTime.of(0, 0, 1)));
    }

    MockHttpServletRequest req(boolean owner, UUID shop) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("shopId", shop.toString());
        r.setAttribute("userId", (owner ? UUID.randomUUID() : userId).toString());
        r.setAttribute("roles", owner ? List.of("SHOP_OWNER") : List.of("TECHNICIAN"));
        return r;
    }

    Map<String, Object> ping(double metres, double acc, long secondsAgo) {
        return c.ping(req(false, shopId), new ShopPresenceController.PingRequest(
                LAT + metres * M, LNG, acc, Instant.now().minusSeconds(secondsAgo).toEpochMilli(), null, null));
    }

    int events(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM employee_presence_events WHERE event_type = ?", Integer.class, type);
    }

    @Test
    void notCheckedInNeverFreezesOrRecords() {
        Map<String, Object> r = ping(500, 5, 0);
        assertEquals("NOT_CHECKED_IN", r.get("state"));
        assertEquals(false, r.get("freeze"));
        assertEquals(0, (int) jdbc.queryForObject("SELECT COUNT(*) FROM employee_presence_events", Integer.class));
    }

    @Test
    void exitAfterGraceFreezesThenReturnUnlocks() {
        checkIn();
        assertEquals("INSIDE_SHOP", ping(20, 5, 500).get("state"));

        Map<String, Object> pending = ping(150, 5, 400);
        assertEquals("INSIDE_SHOP", pending.get("state"), "inside the grace period");
        assertEquals(false, pending.get("freeze"));
        assertNotNull(pending.get("graceRemainingSeconds"));

        Map<String, Object> out = ping(150, 5, 200); // 200 s > 120 s grace
        assertEquals("OUTSIDE_SHOP", out.get("state"));
        assertEquals(true, out.get("freeze"));
        assertEquals(1, events("EXITED"));

        ping(160, 5, 150); // repeated polling: no duplicate exit
        assertEquals(1, events("EXITED"));

        // No automatic checkout / attendance change.
        assertNull(jdbc.queryForMap("SELECT check_out_time FROM technician_attendance WHERE id = ?", attId).get("check_out_time"));

        Map<String, Object> back = ping(10, 5, 100);
        assertEquals("INSIDE_SHOP", back.get("state"));
        assertEquals(false, back.get("freeze"));
        assertEquals(1, events("RETURNED"));
        assertEquals(1, back.get("outsideVisits"));
        long outsideSec = ((Number) back.get("outsideSeconds")).longValue();
        assertTrue(outsideSec >= 295 && outsideSec <= 305, "exit at -400 s, return at -100 s: " + outsideSec);
    }

    @Test
    void secondExitCountsSeparately() {
        checkIn();
        ping(150, 5, 590); ping(150, 5, 450); ping(10, 5, 400);
        ping(150, 5, 300); Map<String, Object> r = ping(150, 5, 100);
        assertEquals("OUTSIDE_SHOP", r.get("state"));
        assertEquals(2, r.get("outsideVisits"));
        assertEquals(true, r.get("outsideOpen"));
    }

    @Test
    void inaccurateOrBoundaryReadingsDoNotExit() {
        checkIn();
        ping(150, 200, 500); ping(150, 200, 300); // accuracy 200 m: ignored
        assertEquals(0, events("EXITED"));
        ping(55, 5, 250); ping(55, 5, 50);        // 55 m with 50 m radius: inside the hysteresis margin
        assertEquals(0, events("EXITED"));
    }

    @Test
    void locationErrorIsUnverifiedNotOutside() {
        checkIn();
        Map<String, Object> r = c.ping(req(false, shopId), new ShopPresenceController.PingRequest(null, null, null, null, "LOCATION_PERMISSION_DENIED", null));
        assertEquals("LOCATION_UNVERIFIED", r.get("state"));
        assertEquals(false, r.get("freeze"));
        assertEquals(0, events("EXITED"));
    }

    @Test
    void outOfOrderReadingIsIgnored() {
        checkIn();
        ping(150, 5, 300); ping(150, 5, 100);   // exited
        Map<String, Object> r = ping(10, 5, 400); // older than the last reading
        assertEquals("OUTSIDE_SHOP", r.get("state"));
        assertEquals(0, events("RETURNED"));
    }

    @Test
    void authorizedPickupIsOnDutyAndNeverFrozen() {
        checkIn();
        jdbc.update("INSERT INTO repair_bookings VALUES ('BK1', ?, 'PICKUP_ON_THE_WAY')", techId);
        ping(2000, 5, 400);
        Map<String, Object> r = ping(2000, 5, 100);
        assertEquals("ON_AUTHORIZED_DUTY", r.get("state"));
        assertEquals(false, r.get("freeze"));
        assertEquals(1, events("DUTY_STARTED"));
        assertEquals(0, events("EXITED"));

        jdbc.update("UPDATE repair_bookings SET status = 'RECEIVED_AT_SHOP'");
        Map<String, Object> after = ping(10, 5, 50);
        assertEquals("INSIDE_SHOP", after.get("state"));
        assertEquals(1, events("DUTY_ENDED"));
    }

    @Test
    void ownerEndpointsAreShopScoped() {
        checkIn();
        assertThrows(ResponseStatusException.class, () -> c.shopPresence(req(false, shopId), null), "employee cannot list the shop");
        assertThrows(ResponseStatusException.class, () -> c.employeePresence(req(true, UUID.randomUUID()), techId, null), "other shop's owner");
        ping(150, 5, 300); ping(150, 5, 100);
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) c.shopPresence(req(true, shopId), null).get("counts");
        assertEquals(1, counts.get("checkedIn"));
        assertEquals(1, counts.get("outside"));
        assertEquals(0, counts.get("inside"));
        Map<String, Object> d = c.employeePresence(req(true, shopId), techId, null);
        assertFalse(((List<?>) d.get("timeline")).isEmpty());
    }

    @Test
    void settingsValidationRejectsOutOfRange() {
        assertThrows(ResponseStatusException.class, () -> c.putSettings(req(false, shopId),
                new ShopPresenceController.SettingsRequest(null, null, 100, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> c.putSettings(req(true, shopId),
                new ShopPresenceController.SettingsRequest(null, null, 5, null, null, null, null, null)));
        assertEquals(50, c.getSettings(req(false, shopId)).get("radiusMeters"));
    }

    // ------------------------------------------------------------ availability (90 s timeout, enforcement flag)

    PresenceAvailability enforcing() { return new PresenceAvailability(jdbc, true, 90); }
    String avail() { return enforcing().evaluate(shopId, techId).status(); }

    @Test
    void offDutyWhenNotCheckedInOrCheckedOut() {
        assertEquals("OFF_DUTY", avail());
        checkIn();
        jdbc.update("UPDATE technician_attendance SET check_out_time = ?", Time.valueOf(LocalTime.of(0, 0, 2)));
        assertEquals("OFF_DUTY", avail());
        assertThrows(PresenceAvailability.PresenceAssignmentException.class, () -> enforcing().requireAssignable(shopId, techId));
    }

    @Test
    void checkInAloneIsNotAvailable() {
        checkIn();
        assertEquals("LOCATION_UNVERIFIED", avail(), "no verified reading yet");
    }

    @Test
    void freshInsideIsAvailableAndBusyWithOpenJob() {
        checkIn();
        ping(10, 5, 5);
        assertEquals("AVAILABLE", avail());
        enforcing().requireAssignable(shopId, userId); // user id resolves to the same employee
        jdbc.update("INSERT INTO tickets VALUES (?, ?, 'IN_PROGRESS')", UUID.randomUUID(), techId);
        PresenceAvailability.Result r = enforcing().evaluate(shopId, techId);
        assertEquals("BUSY", r.status());
        assertTrue(r.assignable(), "queued work allowed as today");
        assertEquals(1, r.activeJobs());
    }

    @Test
    void staleReadingExpiresAfterTimeoutAsUnverifiedNotOutside() {
        checkIn();
        ping(10, 5, 100); // verified 100 s ago, server contact now
        assertEquals("LOCATION_UNVERIFIED", avail());
        jdbc.update("UPDATE employee_presence_state SET updated_at = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(200)));
        assertEquals("CONNECTION_LOST", avail());
        assertEquals(0, events("EXITED"), "offline is never a confirmed exit");
        assertEquals("LOCATION_UNVERIFIED", c.shopPresence(req(true, shopId), null).get("employees") instanceof List<?> l
                ? ((Map<?, ?>) l.get(0)).get("presence") : null);
    }

    @Test
    void graceIsVerifyingThenOutsideBlocks() {
        checkIn();
        ping(10, 5, 60); ping(150, 5, 30);
        assertEquals("VERIFYING", avail());
        ping(150, 5, 0);
        ping(150, 5, -1); // not yet past grace: still verifying
        jdbc.update("UPDATE employee_presence_state SET state = 'OUTSIDE_SHOP', pending_outside_since = NULL");
        assertEquals("OUTSIDE_SHOP", avail());
        assertThrows(PresenceAvailability.PresenceAssignmentException.class, () -> enforcing().requireAssignable(shopId, techId));
        new PresenceAvailability(jdbc, false, 90).requireAssignable(shopId, techId); // flag OFF: never blocks
    }

    @Test
    void activeFieldDutyIsOnDutyEvenWithoutFreshGps() {
        checkIn();
        jdbc.update("INSERT INTO sell_orders VALUES ('S1', ?, 'OUT_FOR_PICKUP')", techId);
        PresenceAvailability.Result r = enforcing().evaluate(shopId, techId);
        assertEquals("ON_DUTY", r.status());
        assertTrue(r.assignable());
        jdbc.update("UPDATE sell_orders SET status = 'COMPLETED'");
        assertEquals("LOCATION_UNVERIFIED", avail(), "duty over: needs fresh verification, not assumed inside");
    }

    @Test
    void monitoringDisabledKeepsExistingBehavior() {
        checkIn();
        c.putSettings(req(true, shopId), new ShopPresenceController.SettingsRequest(false, null, null, null, null, null, null, null));
        assertEquals("MONITORING_OFF", avail());
        enforcing().requireAssignable(shopId, techId);
    }

    @Test
    void recoveryEventRecordedOnceAfterLocationError() {
        checkIn();
        ping(10, 5, 60);
        c.ping(req(false, shopId), new ShopPresenceController.PingRequest(null, null, null, Instant.now().minusSeconds(40).toEpochMilli(), "GPS_OFF", null));
        ping(10, 5, 20);
        ping(10, 5, 5);
        assertEquals(1, events("LOCATION_VERIFIED"));
        assertEquals("AVAILABLE", avail());
        assertEquals(0, events("RETURNED"), "recovery never fabricates a return");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tl = (List<Map<String, Object>>) c.employeePresence(req(true, shopId), techId, null).get("timeline");
        List<String> types = tl.stream().map(m -> String.valueOf(m.get("type"))).toList();
        assertEquals(List.of("CHECKED_IN", "LOCATION_UNVERIFIED", "LOCATION_VERIFIED"), types, "server order");
        Map<String, Object> rec = tl.get(2);
        assertEquals(50, rec.get("radiusMeters"));
        assertTrue(((Number) rec.get("distanceMeters")).intValue() <= 50);
        assertTrue(((Number) rec.get("durationSeconds")).longValue() >= 35, "gap since the previous verified reading");
    }

    @Test
    void availabilityEndpointIsOwnerOnlyAndShopScoped() {
        checkIn();
        assertThrows(ResponseStatusException.class, () -> c.availability(req(false, shopId), null));
        assertThrows(ResponseStatusException.class, () -> c.availability(req(true, UUID.randomUUID()), techId));
        Map<String, Object> r = c.availability(req(true, shopId), null);
        assertEquals(1, ((List<?>) r.get("employees")).size());
        assertEquals(false, r.get("enforcementEnabled"));
        // Another shop's employee is unknown to the validator (call-site shop check decides).
        enforcing().requireAssignable(UUID.randomUUID(), techId);
    }
}