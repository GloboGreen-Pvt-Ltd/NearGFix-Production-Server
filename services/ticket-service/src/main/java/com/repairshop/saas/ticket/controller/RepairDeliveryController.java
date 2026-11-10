package com.repairshop.saas.ticket.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Repair return delivery: after the repair is finished (QC passed, invoice made,
 * ticket READY / INVOICE_*) the shop delivers the device back to the customer.
 * One delivery row per ticket (repair_deliveries) with its attempt number;
 * every step is a repair_delivery_events row.
 *
 * <p>ASSIGNED (scheduled + employee) → ACCEPTED (employee) → [shop releases the
 * device] → COLLECTED (employee verified IMEI / serial, accessories, photos;
 * custody: employee) → OUT_FOR_DELIVERY (ticket DELIVERED_PROCESSING; a 4-digit
 * OTP only the customer sees, 2 h, 5 tries) → ARRIVED → DELIVERED (OTP + the
 * invoice's outstanding amount, exactly; ticket DELIVERED). Decline → UNASSIGNED
 * (reassign). FAILED (customer unavailable, …) → RETURNING → RETURNED (the shop
 * confirms it has the device again) → reschedule = next attempt. The repair
 * ticket stays open until a verified delivery.
 */
@RestController
@RequiredArgsConstructor
public class RepairDeliveryController {

    private static final Set<String> OWNER_ROLES = Set.of("SHOP_OWNER", "OWNER", "ADMIN", "SUPER_ADMIN");
    /** Ticket statuses from which a delivery may be scheduled (repair finished, invoiced, not delivered). */
    private static final Set<String> DELIVERABLE = Set.of("READY", "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING");
    /** Device is still at the shop: assignment and schedule may change. */
    private static final Set<String> AT_SHOP = Set.of("UNASSIGNED", "ASSIGNED", "ACCEPTED", "RETURNED");
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final Duration OTP_TTL = Duration.ofHours(2);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TechnicianRepository technicianRepository;
    private final TicketService ticketService;
    private final ObjectMapper objectMapper;

    public record ScheduleRequest(String date, String slotStart, String slotEnd, UUID technicianId, String note) {}
    public record AssignRequest(UUID technicianId) {}
    public record ReasonRequest(String reason) {}
    public record CollectRequest(String identifier, List<String> accessories, List<String> photos) {}
    public record CompleteRequest(String otp, BigDecimal amountCollected, String paymentMethod, String paymentRef, List<String> photos) {}

    // ══ Partner App (shop owner) ═════════════════════════════════════════════

    @GetMapping("/tickets/{ticketId}/repair-delivery")
    public Map<String, Object> ownerView(HttpServletRequest req, @PathVariable UUID ticketId) {
        UUID shopId = ownerShop(req);
        Map<String, Object> t = ticket(shopId, ticketId);
        Map<String, Object> d = byTicket(ticketId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eligibility", eligibility(t));
        out.put("delivery", d == null ? null : view(d, "OWNER"));
        out.put("events", d == null ? List.of() : events(uuid(d.get("id"))));
        return out;
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/schedule")
    @Transactional
    public ResponseEntity<Map<String, Object>> schedule(HttpServletRequest req, @PathVariable UUID ticketId, @RequestBody ScheduleRequest body) {
        UUID shopId = ownerShop(req);
        Map<String, Object> t = lockTicket(shopId, ticketId);
        Map<String, Object> el = eligibility(t);
        if (!Boolean.TRUE.equals(el.get("canSchedule"))) return refuse(HttpStatus.CONFLICT, "NOT_DELIVERABLE", String.valueOf(((List<?>) el.get("reasons")).get(0)));
        Slot slot = slot(body == null ? null : body.date(), body == null ? null : body.slotStart(), body == null ? null : body.slotEnd());
        Map<String, Object> tech = shopTechnician(shopId, body == null ? null : body.technicianId());
        Map<String, Object> d = byTicket(ticketId);
        if (d != null && !AT_SHOP.contains(str(d.get("status")))) {
            return refuse(HttpStatus.CONFLICT, "DELIVERY_IN_PROGRESS", "This delivery is already " + label(str(d.get("status"))).toLowerCase() + ".");
        }
        String note = clip(body.note(), 500);
        if (d == null) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO repair_deliveries (id, ticket_id, shop_id, customer_user_id, status, attempt, scheduled_date, slot_start, slot_end, "
                            + "technician_id, technician_name, technician_phone, assigned_at, shop_address, customer_address, device_identifier, amount_due, note, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'ASSIGNED', 1, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?, now(), now())",
                    id, ticketId, shopId, t.get("customer_id"), Date.valueOf(slot.date), Time.valueOf(slot.start), Time.valueOf(slot.end),
                    tech.get("id"), tech.get("name"), tech.get("phone"), shopAddress(shopId), t.get("customer_address"), identifier(t), outstanding(ticketId, t), note);
            d = byId(id);
        } else {
            boolean retry = "RETURNED".equals(str(d.get("status")));
            jdbc.update("UPDATE repair_deliveries SET status = 'ASSIGNED', attempt = attempt + ?, scheduled_date = ?, slot_start = ?, slot_end = ?, technician_id = ?, "
                            + "technician_name = ?, technician_phone = ?, assigned_at = now(), accepted_at = NULL, declined_reason = NULL, released_at = NULL, collected_at = NULL, "
                            + "collected_identifier = NULL, accessories_json = NULL, collect_photos_json = NULL, started_at = NULL, arrived_at = NULL, otp = NULL, otp_sent_at = NULL, "
                            + "otp_attempts = 0, failed_at = NULL, fail_reason = NULL, returning_at = NULL, amount_due = ?, note = COALESCE(?, note), updated_at = now() WHERE id = ?",
                    retry ? 1 : 0, Date.valueOf(slot.date), Time.valueOf(slot.start), Time.valueOf(slot.end), tech.get("id"), tech.get("name"), tech.get("phone"),
                    outstanding(ticketId, t), note, d.get("id"));
            d = byId(uuid(d.get("id")));
        }
        event(d, "ASSIGNED", String.format("Delivery scheduled for %s, assigned to %s", slot.label(), tech.get("name")), "OWNER", null);
        notifyCustomer(t, d, "REPAIR_DELIVERY_SCHEDULED", "Delivery scheduled", String.format("Your repaired %s will be delivered on %s.", device(t), slot.label()));
        return ResponseEntity.ok(ownerView(req, ticketId));
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/assign")
    @Transactional
    public ResponseEntity<Map<String, Object>> assign(HttpServletRequest req, @PathVariable UUID ticketId, @RequestBody AssignRequest body) {
        UUID shopId = ownerShop(req);
        lockTicket(shopId, ticketId);
        Map<String, Object> d = lockDelivery(ticketId);
        if (d == null) return refuse(HttpStatus.CONFLICT, "NO_DELIVERY", "Schedule the delivery first.");
        if (!AT_SHOP.contains(str(d.get("status")))) return refuse(HttpStatus.CONFLICT, "IN_CUSTODY", "The employee already has the device — reassign after it is back at the shop.");
        Map<String, Object> tech = shopTechnician(shopId, body == null ? null : body.technicianId());
        if (tech.get("id").equals(d.get("technician_id")) && !"UNASSIGNED".equals(str(d.get("status")))) return ResponseEntity.ok(ownerView(req, ticketId));
        jdbc.update("UPDATE repair_deliveries SET status = 'ASSIGNED', technician_id = ?, technician_name = ?, technician_phone = ?, assigned_at = now(), "
                + "accepted_at = NULL, declined_reason = NULL, released_at = NULL, updated_at = now() WHERE id = ?", tech.get("id"), tech.get("name"), tech.get("phone"), d.get("id"));
        event(byId(uuid(d.get("id"))), "ASSIGNED", "Assigned to " + tech.get("name"), "OWNER", null);
        return ResponseEntity.ok(ownerView(req, ticketId));
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/reschedule")
    @Transactional
    public ResponseEntity<Map<String, Object>> reschedule(HttpServletRequest req, @PathVariable UUID ticketId, @RequestBody ScheduleRequest body) {
        UUID shopId = ownerShop(req);
        Map<String, Object> t = lockTicket(shopId, ticketId);
        Map<String, Object> d = lockDelivery(ticketId);
        if (d == null) return refuse(HttpStatus.CONFLICT, "NO_DELIVERY", "Schedule the delivery first.");
        if ("RETURNED".equals(str(d.get("status")))) {
            // Next attempt: same employee unless another is chosen.
            UUID techId = body != null && body.technicianId() != null ? body.technicianId() : uuid(d.get("technician_id"));
            return schedule(req, ticketId, new ScheduleRequest(body == null ? null : body.date(), body == null ? null : body.slotStart(), body == null ? null : body.slotEnd(), techId, body == null ? null : body.note()));
        }
        if (!AT_SHOP.contains(str(d.get("status")))) return refuse(HttpStatus.CONFLICT, "IN_CUSTODY", "The delivery is under way — it can be rescheduled after the device is back at the shop.");
        Slot slot = slot(body == null ? null : body.date(), body == null ? null : body.slotStart(), body == null ? null : body.slotEnd());
        jdbc.update("UPDATE repair_deliveries SET scheduled_date = ?, slot_start = ?, slot_end = ?, updated_at = now() WHERE id = ?",
                Date.valueOf(slot.date), Time.valueOf(slot.start), Time.valueOf(slot.end), d.get("id"));
        event(byId(uuid(d.get("id"))), str(d.get("status")), "Rescheduled to " + slot.label(), "OWNER", null);
        notifyCustomer(t, d, "REPAIR_DELIVERY_RESCHEDULED", "Delivery rescheduled", String.format("Your %s will now be delivered on %s.", device(t), slot.label()));
        return ResponseEntity.ok(ownerView(req, ticketId));
    }

    /** The shop hands the device to the accepted employee (custody release). */
    @PostMapping("/tickets/{ticketId}/repair-delivery/release")
    @Transactional
    public ResponseEntity<Map<String, Object>> release(HttpServletRequest req, @PathVariable UUID ticketId) {
        UUID shopId = ownerShop(req);
        lockTicket(shopId, ticketId);
        Map<String, Object> d = lockDelivery(ticketId);
        if (d == null) return refuse(HttpStatus.CONFLICT, "NO_DELIVERY", "Schedule the delivery first.");
        if (d.get("released_at") != null) return ResponseEntity.ok(ownerView(req, ticketId));
        if (!"ACCEPTED".equals(str(d.get("status")))) return refuse(HttpStatus.CONFLICT, "NOT_ACCEPTED", "Release the device after the employee accepts the delivery.");
        jdbc.update("UPDATE repair_deliveries SET released_at = now(), updated_at = now() WHERE id = ?", d.get("id"));
        event(byId(uuid(d.get("id"))), "RELEASED", "Shop released the device to " + d.get("technician_name"), "OWNER", null);
        return ResponseEntity.ok(ownerView(req, ticketId));
    }

    /** The device is back at the shop after a failed attempt (custody: shop). */
    @PostMapping("/tickets/{ticketId}/repair-delivery/receive-return")
    @Transactional
    public ResponseEntity<Map<String, Object>> receiveReturn(HttpServletRequest req, @PathVariable UUID ticketId) {
        UUID shopId = ownerShop(req);
        lockTicket(shopId, ticketId);
        Map<String, Object> d = lockDelivery(ticketId);
        if (d == null) return refuse(HttpStatus.CONFLICT, "NO_DELIVERY", "There is no delivery for this repair.");
        if ("RETURNED".equals(str(d.get("status")))) return ResponseEntity.ok(ownerView(req, ticketId));
        if (!Set.of("FAILED", "RETURNING").contains(str(d.get("status")))) return refuse(HttpStatus.CONFLICT, "NOT_RETURNING", "The device is not being returned to the shop.");
        jdbc.update("UPDATE repair_deliveries SET status = 'RETURNED', returned_at = now(), released_at = NULL, updated_at = now() WHERE id = ?", d.get("id"));
        event(byId(uuid(d.get("id"))), "RETURNED", "Device received back at the shop", "OWNER", null);
        return ResponseEntity.ok(ownerView(req, ticketId));
    }

    // ══ Staff App (assigned employee) ════════════════════════════════════════

    @GetMapping("/technicians/me/repair-deliveries")
    public List<Map<String, Object>> myDeliveries(HttpServletRequest req) {
        Technician me = me(req);
        if (me == null || !hasTable()) return List.of();
        return jdbc.queryForList("SELECT * FROM repair_deliveries WHERE shop_id = ? AND technician_id = ? ORDER BY scheduled_date NULLS LAST, updated_at DESC LIMIT 200",
                me.getShopId(), me.getId()).stream().map((d) -> view(d, "EMPLOYEE")).toList();
    }

    @GetMapping("/technicians/me/repair-deliveries/{id}")
    public ResponseEntity<Map<String, Object>> myDelivery(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> d = byId(id);
        if (d == null || !me.getShopId().equals(d.get("shop_id"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
        if (!me.getId().equals(d.get("technician_id"))) return notMine();
        Map<String, Object> out = view(d, "EMPLOYEE");
        out.put("events", events(id));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/accept")
    @Transactional
    public ResponseEntity<Map<String, Object>> accept(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if ("ACCEPTED".equals(str(d.get("status")))) return employeeView(id);
        if (!"ASSIGNED".equals(str(d.get("status")))) return stale(d);
        jdbc.update("UPDATE repair_deliveries SET status = 'ACCEPTED', accepted_at = now(), updated_at = now() WHERE id = ?", id);
        event(byId(id), "ACCEPTED", me.getName() + " accepted the delivery", "EMPLOYEE", me.getName());
        notifyShop(d, "REPAIR_DELIVERY_ACCEPTED", "Delivery accepted", String.format("%s accepted the delivery for %s. Release the device when they collect it.", me.getName(), number(d)));
        return employeeView(id);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/decline")
    @Transactional
    public ResponseEntity<Map<String, Object>> decline(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReasonRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if (!Set.of("ASSIGNED", "ACCEPTED").contains(str(d.get("status"))) || d.get("released_at") != null) return stale(d);
        String reason = clip(body == null ? null : body.reason(), 300);
        if (reason == null) return refuse(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Tell the shop why you can't take this delivery.");
        jdbc.update("UPDATE repair_deliveries SET status = 'UNASSIGNED', technician_id = NULL, technician_name = NULL, technician_phone = NULL, accepted_at = NULL, "
                + "declined_reason = ?, updated_at = now() WHERE id = ?", reason, id);
        event(byId(id), "DECLINED", me.getName() + " declined: " + reason, "EMPLOYEE", me.getName());
        notifyShop(d, "REPAIR_DELIVERY_DECLINED", "Delivery declined — reassign", String.format("%s declined the delivery for %s: %s", me.getName(), number(d), reason));
        return ResponseEntity.ok(Map.of("id", id, "status", "UNASSIGNED"));
    }

    /** Shop collection: the device identifier must match the repaired device; the shop must have released it. */
    @PostMapping("/technicians/me/repair-deliveries/{id}/collect")
    @Transactional
    public ResponseEntity<Map<String, Object>> collect(HttpServletRequest req, @PathVariable UUID id, @RequestBody CollectRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if ("COLLECTED".equals(str(d.get("status")))) return employeeView(id);
        if (!"ACCEPTED".equals(str(d.get("status")))) return stale(d);
        if (d.get("released_at") == null) return refuse(HttpStatus.CONFLICT, "SHOP_RELEASE_REQUIRED", "Ask the shop to release the device to you in the Partner App first.");
        String given = norm(body == null ? null : body.identifier());
        if (given == null) return refuse(HttpStatus.BAD_REQUEST, "IDENTIFIER_REQUIRED", "Enter the device IMEI / serial number.");
        String expected = norm(str(d.get("device_identifier")));
        if (expected != null && !expected.equals(given)) {
            event(d, "IDENTIFIER_MISMATCH", "IMEI / serial entered did not match the repaired device", "EMPLOYEE", me.getName());
            return refuse(HttpStatus.CONFLICT, "IDENTIFIER_MISMATCH", "This IMEI / serial doesn't match the repaired device. Check the device with the shop.");
        }
        List<String> photos = urls(body.photos());
        if (photos.size() < 2) return refuse(HttpStatus.BAD_REQUEST, "PHOTOS_REQUIRED", "Add at least 2 photos of the device at collection.");
        jdbc.update("UPDATE repair_deliveries SET status = 'COLLECTED', collected_at = now(), collected_identifier = ?, accessories_json = ?, collect_photos_json = ?, updated_at = now() WHERE id = ?",
                given, json(clean(body.accessories())), json(photos), id);
        event(byId(id), "COLLECTED", "Collected from the shop" + (clean(body.accessories()).isEmpty() ? "" : " with " + String.join(", ", clean(body.accessories()))), "EMPLOYEE", me.getName());
        return employeeView(id);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/start")
    @Transactional
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if ("OUT_FOR_DELIVERY".equals(str(d.get("status")))) return employeeView(id);
        if (!"COLLECTED".equals(str(d.get("status")))) return stale(d);
        jdbc.update("UPDATE repair_deliveries SET status = 'OUT_FOR_DELIVERY', started_at = now(), otp = ?, otp_sent_at = now(), otp_attempts = 0, otp_verified_at = NULL, updated_at = now() WHERE id = ?", newOtp(), id);
        ticketService.emitProgressStepEvent(uuid(d.get("shop_id")), uuid(d.get("ticket_id")), "DELIVERED_PROCESSING", null, "SHOP");
        event(byId(id), "OUT_FOR_DELIVERY", me.getName() + " is on the way", "EMPLOYEE", me.getName());
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", d.get("ticket_id"));
        notifyCustomer(t, d, "REPAIR_OUT_FOR_DELIVERY", "Out for delivery",
                String.format("%s is bringing your %s. Your delivery OTP is on the repair page — share it only at handover.", me.getName(), device(t)));
        return employeeView(id);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/arrive")
    @Transactional
    public ResponseEntity<Map<String, Object>> arrive(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if ("ARRIVED".equals(str(d.get("status")))) return employeeView(id);
        if (!"OUT_FOR_DELIVERY".equals(str(d.get("status")))) return stale(d);
        jdbc.update("UPDATE repair_deliveries SET status = 'ARRIVED', arrived_at = now(), updated_at = now() WHERE id = ?", id);
        event(byId(id), "ARRIVED", "Arrived at the customer", "EMPLOYEE", me.getName());
        return employeeView(id);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/otp/resend")
    @Transactional
    public ResponseEntity<Map<String, Object>> resendOtp(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if (!Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(str(d.get("status")))) return stale(d);
        Instant sent = instant(d.get("otp_sent_at"));
        if (sent != null && Instant.now().isBefore(sent.plusSeconds(30))) return refuse(HttpStatus.TOO_MANY_REQUESTS, "OTP_WAIT", "Wait 30 seconds before generating a new OTP.");
        jdbc.update("UPDATE repair_deliveries SET otp = ?, otp_sent_at = now(), otp_attempts = 0, updated_at = now() WHERE id = ?", newOtp(), id);
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", d.get("ticket_id"));
        notifyCustomer(t, d, "REPAIR_DELIVERY_OTP", "New delivery OTP", "A new delivery OTP is on your repair page. Share it only with the delivery person at your door.");
        event(byId(id), str(d.get("status")), "New delivery OTP generated", "EMPLOYEE", me.getName());
        return employeeView(id);
    }

    /** OTP + the outstanding amount collected (exactly) → DELIVERED; the ticket closes as delivered. Once only. */
    @PostMapping("/technicians/me/repair-deliveries/{id}/complete")
    @Transactional
    public ResponseEntity<Map<String, Object>> complete(HttpServletRequest req, @PathVariable UUID id, @RequestBody CompleteRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if ("DELIVERED".equals(str(d.get("status")))) return refuse(HttpStatus.CONFLICT, "ALREADY_DELIVERED", "This delivery is already completed.");
        if (!Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(str(d.get("status")))) return stale(d);
        if (d.get("collected_at") == null) return refuse(HttpStatus.CONFLICT, "NOT_COLLECTED", "The device was not collected from the shop.");
        // OTP: customer-only, 2 h, 5 tries.
        int attempts = d.get("otp_attempts") instanceof Number n ? n.intValue() : 0;
        if (attempts >= OTP_MAX_ATTEMPTS) return refuse(HttpStatus.CONFLICT, "OTP_LOCKED", "Too many wrong OTPs. Generate a new OTP for the customer.");
        Instant sent = instant(d.get("otp_sent_at"));
        if (d.get("otp") == null || sent == null) return refuse(HttpStatus.CONFLICT, "OTP_NOT_SENT", "Generate a new OTP for the customer.");
        if (Instant.now().isAfter(sent.plus(OTP_TTL))) return refuse(HttpStatus.CONFLICT, "OTP_EXPIRED", "The OTP expired. Generate a new OTP for the customer.");
        String otp = body == null || body.otp() == null ? "" : body.otp().replaceAll("\\D", "");
        if (!otp.equals(str(d.get("otp")))) {
            jdbc.update("UPDATE repair_deliveries SET otp_attempts = COALESCE(otp_attempts, 0) + 1, updated_at = now() WHERE id = ?", id);
            event(d, "OTP_FAILED", "Wrong OTP entered", "EMPLOYEE", me.getName());
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_INVALID", "Wrong OTP. Ask the customer for the OTP on their repair page.");
        }
        // Payment: exactly the invoice's outstanding amount, re-read now.
        BigDecimal due = outstanding(uuid(d.get("ticket_id")), null);
        BigDecimal paid = body.amountCollected() == null ? BigDecimal.ZERO : body.amountCollected().setScale(2, RoundingMode.HALF_UP);
        String method = body.paymentMethod() == null ? "" : body.paymentMethod().trim().toUpperCase();
        String ref = clip(body.paymentRef(), 40);
        if (due.signum() > 0) {
            if (paid.compareTo(due) != 0) return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_MISMATCH", "Collect exactly " + rupees(due) + " (the outstanding amount on the invoice).");
            if (!Set.of("CASH", "UPI").contains(method)) return refuse(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD", "Choose cash or UPI.");
            if ("UPI".equals(method) && (ref == null || !ref.matches("[A-Za-z0-9]{6,40}"))) return refuse(HttpStatus.BAD_REQUEST, "PAYMENT_REF", "Enter the UPI transaction reference.");
        } else if (paid.signum() > 0) {
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "NOTHING_DUE", "Nothing is due on this invoice — don't collect any money.");
        }
        jdbc.update("UPDATE repair_deliveries SET status = 'DELIVERED', otp_verified_at = now(), delivered_at = now(), amount_due = ?, amount_collected = ?, payment_method = ?, payment_ref = ?, "
                        + "delivery_photos_json = ?, updated_at = now() WHERE id = ?",
                due, paid, due.signum() > 0 ? method : null, due.signum() > 0 ? ref : null, json(urls(body.photos())), id);
        if (due.signum() > 0) {
            jdbc.update("UPDATE invoices SET amount_paid = amount_paid + ?, credit_amount = GREATEST(credit_amount - ?, 0), payment_date = CURRENT_DATE, "
                    + "payment_note = LEFT(CONCAT_WS(' · ', NULLIF(payment_note, ''), ?), 500), updated_at = now() WHERE ticket_id = ?",
                    paid, paid, "Collected at delivery by " + me.getName() + " (" + method + (ref == null ? "" : " " + ref) + ")", d.get("ticket_id"));
        }
        ticketService.emitProgressStepEvent(uuid(d.get("shop_id")), uuid(d.get("ticket_id")), "DELIVERED", null, "SHOP");
        event(byId(id), "DELIVERED", "Delivered to the customer (OTP verified" + (due.signum() > 0 ? ", " + rupees(paid) + " " + method : "") + ")", "EMPLOYEE", me.getName());
        notifyShop(d, "REPAIR_DELIVERED", "Repair delivered", String.format("%s delivered %s%s.", me.getName(), number(d), due.signum() > 0 ? " and collected " + rupees(paid) : ""));
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", d.get("ticket_id"));
        notifyCustomer(t, d, "REPAIR_DELIVERED", "Device delivered", String.format("Your repaired %s has been delivered. Thank you!", device(t)));
        return employeeView(id);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/fail")
    @Transactional
    public ResponseEntity<Map<String, Object>> fail(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReasonRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if (!Set.of("COLLECTED", "OUT_FOR_DELIVERY", "ARRIVED").contains(str(d.get("status")))) return stale(d);
        String reason = clip(body == null ? null : body.reason(), 300);
        if (reason == null) return refuse(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Choose why the delivery failed.");
        jdbc.update("UPDATE repair_deliveries SET status = 'FAILED', failed_at = now(), fail_reason = ?, otp = NULL, updated_at = now() WHERE id = ?", reason, id);
        event(byId(id), "FAILED", "Delivery failed: " + reason, "EMPLOYEE", me.getName());
        notifyShop(d, "REPAIR_DELIVERY_FAILED", "Delivery failed", String.format("%s couldn't deliver %s: %s. The device is coming back to the shop.", me.getName(), number(d), reason));
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", d.get("ticket_id"));
        notifyCustomer(t, d, "REPAIR_DELIVERY_FAILED", "Delivery not completed", "We couldn't complete the delivery today. The shop will contact you to arrange it again.");
        return employeeView(id);
    }

    /** Taking the device back to the shop after a failed attempt (the shop confirms receipt). */
    @PostMapping("/technicians/me/repair-deliveries/{id}/return")
    @Transactional
    public ResponseEntity<Map<String, Object>> returnToShop(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> d = lockMine(me, id);
        if (d == null) return notMine();
        if ("RETURNING".equals(str(d.get("status")))) return employeeView(id);
        if (!"FAILED".equals(str(d.get("status")))) return stale(d);
        jdbc.update("UPDATE repair_deliveries SET status = 'RETURNING', returning_at = now(), updated_at = now() WHERE id = ?", id);
        event(byId(id), "RETURNING", "Returning the device to the shop", "EMPLOYEE", me.getName());
        notifyShop(d, "REPAIR_DELIVERY_RETURNING", "Device coming back", String.format("%s is returning the device for %s. Confirm when you receive it.", me.getName(), number(d)));
        return employeeView(id);
    }

    // ══ Customer App ═════════════════════════════════════════════════════════

    @GetMapping("/tickets/customer/{ticketId}/repair-delivery")
    public Map<String, Object> customerView(HttpServletRequest req, @PathVariable UUID ticketId) {
        if (!hasRole(req, Set.of("CUSTOMER"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not allowed");
        UUID userId = uuid(req.getAttribute("userId"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM tickets WHERE id = ?", ticketId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        Map<String, Object> t = rows.get(0);
        if (userId == null || !userId.equals(uuid(t.get("customer_id")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your repair");
        Map<String, Object> d = hasTable() ? byTicket(ticketId) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketStatus", t.get("status"));
        out.put("qcPassed", qcPassed(ticketId));
        out.put("delivery", d == null ? null : view(d, "CUSTOMER"));
        out.put("events", d == null ? List.of() : events(uuid(d.get("id"))).stream()
                .filter((e) -> !Set.of("OTP_FAILED", "IDENTIFIER_MISMATCH", "DECLINED", "RELEASED").contains(e.get("status"))).toList());
        return out;
    }

    // ── views ────────────────────────────────────────────────────────────────

    private Map<String, Object> view(Map<String, Object> d, String audience) {
        String status = str(d.get("status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.get("id"));
        out.put("ticketId", d.get("ticket_id"));
        out.put("status", status);
        out.put("statusLabel", label(status));
        out.put("attempt", d.get("attempt"));
        out.put("scheduledDate", d.get("scheduled_date") == null ? null : ((Date) d.get("scheduled_date")).toLocalDate().toString());
        out.put("slotStart", d.get("slot_start") == null ? null : ((Time) d.get("slot_start")).toLocalTime().toString());
        out.put("slotEnd", d.get("slot_end") == null ? null : ((Time) d.get("slot_end")).toLocalTime().toString());
        boolean showEmployee = !"CUSTOMER".equals(audience) || d.get("accepted_at") != null;
        out.put("employee", d.get("technician_id") == null || !showEmployee ? null
                : Map.of("id", d.get("technician_id"), "name", Objects.toString(d.get("technician_name"), ""), "phone", Objects.toString(d.get("technician_phone"), "")));
        out.put("amountDue", d.get("amount_due"));
        out.put("amountCollected", d.get("amount_collected"));
        out.put("paymentMethod", d.get("payment_method"));
        out.put("assignedAt", iso(d.get("assigned_at")));
        out.put("acceptedAt", iso(d.get("accepted_at")));
        out.put("releasedAt", iso(d.get("released_at")));
        out.put("collectedAt", iso(d.get("collected_at")));
        out.put("startedAt", iso(d.get("started_at")));
        out.put("arrivedAt", iso(d.get("arrived_at")));
        out.put("deliveredAt", iso(d.get("delivered_at")));
        out.put("failedAt", iso(d.get("failed_at")));
        out.put("failReason", d.get("fail_reason"));
        out.put("returnedAt", iso(d.get("returned_at")));
        out.put("declinedReason", "CUSTOMER".equals(audience) ? null : d.get("declined_reason"));
        if (!"CUSTOMER".equals(audience)) {
            Map<String, Object> t = jdbc.queryForList("SELECT * FROM tickets WHERE id = ?", d.get("ticket_id")).stream().findFirst().orElse(Map.of());
            out.put("bookingNumber", t.get("booking_number"));
            out.put("device", device(t));
            out.put("deviceImageUrl", t.get("device_image_url"));
            out.put("deviceIdentifierHint", mask(str(d.get("device_identifier"))));
            out.put("shopAddress", d.get("shop_address"));
            boolean contact = "OWNER".equals(audience) || d.get("accepted_at") != null;
            out.put("customerName", contact ? t.get("customer_name") : null);
            out.put("customerPhone", contact ? t.get("customer_phone") : null);
            out.put("customerAddress", contact ? d.get("customer_address") : null);
            out.put("accessories", read(d.get("accessories_json")));
            out.put("collectPhotos", read(d.get("collect_photos_json")));
            out.put("otpSentAt", iso(d.get("otp_sent_at")));
        } else {
            // The OTP: only for the customer, only while the device is on its way, never after.
            boolean live = Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(status) && d.get("otp") != null
                    && instant(d.get("otp_sent_at")) != null && Instant.now().isBefore(instant(d.get("otp_sent_at")).plus(OTP_TTL));
            out.put("otp", live ? d.get("otp") : null);
        }
        return out;
    }

    private Map<String, Object> eligibility(Map<String, Object> t) {
        UUID ticketId = uuid(t.get("id"));
        String status = up(t.get("status"));
        boolean qc = qcPassed(ticketId);
        Map<String, Object> inv = jdbc.queryForList("SELECT id, net_payable_amount, amount_paid, credit_amount FROM invoices WHERE ticket_id = ?", ticketId).stream().findFirst().orElse(null);
        List<String> reasons = new ArrayList<>();
        if ("DELIVERED".equals(status)) reasons.add("This repair is already delivered.");
        else if (!DELIVERABLE.contains(status)) reasons.add("The repair is not finished yet (mark it Ready after QC).");
        if (!qc) reasons.add("Quality check (QC) is not passed yet.");
        if (inv == null) reasons.add("Generate the invoice before scheduling the delivery.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketStatus", status);
        out.put("qcPassed", qc);
        out.put("invoice", inv == null ? null : Map.of("id", inv.get("id"), "netPayable", inv.get("net_payable_amount"), "paid", inv.get("amount_paid"), "due", inv.get("credit_amount")));
        out.put("canSchedule", reasons.isEmpty());
        out.put("reasons", reasons);
        return out;
    }

    private List<Map<String, Object>> events(UUID deliveryId) {
        return jdbc.queryForList("SELECT status, note, actor, actor_name, attempt, created_at FROM repair_delivery_events WHERE delivery_id = ? ORDER BY created_at, id", deliveryId)
                .stream().map((e) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", e.get("status"));
                    m.put("label", label(str(e.get("status"))));
                    m.put("note", e.get("note"));
                    m.put("actor", e.get("actor"));
                    m.put("actorName", e.get("actor_name"));
                    m.put("attempt", e.get("attempt"));
                    m.put("at", iso(e.get("created_at")));
                    return m;
                }).toList();
    }

    static String label(String s) {
        return switch (s == null ? "" : s) {
            case "UNASSIGNED" -> "Needs Employee";
            case "ASSIGNED" -> "Scheduled";
            case "ACCEPTED" -> "Accepted";
            case "RELEASED" -> "Released by Shop";
            case "COLLECTED" -> "Collected from Shop";
            case "OUT_FOR_DELIVERY" -> "Out for Delivery";
            case "ARRIVED" -> "Arrived";
            case "DELIVERED" -> "Delivered";
            case "FAILED" -> "Delivery Failed";
            case "RETURNING" -> "Returning to Shop";
            case "RETURNED" -> "Back at Shop";
            case "DECLINED" -> "Declined";
            case "OTP_FAILED" -> "Wrong OTP";
            case "IDENTIFIER_MISMATCH" -> "IMEI Mismatch";
            default -> s;
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Slot(LocalDate date, LocalTime start, LocalTime end) {
        String label() {
            java.time.format.DateTimeFormatter d = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
            java.time.format.DateTimeFormatter h = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
            return date.format(d) + ", " + start.format(h) + " – " + end.format(h);
        }
    }

    private static Slot slot(String date, String start, String end) {
        try {
            LocalDate d = LocalDate.parse(date);
            LocalTime s = LocalTime.parse(start);
            LocalTime e = LocalTime.parse(end);
            if (d.isBefore(LocalDate.now(IST))) throw new IllegalArgumentException("Pick today or a later date");
            if (!e.isAfter(s)) throw new IllegalArgumentException("The slot must end after it starts");
            if (d.isAfter(LocalDate.now(IST).plusDays(30))) throw new IllegalArgumentException("Pick a date within 30 days");
            return new Slot(d, s, e);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick the delivery date and time slot");
        }
    }

    private boolean qcPassed(UUID ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM repair_booking_events e JOIN repair_bookings b ON b.id = e.booking_id "
                + "WHERE b.ticket_id = ? AND e.status = 'QUALITY_CHECK_COMPLETED')", Boolean.class, ticketId));
    }

    /** Outstanding on the invoice (credit = net payable − paid); 0 when nothing is due. */
    private BigDecimal outstanding(UUID ticketId, Map<String, Object> ignored) {
        BigDecimal due = jdbc.queryForList("SELECT credit_amount FROM invoices WHERE ticket_id = ?", ticketId).stream().findFirst()
                .map((r) -> (BigDecimal) r.get("credit_amount")).orElse(BigDecimal.ZERO);
        return due == null || due.signum() < 0 ? BigDecimal.ZERO.setScale(2) : due.setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> shopTechnician(UUID shopId, UUID technicianId) {
        if (technicianId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the delivery employee");
        Map<String, Object> tech = jdbc.queryForList("SELECT id, name, phone, user_id FROM technicians WHERE id = ? AND shop_id = ?", technicianId, shopId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "That employee isn't in your shop"));
        if (tech.get("user_id") == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, tech.get("name") + " has no Employee App login yet.");
        return tech;
    }

    private String shopAddress(UUID shopId) {
        return jdbc.queryForList("SELECT * FROM shops WHERE id = ?", shopId).stream().findFirst().map((s) -> String.join(", ",
                Arrays.stream(new Object[] { s.get("name"), s.get("address"), s.get("address_line"), s.get("area"), s.get("city"), s.get("district"), s.get("state"), s.get("pincode") })
                        .filter(Objects::nonNull).map(Object::toString).filter((v) -> !v.isBlank()).distinct().toList())).orElse(null);
    }

    private static String identifier(Map<String, Object> t) {
        String imei = norm(str(t.get("imei")));
        return imei != null ? imei : norm(str(t.get("serial_number")));
    }

    private static String device(Map<String, Object> t) {
        Object n = t.get("device_display_name");
        return n == null || n.toString().isBlank() ? "device" : n.toString();
    }

    private Map<String, Object> ticket(UUID shopId, UUID ticketId) {
        if (!hasTable()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Repair delivery needs the latest database update");
        return jdbc.queryForList("SELECT * FROM tickets WHERE id = ? AND shop_id = ?", ticketId, shopId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
    }

    private Map<String, Object> lockTicket(UUID shopId, UUID ticketId) {
        ticket(shopId, ticketId);
        return jdbc.queryForList("SELECT * FROM tickets WHERE id = ? AND shop_id = ? FOR UPDATE", ticketId, shopId).get(0);
    }

    private Map<String, Object> byTicket(UUID ticketId) {
        return jdbc.queryForList("SELECT * FROM repair_deliveries WHERE ticket_id = ?", ticketId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> lockDelivery(UUID ticketId) {
        return jdbc.queryForList("SELECT * FROM repair_deliveries WHERE ticket_id = ? FOR UPDATE", ticketId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> byId(UUID id) {
        return jdbc.queryForList("SELECT * FROM repair_deliveries WHERE id = ?", id).stream().findFirst().orElse(null);
    }

    /** The delivery locked, or null when it isn't this employee's. */
    private Map<String, Object> lockMine(Technician me, UUID id) {
        if (!hasTable()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM repair_deliveries WHERE id = ? AND shop_id = ? FOR UPDATE", id, me.getShopId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
        return me.getId().equals(rows.get(0).get("technician_id")) ? rows.get(0) : null;
    }

    private ResponseEntity<Map<String, Object>> employeeView(UUID id) {
        Map<String, Object> out = view(byId(id), "EMPLOYEE");
        out.put("events", events(id));
        return ResponseEntity.ok(out);
    }

    private void event(Map<String, Object> d, String status, String note, String actor, String actorName) {
        jdbc.update("INSERT INTO repair_delivery_events (id, delivery_id, ticket_id, status, note, actor, actor_name, attempt, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())",
                UUID.randomUUID(), d.get("id"), d.get("ticket_id"), status, clip(note, 500), actor, actorName, d.get("attempt"));
    }

    private void notifyCustomer(Map<String, Object> t, Map<String, Object> d, String key, String title, String body) {
        if (t.get("customer_id") == null) return;
        jdbc.update("INSERT INTO customer_notifications (id, customer_user_id, booking_id, booking_number, status_key, title, body, type, is_read, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'orders', false, now())",
                UUID.randomUUID(), t.get("customer_id"), t.get("id"), t.get("booking_number"), key, title, body);
    }

    private void notifyShop(Map<String, Object> d, String key, String title, String body) {
        jdbc.update("INSERT INTO shop_notifications (id, shop_id, booking_id, booking_number, status_key, title, body, type, is_read, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'delivery', false, now())",
                UUID.randomUUID(), d.get("shop_id"), d.get("ticket_id"), number(d), key, title, body);
    }

    private String number(Map<String, Object> d) {
        return jdbc.queryForList("SELECT booking_number FROM tickets WHERE id = ?", d.get("ticket_id")).stream().findFirst()
                .map((r) -> Objects.toString(r.get("booking_number"), "the repair")).orElse("the repair");
    }

    private UUID ownerShop(HttpServletRequest req) {
        Object shop = req.getAttribute("shopId");
        if (shop == null || !hasRole(req, OWNER_ROLES)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop owner can manage deliveries");
        return UUID.fromString(shop.toString());
    }

    private static boolean hasRole(HttpServletRequest req, Set<String> allowed) {
        return req.getAttribute("roles") instanceof List<?> roles && roles.stream().anyMatch((r) -> allowed.contains(String.valueOf(r).trim().toUpperCase(Locale.ROOT)));
    }

    private Technician me(HttpServletRequest req) {
        Object shop = req.getAttribute("shopId");
        Object user = req.getAttribute("userId");
        if (shop == null || user == null) return null;
        return technicianRepository.findByShopIdAndUserId(UUID.fromString(shop.toString()), UUID.fromString(user.toString())).orElse(null);
    }

    private Technician requireMe(HttpServletRequest req) {
        Technician me = me(req);
        if (me == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop's employees can do this");
        return me;
    }

    /** Checked first, not caught: a failed statement would abort the surrounding transaction. */
    private boolean hasTable() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('public.repair_deliveries') IS NOT NULL", Boolean.class));
    }

    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    private static ResponseEntity<Map<String, Object>> notMine() {
        return refuse(HttpStatus.CONFLICT, "NOT_ASSIGNED", "This delivery is no longer assigned to you.");
    }

    private static ResponseEntity<Map<String, Object>> stale(Map<String, Object> d) {
        return refuse(HttpStatus.CONFLICT, "STALE_STATUS", "This delivery is " + label(str(d.get("status"))).toLowerCase() + " — refresh to see the latest.");
    }

    private static String newOtp() {
        return String.format("%04d", RANDOM.nextInt(10000));
    }

    private static String norm(String s) {
        if (s == null) return null;
        String v = s.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String mask(String id) {
        return id == null ? null : id.length() <= 4 ? id : "•••• " + id.substring(id.length() - 4);
    }

    private static List<String> urls(List<String> list) {
        return list == null ? List.of() : list.stream().filter((u) -> u != null && u.startsWith("http")).map(String::trim).distinct().limit(10).toList();
    }

    private static List<String> clean(List<String> list) {
        return list == null ? List.of() : list.stream().filter((s) -> s != null && !s.isBlank()).map((s) -> s.trim().substring(0, Math.min(s.trim().length(), 40))).distinct().limit(10).toList();
    }

    private String json(List<String> list) {
        try { return objectMapper.writeValueAsString(list); } catch (Exception e) { return "[]"; }
    }

    private List<String> read(Object json) {
        if (json == null) return List.of();
        try { return objectMapper.readValue(json.toString(), new TypeReference<List<String>>() {}); } catch (Exception e) { return List.of(); }
    }

    private static String rupees(BigDecimal v) {
        return v == null ? "—" : "₹" + v.stripTrailingZeros().toPlainString();
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String up(Object o) {
        return o == null ? "" : o.toString().trim().toUpperCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    private static Instant instant(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private static String iso(Object o) {
        Instant i = instant(o);
        return i == null ? (o == null ? null : o.toString()) : i.toString();
    }
}
