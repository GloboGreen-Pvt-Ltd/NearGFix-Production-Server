package com.repairshop.saas.common.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Presence-based assignment availability, shared by ticket / order / marketplace
 * services (they share one database). SQL only, no Spring bean: each service
 * builds one with its JdbcTemplate and its configured flag / timeout.
 *
 * Freshness uses SERVER time: a reading counts only while
 * last_verified_at (phone time, clamped to server time on receipt) and the last
 * server contact (employee_presence_state.updated_at) are within the timeout.
 * Missing / stale readings are UNVERIFIED or CONNECTION_LOST — never OUTSIDE.
 *
 * Enforcement (blocking assignments) is OFF unless explicitly enabled.
 */
public class PresenceAvailability {

    private static final Logger log = LoggerFactory.getLogger(PresenceAvailability.class);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final int DEFAULT_TIMEOUT_SECONDS = 90;
    public static final String FLAG_PROPERTY = "presence.assignment-enforcement.enabled";
    public static final String TIMEOUT_PROPERTY = "presence.verification-timeout-seconds";

    /** Statuses that may receive a new assignment (BUSY / ON_DUTY = queued, as existing workflows allow). */
    static final Set<String> ASSIGNABLE = Set.of("AVAILABLE", "BUSY", "ON_DUTY", "MONITORING_OFF");

    private final JdbcTemplate jdbc;
    private final boolean enforcement;
    private final int timeoutSeconds;
    private final Clock clock;

    public PresenceAvailability(JdbcTemplate jdbc, boolean enforcement, int timeoutSeconds) {
        this(jdbc, enforcement, timeoutSeconds, Clock.systemUTC());
    }

    public PresenceAvailability(JdbcTemplate jdbc, boolean enforcement, int timeoutSeconds, Clock clock) {
        this.jdbc = jdbc;
        this.enforcement = enforcement;
        this.timeoutSeconds = timeoutSeconds < 30 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        this.clock = clock;
    }

    public boolean enforcementEnabled() { return enforcement; }
    public int timeoutSeconds() { return timeoutSeconds; }

    public static class PresenceAssignmentException extends IllegalArgumentException {
        private final String status;
        public PresenceAssignmentException(String status, String message) { super(message); this.status = status; }
        public String getStatus() { return status; }
    }

