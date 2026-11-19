package com.repairshop.saas.ticket.security;

import com.repairshop.saas.common.notify.EmployeeAttendance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Attendance-gated assignment rule shared by every assign endpoint. Needs TEST_PG_URL. */
class EmployeeAttendanceDbTest {

    static JdbcTemplate jdbc;
    UUID shop, emp, tech;

    @BeforeAll
    static void db() {
        String url = System.getenv("TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_PG_URL not set");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", System.getenv().getOrDefault("TEST_PG_PASSWORD", "test")));
    }

    @BeforeEach
    void fixtures() {
        shop = UUID.randomUUID(); emp = UUID.randomUUID(); tech = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, 'T', ?)", shop, "a-" + shop);
        jdbc.update("INSERT INTO users (id, shop_id, email, role, is_active) VALUES (?,?,?,'TECHNICIAN',true)", emp, shop, emp + "@t.in");
        jdbc.update("INSERT INTO technicians (id, shop_id, name, user_id) VALUES (?,?,'E',?)", tech, shop, emp);
    }

    void appCheckIn(String dateExpr, boolean checkedOut) {
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_time, check_out_time) VALUES (?, "
                + dateExpr + ", '09:00', " + (checkedOut ? "'18:00'" : "NULL") + ")", tech);
    }

    static final String TODAY = "(now() AT TIME ZONE 'Asia/Kolkata')::date";

    @Test
    void notCheckedInIsBlocked() {
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void employeeAppCheckInTodayIsAssignable() {
        appCheckIn(TODAY, false);
        assertTrue(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void checkedOutIsBlocked() {
        appCheckIn(TODAY, true);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void yesterdaysOpenCheckInIsBlocked() {
        appCheckIn(TODAY + " - 1", false);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void tpinCheckInCountsUntilCheckOut() {
        jdbc.update("INSERT INTO technician_attendance (technician_id, attendance_date, check_in_at) VALUES (?, " + TODAY + ", now())", tech);
        assertTrue(EmployeeAttendance.isAssignable(jdbc, shop, tech));
        jdbc.update("UPDATE technician_attendance SET check_out_at = now() WHERE technician_id = ?", tech);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void otherShopInactiveAndUnavailableAreBlocked() {
        appCheckIn(TODAY, false);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, UUID.randomUUID(), tech));
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", emp);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
        jdbc.update("UPDATE users SET is_active = true WHERE id = ?", emp);
        jdbc.update("UPDATE technicians SET is_available = false WHERE id = ?", tech);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void ownerAsTechnicianOnNeedsNoCheckIn() {
        jdbc.update("UPDATE technicians SET is_owner = true, owner_tech_enabled = true WHERE id = ?", tech);
        assertTrue(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void ownerAsTechnicianOffIsBlockedEvenIfCheckedIn() {
        jdbc.update("UPDATE technicians SET is_owner = true, owner_tech_enabled = false WHERE id = ?", tech);
        appCheckIn(TODAY, false);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void ownerTechInactiveAccountOrShopIsBlocked() {
        jdbc.update("UPDATE technicians SET is_owner = true, owner_tech_enabled = true WHERE id = ?", tech);
        jdbc.update("UPDATE shops SET is_active = false WHERE id = ?", shop);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
        jdbc.update("UPDATE shops SET is_active = true WHERE id = ?", shop);
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", emp);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }

    @Test
    void employeeIsNotExemptViaOwnerSettingAlone() {
        // owner_tech_enabled without the server-set is_owner flag gives no exemption
        jdbc.update("UPDATE technicians SET owner_tech_enabled = true WHERE id = ?", tech);
        assertFalse(EmployeeAttendance.isAssignable(jdbc, shop, tech));
    }
}
