package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repairshop.saas.ticket.dto.TicketRequest;
import com.repairshop.saas.ticket.entity.Invoice;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.exception.ResourceNotFoundException;
import com.repairshop.saas.ticket.repository.InvoiceRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-Service as linked child tickets (migration 129).
 *
 * <p>A delivered device that comes back gets a NEW ticket linked to the service
 * before it — GTS-231 → GTS-231-R01 → GTS-231-R02. The delivered ticket is never
 * reopened or changed, so its complaint, estimate, history, invoice, payment,
 * technician, photos and delivery stay exactly as they were. Every ticket owns
 * its own invoice / delivery / ledger rows, so nothing else needs splitting.
 *
 * <p>Rules (enforced here, not just in the app):
 * <ul>
 *   <li>Shop-scoped: another shop's ticket is "not found".</li>
 *   <li>Only a DELIVERED service with no Re-Service yet can be re-serviced, so a
 *       chain has at most one active service (uq_tickets_parent_ticket_id).</li>
 *   <li>Idempotent: a retry carrying the same reServiceRequestId returns the
 *       record the first attempt created.</li>
 *   <li>WARRANTY needs a delivered service in the chain whose per-service
 *       warranty (priceItemsJson "warranty": "6 Months") still covers today.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReServiceLinkService {

    public static final Set<String> TYPES = Set.of("WARRANTY", "PAID", "FREE_REWORK");
    public static final Set<String> REASONS = Set.of("SAME_ISSUE", "NEW_ISSUE", "NOT_RESOLVED", "REPEATED", "OTHER");
    /** Return reasons a warranty can cover: the repaired fault came back / wasn't fixed. */
    public static final Set<String> WARRANTY_REASONS = Set.of("SAME_ISSUE", "NOT_RESOLVED", "REPEATED");
    /** Services that carry their own warranty: the original repair and paid Re-Services.
     *  Warranty / free rework never restart or extend the warranty. */
    static boolean grantsWarranty(Ticket t) {
        return t.getServiceType() == null || "PAID".equals(t.getServiceType());
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MONTHS = Pattern.compile("(\\d+)");

    private final TicketRepository ticketRepository;
    private final InvoiceRepository invoiceRepository;
    private final JdbcTemplate jdbc;

    /** existing != null → idempotent retry: return it as-is. Otherwise create with these link values. */
    public record Prepared(Ticket existing, Ticket parent, UUID rootId, int cycle,
                           TicketNumberService.Assigned number, String notesJson) {}

    @Transactional
    public Prepared prepare(UUID shopId, TicketRequest req) {
        String type = upper(req.getServiceType());
        String reason = upper(req.getReturnReason());
        if (!TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a Re-Service type: Warranty, Paid or Free Rework.");
        }
        if (!REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a return reason.");
        }
        Ticket parent = ticketRepository.findByShopIdAndId(shopId, req.getReServiceOf())
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + req.getReServiceOf()));
        // Serialise concurrent Re-Service creates on this service (double tap, two phones).
        jdbc.queryForList("SELECT id FROM tickets WHERE id = ? FOR UPDATE", parent.getId());

        Optional<Ticket> child = ticketRepository.findFirstByShopIdAndParentTicketId(shopId, parent.getId());
        if (child.isPresent()) {
            String key = trim(req.getReServiceRequestId());
            // Same request retried (same key, same type) → the record it created.
            // Anything else is a different request: refuse, never hand back another record.
            if (key != null && key.equals(child.get().getReServiceRequestId())
                    && type.equals(child.get().getServiceType())) {
                return new Prepared(child.get(), parent, null, 0, null, null);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This service already has a Re-Service ("
                    + child.get().getTrackingId() + "). Open the latest service to re-service it again.");
        }
        if (!"DELIVERED".equalsIgnoreCase(parent.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a delivered service can be re-serviced.");
        }

        UUID rootId = parent.getRootTicketId() != null ? parent.getRootTicketId() : parent.getId();
        Ticket root = rootId.equals(parent.getId()) ? parent
                : ticketRepository.findByShopIdAndId(shopId, rootId).orElse(parent);
        int cycle = (parent.getServiceCycleNo() == null ? 0 : parent.getServiceCycleNo()) + 1;
        String tracking = root.getTrackingId() + String.format("-R%02d", cycle);

        // References kept with the record: warranty source + the previous invoice.
        ObjectNode notes = parseObject(req.getReServiceNotes());
        Warranty w = warrantyCover(shopId, root);
        if ("WARRANTY".equals(type)) {
            // Warranty covers the repair that was done — not a new or unrelated fault.
            if (!WARRANTY_REASONS.contains(reason)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "A new or different issue isn't covered by the previous repair's warranty. Choose Paid Re-Service or Free Rework.");
            }
            if (w == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No active warranty on this device's previous services. Choose Paid Re-Service or Free Rework.");
            }
            notes.put("warrantyReference", w.trackingId());
            notes.put("warrantyUntil", w.until().toString());
        }
        invoiceRepository.findByTicketId(parent.getId()).ifPresent(inv -> {
            if (inv.getInvoiceNo() != null) notes.put("previousInvoiceNo", inv.getInvoiceNo());
            notes.put("previousInvoiceId", inv.getId().toString());
        });
        notes.put("previousTrackingId", parent.getTrackingId());

        return new Prepared(null, parent, rootId, cycle,
                new TicketNumberService.Assigned(root.getPartnerId(), tracking, tracking, null),
                notes.toString());
    }

    /** Copy the link onto a new ticket (called by TicketService.create before saving). */
    public void apply(Ticket t, Prepared p, TicketRequest req) {
        Ticket parent = p.parent();
        t.setParentTicketId(parent.getId());
        t.setRootTicketId(p.rootId());
        t.setServiceCycleNo(p.cycle());
        t.setServiceType(upper(req.getServiceType()));
        t.setReturnReason(upper(req.getReturnReason()));
        t.setReServiceNotes(p.notesJson());
        t.setReServiceRequestId(trim(req.getReServiceRequestId()));
        // Identity carries forward from the delivered service — not from the client.
        t.setCustomerId(parent.getCustomerId());
        if (t.getCustomerName() == null) t.setCustomerName(parent.getCustomerName());
        if (t.getCustomerPhone() == null) t.setCustomerPhone(parent.getCustomerPhone());
        if (t.getImei() == null) t.setImei(parent.getImei());
        if (t.getSerialNumber() == null) t.setSerialNumber(parent.getSerialNumber());
    }

    /** Every service of the ticket's chain, original first. Read-only summary for the record tabs / invoice history. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> serviceRecords(UUID shopId, UUID ticketId) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        UUID rootId = t.getRootTicketId() != null ? t.getRootTicketId() : t.getId();
        List<Ticket> chain = new ArrayList<>();
        ticketRepository.findByShopIdAndId(shopId, rootId).ifPresent(chain::add);
        chain.addAll(ticketRepository.findByShopIdAndRootTicketIdOrderByServiceCycleNoAsc(shopId, rootId));
        List<UUID> ids = chain.stream().map(Ticket::getId).toList();
        Map<UUID, Invoice> invoices = new HashMap<>();
        invoiceRepository.findByTicketIdIn(ids).forEach(i -> invoices.put(i.getTicketId(), i));
        Map<UUID, Instant> delivered = deliveredAt(ids);
        Map<UUID, Map<String, Object>> deliveries = deliveryRecords(chain, delivered);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Ticket s : chain) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("trackingId", s.getTrackingId());
            m.put("serviceCycleNo", s.getServiceCycleNo() == null ? 0 : s.getServiceCycleNo());
            m.put("serviceType", s.getServiceType() == null ? "ORIGINAL" : s.getServiceType());
            m.put("returnReason", s.getReturnReason());
            m.put("status", s.getStatus());
            m.put("createdAt", s.getCreatedAt());
            m.put("deliveredAt", delivered.get(s.getId()));
            m.put("estimatedPrice", s.getEstimatedPrice());
            m.put("finalPrice", s.getFinalPrice());
            int wm = grantsWarranty(s) ? maxWarrantyMonths(s.getPriceItemsJson()) : 0;
            Instant at = delivered.get(s.getId());
            m.put("warrantyMonths", wm);
            m.put("warrantyUntil", at != null && wm > 0 ? at.atZone(ZoneOffset.UTC).plusMonths(wm).toInstant() : null);
            m.put("reServiceNotes", s.getReServiceNotes());
            m.put("delivery", deliveries.get(s.getId()));
            Invoice inv = invoices.get(s.getId());
            if (inv != null) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("id", inv.getId());
                im.put("invoiceNo", inv.getInvoiceNo());
                im.put("generatedAt", inv.getGeneratedAt() != null ? inv.getGeneratedAt() : inv.getCreatedAt());
                im.put("amount", nz(inv.getFinalPayableAmount()));
                im.put("advancePaid", nz(inv.getAdvancePaid()));
                im.put("amountPaid", nz(inv.getAmountPaid()));
                im.put("creditAmount", nz(inv.getCreditAmount()));
                m.put("invoice", im);
            }
            out.add(m);
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Warranty(String trackingId, Instant until) {}

    /** Latest-ending warranty among the chain's delivered services that still covers now, else null. */
    private Warranty warrantyCover(UUID shopId, Ticket root) {
        List<Ticket> chain = new ArrayList<>();
        chain.add(root);
        chain.addAll(ticketRepository.findByShopIdAndRootTicketIdOrderByServiceCycleNoAsc(shopId, root.getId()));
        Map<UUID, Instant> delivered = deliveredAt(chain.stream().map(Ticket::getId).toList());
        Instant now = Instant.now();
        Warranty best = null;
        for (Ticket s : chain) {
            if (!grantsWarranty(s)) continue;
            Instant at = delivered.get(s.getId());
            int months = maxWarrantyMonths(s.getPriceItemsJson());
            if (at == null || months <= 0) continue;
            Instant until = at.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
            if (until.isAfter(now) && (best == null || until.isAfter(best.until()))) best = new Warranty(s.getTrackingId(), until);
        }
        return best;
    }

    /** Actual hand-over time: the delivery record, else the DELIVERED step on the booking timeline. */
    private Map<UUID, Instant> deliveredAt(List<UUID> ticketIds) {
        Map<UUID, Instant> out = new HashMap<>();
        if (ticketIds.isEmpty()) return out;
        String in = String.join(",", Collections.nCopies(ticketIds.size(), "?"));
        Object[] args = ticketIds.toArray();
        jdbc.query("SELECT ticket_id, delivered_at FROM repair_deliveries WHERE delivered_at IS NOT NULL AND ticket_id IN (" + in + ")",
                rs -> { out.put((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant()); }, args);
        jdbc.query("SELECT b.ticket_id, MAX(e.created_at) FROM repair_booking_events e JOIN repair_bookings b ON b.id = e.booking_id"
                        + " WHERE UPPER(e.status) = 'DELIVERED' AND b.ticket_id IN (" + in + ") GROUP BY b.ticket_id",
                rs -> {
                    UUID id = (UUID) rs.getObject(1);
                    Timestamp ts = rs.getTimestamp(2);
                    if (ts != null) out.putIfAbsent(id, ts.toInstant());
                }, args);
        return out;
    }

    /**
     * Each service's own hand-over: the doorstep delivery row (method, employee,
     * schedule, attempts, OTP, proof photos, failure) or, for a walk-in, the
     * shop's DELIVERED step (who handed it over). deliveredAt is the actual
     * hand-over — never the invoice time. Nothing is borrowed across services.
     */
    private Map<UUID, Map<String, Object>> deliveryRecords(List<Ticket> chain, Map<UUID, Instant> delivered) {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        if (chain.isEmpty()) return out;
        List<UUID> ids = chain.stream().map(Ticket::getId).toList();
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.toArray();
        jdbc.query("SELECT d.ticket_id, d.status, d.attempt, d.scheduled_date, d.slot_start, d.slot_end, d.delivered_at,"
                        + " d.failed_at, d.fail_reason, d.returned_at, d.payment_method, d.amount_collected, d.proof_photos_json,"
                        + " d.note, tech.name FROM repair_deliveries d LEFT JOIN technicians tech ON tech.id = d.technician_id"
                        + " WHERE d.ticket_id IN (" + in + ")",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String status = rs.getString(2);
                    m.put("method", "DOORSTEP");
                    m.put("status", status);
                    m.put("attempts", rs.getInt(3));
                    m.put("scheduledDate", rs.getString(4));
                    m.put("slotStart", rs.getString(5));
                    m.put("slotEnd", rs.getString(6));
                    m.put("deliveredAt", ts(rs.getTimestamp(7)));
                    m.put("failedAt", ts(rs.getTimestamp(8)));
                    m.put("failReason", rs.getString(9));
                    m.put("returnedAt", ts(rs.getTimestamp(10)));
                    m.put("paymentMethod", rs.getString(11));
                    m.put("amountCollected", rs.getBigDecimal(12));
                    m.put("proofPhotos", parseList(rs.getString(13)));
                    m.put("note", rs.getString(14));
                    m.put("employee", rs.getString(15));
                    m.put("deliveredBy", "DELIVERED".equalsIgnoreCase(status) ? rs.getString(15) : null);
                    m.put("verification", "DELIVERED".equalsIgnoreCase(status) ? "Customer OTP verified" : "Customer OTP required");
                    out.put((UUID) rs.getObject(1), m);
                }, args);
        // Walk-in: the shop's own DELIVERED step ("Delivered by <name>").
        jdbc.query("SELECT DISTINCT ON (b.ticket_id) b.ticket_id, e.created_at, e.note, e.actor FROM repair_booking_events e"
                        + " JOIN repair_bookings b ON b.id = e.booking_id WHERE UPPER(e.status) = 'DELIVERED' AND b.ticket_id IN (" + in + ")"
                        + " ORDER BY b.ticket_id, e.created_at DESC",
                rs -> {
                    UUID id = (UUID) rs.getObject(1);
                    if (out.containsKey(id)) return;
                    String note = rs.getString(3);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("method", "WALK_IN");
                    m.put("status", "DELIVERED");
                    m.put("deliveredAt", ts(rs.getTimestamp(2)));
                    m.put("deliveredBy", note != null && note.matches("(?i)^delivered by .+") ? note.replaceFirst("(?i)^delivered by ", "").trim() : null);
                    m.put("verification", "Shop hand-over");
                    m.put("note", note);
                    out.put(id, m);
                }, args);
        for (Ticket t : chain) {
            if (out.containsKey(t.getId())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            boolean done = "DELIVERED".equalsIgnoreCase(t.getStatus());
            m.put("method", done ? "WALK_IN" : null);
            m.put("status", done ? "DELIVERED" : "PENDING");
            m.put("deliveredAt", delivered.get(t.getId()));
            out.put(t.getId(), m);
        }
        return out;
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static List<String> parseList(String json) {
        List<String> out = new ArrayList<>();
        try {
            if (json != null && !json.isBlank()) for (JsonNode n : MAPPER.readTree(json)) if (n.isTextual()) out.add(n.asText());
        } catch (Exception ignored) { /* none */ }
        return out;
    }

    static int maxWarrantyMonths(String priceItemsJson) {
        if (priceItemsJson == null || priceItemsJson.isBlank()) return 0;
        int max = 0;
        try {
            JsonNode arr = MAPPER.readTree(priceItemsJson);
            if (!arr.isArray()) return 0;
            for (JsonNode it : arr) {
                if (it.path("cancelled").asBoolean(false) || "CANCELLED".equalsIgnoreCase(it.path("status").asText(""))) continue;
                Matcher m = MONTHS.matcher(it.path("warranty").asText(""));
                if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        } catch (Exception ignored) { /* unreadable → no warranty */ }
        return max;
    }

    private static ObjectNode parseObject(String json) {
        try {
            if (json != null && !json.isBlank()) {
                JsonNode n = MAPPER.readTree(json);
                if (n instanceof ObjectNode o) return o;
            }
        } catch (Exception ignored) { /* fall through */ }
        return MAPPER.createObjectNode();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
