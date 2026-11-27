package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.TicketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

/**
 * Home → Reports → Service Status. ONE classification ({@link #BUCKET_SQL})
 * feeds both the card counts and the card's paged list, so a card's number is
 * always the list's total. Every service record (original or Re-Service) lands
 * in exactly one current-status bucket — no double counting.
 *
 * <p>Filters: kind (ALL | ORIGINAL | RE_SERVICE | WARRANTY | PAID | FREE_REWORK)
 * and a booking-date period (from/to, India time). "Delivered in period" is a
 * separate historical figure keyed on the actual hand-over time.
 */
@Service
@RequiredArgsConstructor
public class ServiceStatusReportService {

    /** Card order shown in the app. */
    public static final List<String> BUCKETS = List.of(
            "SERVICE_ACCEPTED", "TECHNICIAN_ASSIGNED", "TECHNICIAN_ACCEPTED", "IN_PROCESS", "WORK_PENDING",
            "QC_PENDING", "READY_FOR_DELIVERY", "INVOICE_READY", "DELIVERY_PROCESSING", "DELIVERED", "RETURNED", "CANCELLED");
    private static final Set<String> CLOSED = Set.of("DELIVERED", "RETURNED", "CANCELLED");
    private static final Set<String> KINDS = Set.of("ALL", "ORIGINAL", "RE_SERVICE", "WARRANTY", "PAID", "FREE_REWORK");

    /**
     * Current-cycle step flags per ticket. Rows before a legacy RE_SERVICE marker
     * (old reopen flow) belong to the earlier repair and are ignored.
     */
    private static final String BASE_SQL = """
            SELECT t.id, t.created_at, t.root_ticket_id, t.service_type, UPPER(COALESCE(t.status, '')) AS s,
                   t.assigned_technician_id, t.technician_accepted_at,
                   COALESCE(ev.rc, false) AS rc, COALESCE(ev.qc, false) AS qc, COALESCE(ev.rej, false) AS rej
            FROM tickets t
            LEFT JOIN LATERAL (
                SELECT bool_or(UPPER(e.status) = 'REPAIR_COMPLETED')        AS rc,
                       bool_or(UPPER(e.status) = 'QUALITY_CHECK_COMPLETED') AS qc,
                       bool_or(UPPER(e.status) = 'CUSTOMER_REJECTED')       AS rej
                FROM repair_bookings b
                JOIN repair_booking_events e ON e.booking_id = b.id
                WHERE b.ticket_id = t.id
                  AND e.created_at >= COALESCE((SELECT MAX(m.created_at) FROM repair_booking_events m
                                                WHERE m.booking_id = b.id AND UPPER(m.status) = 'RE_SERVICE'),
                                               '-infinity'::timestamptz)
            ) ev ON true
            WHERE t.shop_id = ?
            """;

    /** Mutually exclusive: the first matching branch wins. */
    private static final String BUCKET_SQL = """
            CASE
              WHEN s = 'CANCELLED' THEN 'CANCELLED'
              WHEN s = 'RETURNED' OR (rej AND s IN ('DELIVERED', 'DELIVERED_TO_CUSTOMER', 'CLOSED', 'COMPLETED')) THEN 'RETURNED'
              WHEN s IN ('DELIVERED', 'DELIVERED_TO_CUSTOMER', 'CLOSED', 'COMPLETED') THEN 'DELIVERED'
              WHEN s IN ('DELIVERED_PROCESSING', 'RETURN_DELIVERY') THEN 'DELIVERY_PROCESSING'
              WHEN s IN ('INVOICE_GENERATED', 'INVOICE_READY') THEN 'INVOICE_READY'
              WHEN s = 'READY' THEN 'READY_FOR_DELIVERY'
              WHEN s IN ('QUOTED', 'APPROVED') THEN 'WORK_PENDING'
              WHEN s IN ('IN_DIAGNOSIS', 'IN_REPAIR') AND rc AND NOT qc THEN 'QC_PENDING'
              WHEN s IN ('IN_DIAGNOSIS', 'IN_REPAIR') THEN 'IN_PROCESS'
              WHEN assigned_technician_id IS NOT NULL AND technician_accepted_at IS NOT NULL THEN 'TECHNICIAN_ACCEPTED'
              WHEN assigned_technician_id IS NOT NULL THEN 'TECHNICIAN_ASSIGNED'
              ELSE 'SERVICE_ACCEPTED'
            END
            """;

    private final JdbcTemplate jdbc;
    private final TicketService ticketService;

    public record Filter(String kind, LocalDate from, LocalDate to) {}

