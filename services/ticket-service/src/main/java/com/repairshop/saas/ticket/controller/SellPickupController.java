package com.repairshop.saas.ticket.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Employee App: devices a shop is buying from customers (sell_orders, after
 * the customer accepted the shop's quote) that the owner assigned to the
 * calling pickup person (order-service POST /sell-orders/shop-purchases/{id}/assign).
 *
 * <p>Same shape and status vocabulary as {@link PickupBookingController}'s repair
 * pickups, plus {@code kind: "SELL"}, so the Employee App's pickup screens work
 * unchanged: PICKUP_PERSON_ASSIGNED (accept / decline) → PICKUP_ON_THE_WAY →
 * DEVICE_PICKED_UP → RECEIVED_AT_SHOP. CANCELLED from the pickup person means
 * "unable to pick up": the order goes back to the shop to reassign. Customer
 * contact and address are hidden until the pickup is accepted.
 *
 * <p>The journey opens 1 hour before the scheduled pickup time. At the doorstep
 * (status OUT_FOR_PICKUP) the pickup person verifies the customer's pickup PIN
 * (pickup_otp, shown only on the customer's order page) → saves the inspection
 * (listing vs actual, checklist, at least 3 photos) → optionally suggests a
 * revised price (revision_status SHOP_PENDING). The shop owner decides in the
 * Partner App: keeps the quote (SHOP_CONFIRMED), approves / sets a revised price
 * (SHOP_APPROVED → the customer accepts APPROVED or declines REJECTED, in the
 * Customer App or here at the door) or asks for a re-check (REINSPECT). Payment
 * is locked until the price is APPROVED or SHOP_CONFIRMED. Then the payment with
 * a proof photo → DEVICE_PICKED_UP → RECEIVED_AT_SHOP (handed over; the owner
 * verifies the handover and confirms receipt in the Partner App).
 */
@RestController
@RequestMapping("/technicians/me/sell-pickups")
@RequiredArgsConstructor
public class SellPickupController {

    /** sell_orders.status → the Employee App's pickup codes. */
    private static final Map<String, String> TO_PICKUP = Map.of(
            "PICKUP_ASSIGNED", "PICKUP_PERSON_ASSIGNED",
            "OUT_FOR_PICKUP", "PICKUP_ON_THE_WAY",
            "PICKED_UP", "DEVICE_PICKED_UP",
            "CANCELLED", "CANCELLED");
    /** Revision states in which the price is settled and the employee may pay the customer. */
    private static final Set<String> PRICE_SETTLED = Set.of("APPROVED", "SHOP_CONFIRMED");
    /** Revision states in which the price is with the owner or the customer: no new inspection / suggestion. */
    private static final Set<String> PRICE_IN_REVIEW = Set.of("SHOP_PENDING", "SHOP_APPROVED", "APPROVED");
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter START_FMT = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TechnicianRepository technicianRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    /** Partner feed row + push, allowlisted (PartnerNotificationPolicy). */
    private final com.repairshop.saas.common.notify.PartnerNotifications partnerNotifications;

    public record StatusRequest(String status, String note, Map<String, Object> inspection, Double latitude, Double longitude) {}
    public record DeclineRequest(String reason, String note) {}
    public record OtpRequest(String otp) {}
    public record InspectionRequest(Map<String, Object> inspection) {}
    public record RevisionRequest(BigDecimal amount, String reason, String note) {}
    public record DecisionRequest(String decision) {}
    public record PaymentRequest(String method, String reference, String proofUrl) {}

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        Technician me = me(req);
        if (me == null || !hasTable()) return List.of();
        return jdbc.queryForList(SELECT + "WHERE s.shop_id = ? AND s.assigned_pickup_person_id = ? ORDER BY s.pickup_date NULLS LAST, s.updated_at DESC LIMIT 200",
                me.getShopId(), me.getId()).stream().map(this::row).toList();
    }

    @PostMapping("/{id}/accept")
    @Transactional
    public ResponseEntity<Map<String, Object>> accept(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        if (s.get("pickup_accepted_at") != null) return ResponseEntity.ok(row(reload(id)));
        if (!"PICKUP_ASSIGNED".equals(s.get("status"))) return refuse(HttpStatus.CONFLICT, "STALE_STATUS", "This pickup was updated by the shop.");
        jdbc.update("UPDATE sell_orders SET pickup_accepted_at = now(), updated_at = now() WHERE id = ?", id);
        notifyShop(s, "SELL_PICKUP_ACCEPTED", "Pickup accepted", String.format("%s accepted the pickup for %s.", me.getName(), s.get("sell_number")));
        return ResponseEntity.ok(row(reload(id)));
    }

    /** Before the journey starts: the order goes back to the shop to reassign. */
    @PostMapping("/{id}/decline")
    @Transactional
    public ResponseEntity<Map<String, Object>> decline(HttpServletRequest req, @PathVariable UUID id, @RequestBody DeclineRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        if (!"PICKUP_ASSIGNED".equals(s.get("status"))) return refuse(HttpStatus.CONFLICT, "STALE_STATUS", "This pickup can't be declined now.");
        String reason = joinNote(body == null ? null : body.reason(), body == null ? null : body.note());
        if (reason == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a reason");
        unassign(id, "Declined by " + me.getName() + ": " + reason);
        notifyShop(s, "SELL_PICKUP_DECLINED", "Pickup declined — reassign",
                String.format("%s declined the pickup for %s: %s", me.getName(), s.get("sell_number"), reason));
        return ResponseEntity.ok(Map.of("id", id, "status", "DECLINED"));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest req, @PathVariable UUID id, @RequestBody StatusRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        String next = body == null || body.status() == null ? "" : body.status().trim().toUpperCase();
        String cur = String.valueOf(s.get("status"));
        String number = String.valueOf(s.get("sell_number"));
        switch (next) {
            case "PICKUP_ON_THE_WAY" -> {
                if ("OUT_FOR_PICKUP".equals(cur)) return ResponseEntity.ok(row(reload(id)));
                if (!"PICKUP_ASSIGNED".equals(cur)) return stale();
                if (s.get("pickup_accepted_at") == null) return refuse(HttpStatus.CONFLICT, "ACCEPT_REQUIRED", "Accept this pickup first.");
                Instant from = startAllowedAt(s);
                if (from != null && Instant.now().isBefore(from)) {
                    return refuse(HttpStatus.CONFLICT, "TOO_EARLY", "You can start this pickup from "
                            + from.atZone(IST).format(START_FMT) + " (1 hour before the pickup time).");
                }
                // A fresh pickup PIN for the doorstep handover.
                jdbc.update("UPDATE sell_orders SET status = 'OUT_FOR_PICKUP', pickup_started_at = now(), pickup_otp = ?, pickup_otp_sent_at = now(), "
                        + "pickup_otp_verified_at = NULL, pickup_otp_attempts = 0, updated_at = now() WHERE id = ?", newPin(), id);
                // The PIN itself is shown only on the customer's order page (GET /sell-orders/{id}/purchase), never in a notification.
                notifyCustomer(s, "SELL_PICKUP_ON_THE_WAY", "Pickup on the way",
                        String.format("%s is on the way to collect your device. Your pickup PIN is on the order page — share it only at handover.", me.getName()));
                notifyShop(s, "SELL_PICKUP_ON_THE_WAY", "Pickup started", String.format("%s started the journey for %s.", me.getName(), number));
            }
            case "DEVICE_PICKED_UP", "PICKED_UP" -> {
                if ("PICKED_UP".equals(cur)) return ResponseEntity.ok(row(reload(id)));
                if (!"OUT_FOR_PICKUP".equals(cur)) return stale();
                if (s.get("pickup_otp_verified_at") == null) return otpRequired();
                if (s.get("pickup_inspected_at") == null) return refuse(HttpStatus.CONFLICT, "INSPECTION_REQUIRED", "Save the inspection first.");
                if (s.get("payout_recorded_at") == null) return refuse(HttpStatus.CONFLICT, "PAYMENT_REQUIRED", "Record the payment to the customer first.");
                jdbc.update("UPDATE sell_orders SET status = 'PICKED_UP', picked_up_at = now(), updated_at = now() WHERE id = ?", id);
                notifyCustomer(s, "SELL_PICKED_UP", "Device picked up", String.format("%s collected your device and is taking it to the shop.", me.getName()));
                notifyShop(s, "SELL_PICKED_UP", "Device picked up", String.format("%s picked up the device for %s. Verify the handover when it reaches the shop.", me.getName(), number));
            }
            case "RECEIVED_AT_SHOP" -> {
                // Handed over: the owner verifies the order, IMEI and condition and confirms receipt in the Partner App.
                if (s.get("handed_over_at") != null || s.get("received_at") != null) return ResponseEntity.ok(row(reload(id)));
                if (!"PICKED_UP".equals(cur)) return stale();
                jdbc.update("UPDATE sell_orders SET handed_over_at = now(), updated_at = now() WHERE id = ?", id);
                notifyShop(s, "SELL_DEVICE_HANDED_OVER", "Device handed over — verify",
                        String.format("%s handed over the device for %s. Verify the order, IMEI and condition, then confirm receipt.", me.getName(), number));
            }
            case "CANCELLED" -> {
                // Unable to pick up (before collection): back to the shop to reassign.
                if (!"PICKUP_ASSIGNED".equals(cur) && !"OUT_FOR_PICKUP".equals(cur)) return stale();
                if (s.get("payout_recorded_at") != null) return refuse(HttpStatus.CONFLICT, "PAYMENT_RECORDED", "The customer is already paid — collect the device.");
                String note = joinNote(body.note(), null);
                if (note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tell the shop what went wrong");
                unassign(id, "Unable to pick up (" + me.getName() + "): " + note);
                notifyShop(s, "SELL_PICKUP_FAILED", "Pickup failed — reassign or reschedule",
                        String.format("%s couldn't pick up %s: %s", me.getName(), number, note));
                notifyCustomer(s, "SELL_PICKUP_FAILED", "Pickup didn't happen", "The pickup couldn't be completed. The shop will contact you to arrange it again.");
                return ResponseEntity.ok(Map.of("id", id, "status", "CANCELLED", "message", "Reported to the shop"));
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        return ResponseEntity.ok(row(reload(id)));
    }

    /** The customer's sell answers (device ids, screening, conditions, issues, accessories, warranty, photos) — pre-fills the inspection. */
    @GetMapping("/{id}/evaluation")
    public ResponseEntity<Map<String, Object>> evaluation(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        if (!hasTable()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup not found");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sell_orders WHERE id = ? AND shop_id = ?", id, me.getShopId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup not found");
        Map<String, Object> s = rows.get(0);
        if (!me.getId().equals(s.get("assigned_pickup_person_id"))) return notMine();
        if (s.get("pickup_accepted_at") == null) return refuse(HttpStatus.CONFLICT, "ACCEPT_REQUIRED", "Accept this pickup first.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("brandId", s.get("brand_id"));
        out.put("modelId", s.get("model_id"));
        out.put("ramOptionId", s.get("ram_option_id"));
        out.put("storageOptionId", s.get("storage_option_id"));
        Object model = s.get("model_id");
        out.put("categoryId", model == null ? null : jdbc.queryForList("SELECT * FROM master_models WHERE id = ?", model).stream().findFirst()
                .map(m -> m.get("category_id") != null ? m.get("category_id") : m.get("device_category_id")).orElse(null));
        out.put("color", s.get("color"));
        out.put("imei", s.get("imei"));
        out.put("workingCondition", s.get("working_condition"));
        out.put("warrantyCode", s.get("warranty_code"));
        out.put("conditionSummary", s.get("device_condition_summary"));
        out.put("conditions", answers("SELECT group_code AS \"groupCode\", group_name AS \"groupName\", option_id AS \"optionId\", option_label AS \"optionLabel\" FROM sell_order_conditions WHERE sell_order_id = ?", id));
        out.put("issues", answers("SELECT issue_id AS \"issueId\", issue_code AS \"issueCode\" FROM sell_order_issues WHERE sell_order_id = ?", id));
        out.put("screeningAnswers", answers("SELECT question_id AS \"questionId\", question, answer FROM sell_order_screening_answers WHERE sell_order_id = ?", id));
        out.put("accessories", answers("SELECT accessory_id AS \"accessoryId\", accessory_code AS \"accessoryCode\", label FROM sell_order_accessories WHERE sell_order_id = ?", id));
        out.put("photos", Arrays.stream(new Object[] { s.get("front_image_url"), s.get("back_image_url"), s.get("side_image_url"), s.get("camera_image_url"), s.get("other_image_url") })
                .filter(Objects::nonNull).map(Object::toString).filter(u -> u.startsWith("http")).toList());
        out.put("pickupInspection", readJson(s.get("pickup_inspection_json")));
        return ResponseEntity.ok(out);
    }

    // ── doorstep: OTP → inspection → price → payment ─────────────────────────

    /** A new pickup PIN (one is made when the journey starts), shown on the customer's order page; one every 30 s at most. */
    @PostMapping("/{id}/otp/send")
    @Transactional
    public ResponseEntity<Map<String, Object>> sendOtp(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        if (!"OUT_FOR_PICKUP".equals(s.get("status"))) return stale();
        if (s.get("pickup_otp_verified_at") != null) return ResponseEntity.ok(row(reload(id)));
        Instant sent = instant(s.get("pickup_otp_sent_at"));
        if (sent != null && Instant.now().isBefore(sent.plusSeconds(30))) {
            return refuse(HttpStatus.TOO_MANY_REQUESTS, "OTP_WAIT", "Wait 30 seconds before sending a new PIN.");
        }
        jdbc.update("UPDATE sell_orders SET pickup_otp = ?, pickup_otp_sent_at = now(), pickup_otp_attempts = 0, updated_at = now() WHERE id = ?", newPin(), id);
        notifyCustomer(s, "SELL_PICKUP_PIN", "New pickup PIN", "A new pickup PIN is on your order page. Share it only with the pickup person at your door.");
        return ResponseEntity.ok(row(reload(id)));
    }

    @PostMapping("/{id}/otp/verify")
    @Transactional
    public ResponseEntity<Map<String, Object>> verifyOtp(HttpServletRequest req, @PathVariable UUID id, @RequestBody OtpRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        if (!"OUT_FOR_PICKUP".equals(s.get("status"))) return stale();
        if (s.get("pickup_otp_verified_at") != null) return ResponseEntity.ok(row(reload(id)));
        int attempts = s.get("pickup_otp_attempts") instanceof Number n ? n.intValue() : 0;
        if (attempts >= OTP_MAX_ATTEMPTS) return refuse(HttpStatus.CONFLICT, "OTP_LOCKED", "Too many wrong PINs. Send a new PIN to the customer.");
        String otp = body == null || body.otp() == null ? "" : body.otp().replaceAll("\\D", "");
        String pin = s.get("pickup_otp") == null ? null : s.get("pickup_otp").toString();
        if (pin == null) return refuse(HttpStatus.CONFLICT, "OTP_NOT_SENT", "Generate a new PIN for the customer first.");
        if (!pin.equals(otp)) {
            jdbc.update("UPDATE sell_orders SET pickup_otp_attempts = COALESCE(pickup_otp_attempts, 0) + 1 WHERE id = ?", id);
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_INVALID", "Wrong PIN. Ask the customer for the PIN on their order page.");
        }
        // The PIN is shared only at the door: verifying it records the arrival if it wasn't recorded before.
        jdbc.update("UPDATE sell_orders SET pickup_otp_verified_at = now(), pickup_arrived_at = COALESCE(pickup_arrived_at, now()), updated_at = now() WHERE id = ?", id);
        notifyShop(s, "SELL_PICKUP_OTP_VERIFIED", "Customer verified",
                String.format("%s verified the customer's PIN for %s and is inspecting the device.", me.getName(), s.get("sell_number")));
        return ResponseEntity.ok(row(reload(id)));
    }

    /** The pickup person opened the inspection (first time only); shown to the owner as "Inspection Started". */
    @PostMapping("/{id}/inspection/start")
    @Transactional
    public ResponseEntity<Map<String, Object>> startInspection(HttpServletRequest req, @PathVariable UUID id) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        if (s.get("pickup_inspection_started_at") != null) return ResponseEntity.ok(row(reload(id)));
        ResponseEntity<Map<String, Object>> gate = doorstepGate(s, false);
        if (gate != null) return gate;
        jdbc.update("UPDATE sell_orders SET pickup_inspection_started_at = now(), updated_at = now() WHERE id = ?", id);
        return ResponseEntity.ok(row(reload(id)));
    }

    /** Listing vs actual, the checklist and at least 3 photos — the owner reviews it before the price is settled. */
    @PostMapping("/{id}/inspection")
    @Transactional
    public ResponseEntity<Map<String, Object>> inspection(HttpServletRequest req, @PathVariable UUID id, @RequestBody InspectionRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        ResponseEntity<Map<String, Object>> gate = doorstepGate(s, false);
        if (gate != null) return gate;
        String rev = String.valueOf(s.get("revision_status"));
        if (PRICE_IN_REVIEW.contains(rev)) return refuse(HttpStatus.CONFLICT, "PRICE_IN_REVIEW", "The price is already with the shop owner or the customer.");
        Map<String, Object> rec = body == null ? null : body.inspection();
        if (rec == null || rec.isEmpty()) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Fill in the inspection.");
        long photos = rec.get("photos") instanceof List<?> l ? l.stream().filter(p -> p instanceof String u && u.startsWith("http")).count() : 0;
        if (photos < 3) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Add at least 3 device photos.");
        if (photos > 8) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Add up to 8 photos.");
        String json;
        try { json = objectMapper.writeValueAsString(rec); } catch (Exception e) { return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Invalid inspection."); }
        if (json.length() > 20000) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Inspection is too large.");
        // A new inspection needs the owner's decision again (a kept quote, a re-check request or a declined price start over).
        jdbc.update("UPDATE sell_orders SET pickup_inspection_json = ?, pickup_inspected_at = now(), "
                + "revision_status = CASE WHEN revision_status IN ('SHOP_CONFIRMED', 'REINSPECT', 'SHOP_REJECTED') THEN NULL ELSE revision_status END, "
                + "updated_at = now() WHERE id = ?", json, id);
        notifyShop(s, "SELL_PICKUP_INSPECTED", "Inspection submitted — approve the price",
                String.format("%s inspected the device for %s at the customer's place. Review it and approve the final price.", me.getName(), s.get("sell_number")));
        return ResponseEntity.ok(row(reload(id)));
    }

    /** A different price after the inspection: the shop owner approves it before the customer is asked. */
    @PostMapping("/{id}/revision")
    @Transactional
    public ResponseEntity<Map<String, Object>> revision(HttpServletRequest req, @PathVariable UUID id, @RequestBody RevisionRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        ResponseEntity<Map<String, Object>> gate = doorstepGate(s, true);
        if (gate != null) return gate;
        String rev = String.valueOf(s.get("revision_status"));
        if ("SHOP_APPROVED".equals(rev) || "APPROVED".equals(rev)) return refuse(HttpStatus.CONFLICT, "PRICE_DECIDED", "The shop owner already approved a price.");
        BigDecimal amount = body == null ? null : body.amount();
        if (amount == null || amount.signum() <= 0) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Enter the revised price.");
        if (amount.compareTo(new BigDecimal("10000000")) > 0) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "That price looks too high.");
        if (s.get("final_price") instanceof BigDecimal q && amount.compareTo(q) == 0) {
            return refuse(HttpStatus.BAD_REQUEST, "INVALID", "The revised price is the same as the quote.");
        }
        String reason = clip(body.reason(), 500);
        if (reason == null || reason.length() < 3) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Choose the reason for the revision.");
        jdbc.update("UPDATE sell_orders SET revised_price = ?, revision_reason = ?, revision_pickup_note = ?, revision_shop_note = NULL, revision_note = NULL, "
                + "revision_status = 'SHOP_PENDING', updated_at = now() WHERE id = ?", amount, reason, clip(body.note(), 1000), id);
        notifyShop(s, "SELL_PICKUP_REVISION", "Revised price — approval needed",
                String.format("%s asks for %s instead of %s on %s: %s", me.getName(), rupees(amount), rupees(s.get("final_price")), s.get("sell_number"), reason));
        return ResponseEntity.ok(row(reload(id)));
    }

    /** The customer's answer to the owner-approved price (AGREE / DECLINE), when they answer in person at the door. */
    @PostMapping("/{id}/revision/customer")
    @Transactional
    public ResponseEntity<Map<String, Object>> customerDecision(HttpServletRequest req, @PathVariable UUID id, @RequestBody DecisionRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        String decision = body == null || body.decision() == null ? "" : body.decision().trim().toUpperCase();
        if (!"AGREE".equals(decision) && !"DECLINE".equals(decision)) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Choose agree or decline.");
        String rev = String.valueOf(s.get("revision_status"));
        if (("AGREE".equals(decision) && "APPROVED".equals(rev)) || ("DECLINE".equals(decision) && "REJECTED".equals(rev))) return ResponseEntity.ok(row(reload(id)));
        ResponseEntity<Map<String, Object>> gate = doorstepGate(s, true);
        if (gate != null) return gate;
        if (!"SHOP_APPROVED".equals(rev)) return refuse(HttpStatus.CONFLICT, "STALE_STATUS", "Wait for the shop owner to approve the price.");
        String number = String.valueOf(s.get("sell_number"));
        if ("AGREE".equals(decision)) {
            jdbc.update("UPDATE sell_orders SET final_price = revised_price, revision_status = 'APPROVED', updated_at = now() WHERE id = ?", id);
            notifyCustomer(s, "SELL_PRICE_CONFIRMED", "Revised price accepted", String.format("You agreed to sell your device for %s.", rupees(s.get("revised_price"))));
            notifyShop(s, "SELL_PICKUP_REVISION_AGREED", "Customer accepted the final price", String.format("The customer agreed to %s for %s.", rupees(s.get("revised_price")), number));
        } else {
            jdbc.update("UPDATE sell_orders SET revision_status = 'REJECTED', updated_at = now() WHERE id = ?", id);
            notifyShop(s, "SELL_PICKUP_REVISION_DECLINED", "Customer declined the final price",
                    String.format("The customer declined %s for %s.", rupees(s.get("revised_price")), number));
        }
        return ResponseEntity.ok(row(reload(id)));
    }

    /** UPI / cash paid to the customer at the doorstep, with a proof photo — only once the owner (and the customer) settled the price. */
    @PostMapping("/{id}/payment")
    @Transactional
    public ResponseEntity<Map<String, Object>> payment(HttpServletRequest req, @PathVariable UUID id, @RequestBody PaymentRequest body) {
        Technician me = requireMe(req);
        Map<String, Object> s = lockMine(me, id);
        if (s == null) return notMine();
        if (s.get("payout_recorded_at") != null && "OUT_FOR_PICKUP".equals(s.get("status"))) return ResponseEntity.ok(row(reload(id)));
        ResponseEntity<Map<String, Object>> gate = doorstepGate(s, true);
        if (gate != null) return gate;
        if (!PRICE_SETTLED.contains(String.valueOf(s.get("revision_status")))) {
            return refuse(HttpStatus.CONFLICT, "PRICE_NOT_APPROVED", "Payment unlocks after the shop owner approves the final price (and the customer accepts a revised price).");
        }
        String method = body == null || body.method() == null ? "" : body.method().trim().toUpperCase();
        if (!"UPI".equals(method) && !"CASH".equals(method)) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Choose UPI or cash.");
        String reference = clip(body.reference(), 30);
        if (reference != null && !reference.matches("[A-Za-z0-9]{6,30}")) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Enter a valid UPI reference (6–30 letters/digits).");
        String proof = clip(body.proofUrl(), 500);
        if (proof == null || !proof.startsWith("http")) return refuse(HttpStatus.BAD_REQUEST, "INVALID", "Upload the payment proof.");
        Object amount = s.get("final_price");
        if (amount == null) return refuse(HttpStatus.CONFLICT, "NO_PRICE", "This order has no final price.");
        jdbc.update("UPDATE sell_orders SET payout_method = ?, payout_account = NULL, payout_reference = ?, payout_amount = final_price, payout_proof_url = ?, "
                + "payout_recorded_at = now(), payout_confirmed_at = NULL, payout_dispute_note = NULL, updated_at = now() WHERE id = ?", method, reference, proof, id);
        notifyCustomer(s, "SELL_PAYOUT_RECORDED", "Payment recorded",
                String.format("%s paid you %s by %s for your device.", me.getName(), rupees(amount), "UPI".equals(method) ? "UPI" : "cash"));
        notifyShop(s, "SELL_PICKUP_PAID", "Customer paid",
                String.format("%s paid %s (%s) for %s.", me.getName(), rupees(amount), method, s.get("sell_number")));
        return ResponseEntity.ok(row(reload(id)));
    }

    /** Doorstep steps need the journey on, the PIN verified, no payment yet and (when asked) the inspection saved. */
    private ResponseEntity<Map<String, Object>> doorstepGate(Map<String, Object> s, boolean needsInspection) {
        if (!"OUT_FOR_PICKUP".equals(s.get("status"))) return stale();
        if (s.get("pickup_otp_verified_at") == null) return otpRequired();
        if (s.get("payout_recorded_at") != null) return refuse(HttpStatus.CONFLICT, "PAYMENT_RECORDED", "The payment is already recorded.");
        if (needsInspection && s.get("pickup_inspected_at") == null) {
            return refuse(HttpStatus.CONFLICT, "INSPECTION_REQUIRED", "REINSPECT".equals(s.get("revision_status"))
                    ? "The shop owner asked for a re-check: " + Objects.toString(s.get("revision_shop_note"), "inspect the device again") + "."
                    : "Save the inspection first.");
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final String SELECT =
            "SELECT s.*, mb.name AS brand_name, mm.name AS model_name, ms.label AS storage_label, "
                    + "cu.full_name AS customer_name, cu.mobile AS customer_mobile, "
                    + "ca.address_line, ca.locality, ca.city AS addr_city, ca.state AS addr_state, ca.pincode, ca.latitude AS addr_lat, ca.longitude AS addr_lng, "
                    + "ca.mobile AS addr_mobile, ca.full_name AS addr_name, q.quotation_price "
                    + "FROM sell_orders s "
                    + "LEFT JOIN master_brands mb ON mb.id = s.brand_id "
                    + "LEFT JOIN master_models mm ON mm.id = s.model_id "
                    + "LEFT JOIN master_storage_options ms ON ms.id = s.storage_option_id "
                    + "LEFT JOIN customer_users cu ON cu.id = s.customer_user_id "
                    + "LEFT JOIN customer_addresses ca ON ca.id = s.address_id "
                    + "LEFT JOIN LATERAL (SELECT quotation_price FROM sell_order_quotations "
                    + "  WHERE sell_order_id = s.id AND shop_id = s.shop_id "
                    + "  ORDER BY (status = 'ACCEPTED') DESC, updated_at DESC LIMIT 1) q ON true ";

    private Map<String, Object> row(Map<String, Object> s) {
        boolean accepted = s.get("pickup_accepted_at") != null;
        String status = String.valueOf(s.get("status"));
        boolean handedOver = (s.get("received_at") != null || s.get("handed_over_at") != null) && !"CANCELLED".equals(status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.get("id"));
        out.put("kind", "SELL");
        out.put("bookingNumber", s.get("sell_number"));
        out.put("shopId", s.get("shop_id"));
        out.put("serviceMode", "PICKUP");
        out.put("status", handedOver ? "RECEIVED_AT_SHOP" : TO_PICKUP.getOrDefault(status, "CANCELLED"));
        out.put("issueSummary", "Device purchase — collect the customer's device");
        out.put("pickupDate", s.get("pickup_date") == null ? null : ((Date) s.get("pickup_date")).toLocalDate().toString());
        out.put("pickupSlotStart", s.get("pickup_slot_start") == null ? null : ((Time) s.get("pickup_slot_start")).toLocalTime().toString());
        out.put("pickupSlotEnd", s.get("pickup_slot_end") == null ? null : ((Time) s.get("pickup_slot_end")).toLocalTime().toString());
        Instant startFrom = startAllowedAt(s);
        out.put("startAllowedAt", startFrom == null ? null : startFrom.toString());
        out.put("assignedPickupPersonId", s.get("assigned_pickup_person_id"));
        out.put("pickupPersonName", s.get("pickup_person_name"));
        out.put("accepted", accepted);
        out.put("acceptedAt", iso(s.get("pickup_accepted_at")));
        out.put("brandName", s.get("brand_name"));
        out.put("modelName", s.get("model_name"));
        out.put("storageLabel", s.get("storage_label"));
        out.put("color", s.get("color"));
        out.put("imei", s.get("imei"));
        out.put("frontImageUrl", s.get("front_image_url"));
        out.put("quotedPrice", s.get("quotation_price"));
        out.put("finalPrice", s.get("final_price"));
        if (accepted) {
            out.put("customerName", s.get("customer_name") != null ? s.get("customer_name") : s.get("addr_name"));
            out.put("customerMobile", s.get("addr_mobile") != null ? s.get("addr_mobile") : s.get("customer_mobile"));
            String addr = String.join(", ", Arrays.stream(new Object[] { s.get("address_line"), s.get("locality"), s.get("addr_city"), s.get("addr_state"), s.get("pincode") })
                    .filter(Objects::nonNull).map(Object::toString).filter(v -> !v.isBlank()).toList());
            out.put("pickupAddressText", addr.isBlank() ? null : addr);
            out.put("pickupLatitude", s.get("addr_lat"));
            out.put("pickupLongitude", s.get("addr_lng"));
        }
        // Doorstep progress (the PIN itself is never sent to the Employee App).
        out.put("otpSentAt", iso(s.get("pickup_otp_sent_at")));
        out.put("otpVerifiedAt", iso(s.get("pickup_otp_verified_at")));
        out.put("arrivedAt", iso(s.get("pickup_arrived_at")));
        out.put("inspectionStartedAt", iso(s.get("pickup_inspection_started_at")));
        out.put("inspectedAt", iso(s.get("pickup_inspected_at")));
        out.put("handedOverAt", iso(s.get("handed_over_at")));
        if (s.get("revision_status") != null) {
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("status", s.get("revision_status"));
            rev.put("amount", s.get("revised_price"));
            rev.put("reason", s.get("revision_reason"));
            rev.put("note", s.get("revision_pickup_note"));
            rev.put("shopNote", s.get("revision_shop_note"));
            rev.put("customerNote", s.get("revision_note"));
            out.put("revision", rev);
        } else {
            out.put("revision", null);
        }
        if (s.get("payout_recorded_at") != null) {
            Map<String, Object> pay = new LinkedHashMap<>();
            pay.put("method", s.get("payout_method"));
            pay.put("reference", s.get("payout_reference"));
            pay.put("amount", s.get("payout_amount"));
            pay.put("proofUrl", s.get("payout_proof_url"));
            pay.put("recordedAt", iso(s.get("payout_recorded_at")));
            out.put("payment", pay);
        } else {
            out.put("payment", null);
        }
        List<Map<String, Object>> events = new ArrayList<>();
        event(events, "PICKUP_PERSON_ASSIGNED", s.get("pickup_assigned_at"), "Assigned by the shop");
        event(events, "PICKUP_ACCEPTED", s.get("pickup_accepted_at"), "Accepted");
        event(events, "PICKUP_ON_THE_WAY", s.get("pickup_started_at"), "On the way");
        event(events, "ARRIVED", s.get("pickup_arrived_at"), "Arrived at the customer");
        event(events, "OTP_VERIFIED", s.get("pickup_otp_verified_at"), "Customer PIN verified");
        event(events, "INSPECTION_STARTED", s.get("pickup_inspection_started_at"), "Inspection started");
        event(events, "INSPECTED", s.get("pickup_inspected_at"), "Inspection submitted");
        event(events, "PAID", s.get("payout_recorded_at"), "Customer paid");
        event(events, "DEVICE_PICKED_UP", s.get("picked_up_at"), "Device collected");
        event(events, "RECEIVED_AT_SHOP", s.get("handed_over_at") != null ? s.get("handed_over_at") : s.get("received_at"), "Handed over to the shop");
        out.put("events", events);
        out.put("createdAt", iso(s.get("pickup_assigned_at")));
        out.put("updatedAt", iso(s.get("updated_at")));
        return out;
    }

    private static void event(List<Map<String, Object>> events, String status, Object at, String note) {
        if (at == null) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("status", status);
        e.put("note", note);
        e.put("createdAt", iso(at));
        events.add(e);
    }

    private List<Map<String, Object>> answers(String sql, UUID id) {
        try { return jdbc.queryForList(sql, id); } catch (Exception e) { return List.of(); } // read-only, outside a transaction
    }

    private Map<String, Object> readJson(Object json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json.toString(), new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return null; }
    }

    private void unassign(UUID id, String issue) {
        jdbc.update("UPDATE sell_orders SET assigned_pickup_person_id = NULL, pickup_person_name = NULL, pickup_person_phone = NULL, "
                + "pickup_assigned_at = NULL, pickup_accepted_at = NULL, pickup_started_at = NULL, pickup_otp = NULL, pickup_otp_sent_at = NULL, "
                + "pickup_otp_verified_at = NULL, pickup_otp_attempts = 0, pickup_arrived_at = NULL, pickup_inspection_started_at = NULL, "
                + "pickup_inspection_json = NULL, pickup_inspected_at = NULL, "
                + "revised_price = CASE WHEN revision_status IN ('SHOP_PENDING', 'SHOP_APPROVED', 'SHOP_REJECTED', 'SHOP_CONFIRMED', 'REINSPECT', 'REJECTED', 'NEGOTIATION') THEN NULL ELSE revised_price END, "
                + "revision_reason = CASE WHEN revision_status IN ('SHOP_PENDING', 'SHOP_APPROVED', 'SHOP_REJECTED', 'SHOP_CONFIRMED', 'REINSPECT', 'REJECTED', 'NEGOTIATION') THEN NULL ELSE revision_reason END, "
                + "revision_status = CASE WHEN revision_status IN ('SHOP_PENDING', 'SHOP_APPROVED', 'SHOP_REJECTED', 'SHOP_CONFIRMED', 'REINSPECT', 'REJECTED', 'NEGOTIATION') THEN NULL ELSE revision_status END, "
                + "pickup_issue = ?, "
                + "status = CASE WHEN pickup_date IS NULL THEN 'SELL_CONFIRMED' ELSE 'PICKUP_SCHEDULED' END, updated_at = now() WHERE id = ?",
                issue.length() > 500 ? issue.substring(0, 500) : issue, id);
    }

    /** The order locked, or null when it isn't assigned to this pickup person any more. */
    private Map<String, Object> lockMine(Technician me, UUID id) {
        if (!hasTable()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sell_orders WHERE id = ? AND shop_id = ? FOR UPDATE", id, me.getShopId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup not found");
        Map<String, Object> s = rows.get(0);
        return me.getId().equals(s.get("assigned_pickup_person_id")) ? s : null;
    }

    private Map<String, Object> reload(UUID id) {
        return jdbc.queryForList(SELECT + "WHERE s.id = ?", id).get(0);
    }

    private void notifyCustomer(Map<String, Object> s, String key, String title, String body) {
        jdbc.update("INSERT INTO customer_notifications (id, customer_user_id, booking_id, booking_number, status_key, title, body, type, is_read, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'orders', false, now())",
                UUID.randomUUID(), s.get("customer_user_id"), s.get("id"), s.get("sell_number"), key, title, body);
    }

    /**
     * Sell tab (type purchase). Only the allowlisted steps — pickup failed / declined,
     * inspection submitted, final price accepted / declined, customer paid — create a
     * row and a push; routine progress stays in the sell order's history. Written after
     * the step commits; each call is a distinct step under the order's lock (no event key:
     * a pickup can legitimately fail or be declined again after a reassignment).
     */
    private void notifyShop(Map<String, Object> s, String key, String title, String body) {
        Object shop = s.get("shop_id");
        if (shop == null) return;
        Object id = s.get("id");
        partnerNotifications.notify(com.repairshop.saas.common.notify.PartnerNotifications.Event
                .of(UUID.fromString(String.valueOf(shop)), "purchase", key, title).body(body)
                .booking(id == null ? null : UUID.fromString(String.valueOf(id)),
                        s.get("sell_number") == null ? null : String.valueOf(s.get("sell_number")))
                .kind("SELL_PURCHASE"));
    }

    private Technician me(HttpServletRequest req) {
        Object shop = req.getAttribute("shopId");
        Object user = req.getAttribute("userId");
        if (shop == null || user == null) return null;
        return technicianRepository.findByShopIdAndUserId(UUID.fromString(shop.toString()), UUID.fromString(user.toString())).orElse(null);
    }

    private Technician requireMe(HttpServletRequest req) {
        Technician me = me(req);
        if (me == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop's pickup staff can do this");
        return me;
    }

    /** Checked first, not caught: a failed statement would abort the surrounding transaction. */
    private boolean hasTable() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'sell_orders' AND column_name = 'assigned_pickup_person_id')", Boolean.class));
    }

    /** 1 hour before the scheduled pickup time (IST); null when no time is scheduled. */
    private static Instant startAllowedAt(Map<String, Object> s) {
        if (!(s.get("pickup_date") instanceof Date d) || !(s.get("pickup_slot_start") instanceof Time t)) return null;
        return LocalDateTime.of(d.toLocalDate(), t.toLocalTime()).atZone(IST).minusHours(1).toInstant();
    }

    private static String newPin() {
        return String.format("%04d", RANDOM.nextInt(10000));
    }

    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    private static ResponseEntity<Map<String, Object>> stale() {
        return refuse(HttpStatus.CONFLICT, "STALE_STATUS", "This pickup was already updated.");
    }

    private static ResponseEntity<Map<String, Object>> notMine() {
        return refuse(HttpStatus.CONFLICT, "NOT_ASSIGNED", "This pickup is no longer assigned to you.");
    }

    private static ResponseEntity<Map<String, Object>> otpRequired() {
        return refuse(HttpStatus.CONFLICT, "OTP_REQUIRED", "Verify the customer's pickup PIN first.");
    }

    private static String rupees(Object v) {
        return v instanceof BigDecimal b ? "₹" + b.stripTrailingZeros().toPlainString() : v == null ? "—" : "₹" + v;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String joinNote(String a, String b) {
        String s = String.join(" — ", Arrays.stream(new String[] { a, b }).filter(v -> v != null && !v.isBlank()).map(String::trim).toList());
        return s.isBlank() ? null : (s.length() > 400 ? s.substring(0, 400) : s);
    }

    private static Instant instant(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private static String iso(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant().toString();
        if (o instanceof OffsetDateTime odt) return odt.toInstant().toString();
        return o.toString();
    }
}
