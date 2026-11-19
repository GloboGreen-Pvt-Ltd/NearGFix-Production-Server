package com.repairshop.saas.common.notify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Server-side rule for "can this employee take a new job right now": same shop, active, and
 * checked in (no check-out) for the shop-local workday. Shared by every assignment endpoint so
 * the Partner App's availability flags are never trusted. Existing assignments are untouched.
 */
public final class EmployeeAttendance {

    public static final String NOT_CHECKED_IN = "EMPLOYEE_NOT_CHECKED_IN";
    public static final String MESSAGE = "This employee hasn't checked in today (or has checked out). Ask them to check in from the Employee App.";

    /** Open check-in for technician alias {@code t}: TPIN check-in (check_in_at) or Employee App check-in (time columns, shop-local day). */
    public static final String OPEN_CHECK_IN_SQL = """
            EXISTS (SELECT 1 FROM technician_attendance ta
                    WHERE ta.technician_id = t.id
                      AND ((ta.check_in_at IS NOT NULL AND ta.check_out_at IS NULL AND ta.check_in_at > now() - interval '20 hours')
                        OR (ta.check_in_at IS NULL AND ta.check_in_time IS NOT NULL AND ta.check_out_time IS NULL
                            AND ta.attendance_date = (now() AT TIME ZONE coalesce((SELECT s.timezone FROM shops s WHERE s.id = t.shop_id), 'Asia/Kolkata'))::date)))""";

    private static final String ASSIGNABLE_SQL = """
            SELECT count(*) FROM technicians t
            WHERE t.id = ? AND t.shop_id = ?
              AND (CASE WHEN t.is_owner THEN t.owner_tech_enabled ELSE t.is_available END)
              AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = t.user_id AND u.is_active = false)
            """ + " AND " + OPEN_CHECK_IN_SQL;

    private EmployeeAttendance() {}

    /** True when technician {@code technicianId} of {@code shopId} is active and currently checked in. */
    public static boolean isAssignable(JdbcTemplate jdbc, UUID shopId, UUID technicianId) {
        if (shopId == null || technicianId == null) return false;
        Integer n = jdbc.queryForObject(ASSIGNABLE_SQL, Integer.class, technicianId, shopId);
        return n != null && n > 0;
    }
}
