package com.repairshop.saas.order.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.order.entity.CustomerNotification;
import com.repairshop.saas.order.entity.SellOrder;
import com.repairshop.saas.order.exception.ConflictException;
import com.repairshop.saas.order.exception.ForbiddenException;
import com.repairshop.saas.order.exception.ResourceNotFoundException;
import com.repairshop.saas.order.repository.CustomerNotificationRepository;
import com.repairshop.saas.order.repository.CustomerOrderRepository;
import com.repairshop.saas.order.repository.SellOrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Partner app "Purchase Orders" for devices a shop buys from customers: sell
 * orders whose quote the customer accepted (sell_orders.shop_id = this shop,
 * set by POST /sell-orders/{id}/choose-quotation).
 *
 * <p>Flow (sell_orders.status; every step is an explicit action, nothing
 * advances on its own):
 * SELL_CONFIRMED [→ PICKUP_SCHEDULED] → PICKUP_ASSIGNED (owner picks a pickup
 * person) → OUT_FOR_PICKUP → PICKED_UP (pickup person, Employee App via
 * ticket-service /technicians/me/sell-pickups) → DEVICE_RECEIVED (pickup person
 * hands over, or the owner confirms; a shop visit skips the pickup) → INSPECTED
 * → PRICE_CONFIRMED (original quote) or PRICE_REVISION_PENDING → customer
 * approves (PRICE_CONFIRMED) or rejects (REVISION_REJECTED) → PAYMENT_PENDING
 * (owner recorded the UPI / bank payout) → PAID (the customer confirmed receipt)
 * → COMPLETED. CANCELLED at any point before the payout. The customer's phone
 * and address are shown only to the shop that won the order.
 *
 * <p>Pickup at the customer's door (OUT_FOR_PICKUP, Employee App): PIN verified →
 * inspection → the owner decides the price here: keeps the quote
 * (revision_status SHOP_CONFIRMED), approves the employee's suggestion or sets
 * a price (SHOP_APPROVED → the customer accepts APPROVED / declines REJECTED /
 * asks to negotiate NEGOTIATION), or asks for a re-check (REINSPECT). The
 * employee can pay only once the price is SHOP_CONFIRMED or APPROVED → PICKED_UP
 * → handed over → the owner verifies the IMEI / order / condition and confirms
 * receipt (PAYMENT_PENDING, a stock entry is added) → PAID → COMPLETED.
 */
@RestController
@RequestMapping("/sell-orders/shop-purchases")
@RequiredArgsConstructor
public class ShopPurchaseController {

    private static final Set<String> SELLER_ROLES = Set.of("SHOP_OWNER", "OWNER", "ADMIN", "SUPER_ADMIN");
    static final Set<String> BEFORE_JOURNEY = Set.of("SELL_CONFIRMED", "PICKUP_SCHEDULED", "PICKUP_ASSIGNED");
    static final Set<String> NOT_CANCELLABLE = Set.of("PAYMENT_PENDING", "PAID", "COMPLETED", "CANCELLED");
    private static final Set<String> PRICE_DECIDABLE = Set.of("INSPECTED", "REVISION_REJECTED");
    private static final List<String> CHECKS = List.of("display", "touch", "cameras", "speakerMic", "charging", "buttons", "connectivity", "biometrics");
    private static final Set<String> CONDITIONS = Set.of("Like New", "Excellent", "Good", "Fair", "Poor");
    private static final Pattern IMEI = Pattern.compile("\\d{15}");
    private static final Pattern UTR = Pattern.compile("[A-Za-z0-9]{6,30}");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, d MMM, h:mm a", Locale.ENGLISH);

    private static final String BASE_SELECT =
            "SELECT s.*, mm.name AS model_name, st.label AS storage_label, "
                    + "q.quotation_price, q.note AS quote_note, q.updated_at AS accepted_at "
                    + "FROM sell_orders s "
                    + "LEFT JOIN master_models mm ON mm.id = s.model_id "
                    + "LEFT JOIN master_storage_options st ON st.id = s.storage_option_id "
                    + "LEFT JOIN LATERAL (SELECT quotation_price, note, updated_at FROM sell_order_quotations "
                    + "  WHERE sell_order_id = s.id AND shop_id = s.shop_id "
                    + "  ORDER BY (status = 'ACCEPTED') DESC, updated_at DESC LIMIT 1) q ON true "
                    + "WHERE s.shop_id = ? ";

    private final JdbcTemplate jdbc;
    private final SellOrderRepository sellRepo;
    private final CustomerOrderRepository customerOrderRepo;
    private final CustomerNotificationRepository notificationRepo;
    private final ObjectMapper objectMapper;

    public record ScheduleRequest(String pickupDate, String slotStart, String slotEnd) {}
    public record AssignRequest(UUID technicianId) {}
    public record InspectionRequest(String imei, String serial, Boolean modelVerified, String condition,
                                    Map<String, Boolean> checks, List<String> photos, String notes,
                                    Integer batteryHealth, List<String> accessories) {}
    public record ReviseRequest(BigDecimal amount, String reason) {}
    public record PayoutRequest(String method, String account, String reference) {}
    public record ReasonRequest(String reason) {}
    public record FulfilmentRequest(String fulfilment) {}
    public record ReceiveRequest(String imei, Boolean orderVerified, Boolean conditionVerified) {}

