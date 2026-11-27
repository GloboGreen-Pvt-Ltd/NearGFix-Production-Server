package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.common.notify.PartnerNotifications;
import com.repairshop.saas.common.presence.PresenceAvailability;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Shop presence monitoring during an attendance session.
 *
 * Manual check-in / check-out stay in TechnicianService (unchanged). While an
 * employee is checked in, the Employee App sends GPS readings here; the SERVER
 * decides inside / outside (shop coordinates + per-shop settings), applies the
 * exit grace period and hysteresis, checks authorized field duty from the
 * assignment tables (never from a client flag), and records exit / return
 * events once per transition (row lock on the state + unique event key).
 * Nothing here logs out, revokes sessions or checks the employee out.
 *
 * Limitation: readings come from the phone. A spoofed GPS app can fake a
 * location; accuracy and recency checks reduce, not remove, that risk.
 */
@RestController
@RequestMapping("/technicians")
@RequiredArgsConstructor
@Slf4j
public class ShopPresenceController {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int MAX_ACCURACY_M = 75;        // worse readings never change inside/outside
    static final int MAX_READING_AGE_S = 600;    // older readings are ignored

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    /** Shared availability rules + duty lookup; its timeout (default 90 s) also marks stale presence. */
    private final PresenceAvailability availability;
    /** Partner Team notifications: Employee Outside Shop / Returned to Shop (after commit, once per session). */
    private final PartnerNotifications partnerNotifications;

    // ------------------------------------------------------------ request models

    public record PingRequest(Double latitude, Double longitude, Double accuracy, Long observedAt, String error, Boolean manual) {}

    public record SettingsRequest(Boolean monitoringEnabled, Boolean autoFreeze, Integer radiusMeters, Integer graceSeconds,
                                  Boolean autoUnlock, Boolean recordEvents, Boolean dutyExemption, Integer prolongedAlertMinutes) {}

    // ------------------------------------------------------------ auth helpers