    public static Filter filter(String kind, String from, String to) {
        String k = kind == null || kind.isBlank() ? "ALL" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(k)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown service kind: " + kind);
        try {
            LocalDate f = from == null || from.isBlank() ? null : LocalDate.parse(from.trim());
            LocalDate t = to == null || to.isBlank() ? null : LocalDate.parse(to.trim());
            if (f != null && t != null && t.isBefore(f)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
            return new Filter(k, f, t);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must be YYYY-MM-DD.");
        }
    }

    /** kind + booking-date conditions on the base rows (alias-free; applied inside BASE_SQL's WHERE). */
    private static void where(StringBuilder sql, List<Object> args, Filter f, boolean bookingDate) {
        switch (f.kind()) {
            case "ORIGINAL" -> sql.append(" AND t.service_type IS NULL");
            case "RE_SERVICE" -> sql.append(" AND t.service_type IS NOT NULL");
            case "WARRANTY", "PAID", "FREE_REWORK" -> { sql.append(" AND t.service_type = ?"); args.add(f.kind()); }
            default -> { }
        }
        if (!bookingDate) return;
        if (f.from() != null) { sql.append(" AND t.created_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Kolkata'"); args.add(f.from().toString()); }
        if (f.to() != null) { sql.append(" AND t.created_at < ((?::date) + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'"); args.add(f.to().toString()); }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID shopId, Filter f) {
        List<Object> args = new ArrayList<>(List.of(shopId));
        StringBuilder base = new StringBuilder(BASE_SQL);
        where(base, args, f, true);
        String sql = "SELECT " + BUCKET_SQL + " AS bucket, COUNT(*) AS n, COUNT(DISTINCT COALESCE(root_ticket_id, id)) AS chains,"
                + " COUNT(*) FILTER (WHERE service_type IS NULL) AS originals"
                + " FROM (" + base + ") x GROUP BY 1";
        Map<String, Long> counts = new LinkedHashMap<>();
        BUCKETS.forEach(b -> counts.put(b, 0L));
        long total = 0, originals = 0, active = 0;
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            String b = String.valueOf(r.get("bucket"));
            long n = ((Number) r.get("n")).longValue();
            counts.merge(b, n, Long::sum);
            total += n;
            originals += ((Number) r.get("originals")).longValue();
            if (!CLOSED.contains(b)) active += n;
        }
        // Unique bookings across buckets (a chain can sit in two buckets via two visits).
        List<Object> uArgs = new ArrayList<>(List.of(shopId));
        StringBuilder uBase = new StringBuilder(BASE_SQL);
        where(uBase, uArgs, f, true);
        Long unique = jdbc.queryForObject("SELECT COUNT(DISTINCT COALESCE(root_ticket_id, id)) FROM (" + uBase + ") x", Long.class, uArgs.toArray());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("uniqueBookings", unique == null ? 0 : unique);
        summary.put("totalVisits", total);
        summary.put("originalServices", originals);
        summary.put("reServiceVisits", total - originals);
        summary.put("activeServices", active);
        summary.put("deliveredInPeriod", deliveredInPeriod(shopId, f));

        List<Map<String, Object>> cards = new ArrayList<>();
        counts.forEach((k, v) -> cards.add(Map.of("key", k, "count", v)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", cards);
        out.put("summary", summary);
        out.put("kind", f.kind());
        out.put("from", f.from());
        out.put("to", f.to());
        return out;
    }

    /** Services handed over in the period (actual hand-over time: doorstep delivery, else the shop's DELIVERED step). */
    private long deliveredInPeriod(UUID shopId, Filter f) {
        List<Object> args = new ArrayList<>(List.of(shopId));
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM (
                  SELECT t.id, COALESCE(d.delivered_at,
                         (SELECT MAX(e.created_at) FROM repair_bookings b JOIN repair_booking_events e ON e.booking_id = b.id
                          WHERE b.ticket_id = t.id AND UPPER(e.status) = 'DELIVERED')) AS delivered_at
                  FROM tickets t LEFT JOIN repair_deliveries d ON d.ticket_id = t.id
                  WHERE t.shop_id = ? AND UPPER(COALESCE(t.status, '')) IN ('DELIVERED', 'DELIVERED_TO_CUSTOMER', 'CLOSED', 'COMPLETED')
                """);
        where(sql, args, f, false);
        sql.append(") x WHERE delivered_at IS NOT NULL");
        if (f.from() != null) { sql.append(" AND delivered_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Kolkata'"); args.add(f.from().toString()); }
        if (f.to() != null) { sql.append(" AND delivered_at < ((?::date) + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'"); args.add(f.to().toString()); }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** One card's services, newest first, server-paged. total = the card's count. */
    @Transactional(readOnly = true)
    public Page<TicketResponse> list(UUID shopId, String bucket, Filter f, String q, int page, int size) {
        String b = bucket == null ? "" : bucket.trim().toUpperCase(Locale.ROOT);
        boolean active = "ACTIVE".equals(b);
        if (!active && !"ALL".equals(b) && !BUCKETS.contains(b)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status card: " + bucket);
        int p = Math.max(0, page), sz = Math.min(Math.max(1, size), 100);
        List<Object> args = new ArrayList<>(List.of(shopId));
        StringBuilder base = new StringBuilder(BASE_SQL);
        where(base, args, f, true);
        String query = q == null ? "" : q.trim();
        if (!query.isEmpty()) {
            // Tracking id prefix finds the whole chain: GTS-231 → GTS-231, -R01, -R02.
            base.append(" AND (t.tracking_id ILIKE ? OR t.customer_name ILIKE ? OR t.customer_phone ILIKE ? OR t.imei ILIKE ? OR t.serial_number ILIKE ?)");
            String like = "%" + query.replace("%", "").replace("_", "\\_") + "%";
            args.add(query.replace("%", "") + "%"); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        String classified = "SELECT id, created_at, " + BUCKET_SQL + " AS bucket FROM (" + base + ") x";
        String filter = active ? " WHERE bucket NOT IN ('DELIVERED','RETURNED','CANCELLED')" : "ALL".equals(b) ? "" : " WHERE bucket = ?";
        List<Object> fArgs = new ArrayList<>(args);
        if (!active && !"ALL".equals(b)) fArgs.add(b);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + classified + ") y" + filter, Long.class, fArgs.toArray());
        List<Object> pArgs = new ArrayList<>(fArgs);
        pArgs.add(sz); pArgs.add((long) p * sz);
        List<UUID> ids = jdbc.queryForList("SELECT id FROM (" + classified + ") y" + filter + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                UUID.class, pArgs.toArray());
        return new PageImpl<>(ticketService.responsesFor(shopId, ids), PageRequest.of(p, sz), total == null ? 0 : total);
    }
}