    /** Doorstep revision states in which the customer has (or is answering) the price: the owner can't change it. */
    private static final Set<String> PRICE_WITH_CUSTOMER = Set.of("SHOP_APPROVED", "APPROVED");

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest req) {
        UUID shopId = sellerShop(req);
        List<Map<String, Object>> rows = jdbc.queryForList(BASE_SELECT + "ORDER BY COALESCE(q.updated_at, s.updated_at) DESC LIMIT 300", shopId);
        Map<UUID, Map<String, Object>> listings = listingsFor(rows.stream().map(r -> uuid(r.get("id"))).toList());
        return ResponseEntity.ok(rows.stream().map(r -> summary(r, listings.get(uuid(r.get("id"))))).toList());
    }

    /**
     * Read-only stock list for the owner's Inventory page (inventory_items of this shop, by name),
     * including the devices bought here. inventory-service has no endpoints yet; empty when the table is missing.
     */
    @GetMapping("/inventory")
    public ResponseEntity<List<Map<String, Object>>> inventory(HttpServletRequest req) {
        UUID shopId = sellerShop(req);
        if (!hasInventory()) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(jdbc.queryForList("SELECT id, sku, name, category, quantity, unit_price AS \"unitPrice\" "
                + "FROM inventory_items WHERE shop_id = ? ORDER BY name LIMIT 500", shopId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest req, @PathVariable UUID id) {
        return ResponseEntity.ok(details(sellerShop(req), id));
    }

    /** Sets (or moves) the pickup / shop-visit time, before the journey starts. */
    @PostMapping("/{id}/schedule")
    @Transactional
    public ResponseEntity<Map<String, Object>> schedule(HttpServletRequest req, @PathVariable UUID id, @RequestBody ScheduleRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        if (!BEFORE_JOURNEY.contains(up(s.getStatus()))) throw new ConflictException("This order can't be rescheduled any more");
        LocalDate date;
        LocalTime start;
        LocalTime end;
        try {
            date = LocalDate.parse(Objects.requireNonNull(body.pickupDate(), "pickupDate"));
            start = LocalTime.parse(Objects.requireNonNull(body.slotStart(), "slotStart"));
            end = body.slotEnd() == null || body.slotEnd().isBlank() ? start.plusHours(1) : LocalTime.parse(body.slotEnd());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Pick a valid date and time");
        }
        LocalDate today = LocalDate.now(IST);
        if (date.isBefore(today) || (date.isEqual(today) && start.isBefore(LocalTime.now(IST).minusMinutes(5)))) {
            throw new IllegalArgumentException("Pick a time in the future");
        }
        if (date.isAfter(today.plusDays(60))) throw new IllegalArgumentException("Pick a date within the next 60 days");
        if (!end.isAfter(start)) end = start.plusHours(1);

        boolean moved = s.getPickupDate() != null;
        s.setPickupDate(date);
        s.setPickupSlotStart(start);
        s.setPickupSlotEnd(end);
        if ("SELL_CONFIRMED".equals(up(s.getStatus()))) s.setStatus("PICKUP_SCHEDULED");
        sellRepo.saveAndFlush(s); // details() reads through JDBC

        Map<String, Object> d = details(shopId, id);
        boolean visit = "SHOP_VISIT".equals(d.get("fulfilment"));
        String when = LocalDateTime.of(date, start).format(WHEN);
        notifyCustomer(s, moved ? "SELL_PICKUP_RESCHEDULED" : "SELL_PICKUP_SCHEDULED",
                visit ? (moved ? "Shop visit moved" : "Shop visit scheduled") : (moved ? "Pickup rescheduled" : "Pickup scheduled"),
                visit ? String.format("Visit %s on %s to sell your %s.", shopName(shopId), when, d.get("productName"))
                        : String.format("%s will pick up your %s on %s.", shopName(shopId), d.get("productName"), when));
        return ResponseEntity.ok(d);
    }

    /** Assigns (or reassigns) a pickup person of this shop; they accept in the Employee App. */
    @PostMapping("/{id}/assign")
    @Transactional
    public ResponseEntity<Map<String, Object>> assign(HttpServletRequest req, @PathVariable UUID id, @RequestBody AssignRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        String status = up(s.getStatus());
        if (!BEFORE_JOURNEY.contains(status) && !"OUT_FOR_PICKUP".equals(status)) throw new ConflictException("The pickup can't be reassigned any more");
        if (body == null || body.technicianId() == null) throw new IllegalArgumentException("Choose an employee");
        Map<String, Object> tech = jdbc.queryForList("SELECT id, name, phone, user_id FROM technicians WHERE id = ? AND shop_id = ?", body.technicianId(), shopId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("That employee isn't in your shop"));
        // The Employee App finds pickups by the signed-in user's technician row; without a login it would never arrive.
        if (tech.get("user_id") == null) {
            throw new IllegalArgumentException(Optional.ofNullable(str(tech.get("name"))).orElse("This employee") + " has no Employee App login yet. Add login details first.");
        }
        Map<String, Object> cur = workflow(id);
        boolean reassign = cur.get("assigned_pickup_person_id") != null;
        if (body.technicianId().equals(uuid(cur.get("assigned_pickup_person_id")))) return ResponseEntity.ok(details(shopId, id)); // double tap
        if (!com.repairshop.saas.common.notify.EmployeeAttendance.isAssignable(jdbc, shopId, body.technicianId())) {
            throw new ConflictException(com.repairshop.saas.common.notify.EmployeeAttendance.MESSAGE);
        }
        // Paid at the door: the employee who paid collects the device.
        if (cur.get("payout_recorded_at") != null) throw new ConflictException("The customer is already paid — the pickup can't be reassigned");
        // A new pickup person accepts afresh and verifies the customer's PIN again; the previous one loses access.
        // The doorstep inspection and any agreed price stay with the order.
        jdbc.update("UPDATE sell_orders SET assigned_pickup_person_id = ?, pickup_person_name = ?, pickup_person_phone = ?, "
                        + "pickup_assigned_at = now(), pickup_accepted_at = NULL, pickup_started_at = NULL, pickup_issue = NULL, "
                        + "pickup_otp = NULL, pickup_otp_sent_at = NULL, pickup_otp_verified_at = NULL, pickup_otp_attempts = 0, pickup_arrived_at = NULL, "
                        + "status = 'PICKUP_ASSIGNED', updated_at = now() WHERE id = ?",
                body.technicianId(), str(tech.get("name")), str(tech.get("phone")), id);
        notifyCustomer(s, "SELL_PICKUP_ASSIGNED", reassign ? "Pickup person changed" : "Pickup person assigned",
                String.format("%s will pick up your device%s.", Optional.ofNullable(str(tech.get("name"))).orElse("A pickup person"),
                        s.getPickupDate() == null ? "" : " on " + LocalDateTime.of(s.getPickupDate(), Optional.ofNullable(s.getPickupSlotStart()).orElse(LocalTime.NOON)).format(WHEN)));
        return ResponseEntity.ok(details(shopId, id));
    }

    /**
     * Collection method: PICKUP (an employee collects) or SHOP_VISIT (the customer brings it), before the
     * journey starts. Stored in the accepted quote's note, like at quote time; a pickup person is released.
     */
    @PostMapping("/{id}/fulfilment")
    @Transactional
    public ResponseEntity<Map<String, Object>> fulfilment(HttpServletRequest req, @PathVariable UUID id, @RequestBody FulfilmentRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        String want = body == null ? null : up(body.fulfilment());
        if (!"PICKUP".equals(want) && !"SHOP_VISIT".equals(want)) throw new IllegalArgumentException("Choose Pickup or Shop Visit");
        Map<String, Object> d = details(shopId, id);
        if (want.equals(d.get("fulfilment"))) return ResponseEntity.ok(d);
        if (!BEFORE_JOURNEY.contains(up(s.getStatus()))) throw new ConflictException("The collection method can't be changed after the pickup has started");
        boolean visit = "SHOP_VISIT".equals(want);
        String pickupText = "Free pickup from your location";
        String visitText = "Visit the shop to sell";
        String to = visit ? visitText : pickupText;
        String from = visit ? pickupText : visitText;
        int n = jdbc.update("UPDATE sell_order_quotations SET note = CASE WHEN note IS NULL OR btrim(note) = '' THEN ? "
                        + "WHEN strpos(note, ?) > 0 THEN replace(note, ?, ?) ELSE ? || ' · ' || note END "
                        + "WHERE sell_order_id = ? AND shop_id = ? AND status = 'ACCEPTED'",
                to, from, from, to, to, id, shopId);
        if (n == 0) throw new ConflictException("The accepted quote for this order wasn't found");
        if (visit) {
            jdbc.update("UPDATE sell_orders SET assigned_pickup_person_id = NULL, pickup_person_name = NULL, pickup_person_phone = NULL, "
                    + "pickup_assigned_at = NULL, pickup_accepted_at = NULL, pickup_started_at = NULL, "
                    + "status = CASE WHEN status = 'PICKUP_ASSIGNED' THEN (CASE WHEN pickup_date IS NULL THEN 'SELL_CONFIRMED' ELSE 'PICKUP_SCHEDULED' END) ELSE status END, "
                    + "updated_at = now() WHERE id = ?", id);
        }
        Map<String, Object> out = details(shopId, id);
        if (!want.equals(out.get("fulfilment"))) throw new ConflictException("Couldn't change the collection method; edit the quote note");
        notifyCustomer(s, "SELL_FULFILMENT_CHANGED", visit ? "Visit the shop to sell" : "Free pickup arranged",
                visit ? String.format("Please bring your %s to %s.", out.get("productName"), shopName(shopId))
                        : String.format("%s will pick up your %s from your location.", shopName(shopId), out.get("productName")));
        return ResponseEntity.ok(out);
    }

    /**
     * The shop has the device: brought in on a shop visit, or handed over by the
     * pickup person — then the owner verifies the order, the IMEI (must match the
     * doorstep inspection) and the condition. Accepted once; a paid pickup moves
     * to PAYMENT_PENDING (the customer confirms) and gets its stock entry.
     */
    @PostMapping("/{id}/receive")
    @Transactional
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest req, @PathVariable UUID id, @RequestBody(required = false) ReceiveRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        Map<String, Object> d = details(shopId, id);
        Map<String, Object> w = workflow(id);
        if (w.get("received_at") != null) return ResponseEntity.ok(d); // double tap
        if (!actions(d).contains("RECEIVE")) throw new ConflictException("Confirm receipt after the device is collected");
        String handover = null;
        if (!"SHOP_VISIT".equals(d.get("fulfilment"))) {
            if (body == null || !Boolean.TRUE.equals(body.orderVerified()) || !Boolean.TRUE.equals(body.conditionVerified())) {
                throw new IllegalArgumentException("Verify the order ID and the device condition first");
            }
            String expected = digits(first(d.get("pickupInspection") instanceof Map<?, ?> pin ? pin.get("imei") : null, d.get("imei")));
            String given = digits(body.imei());
            if (expected != null && given == null) throw new IllegalArgumentException("Enter the IMEI on the device to verify the handover");
            if (expected != null && !expected.equals(given)) throw new ConflictException("The IMEI doesn't match the device inspected at the customer's place");
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("imei", given);
            rec.put("imeiMatched", expected != null);
            rec.put("orderVerified", true);
            rec.put("conditionVerified", true);
            rec.put("verifiedAt", Instant.now().toString());
            try { handover = objectMapper.writeValueAsString(rec); } catch (Exception e) { throw new IllegalStateException("Could not save the handover"); }
        }
        boolean paid = w.get("payout_recorded_at") != null;
        jdbc.update("UPDATE sell_orders SET received_at = now(), handover_json = ?, status = ?, updated_at = now() WHERE id = ?",
                handover, paid ? "PAYMENT_PENDING" : "DEVICE_RECEIVED", id);
        if (paid) {
            addToInventory(shopId, id);
            notifyCustomer(s, "SELL_PAYOUT_SENT", "Confirm your payment",
                    String.format("%s received your %s. Confirm in the app that you got %s.", shopName(shopId), d.get("productName"), rupees((BigDecimal) w.get("payout_amount"))));
        } else {
            notifyCustomer(s, "SELL_DEVICE_RECEIVED", "Device received",
                    String.format("%s received your %s and will inspect it now.", shopName(shopId), d.get("productName")));
        }
        return ResponseEntity.ok(details(shopId, id));
    }

    // ── doorstep price (pickup, OUT_FOR_PICKUP, after the employee's inspection) ──

    /** Keeps the accepted quote after reviewing the doorstep inspection: the employee may pay the customer. */
    @PostMapping("/{id}/pickup-price/confirm")
    @Transactional
    public ResponseEntity<Map<String, Object>> confirmPickupQuote(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        Map<String, Object> w = workflow(id);
        if ("SHOP_CONFIRMED".equals(w.get("revision_status"))) return ResponseEntity.ok(details(shopId, id));
        doorstepPriceGate(s, w);
        jdbc.update("UPDATE sell_orders SET revision_status = 'SHOP_CONFIRMED', revised_price = NULL, revision_reason = NULL, "
                + "revision_shop_note = NULL, revision_note = NULL, updated_at = now() WHERE id = ?", id);
        notifyCustomer(s, "SELL_PRICE_CONFIRMED", "Price confirmed",
                String.format("%s confirmed %s for your device after the inspection. The pickup person will pay you now.", shopName(shopId), rupees(s.getFinalPrice())));
        return ResponseEntity.ok(details(shopId, id));
    }

    /** The owner's own final price after the doorstep inspection: sent to the customer to accept. */
    @PostMapping("/{id}/pickup-price/revise")
    @Transactional
    public ResponseEntity<Map<String, Object>> revisePickupPrice(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReviseRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        Map<String, Object> w = workflow(id);
        doorstepPriceGate(s, w);
        BigDecimal amount = body == null ? null : body.amount();
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Enter the final price");
        if (amount.compareTo(new BigDecimal("10000000")) > 0) throw new IllegalArgumentException("That price looks too high");
        if (s.getFinalPrice() != null && amount.compareTo(s.getFinalPrice()) == 0) throw new IllegalArgumentException("That's the accepted quote — approve the quote instead");
        String reason = trim(body.reason());
        if (reason == null || reason.length() < 5) throw new IllegalArgumentException("Explain the price (at least 5 characters)");
        reason = reason.substring(0, Math.min(reason.length(), 1000));
        jdbc.update("UPDATE sell_orders SET revised_price = ?, revision_reason = ?, revision_shop_note = NULL, revision_note = NULL, "
                + "revision_status = 'SHOP_APPROVED', updated_at = now() WHERE id = ?", amount, reason, id);
        notifyCustomer(s, "SELL_PRICE_REVISED", "Final price — your approval needed",
                String.format("%s offers %s instead of %s after inspecting your device: %s. Accept or reject it in the app.", shopName(shopId), rupees(amount), rupees(s.getFinalPrice()), reason));
        return ResponseEntity.ok(details(shopId, id));
    }

    /** Asks the pickup person to inspect the device again (the price decision starts over). */
    @PostMapping("/{id}/pickup-reinspect")
    @Transactional
    public ResponseEntity<Map<String, Object>> requestReinspection(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReasonRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        Map<String, Object> w = workflow(id);
        if ("REINSPECT".equals(w.get("revision_status")) && w.get("pickup_inspected_at") == null) return ResponseEntity.ok(details(shopId, id));
        doorstepPriceGate(s, w);
        String reason = trim(body == null ? null : body.reason());
        if (reason == null || reason.length() < 3) throw new IllegalArgumentException("Tell the pickup person what to re-check");
        // The last inspection stays visible (and pre-fills the re-check); it has to be saved again.
        jdbc.update("UPDATE sell_orders SET pickup_inspected_at = NULL, pickup_inspection_started_at = NULL, revised_price = NULL, revision_reason = NULL, revision_pickup_note = NULL, "
                + "revision_note = NULL, revision_status = 'REINSPECT', revision_shop_note = ?, updated_at = now() WHERE id = ?",
                reason.substring(0, Math.min(reason.length(), 500)), id);
        return ResponseEntity.ok(details(shopId, id));
    }

    /** The pickup person's suggested price: the owner approves it before the customer is asked. */
    @PostMapping("/{id}/pickup-revision/approve")
    @Transactional
    public ResponseEntity<Map<String, Object>> approvePickupRevision(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        Map<String, Object> w = workflow(id);
        if ("SHOP_APPROVED".equals(w.get("revision_status"))) return ResponseEntity.ok(details(shopId, id)); // double tap
        if (!"OUT_FOR_PICKUP".equals(up(s.getStatus())) || !"SHOP_PENDING".equals(w.get("revision_status")) || w.get("payout_recorded_at") != null) {
            throw new ConflictException("There is no revised price waiting for your approval");
        }
        jdbc.update("UPDATE sell_orders SET revision_status = 'SHOP_APPROVED', revision_shop_note = NULL, revision_note = NULL, updated_at = now() WHERE id = ?", id);
        notifyCustomer(s, "SELL_PRICE_REVISED", "Final price — your approval needed",
                String.format("%s offers %s instead of %s after inspecting your device: %s. Accept or reject it in the app.",
                        shopName(shopId), rupees((BigDecimal) w.get("revised_price")), rupees(s.getFinalPrice()), Objects.toString(w.get("revision_reason"), "")));
        return ResponseEntity.ok(details(shopId, id));
    }

    /** Sends the pickup person's suggested price back with the owner's reason (they suggest again, or the owner decides). */
    @PostMapping("/{id}/pickup-revision/reject")
    @Transactional
    public ResponseEntity<Map<String, Object>> rejectPickupRevision(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReasonRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        Map<String, Object> w = workflow(id);
        if ("SHOP_REJECTED".equals(w.get("revision_status"))) return ResponseEntity.ok(details(shopId, id));
        if (!"OUT_FOR_PICKUP".equals(up(s.getStatus())) || !"SHOP_PENDING".equals(w.get("revision_status")) || w.get("payout_recorded_at") != null) {
            throw new ConflictException("There is no revised price waiting for your approval");
        }
        String reason = trim(body == null ? null : body.reason());
        if (reason == null || reason.length() < 3) throw new IllegalArgumentException("Tell the pickup person why");
        jdbc.update("UPDATE sell_orders SET revision_status = 'SHOP_REJECTED', revision_shop_note = ?, updated_at = now() WHERE id = ?",
                reason.substring(0, Math.min(reason.length(), 500)), id);
        return ResponseEntity.ok(details(shopId, id));
    }

    /** Doorstep price decisions: on the way, inspected, not paid, and the price not already with the customer. */
    private void doorstepPriceGate(SellOrder s, Map<String, Object> w) {
        if (!"OUT_FOR_PICKUP".equals(up(s.getStatus()))) throw new ConflictException("The price is decided at the customer's place, after the inspection");
        if (w.get("payout_recorded_at") != null) throw new ConflictException("The customer is already paid");
        if (w.get("pickup_inspected_at") == null) throw new ConflictException("Wait for the pickup person's inspection");
        if (PRICE_WITH_CUSTOMER.contains(String.valueOf(w.get("revision_status")))) throw new ConflictException("The final price is already with the customer");
    }

    /** IMEI / serial, model, condition, functional checks and photos. Unlocked once the device is received. */
    @PostMapping("/{id}/inspection")
    @Transactional
    public ResponseEntity<Map<String, Object>> inspection(HttpServletRequest req, @PathVariable UUID id, @RequestBody InspectionRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        String status = up(s.getStatus());
        if (!"DEVICE_RECEIVED".equals(status) && !"INSPECTED".equals(status)) throw new ConflictException("Inspection opens once the device is received");
        if (body == null) throw new IllegalArgumentException("Fill in the inspection");
        String imei = trim(body.imei());
        String serial = trim(body.serial());
        if (imei != null && !(IMEI.matcher(imei).matches() && luhn(imei))) throw new IllegalArgumentException("Enter a valid 15-digit IMEI");
        if (imei == null && (serial == null || serial.length() < 5)) throw new IllegalArgumentException("Enter the IMEI (or the serial number for devices without one)");
        if (body.modelVerified() == null) throw new IllegalArgumentException("Confirm whether the model matches");
        String notes = trim(body.notes());
        if (!body.modelVerified() && notes == null) throw new IllegalArgumentException("Describe the model mismatch in the notes");
        if (body.condition() == null || !CONDITIONS.contains(body.condition())) throw new IllegalArgumentException("Choose the device condition");
        // Checklist: the display is required; the other functional checks are recorded when answered.
        Map<String, Boolean> checks = new LinkedHashMap<>();
        for (String k : CHECKS) {
            Boolean v = body.checks() == null ? null : body.checks().get(k);
            if (v != null) checks.put(k, v);
        }
        if (!checks.containsKey("display")) throw new IllegalArgumentException("Check the display");
        Integer battery = body.batteryHealth();
        if (battery != null && (battery < 1 || battery > 100)) throw new IllegalArgumentException("Battery health must be 1–100%");
        List<String> accessories = body.accessories() == null ? List.of() : body.accessories().stream().map(ShopPurchaseController::trim)
                .filter(Objects::nonNull).map(a -> a.substring(0, Math.min(a.length(), 40))).distinct().limit(10).toList();
        List<String> photos = body.photos() == null ? List.of() : body.photos().stream().map(ShopPurchaseController::trim)
                .filter(p -> p != null && p.startsWith("http")).distinct().toList();
        if (photos.size() < 2) throw new IllegalArgumentException("Add at least 2 device photos");
        if (photos.size() > 8) throw new IllegalArgumentException("Add up to 8 photos");
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("imei", imei);
        rec.put("serial", serial);
        rec.put("modelVerified", body.modelVerified());
        rec.put("condition", body.condition());
        rec.put("checks", checks);
        rec.put("batteryHealth", battery);
        rec.put("accessories", accessories);
        rec.put("photos", photos);
        rec.put("notes", notes == null ? null : notes.substring(0, Math.min(notes.length(), 1000)));
        String json;
        try { json = objectMapper.writeValueAsString(rec); } catch (Exception e) { throw new IllegalStateException("Could not save the inspection"); }
        jdbc.update("UPDATE sell_orders SET inspection_json = ?, inspected_at = now(), status = 'INSPECTED', updated_at = now() WHERE id = ?", json, id);
        if (imei != null && s.getImei() == null) jdbc.update("UPDATE sell_orders SET imei = ? WHERE id = ?", imei, id);
        if ("DEVICE_RECEIVED".equals(status)) {
            notifyCustomer(s, "SELL_INSPECTED", "Device inspected", String.format("%s finished inspecting your device.", shopName(shopId)));
        }
        return ResponseEntity.ok(details(shopId, id));
    }

    /** Keeps the accepted quote as the final price. */
    @PostMapping("/{id}/price/confirm")
    @Transactional
    public ResponseEntity<Map<String, Object>> confirmPrice(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        if ("PRICE_CONFIRMED".equals(up(s.getStatus()))) return ResponseEntity.ok(details(shopId, id));
        if (!PRICE_DECIDABLE.contains(up(s.getStatus()))) throw new ConflictException("Finish the inspection first");
        jdbc.update("UPDATE sell_orders SET status = 'PRICE_CONFIRMED', updated_at = now() WHERE id = ?", id);
        notifyCustomer(s, "SELL_PRICE_CONFIRMED", "Price confirmed",
                String.format("%s confirmed %s for your device. Payment is next.", shopName(shopId), rupees(s.getFinalPrice())));
        return ResponseEntity.ok(details(shopId, id));
    }

    /** A different final price after inspection; the customer must approve it. */
    @PostMapping("/{id}/price/revise")
    @Transactional
    public ResponseEntity<Map<String, Object>> revisePrice(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReviseRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        if (!PRICE_DECIDABLE.contains(up(s.getStatus()))) throw new ConflictException("Finish the inspection first");
        BigDecimal amount = body == null ? null : body.amount();
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Enter the revised price");
        if (amount.compareTo(new BigDecimal("10000000")) > 0) throw new IllegalArgumentException("That price looks too high");
        if (s.getFinalPrice() != null && amount.compareTo(s.getFinalPrice()) == 0) throw new IllegalArgumentException("The revised price is the same as the quote — confirm the quote instead");
        String reason = trim(body.reason());
        if (reason == null || reason.length() < 10) throw new IllegalArgumentException("Explain the revision (at least 10 characters)");
        jdbc.update("UPDATE sell_orders SET revised_price = ?, revision_reason = ?, revision_status = 'PENDING', revision_note = NULL, "
                + "status = 'PRICE_REVISION_PENDING', updated_at = now() WHERE id = ?", amount, reason.substring(0, Math.min(reason.length(), 1000)), id);
        notifyCustomer(s, "SELL_PRICE_REVISED", "Revised price — your approval needed",
                String.format("%s offers %s instead of %s: %s. Approve or reject it in the app.", shopName(shopId), rupees(amount), rupees(s.getFinalPrice()), reason));
        return ResponseEntity.ok(details(shopId, id));
    }

    /** Records the UPI / bank transfer to the customer; it counts as paid only when the customer confirms receipt. */
    @PostMapping("/{id}/payout")
    @Transactional
    public ResponseEntity<Map<String, Object>> payout(HttpServletRequest req, @PathVariable UUID id, @RequestBody PayoutRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        if (!"PRICE_CONFIRMED".equals(up(s.getStatus()))) throw new ConflictException("Confirm the final price before recording the payment");
        String method = body == null || body.method() == null ? "" : body.method().trim().toUpperCase();
        if (!Set.of("UPI", "BANK", "CASH").contains(method)) throw new IllegalArgumentException("Choose UPI, bank transfer or cash");
        boolean cash = method.equals("CASH");
        String account = cash ? null : trim(body.account()); // optional: the reference identifies the transfer
        if (account != null && method.equals("UPI") && !account.matches("[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}")) throw new IllegalArgumentException("Enter a valid UPI ID (name@bank)");
        if (account != null && method.equals("BANK") && !account.replaceAll("\\s", "").matches("(?i)\\d{6,18}(/[A-Z]{4}0[A-Z0-9]{6})?")) throw new IllegalArgumentException("Enter the account number (optionally /IFSC)");
        String reference = trim(body.reference());
        // UPI / bank need the UTR to verify the transfer; cash is verified by the customer's confirmation alone.
        if (cash ? reference != null && !UTR.matcher(reference).matches() : reference == null || !UTR.matcher(reference).matches()) {
            throw new IllegalArgumentException("Enter the transaction reference (UTR, 6–30 letters/digits)");
        }
        if (s.getFinalPrice() == null) throw new ConflictException("This order has no final price");
        jdbc.update("UPDATE sell_orders SET payout_method = ?, payout_account = ?, payout_reference = ?, payout_amount = ?, "
                        + "payout_recorded_at = now(), payout_confirmed_at = NULL, payout_dispute_note = NULL, status = 'PAYMENT_PENDING', updated_at = now() WHERE id = ?",
                method, account, reference, s.getFinalPrice(), id);
        notifyCustomer(s, "SELL_PAYOUT_SENT", "Confirm your payment",
                cash ? String.format("%s paid %s in cash. Confirm in the app once you have it.", shopName(shopId), rupees(s.getFinalPrice()))
                        : String.format("%s sent %s by %s (ref %s). Confirm in the app once you receive it.", shopName(shopId), rupees(s.getFinalPrice()),
                        method.equals("UPI") ? "UPI" : "bank transfer", reference));
        return ResponseEntity.ok(details(shopId, id));
    }

    /** Payment confirmed by the customer and the device is with the shop: the purchase is done. */
    @PostMapping("/{id}/complete")
    @Transactional
    public ResponseEntity<Map<String, Object>> complete(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        String status = up(s.getStatus());
        if ("COMPLETED".equals(status)) return ResponseEntity.ok(details(shopId, id)); // double tap
        Map<String, Object> w = workflow(id);
        if (!"PAID".equals(status) || w.get("payout_confirmed_at") == null) throw new ConflictException("Complete the purchase after the customer confirms the payment");
        if (w.get("received_at") == null) throw new ConflictException("Confirm the device is received first");
        jdbc.update("UPDATE sell_orders SET status = 'COMPLETED', completed_at = now(), updated_at = now() WHERE id = ?", id);
        customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
            co.setStatus("COMPLETED");
            co.setTotalAmount(s.getFinalPrice());
            customerOrderRepo.save(co);
        });
        if (hasListings()) {
            jdbc.update("UPDATE marketplace_listings SET status = 'SOLD', updated_at = now() WHERE sell_order_id = ? AND status = 'AVAILABLE'", id);
        }
        addToInventory(shopId, id); // once per order (a pickup already got it on the verified handover)
        Map<String, Object> d = details(shopId, id);
        notifyCustomer(s, "SELL_COMPLETED", "Device sold",
                String.format("Your sale of %s to %s for %s is complete.", d.get("productName"), shopName(shopId), rupees(s.getFinalPrice())));
        return ResponseEntity.ok(d);
    }

    /** The shop cancels the purchase (reason required); not after the payout is recorded. */
    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<Map<String, Object>> cancel(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReasonRequest body) {
        UUID shopId = sellerShop(req);
        SellOrder s = lockOwned(shopId, id);
        String status = up(s.getStatus());
        if ("CANCELLED".equals(status)) return ResponseEntity.ok(details(shopId, id));
        if (NOT_CANCELLABLE.contains(status) || workflow(id).get("payout_recorded_at") != null) {
            throw new ConflictException("A purchase with a recorded payment can't be cancelled");
        }
        String reason = trim(body == null ? null : body.reason());
        if (reason == null || reason.length() < 5) throw new IllegalArgumentException("Give a reason for cancelling");
        Map<String, Object> w = workflow(id);
        boolean withShop = w.get("picked_up_at") != null || w.get("received_at") != null;
        jdbc.update("UPDATE sell_orders SET status = 'CANCELLED', cancel_reason = ?, cancelled_by = 'SHOP', updated_at = now() WHERE id = ?",
                reason.substring(0, Math.min(reason.length(), 500)), id);
        customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
            co.setStatus("CANCELLED");
            customerOrderRepo.save(co);
        });
        notifyCustomer(s, "SELL_CANCELLED", "Sale cancelled by the shop",
                String.format("%s cancelled the purchase: %s.%s", shopName(shopId), reason, withShop ? " The shop will return your device." : ""));
        return ResponseEntity.ok(details(shopId, id));
    }

    // ── views ────────────────────────────────────────────────────────────────

    private Map<String, Object> details(UUID shopId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(BASE_SELECT + "AND s.id = ?", shopId, id);
        if (rows.isEmpty()) {
            if (sellRepo.existsById(id)) throw new ForbiddenException("This order belongs to another shop");
            throw new ResourceNotFoundException("Purchase order not found");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> l = listingsFor(List.of(id)).get(id);
        Map<String, Object> out = summary(r, l);

        Map<String, Object> customer = new LinkedHashMap<>();
        // SELECT *: older databases lack some customer columns.
        jdbc.queryForList("SELECT * FROM customer_users WHERE id = ?", uuid(r.get("customer_user_id")))
                .stream().findFirst().ifPresent(c -> {
                    customer.put("name", c.get("full_name"));
                    customer.put("phone", c.get("mobile"));
                    customer.put("imageUrl", c.get("profile_image_url"));
                });
        out.put("customer", customer);

        Map<String, Object> address = new LinkedHashMap<>();
        UUID addressId = uuid(r.get("address_id"));
        if (addressId != null) {
            jdbc.queryForList("SELECT * FROM customer_addresses WHERE id = ?", addressId).stream().findFirst().ifPresent(a -> {
                address.put("line", joinNonBlank(", ", a.get("address_line"), first(a.get("area"), a.get("locality")),
                        first(a.get("district"), a.get("city")), a.get("state"), a.get("pincode")));
                address.put("latitude", a.get("latitude"));
                address.put("longitude", a.get("longitude"));
                address.put("contactName", a.get("full_name"));
                address.put("contactPhone", a.get("mobile"));
                if (customer.get("phone") == null) customer.put("phone", a.get("mobile"));
                if (customer.get("name") == null) customer.put("name", a.get("full_name"));
            });
        }
        if (address.isEmpty() && l != null) {
            address.put("line", joinNonBlank(", ", l.get("address"), l.get("city")));
            address.put("latitude", l.get("latitude"));
            address.put("longitude", l.get("longitude"));
        }
        out.put("address", address);
        out.put("sellOrderId", r.get("id"));
        out.put("description", first(l == null ? null : l.get("description"), r.get("device_condition_summary")));
        out.put("expectedPrice", l == null ? null : l.get("expected_price"));
        out.put("quotedPrice", r.get("quotation_price"));
        out.put("imei", r.get("imei"));

        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("personId", r.get("assigned_pickup_person_id"));
        pickup.put("personName", r.get("pickup_person_name"));
        pickup.put("personPhone", r.get("pickup_person_phone"));
        pickup.put("assignedAt", iso(r.get("pickup_assigned_at")));
        pickup.put("acceptedAt", iso(r.get("pickup_accepted_at")));
        pickup.put("startedAt", iso(r.get("pickup_started_at")));
        pickup.put("pickedUpAt", iso(r.get("picked_up_at")));
        pickup.put("receivedAt", iso(r.get("received_at")));
        pickup.put("issue", r.get("pickup_issue"));
        // Doorstep progress (Employee App): PIN verified at the door, inspection saved, handed over at the shop.
        pickup.put("otpSentAt", iso(r.get("pickup_otp_sent_at")));
        pickup.put("otpVerifiedAt", iso(r.get("pickup_otp_verified_at")));
        pickup.put("arrivedAt", iso(r.get("pickup_arrived_at")));
        pickup.put("inspectionStartedAt", iso(r.get("pickup_inspection_started_at")));
        pickup.put("inspectedAt", iso(r.get("pickup_inspected_at")));
        pickup.put("handedOverAt", iso(r.get("handed_over_at")));
        out.put("pickup", pickup);
        // The pickup person's doorstep inspection (listing vs actual, checklist, photos) and the owner's handover check.
        out.put("pickupInspection", readInspection(str(r.get("pickup_inspection_json"))));
        out.put("handover", readInspection(str(r.get("handover_json"))));
        out.put("customerAnswers", customerAnswers(r));

        out.put("inspection", readInspection(str(r.get("inspection_json"))));
        out.put("inspectedAt", iso(r.get("inspected_at")));

        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("amount", r.get("revised_price"));
        revision.put("reason", r.get("revision_reason"));
        revision.put("status", r.get("revision_status"));
        revision.put("customerNote", r.get("revision_note"));
        revision.put("pickupNote", r.get("revision_pickup_note"));
        revision.put("shopNote", r.get("revision_shop_note"));
        out.put("revision", r.get("revision_status") == null ? null : revision);

        String status = up(r.get("status"));
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("amount", r.get("final_price"));
        payment.put("status", r.get("payout_confirmed_at") != null ? "PAID"
                : "CANCELLED".equals(status) ? "CANCELLED"
                : r.get("payout_recorded_at") != null ? "AWAITING_CONFIRMATION" : "PENDING");
        payment.put("method", r.get("payout_method"));
        payment.put("account", r.get("payout_account"));
        payment.put("reference", r.get("payout_reference"));
        payment.put("recordedAt", iso(r.get("payout_recorded_at")));
        payment.put("paidAt", iso(r.get("payout_confirmed_at")));
        payment.put("disputeNote", r.get("payout_dispute_note"));
        payment.put("proofUrl", r.get("payout_proof_url"));
        out.put("payment", payment);
        Map<String, Object> inventory = null;
        if (r.get("inventory_item_id") != null) {
            inventory = new LinkedHashMap<>();
            inventory.put("itemId", r.get("inventory_item_id"));
            inventory.put("sku", inventorySku(r.get("sell_number")));
        }
        out.put("inventory", inventory);
        out.put("completedAt", iso(r.get("completed_at")));
        out.put("cancelReason", r.get("cancel_reason"));
        out.put("cancelledBy", r.get("cancelled_by"));
        out.put("allowedActions", actions(out));
        return out;
    }

    private List<String> actions(Map<String, Object> d) {
        String status = up(d.get("status"));
        boolean visit = "SHOP_VISIT".equals(d.get("fulfilment"));
        List<String> a = new ArrayList<>();
        boolean paid = d.get("payment") instanceof Map<?, ?> p && p.get("recordedAt") != null;
        boolean doorInspected = d.get("pickup") instanceof Map<?, ?> pk && pk.get("inspectedAt") != null;
        String rv = d.get("revision") instanceof Map<?, ?> r ? up(r.get("status")) : "";
        if (BEFORE_JOURNEY.contains(status)) a.add("SCHEDULE");
        if (!visit && !paid && (BEFORE_JOURNEY.contains(status) || "OUT_FOR_PICKUP".equals(status))) a.add("ASSIGN");
        // Doorstep price: the owner decides after the employee's inspection, before the payment.
        if (!visit && "OUT_FOR_PICKUP".equals(status) && !paid && doorInspected) {
            if ("SHOP_PENDING".equals(rv)) { a.add("APPROVE_PICKUP_REVISION"); a.add("REJECT_PICKUP_REVISION"); }
            if (!PRICE_WITH_CUSTOMER.contains(rv)) {
                if (!"SHOP_CONFIRMED".equals(rv)) a.add("CONFIRM_PICKUP_QUOTE");
                a.add("REVISE_PICKUP_PRICE");
                a.add("REQUEST_REINSPECTION");
            }
        }
        if ("PICKED_UP".equals(status) || (visit && Set.of("SELL_CONFIRMED", "PICKUP_SCHEDULED").contains(status))) a.add("RECEIVE");
        if ("DEVICE_RECEIVED".equals(status) || "INSPECTED".equals(status)) a.add("INSPECT");
        if (PRICE_DECIDABLE.contains(status)) { a.add("CONFIRM_PRICE"); a.add("REVISE_PRICE"); }
        if ("PRICE_CONFIRMED".equals(status)) a.add("RECORD_PAYOUT");
        if ("PAID".equals(status)) a.add("COMPLETE");
        if (!NOT_CANCELLABLE.contains(status) && !paid) a.add("CANCEL");
        return a;
    }

    private Map<String, Object> summary(Map<String, Object> r, Map<String, Object> l) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("orderNumber", r.get("sell_number"));
        out.put("status", r.get("status"));
        out.put("amount", first(r.get("final_price"), r.get("quotation_price")));
        out.put("productName", first(l == null ? null : l.get("product_name"), r.get("model_name"), "Device"));
        out.put("modelName", r.get("model_name"));
        out.put("imageUrl", first(l == null ? null : l.get("product_image"), r.get("front_image_url")));
        out.put("condition", l == null ? null : l.get("condition"));
        out.put("color", r.get("color"));
        out.put("storage", r.get("storage_label"));
        out.put("listingId", l == null ? null : l.get("id"));
        String note = str(r.get("quote_note"));
        out.put("fulfilment", note != null && note.toLowerCase().contains("visit the shop") ? "SHOP_VISIT" : "PICKUP");
        out.put("pickupDate", r.get("pickup_date") == null ? null : ((Date) r.get("pickup_date")).toLocalDate().toString());
        out.put("pickupSlotStart", r.get("pickup_slot_start") == null ? null : ((Time) r.get("pickup_slot_start")).toLocalTime().toString());
        out.put("pickupSlotEnd", r.get("pickup_slot_end") == null ? null : ((Time) r.get("pickup_slot_end")).toLocalTime().toString());
        out.put("pickupPersonName", r.get("pickup_person_name"));
        out.put("pickupPersonId", r.get("assigned_pickup_person_id"));
        out.put("createdAt", iso(first(r.get("accepted_at"), r.get("created_at"))));
        out.put("updatedAt", iso(r.get("updated_at")));
        return out;
    }

    private Map<String, Object> readInspection(String json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return null; }
    }

    /** sell order id → its marketplace listing row; empty where there is no listing table. */
    private Map<UUID, Map<String, Object>> listingsFor(List<UUID> sellOrderIds) {
        if (sellOrderIds.isEmpty() || !hasListings()) return Map.of();
        String in = sellOrderIds.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbc.queryForList("SELECT id, sell_order_id, product_name, product_image, condition, description, expected_price, "
                            + "address, city, latitude, longitude FROM marketplace_listings WHERE sell_order_id IN (" + in + ")",
                    sellOrderIds.toArray()).stream()
                    .collect(Collectors.toMap(m -> uuid(m.get("sell_order_id")), m -> m, (a, b) -> a));
    }

    /** What the customer answered when selling: condition, problems, screening and accessories. */
    private Map<String, Object> customerAnswers(Map<String, Object> r) {
        UUID id = uuid(r.get("id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workingCondition", r.get("working_condition"));
        out.put("conditionSummary", r.get("device_condition_summary"));
        out.put("conditions", jdbc.queryForList("SELECT group_name AS \"group\", option_label AS \"option\" FROM sell_order_conditions WHERE sell_order_id = ?", id));
        out.put("issues", jdbc.queryForList("SELECT issue_code FROM sell_order_issues WHERE sell_order_id = ?", id).stream()
                .map(m -> str(m.get("issue_code"))).filter(Objects::nonNull).toList());
        out.put("screening", jdbc.queryForList("SELECT question, answer FROM sell_order_screening_answers WHERE sell_order_id = ?", id));
        out.put("accessories", jdbc.queryForList("SELECT label, accessory_code FROM sell_order_accessories WHERE sell_order_id = ?", id).stream()
                .map(m -> str(first(m.get("label"), m.get("accessory_code")))).filter(Objects::nonNull).toList());
        return out;
    }

    /**
     * One stock entry per purchased device (sku BUY-&lt;sell number&gt;, quantity 1, cost = final price),
     * linked back on sell_orders.inventory_item_id; never a second one for the same order.
     */
    private void addToInventory(UUID shopId, UUID id) {
        if (!hasInventory()) return;
        // The receipt must never fail because of the stock table: a category the table doesn't accept is left empty
        // (or the entry skipped when the column is required).
        String category = inventoryCategory();
        if (category == null && !inventoryColumnNullable("category")) return;
        Map<String, Object> w = workflow(id);
        if (w.get("inventory_item_id") != null) return;
        String sku = inventorySku(w.get("sell_number"));
        Map<String, Object> d = details(shopId, id);
        Object imei = first(d.get("handover") instanceof Map<?, ?> h ? h.get("imei") : null,
                d.get("pickupInspection") instanceof Map<?, ?> pin ? pin.get("imei") : null, w.get("imei"));
        String name = joinNonBlank(" · ", d.get("productName"), d.get("storage"), d.get("color"), imei == null ? null : "IMEI " + imei);
        name = name == null ? "Purchased device" : name.substring(0, Math.min(name.length(), 255));
        jdbc.update("INSERT INTO inventory_items (id, shop_id, sku, name, category, quantity, unit_price) "
                + "SELECT ?, ?, ?, ?, ?, 1, ? WHERE NOT EXISTS (SELECT 1 FROM inventory_items WHERE shop_id = ? AND sku = ?)",
                UUID.randomUUID(), shopId, sku, name, category, w.get("final_price"), shopId, sku);
        jdbc.queryForList("SELECT id FROM inventory_items WHERE shop_id = ? AND sku = ?", shopId, sku).stream().findFirst()
                .ifPresent(m -> jdbc.update("UPDATE sell_orders SET inventory_item_id = ? WHERE id = ?", uuid(m.get("id")), id));
    }

    private static String inventorySku(Object sellNumber) {
        return "BUY-" + sellNumber;
    }

    /** Checked first, not caught: a failed statement would abort the surrounding transaction. */
    private boolean hasInventory() {
        // The columns this insert fills exist, and no other required column (NOT NULL, no default) would be left empty.
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*) = 7 FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'inventory_items' AND column_name IN ('id', 'shop_id', 'sku', 'name', 'category', 'quantity', 'unit_price')", Boolean.class))
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*) = 0 FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'inventory_items' AND is_nullable = 'NO' AND column_default IS NULL "
                + "AND column_name NOT IN ('id', 'shop_id', 'sku', 'name', 'category', 'quantity', 'unit_price')", Boolean.class));
    }

    /** 'DEVICE', unless a CHECK constraint on inventory_items.category doesn't allow it (then null). */
    private String inventoryCategory() {
        List<String> checks = jdbc.queryForList("SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                + "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'public' AND t.relname = 'inventory_items' AND c.contype = 'c'", String.class);
        return checks.stream().anyMatch(d -> d != null && d.contains("category") && !d.contains("'DEVICE'")) ? null : "DEVICE";
    }

    private boolean inventoryColumnNullable(String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_nullable = 'YES' FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'inventory_items' AND column_name = ?", Boolean.class, column));
    }

    /** Checked first, not caught: a failed statement would abort the surrounding transaction. */
    private boolean hasListings() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('public.marketplace_listings') IS NOT NULL", Boolean.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SellOrder lockOwned(UUID shopId, UUID id) {
        jdbc.queryForList("SELECT id FROM sell_orders WHERE id = ? FOR UPDATE", id);
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Purchase order not found"));
        if (!shopId.equals(s.getShopId())) throw new ForbiddenException("This order belongs to another shop");
        return s;
    }

    /** The workflow columns (migration 110), which the SellOrder entity doesn't map. */
    private Map<String, Object> workflow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM sell_orders WHERE id = ?", id);
    }

    private void notifyCustomer(SellOrder s, String key, String title, String body) {
        notificationRepo.save(CustomerNotification.builder()
                .customerUserId(s.getCustomerUserId())
                .bookingId(s.getId())
                .bookingNumber(s.getSellNumber())
                .statusKey(key)
                .title(title)
                .body(body)
                .type("orders")
                .build());
    }

    private String shopName(UUID shopId) {
        return jdbc.queryForList("SELECT name FROM shops WHERE id = ?", shopId).stream()
                .map(m -> str(m.get("name"))).filter(Objects::nonNull).findFirst().orElse("The shop");
    }

    /** The token's shop, for shop-owner (or admin) sessions only. */
    private UUID sellerShop(HttpServletRequest req) {
        Object s = req.getAttribute("shopId");
        if (s == null) throw new ForbiddenException("A shop session is required");
        Object roles = req.getAttribute("roles");
        boolean seller = roles instanceof Collection<?> c && c.stream().anyMatch(r -> SELLER_ROLES.contains(String.valueOf(r).toUpperCase()));
        if (!seller) throw new ForbiddenException("Only shop owners can manage purchases");
        return UUID.fromString(s.toString());
    }

    static String rupees(BigDecimal v) {
        return v == null ? "—" : "₹" + v.stripTrailingZeros().toPlainString();
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Object first(Object... vals) {
        for (Object v : vals) if (v != null && !(v instanceof String str && str.isBlank())) return v;
        return null;
    }

    private static String joinNonBlank(String sep, Object... parts) {
        String s = Arrays.stream(parts).map(ShopPurchaseController::str).filter(p -> p != null && !p.isBlank())
                .map(String::trim).distinct().collect(Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }

    private static String digits(Object o) {
        String d = o == null ? "" : o.toString().replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    private static String up(Object o) {
        return o == null ? "" : o.toString().trim().toUpperCase();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    private static String iso(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant().toString();
        if (o instanceof OffsetDateTime odt) return odt.toInstant().toString();
        if (o instanceof Instant i) return i.toString();
        return o.toString();
    }
}