    private static UUID attr(HttpServletRequest r, String k) {
        Object v = r.getAttribute(k);
        return v == null ? null : UUID.fromString(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static boolean isOwner(HttpServletRequest r) {
        Object v = r.getAttribute("roles");
        List<String> roles = v instanceof List ? (List<String>) v : List.of();
        return roles.contains("SHOP_OWNER") || roles.contains("SUPER_ADMIN");
    }

    private UUID shop(HttpServletRequest r) {
        UUID s = attr(r, "shopId");
        if (s == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shop context");
        return s;
    }

    private void requireOwner(HttpServletRequest r) {
        if (!isOwner(r)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This action requires the shop owner role.");
    }

    private Map<String, Object> me(HttpServletRequest r) {
        UUID shopId = shop(r);
        UUID userId = attr(r, "userId");
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing user context");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, shop_id, user_id, name, role_label FROM technicians WHERE shop_id = ? AND user_id = ?", shopId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found");
        return rows.get(0);
    }

    // ------------------------------------------------------------ partner notifications

    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    /**
     * Team notification for a confirmed presence transition — only ever called on the
     * state change itself (after the grace period, accurate fix, not on authorized
     * duty), so polling, GPS noise and unverified locations never notify. Raised inside
     * the ping transaction, delivered after it commits; the event key (employee +
     * outside-session start) makes each outside session notify once on exit and once
     * on return, while a later session notifies again.
     */
    private void notifyPresence(Map<String, Object> t, UUID shopId, boolean exited, Instant at, Instant outsideSince) {
        UUID techId = (UUID) t.get("id");
        String name = t.get("name") == null || String.valueOf(t.get("name")).isBlank() ? null : String.valueOf(t.get("name")).trim();
        String who = name == null ? "An employee" : "Employee " + name;
        String clock = LocalTime.ofInstant(at, IST).format(CLOCK);
        String body;
        if (exited) {
            body = who + " has been outside the shop since " + clock + ".";
        } else {
            long min = outsideSince == null ? 0 : Math.max(1, Math.round(secs(outsideSince, at) / 60.0));
            body = who + " returned at " + clock + (min > 0 ? " after being outside for " + min + (min == 1 ? " minute." : " minutes.") : ".");
        }
        Instant session = exited ? at : (outsideSince != null ? outsideSince : at);
        String key = exited ? "PRESENCE_OUTSIDE_SHOP" : "PRESENCE_RETURNED";
        partnerNotifications.notify(PartnerNotifications.Event
                .of(shopId, "team", key, exited ? "Employee Outside Shop" : "Employee Returned to Shop")
                .body(body)
                .entityRef(techId.toString())
                .kind("TEAM_PRESENCE")
                .data("technicianId", techId)
                .data("employeeName", name)
                .data("date", LocalDate.ofInstant(at, IST))
                .data("at", at)
                .eventKey(key + ":" + techId + ":" + session.toEpochMilli()));
    }

    // ------------------------------------------------------------ employee APIs

    /** One GPS reading (or a location error) from the Employee App. */
    @PostMapping("/me/presence/ping")
    public Map<String, Object> ping(HttpServletRequest req, @RequestBody(required = false) PingRequest body) {
        Map<String, Object> t = me(req);
        UUID techId = (UUID) t.get("id");
        UUID shopId = (UUID) t.get("shop_id");
        UUID userId = (UUID) t.get("user_id");
        PingRequest b = body == null ? new PingRequest(null, null, null, null, null, null) : body;

        Map<String, Object> s = settings(shopId);
        Map<String, Object> att = attendance(techId, LocalDate.now(IST));
        boolean checkedIn = att != null && att.get("check_in_time") != null && att.get("check_out_time") == null;
        double[] coords = shopCoords(shopId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", s);
        out.put("shopName", shopName(shopId));
        try { // support contact on the pause screen
            out.put("shopPhone", jdbc.queryForList("SELECT phone FROM shops WHERE id = ?", String.class, shopId).stream().findFirst().orElse(null));
        } catch (Exception ignored) { }
        out.put("checkedIn", checkedIn);
        out.put("serverTime", Instant.now().toString());
        if (!checkedIn) {
            out.put("state", att != null && att.get("check_out_time") != null ? "CHECKED_OUT" : "NOT_CHECKED_IN");
            out.put("freeze", false);
            return out;
        }
        if (!bool(s, "monitoringEnabled")) {
            out.put("state", "MONITORING_OFF");
            out.put("freeze", false);
            return out;
        }
        if (coords == null) {
            out.put("state", "LOCATION_UNVERIFIED");
            out.put("reason", "SHOP_LOCATION_MISSING");
            out.put("freeze", false);
            return out;
        }

        // Authorized duty from the assignment tables, outside the write transaction
        // (a failed lookup must not abort it). Unknown → treated as no duty.
        String duty = bool(s, "dutyExemption") ? availability.activeDuty(techId, userId) : null;
        UUID attId = (UUID) att.get("id");
        int radius = num(s, "radiusMeters");
        int grace = num(s, "graceSeconds");

        Instant now = Instant.now();
        Instant observed = b.observedAt() == null ? now : Instant.ofEpochMilli(b.observedAt());
        if (observed.isAfter(now)) observed = now;
        boolean tooOld = observed.isBefore(now.minusSeconds(MAX_READING_AGE_S));
        final Instant obs = observed;

        // State row first, outside the transaction: a concurrent first ping's duplicate is simply ignored.
        try {
            jdbc.update("INSERT INTO employee_presence_state (technician_id, shop_id) SELECT ?, ? "
                    + "WHERE NOT EXISTS (SELECT 1 FROM employee_presence_state WHERE technician_id = ?)", techId, shopId, techId);
        } catch (org.springframework.dao.DataIntegrityViolationException ignored) { }
        Map<String, Object> result = new TransactionTemplate(txManager).execute(tx -> {
            Map<String, Object> st = jdbc.queryForMap("SELECT * FROM employee_presence_state WHERE technician_id = ? FOR UPDATE", techId);
            // New attendance session (next day / re-check-in) or another shop: start clean.
            if (!attId.equals(st.get("attendance_id")) || !shopId.equals(st.get("shop_id"))) {
                jdbc.update("UPDATE employee_presence_state SET shop_id = ?, attendance_id = ?, state = 'INSIDE_SHOP', pending_outside_since = NULL, "
                        + "outside_since = NULL, duty_since = NULL, duty_ref = NULL, last_observed_at = NULL, last_verified_at = NULL, "
                        + "last_distance_m = NULL, last_accuracy_m = NULL, last_exit_at = NULL, last_return_at = NULL, updated_at = now() "
                        + "WHERE technician_id = ?", shopId, attId, techId);
                st = jdbc.queryForMap("SELECT * FROM employee_presence_state WHERE technician_id = ?", techId);
            }
            // Last server contact (server clock): drives CONNECTION_LOST in availability.
            jdbc.update("UPDATE employee_presence_state SET updated_at = ? WHERE technician_id = ?", Timestamp.from(now), techId);
            String state = String.valueOf(st.get("state"));
            Instant lastObs = ts(st.get("last_observed_at"));
            Instant prevVerified = ts(st.get("last_verified_at"));
            boolean record = bool(s, "recordEvents");

            // Out-of-order / stale reading: report the current state, change nothing.
            if (tooOld || (lastObs != null && obs.isBefore(lastObs))) return stateView(techId, attId, s, false, null, null);

            // Location error (permission denied / GPS off / no fix): unverified, never "outside".
            if (b.error() != null || b.latitude() == null || b.longitude() == null) {
                if (!"OUTSIDE_SHOP".equals(state) && !"ON_AUTHORIZED_DUTY".equals(state) && !"LOCATION_UNVERIFIED".equals(state)) {
                    jdbc.update("UPDATE employee_presence_state SET state = 'LOCATION_UNVERIFIED', updated_at = now() WHERE technician_id = ?", techId);
                    if (record) event(techId, shopId, attId, "LOCATION_UNVERIFIED", obs, null, null, radius, null, null, "APP");
                }
                return stateView(techId, attId, s, false, null, b.error() == null ? "NO_FIX" : b.error());
            }

            double lat = b.latitude(), lng = b.longitude();
            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new IllegalArgumentException("Invalid coordinates");
            int distance = (int) Math.round(haversine(lat, lng, coords[0], coords[1]));
            Integer acc = b.accuracy() == null ? null : (int) Math.round(b.accuracy());
            boolean accurate = acc == null || acc <= MAX_ACCURACY_M;
            jdbc.update("UPDATE employee_presence_state SET last_observed_at = ?, last_distance_m = ?, last_accuracy_m = ?, "
                            + "last_verified_at = CASE WHEN ? THEN ? ELSE last_verified_at END, updated_at = now() WHERE technician_id = ?",
                    Timestamp.from(obs), distance, acc, accurate, Timestamp.from(obs), techId);

            // Recovery: first verified reading after a location error or a gap longer than the
            // verification timeout. Records only that verification resumed (with the real gap);
            // it never invents an inside / outside transition.
            if (accurate && record) {
                boolean gap = prevVerified != null && secs(prevVerified, obs) > availability.timeoutSeconds();
                boolean afterError = false;
                if ("LOCATION_UNVERIFIED".equals(state)) {
                    Instant err = ts(jdbc.queryForObject("SELECT MAX(observed_at) FROM employee_presence_events WHERE technician_id = ? "
                            + "AND attendance_id = ? AND event_type = 'LOCATION_UNVERIFIED'", Object.class, techId, attId));
                    afterError = prevVerified == null || (err != null && prevVerified.isBefore(err));
                }
                if (gap || afterError) {
                    event(techId, shopId, attId, "LOCATION_VERIFIED", obs, distance, acc, radius, null,
                            prevVerified == null ? null : secs(prevVerified, obs), "SERVER");
                }
            }

            // Authorized duty: no freeze; close an open outside interval first.
            if (duty != null) {
                if (!"ON_AUTHORIZED_DUTY".equals(state)) {
                    Instant os = ts(st.get("outside_since"));
                    if ("OUTSIDE_SHOP".equals(state) && os != null && record)
                        event(techId, shopId, attId, "OUTSIDE_ENDED", obs, distance, acc, radius, null, secs(os, obs), "SERVER");
                    jdbc.update("UPDATE employee_presence_state SET state = 'ON_AUTHORIZED_DUTY', duty_since = ?, duty_ref = ?, "
                            + "outside_since = NULL, pending_outside_since = NULL WHERE technician_id = ?", Timestamp.from(obs), duty, techId);
                    if (record) event(techId, shopId, attId, "DUTY_STARTED", obs, distance, acc, radius, duty, null, "SERVER");
                }
                return stateView(techId, attId, s, accurate, distance, null);
            }
            if ("ON_AUTHORIZED_DUTY".equals(state)) {
                Instant ds = ts(st.get("duty_since"));
                if (record) event(techId, shopId, attId, "DUTY_ENDED", obs, distance, acc, radius, (String) st.get("duty_ref"), ds == null ? null : secs(ds, obs), "SERVER");
                // Duty over: the normal rule resumes with a fresh grace period.
                jdbc.update("UPDATE employee_presence_state SET state = 'INSIDE_SHOP', duty_since = NULL, duty_ref = NULL, pending_outside_since = NULL WHERE technician_id = ?", techId);
                state = "INSIDE_SHOP";
                st.put("pending_outside_since", null);
            }
            if (!accurate) return stateView(techId, attId, s, false, distance, "LOW_ACCURACY");

            // Hysteresis: inside at <= radius; outside only beyond radius + margin.
            int margin = Math.max(10, Math.min(acc == null ? 10 : acc, 30));
            boolean inside = distance <= radius;
            boolean outside = distance > radius + margin;

            if (inside) {
                if ("OUTSIDE_SHOP".equals(state)) {
                    boolean unlock = bool(s, "autoUnlock") || Boolean.TRUE.equals(b.manual());
                    if (unlock) {
                        Instant os = ts(st.get("outside_since"));
                        jdbc.update("UPDATE employee_presence_state SET state = 'INSIDE_SHOP', outside_since = NULL, pending_outside_since = NULL, "
                                + "last_return_at = ? WHERE technician_id = ?", Timestamp.from(obs), techId);
                        if (record) event(techId, shopId, attId, "RETURNED", obs, distance, acc, radius, null, os == null ? null : secs(os, obs), "SERVER");
                        notifyPresence(t, shopId, false, obs, os);
                    }
                } else {
                    jdbc.update("UPDATE employee_presence_state SET state = 'INSIDE_SHOP', pending_outside_since = NULL WHERE technician_id = ?", techId);
                }
            } else if (outside && !"OUTSIDE_SHOP".equals(state)) {
                Instant pending = ts(st.get("pending_outside_since"));
                if (pending == null) {
                    pending = obs;
                    jdbc.update("UPDATE employee_presence_state SET pending_outside_since = ? WHERE technician_id = ?", Timestamp.from(obs), techId);
                }
                if (secs(pending, obs) >= grace) {
                    jdbc.update("UPDATE employee_presence_state SET state = 'OUTSIDE_SHOP', outside_since = ?, pending_outside_since = NULL, "
                            + "last_exit_at = ? WHERE technician_id = ?", Timestamp.from(pending), Timestamp.from(pending), techId);
                    if (record) event(techId, shopId, attId, "EXITED", pending, distance, acc, radius, null, null, "SERVER");
                    notifyPresence(t, shopId, true, pending, null);
                }
            }
            return stateView(techId, attId, s, true, distance, null);
        });
        out.putAll(result);
        return out;
    }

    /** The caller's own presence for a day (default today): summary + timeline. */
    @GetMapping("/me/presence")
    public Map<String, Object> myPresence(HttpServletRequest req, @RequestParam(required = false) String date) {
        Map<String, Object> t = me(req);
        return dayDetail(t, parseDate(date));
    }

    // ------------------------------------------------------------ owner APIs

    /** Every employee of the caller's shop for a day: current presence + counts. */
    @GetMapping("/presence")
    public Map<String, Object> shopPresence(HttpServletRequest req, @RequestParam(required = false) String date) {
        requireOwner(req);
        UUID shopId = shop(req);
        LocalDate day = parseDate(date);
        List<Map<String, Object>> techs = jdbc.queryForList(
                "SELECT id, shop_id, user_id, name, role_label FROM technicians WHERE shop_id = ? ORDER BY name", shopId);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of("checkedIn", "inside", "outside", "onDuty", "unverified", "checkedOut")) counts.put(k, 0);
        for (Map<String, Object> t : techs) {
            Map<String, Object> d = daySummary(t, day);
            rows.add(d);
            String p = String.valueOf(d.get("presence"));
            if (Boolean.TRUE.equals(d.get("checkedIn"))) counts.merge("checkedIn", 1, Integer::sum);
            switch (p) {
                case "INSIDE_SHOP" -> counts.merge("inside", 1, Integer::sum);
                case "OUTSIDE_SHOP" -> counts.merge("outside", 1, Integer::sum);
                case "ON_AUTHORIZED_DUTY" -> counts.merge("onDuty", 1, Integer::sum);
                case "LOCATION_UNVERIFIED" -> counts.merge("unverified", 1, Integer::sum);
                case "CHECKED_OUT" -> counts.merge("checkedOut", 1, Integer::sum);
                default -> { }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("shopName", shopName(shopId));
        out.put("settings", settings(shopId));
        out.put("counts", counts);
        out.put("employees", rows);
        return out;
    }

    /** One employee's presence for a day (owner, own shop only). */
    @GetMapping("/{id}/presence")
    public Map<String, Object> employeePresence(HttpServletRequest req, @PathVariable UUID id, @RequestParam(required = false) String date) {
        requireOwner(req);
        UUID shopId = shop(req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, shop_id, user_id, name, role_label FROM technicians WHERE id = ? AND shop_id = ?", id, shopId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
        Map<String, Object> d = dayDetail(rows.get(0), parseDate(date));
        // Last 30 days, for weekly / monthly totals.
        LocalDate to = LocalDate.now(IST);
        List<Map<String, Object>> days = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList(
                "SELECT attendance_date FROM technician_attendance WHERE technician_id = ? AND attendance_date BETWEEN ? AND ? AND check_in_time IS NOT NULL ORDER BY attendance_date DESC",
                id, Date.valueOf(to.minusDays(29)), Date.valueOf(to))) {
            Map<String, Object> s = daySummary(rows.get(0), ((Date) a.get("attendance_date")).toLocalDate());
            s.remove("employeeId"); s.remove("name"); s.remove("role");
            days.add(s);
        }
        d.put("recentDays", days);
        return d;
    }

    /**
     * Assignment availability of the caller's shop employees (or one, via employeeId) — server-evaluated,
     * server time. Read-only; the same rules gate assignment APIs when enforcement is enabled.
     */
    @GetMapping("/presence/availability")
    public Map<String, Object> availability(HttpServletRequest req, @RequestParam(required = false) UUID employeeId) {
        requireOwner(req);
        UUID shopId = shop(req);
        List<Map<String, Object>> techs = employeeId == null
                ? jdbc.queryForList("SELECT id, name, role_label FROM technicians WHERE shop_id = ? ORDER BY name", shopId)
                : jdbc.queryForList("SELECT id, name, role_label FROM technicians WHERE shop_id = ? AND (id = ? OR user_id = ?)", shopId, employeeId, employeeId);
        if (employeeId != null && techs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : techs) {
            Map<String, Object> m = availability.evaluate(shopId, (UUID) t.get("id")).toMap();
            m.put("name", t.get("name"));
            m.put("role", t.get("role_label"));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverTime", Instant.now().toString());
        out.put("verificationTimeoutSeconds", availability.timeoutSeconds());
        out.put("enforcementEnabled", availability.enforcementEnabled());
        out.put("employees", rows);
        return out;
    }

    @GetMapping("/presence/settings")
    public Map<String, Object> getSettings(HttpServletRequest req) {
        return settings(shop(req));
    }

    @PutMapping("/presence/settings")
    public Map<String, Object> putSettings(HttpServletRequest req, @RequestBody SettingsRequest b) {
        requireOwner(req);
        UUID shopId = shop(req);
        UUID userId = attr(req, "userId");
        Map<String, Object> cur = settings(shopId);
        int radius = b.radiusMeters() == null ? num(cur, "radiusMeters") : b.radiusMeters();
        int grace = b.graceSeconds() == null ? num(cur, "graceSeconds") : b.graceSeconds();
        int alert = b.prolongedAlertMinutes() == null ? num(cur, "prolongedAlertMinutes") : b.prolongedAlertMinutes();
        if (radius < 20 || radius > 500) throw new IllegalArgumentException("Shop radius must be between 20 and 500 metres");
        if (grace < 30 || grace > 1800) throw new IllegalArgumentException("Exit grace period must be between 30 seconds and 30 minutes");
        if (alert < 0 || alert > 480) throw new IllegalArgumentException("Prolonged outside alert must be between 0 and 480 minutes");
        Object[] vals = {pick(b.monitoringEnabled(), cur, "monitoringEnabled"), pick(b.autoFreeze(), cur, "autoFreeze"), radius, grace,
                pick(b.autoUnlock(), cur, "autoUnlock"), pick(b.recordEvents(), cur, "recordEvents"), pick(b.dutyExemption(), cur, "dutyExemption"),
                alert, userId, shopId};
        String update = "UPDATE shop_presence_settings SET monitoring_enabled = ?, auto_freeze = ?, radius_m = ?, grace_seconds = ?, auto_unlock = ?, "
                + "record_events = ?, duty_exemption = ?, prolonged_alert_minutes = ?, updated_by = ?, version = version + 1, updated_at = now() WHERE shop_id = ?";
        if (jdbc.update(update, vals) == 0) {
            try {
                jdbc.update("INSERT INTO shop_presence_settings (monitoring_enabled, auto_freeze, radius_m, grace_seconds, auto_unlock, record_events, "
                        + "duty_exemption, prolonged_alert_minutes, updated_by, shop_id) VALUES (?,?,?,?,?,?,?,?,?,?)", vals);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                jdbc.update(update, vals); // another owner session inserted it first
            }
        }
        return settings(shopId);
    }

    // ------------------------------------------------------------ views

    private Map<String, Object> stateView(UUID techId, UUID attId, Map<String, Object> s, boolean verified, Integer distance, String reason) {
        Map<String, Object> st = jdbc.queryForMap("SELECT * FROM employee_presence_state WHERE technician_id = ?", techId);
        String state = String.valueOf(st.get("state"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("state", state);
        v.put("verified", verified);
        if (reason != null) v.put("reason", reason);
        v.put("distanceMeters", distance != null ? distance : st.get("last_distance_m"));
        v.put("accuracyMeters", st.get("last_accuracy_m"));
        v.put("radiusMeters", num(s, "radiusMeters"));
        v.put("lastVerifiedAt", iso(st.get("last_verified_at")));
        v.put("lastExitAt", iso(st.get("last_exit_at")));
        v.put("lastReturnAt", iso(st.get("last_return_at")));
        v.put("dutyRef", st.get("duty_ref"));
        Instant pending = ts(st.get("pending_outside_since"));
        if (pending != null && !"OUTSIDE_SHOP".equals(state)) {
            v.put("graceRemainingSeconds", Math.max(0, num(s, "graceSeconds") - secs(pending, Instant.now())));
        }
        v.put("freeze", "OUTSIDE_SHOP".equals(state) && bool(s, "autoFreeze"));
        v.putAll(totals(techId, attId, st, null));
        return v;
    }

    /** Exits, outside and duty seconds for one attendance session (open intervals run to `end` / now). */
    private Map<String, Object> totals(UUID techId, UUID attId, Map<String, Object> st, Instant end) {
        long outside = 0, duty = 0;
        int exits = 0;
        for (Map<String, Object> e : jdbc.queryForList(
                "SELECT event_type, duration_seconds FROM employee_presence_events WHERE technician_id = ? AND attendance_id = ?", techId, attId)) {
            String type = String.valueOf(e.get("event_type"));
            long d = e.get("duration_seconds") == null ? 0 : ((Number) e.get("duration_seconds")).longValue();
            if ("EXITED".equals(type)) exits++;
            if ("RETURNED".equals(type) || "OUTSIDE_ENDED".equals(type)) outside += d;
            if ("DUTY_ENDED".equals(type)) duty += d;
        }
        boolean open = false;
        Instant until = end != null ? end : Instant.now();
        if (st != null && attId.equals(st.get("attendance_id"))) {
            String state = String.valueOf(st.get("state"));
            Instant os = ts(st.get("outside_since"));
            Instant ds = ts(st.get("duty_since"));
            if ("OUTSIDE_SHOP".equals(state) && os != null) { outside += secs(os, until); open = end == null; }
            if ("ON_AUTHORIZED_DUTY".equals(state) && ds != null) duty += secs(ds, until);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outsideVisits", exits);
        m.put("outsideSeconds", outside);
        m.put("dutySeconds", duty);
        m.put("outsideOpen", open);
        return m;
    }

    private Map<String, Object> daySummary(Map<String, Object> t, LocalDate day) {
        UUID techId = (UUID) t.get("id");
        Map<String, Object> att = attendance(techId, day);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("employeeId", techId);
        d.put("name", t.get("name"));
        d.put("role", t.get("role_label"));
        d.put("date", day.toString());
        boolean today = day.equals(LocalDate.now(IST));
        if (att == null || att.get("check_in_time") == null) {
            d.put("checkedIn", false);
            d.put("presence", "NOT_CHECKED_IN");
            return d;
        }
        UUID attId = (UUID) att.get("id");
        d.put("checkInTime", str(att.get("check_in_time")));
        d.put("checkOutTime", str(att.get("check_out_time")));
        d.put("attendanceStatus", att.get("status"));
        boolean out = att.get("check_out_time") != null;
        d.put("checkedIn", !out && today);
        List<Map<String, Object>> sts = jdbc.queryForList("SELECT * FROM employee_presence_state WHERE technician_id = ?", techId);
        Map<String, Object> st = sts.isEmpty() ? null : sts.get(0);
        boolean current = st != null && attId.equals(st.get("attendance_id"));
        Instant endAt = out ? atIst(day, att.get("check_out_time")) : today ? null : atIst(day, LocalTime.of(23, 59, 59));
        d.putAll(totals(techId, attId, current ? st : null, endAt));
        if (current) {
            d.put("lastVerifiedAt", iso(st.get("last_verified_at")));
            d.put("lastExitAt", iso(st.get("last_exit_at")));
            d.put("lastReturnAt", iso(st.get("last_return_at")));
            d.put("distanceMeters", st.get("last_distance_m"));
        }
        String presence;
        if (out) presence = "CHECKED_OUT";
        else if (!today) presence = "LOCATION_UNVERIFIED"; // a past day never checked out
        else if (!current || st.get("last_verified_at") == null) presence = "LOCATION_UNVERIFIED";
        else {
            presence = String.valueOf(st.get("state"));
            Instant lv = ts(st.get("last_verified_at"));
            // Never present an old reading as live presence (duty is server data, not GPS).
            if (!"ON_AUTHORIZED_DUTY".equals(presence) && lv != null && lv.isBefore(Instant.now().minusSeconds(availability.timeoutSeconds()))) {
                d.put("stale", true);
                if ("INSIDE_SHOP".equals(presence)) presence = "LOCATION_UNVERIFIED";
            }
        }
        d.put("presence", presence);
        return d;
    }

    private Map<String, Object> dayDetail(Map<String, Object> t, LocalDate day) {
        Map<String, Object> d = daySummary(t, day);
        UUID shopId = (UUID) t.get("shop_id");
        d.put("shopName", shopName(shopId));
        d.put("settings", settings(shopId));
        Map<String, Object> att = attendance((UUID) t.get("id"), day);
        List<Map<String, Object>> timeline = new ArrayList<>();
        if (att != null && att.get("check_in_time") != null) {
            timeline.add(tl("CHECKED_IN", atIst(day, att.get("check_in_time")), null, null));
            for (Map<String, Object> e : jdbc.queryForList(
                    "SELECT event_type, observed_at, duration_seconds, duty_ref, distance_m, radius_m FROM employee_presence_events "
                            + "WHERE technician_id = ? AND attendance_id = ? ORDER BY observed_at, received_at", t.get("id"), att.get("id"))) {
                Map<String, Object> x = tl(String.valueOf(e.get("event_type")), ts(e.get("observed_at")), e.get("duration_seconds"), e.get("duty_ref"));
                x.put("distanceMeters", e.get("distance_m"));
                x.put("radiusMeters", e.get("radius_m")); // lets the graph place a LOCATION_VERIFIED recovery inside / outside
                timeline.add(x);
            }
            if (att.get("check_out_time") != null) timeline.add(tl("CHECKED_OUT", atIst(day, att.get("check_out_time")), null, null));
        }
        d.put("timeline", timeline);
        return d;
    }

    private static Map<String, Object> tl(String type, Instant at, Object duration, Object ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("at", at == null ? null : at.toString());
        m.put("durationSeconds", duration);
        m.put("dutyRef", ref);
        return m;
    }

    // ------------------------------------------------------------ data helpers

    private void event(UUID techId, UUID shopId, UUID attId, String type, Instant observed, Integer distance, Integer acc,
                       int radius, String dutyRef, Long duration, String source) {
        // Unique (technician, session, type, observed time): a retry never adds a second record.
        jdbc.update("INSERT INTO employee_presence_events (id, technician_id, shop_id, attendance_id, event_type, observed_at, received_at, "
                        + "distance_m, accuracy_m, radius_m, duty_ref, duration_seconds, source) SELECT ?,?,?,?,?,?,now(),?,?,?,?,?,? "
                        + "WHERE NOT EXISTS (SELECT 1 FROM employee_presence_events WHERE technician_id = ? AND attendance_id = ? AND event_type = ? AND observed_at = ?)",
                UUID.randomUUID(), techId, shopId, attId, type, Timestamp.from(observed), distance, acc, radius, dutyRef, duration, source,
                techId, attId, type, Timestamp.from(observed));
    }

    private Map<String, Object> attendance(UUID techId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, check_in_time, check_out_time, status FROM technician_attendance WHERE technician_id = ? AND attendance_date = ? "
                        + "ORDER BY check_in_time NULLS LAST LIMIT 1", techId, Date.valueOf(day));
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> settings(UUID shopId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM shop_presence_settings WHERE shop_id = ?", shopId);
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("monitoringEnabled", r.getOrDefault("monitoring_enabled", true));
        s.put("autoFreeze", r.getOrDefault("auto_freeze", true));
        s.put("radiusMeters", r.getOrDefault("radius_m", 50));
        s.put("graceSeconds", r.getOrDefault("grace_seconds", 120));
        s.put("autoUnlock", r.getOrDefault("auto_unlock", true));
        s.put("recordEvents", r.getOrDefault("record_events", true));
        s.put("dutyExemption", r.getOrDefault("duty_exemption", true));
        s.put("prolongedAlertMinutes", r.getOrDefault("prolonged_alert_minutes", 0));
        s.put("version", r.getOrDefault("version", 0));
        return s;
    }

    private double[] shopCoords(UUID shopId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("SELECT latitude, longitude FROM shops WHERE id = ?", shopId);
            if (row.get("latitude") == null || row.get("longitude") == null) return null;
            double lat = ((Number) row.get("latitude")).doubleValue(), lng = ((Number) row.get("longitude")).doubleValue();
            if (lat == 0 && lng == 0) return null;
            return new double[]{lat, lng};
        } catch (Exception e) {
            return null;
        }
    }

    private String shopName(UUID shopId) {
        try {
            return jdbc.queryForList("SELECT name FROM shops WHERE id = ?", String.class, shopId).stream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ small utils

    private static LocalDate parseDate(String d) {
        if (d == null || d.isBlank()) return LocalDate.now(IST);
        try { return LocalDate.parse(d); } catch (Exception e) { throw new IllegalArgumentException("date must be YYYY-MM-DD"); }
    }

    private static boolean bool(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
    private static int num(Map<String, Object> m, String k) { return ((Number) m.get(k)).intValue(); }
    private static boolean pick(Boolean v, Map<String, Object> cur, String k) { return v != null ? v : bool(cur, k); }
    private static long secs(Instant a, Instant b) { return Math.max(0, Duration.between(a, b).getSeconds()); }
    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Instant ts(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime t) return t.toInstant();
        if (o instanceof Instant t) return t;
        return null;
    }

    private static String iso(Object o) { Instant i = ts(o); return i == null ? null : i.toString(); }

    private static Instant atIst(LocalDate day, Object time) {
        if (time == null) return null;
        LocalTime lt = time instanceof Time t ? t.toLocalTime() : time instanceof LocalTime t ? t : LocalTime.parse(time.toString());
        return day.atTime(lt).atZone(IST).toInstant();
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