    public record Result(UUID technicianId, String status, boolean assignable, String reason, boolean checkedIn,
                         String attendanceStatus, String presenceState, Instant lastVerifiedAt, String dutyRef, Integer activeJobs) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", technicianId);
            m.put("availability", status);
            m.put("assignable", assignable);
            m.put("reason", reason);
            m.put("checkedIn", checkedIn);
            m.put("attendanceStatus", attendanceStatus);
            m.put("presenceState", presenceState);
            m.put("lastVerifiedAt", lastVerifiedAt == null ? null : lastVerifiedAt.toString());
            m.put("onAuthorizedDuty", dutyRef != null);
            m.put("dutyRef", dutyRef);
            m.put("activeJobs", activeJobs);
            return m;
        }
    }

    /**
     * Blocks a NEW assignment only when enforcement is on and the employee is not assignable.
     * {@code employeeId} may be a technicians.id or a user id of the same shop.
     * Unassigning / re-sending the current assignee should not call this.
     */
    public void requireAssignable(UUID shopId, UUID employeeId) {
        if (!enforcement || employeeId == null || shopId == null) return;
        UUID techId = resolveTechnician(shopId, employeeId);
        if (techId == null) return; // existing same-shop validation at the call site decides
        Result r = evaluate(shopId, techId);
        if (!r.assignable()) throw new PresenceAssignmentException(r.status(), r.reason());
    }

    public UUID resolveTechnician(UUID shopId, UUID idOrUserId) {
        List<UUID> r = jdbc.queryForList("SELECT id FROM technicians WHERE shop_id = ? AND (id = ? OR user_id = ?) ORDER BY (id = ?) DESC LIMIT 1",
                UUID.class, shopId, idOrUserId, idOrUserId, idOrUserId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Current availability of one employee of {@code shopId} (caller has checked shop access). */
    public Result evaluate(UUID shopId, UUID techId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, IST);
        Map<String, Object> att = jdbc.queryForList(
                "SELECT id, check_in_time, check_out_time, status FROM technician_attendance WHERE technician_id = ? AND attendance_date = ? "
                        + "ORDER BY check_in_time NULLS LAST LIMIT 1", techId, Date.valueOf(today)).stream().findFirst().orElse(null);
        String attStatus = att == null ? null : str(att.get("status"));
        boolean checkedIn = att != null && att.get("check_in_time") != null && att.get("check_out_time") == null;
        if (!checkedIn) {
            return new Result(techId, "OFF_DUTY", false, att != null && att.get("check_out_time") != null ? "Checked out" : "Not checked in",
                    false, attStatus, null, null, null, null);
        }
        Integer jobs = activeJobs(techId);
        Map<String, Object> settings = jdbc.queryForList("SELECT monitoring_enabled, duty_exemption FROM shop_presence_settings WHERE shop_id = ?", shopId)
                .stream().findFirst().orElse(Map.of());
        boolean monitoring = !Boolean.FALSE.equals(settings.get("monitoring_enabled"));
        if (!monitoring || !shopHasLocation(shopId)) { // existing behavior: no presence gate
            return new Result(techId, "MONITORING_OFF", true, null, true, attStatus, null, null, null, jobs);
        }
        Map<String, Object> st = jdbc.queryForList("SELECT * FROM employee_presence_state WHERE technician_id = ?", techId).stream().findFirst().orElse(null);
        boolean current = st != null && Objects.equals(att.get("id"), st.get("attendance_id")) && Objects.equals(shopId, st.get("shop_id"));
        String state = current ? str(st.get("state")) : null;
        Instant verified = current ? ts(st.get("last_verified_at")) : null;
        Instant contact = current ? ts(st.get("updated_at")) : null;

        String duty = !Boolean.FALSE.equals(settings.get("duty_exemption")) ? activeDuty(techId, userId(techId)) : null;
        if (duty != null) {
            return new Result(techId, "ON_DUTY", true, "On authorized field duty — next assignment is queued", true, attStatus, state, verified, duty, jobs);
        }
        if ("OUTSIDE_SHOP".equals(state)) {
            return new Result(techId, "OUTSIDE_SHOP", false, "Confirmed outside the shop", true, attStatus, state, verified, null, jobs);
        }
        if (!current) { // checked in, first reading not received yet
            return new Result(techId, "LOCATION_UNVERIFIED", false, "Waiting for the first verified location", true, attStatus, null, null, null, jobs);
        }
        Instant cutoff = now.minusSeconds(timeoutSeconds);
        if (contact == null || contact.isBefore(cutoff)) {
            return new Result(techId, "CONNECTION_LOST", false, "No presence update received recently", true, attStatus, state, verified, null, jobs);
        }
        if (verified == null || verified.isBefore(cutoff) || !"INSIDE_SHOP".equals(state)) {
            return new Result(techId, "LOCATION_UNVERIFIED", false, "Location not verified recently", true, attStatus, state, verified, null, jobs);
        }
        if (st.get("pending_outside_since") != null) {
            return new Result(techId, "VERIFYING", false, "Left the shop radius — waiting for the exit grace period", true, attStatus, state, verified, null, jobs);
        }
        if (jobs != null && jobs > 0) {
            return new Result(techId, "BUSY", true, "Handling " + jobs + " active job(s) — new work is queued", true, attStatus, state, verified, null, jobs);
        }
        return new Result(techId, "AVAILABLE", true, null, true, attStatus, state, verified, null, jobs);
    }

    /** Active authorized field work from server assignment data, or null. Unknown → no duty. */
    public String activeDuty(UUID techId, UUID userId) {
        String[][] checks = {
                {"REPAIR_PICKUP", "SELECT booking_number FROM repair_bookings WHERE assigned_pickup_person_id = ? AND UPPER(status) IN "
                        + "('PICKUP_ON_THE_WAY','REACHED_CUSTOMER_LOCATION','REPAIR_ESTIMATE_PROCESSING','ESTIMATE_SUBMITTED','DEVICE_PICKED_UP','PICKED_UP','REACHED_SHOP') LIMIT 1"},
                {"SELL_PICKUP", "SELECT sell_number FROM sell_orders WHERE assigned_pickup_person_id = ? AND UPPER(status) IN ('OUT_FOR_PICKUP','PICKED_UP') LIMIT 1"},
        };
        for (String[] c : checks) {
            try {
                List<String> r = jdbc.queryForList(c[1], String.class, techId);
                if (!r.isEmpty()) return c[0] + ":" + (r.get(0) == null ? "" : r.get(0));
            } catch (Exception e) {
                log.debug("duty lookup {} failed: {}", c[0], e.getMessage());
            }
        }
        if (userId != null) {
            try {
                List<String> r = jdbc.queryForList("SELECT CAST(id AS VARCHAR) FROM marketplace_orders WHERE assigned_staff_user_id = ? "
                        + "AND UPPER(status) IN ('PICKED_UP','OUT_FOR_DELIVERY','DELIVERY_FAILED') LIMIT 1", String.class, userId);
                if (!r.isEmpty()) return "MARKETPLACE_DELIVERY:" + r.get(0);
            } catch (Exception e) {
                log.debug("duty lookup marketplace failed: {}", e.getMessage());
            }
        }
        return null;
    }

    /** Open repair tickets assigned to the employee (best effort; null when unknown). */
    Integer activeJobs(UUID techId) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE assigned_technician_id = ? AND UPPER(COALESCE(status,'')) NOT IN "
                    + "('DELIVERED','COMPLETED','CLOSED','CANCELLED','CUSTOMER_REJECTED','RETURN_DELIVERY','REJECTED')", Integer.class, techId);
        } catch (Exception e) {
            log.debug("active job count failed: {}", e.getMessage());
            return null;
        }
    }

    private UUID userId(UUID techId) {
        try {
            return jdbc.queryForList("SELECT user_id FROM technicians WHERE id = ?", UUID.class, techId).stream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shopHasLocation(UUID shopId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("SELECT latitude, longitude FROM shops WHERE id = ?", shopId);
            Object la = row.get("latitude"), lo = row.get("longitude");
            return la instanceof Number a && lo instanceof Number b && !(a.doubleValue() == 0 && b.doubleValue() == 0);
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Instant ts(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime t) return t.toInstant();
        if (o instanceof Instant t) return t;
        return null;
    }
}
