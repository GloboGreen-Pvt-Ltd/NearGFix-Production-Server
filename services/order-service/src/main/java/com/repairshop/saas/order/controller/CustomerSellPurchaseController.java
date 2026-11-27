package com.repairshop.saas.order.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.order.entity.SellOrder;
import com.repairshop.saas.order.exception.ConflictException;
import com.repairshop.saas.order.exception.ForbiddenException;
import com.repairshop.saas.order.exception.ResourceNotFoundException;
import com.repairshop.saas.order.repository.CustomerOrderRepository;
import com.repairshop.saas.order.repository.SellOrderRepository;
import com.repairshop.saas.common.notify.PartnerNotifications;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * The customer's side of a shop purchase (see {@link ShopPurchaseController}):
 * progress after a quote is accepted, approving or rejecting a revised price
 * (after the shop's inspection, or the final offer the owner approved after the
 * pickup person's doorstep inspection — status OUT_FOR_PICKUP, revision
 * SHOP_APPROVED), asking to negotiate it, the pickup PIN, and confirming (or
 * disputing) the shop's payout. Payment counts as done only when the customer
 * confirms it here.
 */
@RestController
@RequestMapping("/sell-orders")
@RequiredArgsConstructor
public class CustomerSellPurchaseController {

    private final JdbcTemplate jdbc;
    private final SellOrderRepository sellRepo;
    private final CustomerOrderRepository customerOrderRepo;
    private final PartnerNotifications partnerNotifications;
    private final ObjectMapper objectMapper;

    public record NoteRequest(String note) {}
    public record NegotiateRequest(String note, BigDecimal amount) {}

    @GetMapping("/{id}/purchase")
    public ResponseEntity<Map<String, Object>> view(HttpServletRequest req, @PathVariable UUID id) {
        owned(req, id);
        return ResponseEntity.ok(view(id));
    }

    @PostMapping("/{id}/revision/approve")
    @Transactional
    public ResponseEntity<Map<String, Object>> approveRevision(HttpServletRequest req, @PathVariable UUID id) {
        SellOrder s = lock(req, id);
        Map<String, Object> w = jdbc.queryForMap("SELECT * FROM sell_orders WHERE id = ?", id);
        if ("APPROVED".equals(w.get("revision_status")) && !"PRICE_REVISION_PENDING".equals(s.getStatus())) return ResponseEntity.ok(view(id));
        BigDecimal price = (BigDecimal) w.get("revised_price");
        if (doorstepOffer(s, w)) {
            // The final offer after the doorstep inspection: the pickup person pays this amount next.
            jdbc.update("UPDATE sell_orders SET final_price = revised_price, revision_status = 'APPROVED', updated_at = now() WHERE id = ?", id);
            customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
                co.setTotalAmount(price);
                customerOrderRepo.save(co);
            });
            notifyShop(s, "SELL_PICKUP_REVISION_AGREED", "Customer accepted the final price",
                    String.format("The customer accepted %s for %s. The pickup person can pay now.", ShopPurchaseController.rupees(price), s.getSellNumber()));
            return ResponseEntity.ok(view(id));
        }
        if (!"PRICE_REVISION_PENDING".equals(s.getStatus())) throw new ConflictException("There's no revised price waiting for you");
        jdbc.update("UPDATE sell_orders SET final_price = ?, revision_status = 'APPROVED', status = 'PRICE_CONFIRMED', updated_at = now() WHERE id = ?", price, id);
        customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
            co.setTotalAmount(price);
            customerOrderRepo.save(co);
        });
        notifyShop(s, "SELL_REVISION_APPROVED", "Revised price approved",
                String.format("The customer approved %s for %s. Record the payment next.", ShopPurchaseController.rupees(price), s.getSellNumber()));
        return ResponseEntity.ok(view(id));
    }

    @PostMapping("/{id}/revision/reject")
    @Transactional
    public ResponseEntity<Map<String, Object>> rejectRevision(HttpServletRequest req, @PathVariable UUID id, @RequestBody(required = false) NoteRequest body) {
        SellOrder s = lock(req, id);
        if ("REVISION_REJECTED".equals(s.getStatus())) return ResponseEntity.ok(view(id));
        Map<String, Object> w = jdbc.queryForMap("SELECT * FROM sell_orders WHERE id = ?", id);
        String note = trim(body == null ? null : body.note(), 500);
        if ("OUT_FOR_PICKUP".equals(s.getStatus()) && "REJECTED".equals(w.get("revision_status"))) return ResponseEntity.ok(view(id));
        if (doorstepOffer(s, w)) {
            jdbc.update("UPDATE sell_orders SET revision_status = 'REJECTED', revision_note = ?, updated_at = now() WHERE id = ?", note, id);
            notifyShop(s, "SELL_PICKUP_REVISION_DECLINED", "Customer declined the final price",
                    String.format("The customer declined %s for %s%s. Keep the quote, offer another price or cancel.",
                            ShopPurchaseController.rupees((BigDecimal) w.get("revised_price")), s.getSellNumber(), note == null ? "" : " (" + note + ")"));
            return ResponseEntity.ok(view(id));
        }
        if (!"PRICE_REVISION_PENDING".equals(s.getStatus())) throw new ConflictException("There's no revised price waiting for you");
        jdbc.update("UPDATE sell_orders SET revision_status = 'REJECTED', revision_note = ?, status = 'REVISION_REJECTED', updated_at = now() WHERE id = ?", note, id);
        notifyShop(s, "SELL_REVISION_REJECTED", "Revised price rejected",
                String.format("The customer rejected the revised price for %s%s. Keep the original quote, offer another price or cancel and return the device.",
                        s.getSellNumber(), note == null ? "" : " (" + note + ")"));
        return ResponseEntity.ok(view(id));
    }

    /** Asks the shop to reconsider a revised / final price (with an optional counter amount); the shop answers with a new price or the quote. */
    @PostMapping("/{id}/revision/negotiate")
    @Transactional
    public ResponseEntity<Map<String, Object>> negotiateRevision(HttpServletRequest req, @PathVariable UUID id, @RequestBody NegotiateRequest body) {
        SellOrder s = lock(req, id);
        Map<String, Object> w = jdbc.queryForMap("SELECT * FROM sell_orders WHERE id = ?", id);
        if ("NEGOTIATION".equals(w.get("revision_status"))) return ResponseEntity.ok(view(id));
        String note = trim(body == null ? null : body.note(), 500);
        BigDecimal ask = body == null ? null : body.amount();
        if (ask != null && (ask.signum() <= 0 || ask.compareTo(new BigDecimal("10000000")) > 0)) throw new IllegalArgumentException("Enter a valid amount");
        if (note == null && ask == null) throw new IllegalArgumentException("Tell the shop what price you expect");
        String text = ask == null ? note : "Asks " + ShopPurchaseController.rupees(ask) + (note == null ? "" : ": " + note);
        if (doorstepOffer(s, w)) {
            jdbc.update("UPDATE sell_orders SET revision_status = 'NEGOTIATION', revision_note = ?, updated_at = now() WHERE id = ?", text, id);
        } else if ("PRICE_REVISION_PENDING".equals(s.getStatus())) {
            jdbc.update("UPDATE sell_orders SET revision_status = 'NEGOTIATION', revision_note = ?, status = 'REVISION_REJECTED', updated_at = now() WHERE id = ?", text, id);
        } else {
            throw new ConflictException("There's no revised price waiting for you");
        }
        notifyShop(s, "SELL_REVISION_NEGOTIATION", "Customer wants to negotiate",
                String.format("The customer asked to negotiate the price for %s: %s", s.getSellNumber(), text));
        return ResponseEntity.ok(view(id));
    }

    @PostMapping("/{id}/payout/confirm")
    @Transactional
    public ResponseEntity<Map<String, Object>> confirmPayout(HttpServletRequest req, @PathVariable UUID id) {
        SellOrder s = lock(req, id);
        if ("PAID".equals(s.getStatus()) || "COMPLETED".equals(s.getStatus())) return ResponseEntity.ok(view(id));
        if (!"PAYMENT_PENDING".equals(s.getStatus())) throw new ConflictException("There's no payment to confirm yet");
        jdbc.update("UPDATE sell_orders SET payout_confirmed_at = now(), status = 'PAID', updated_at = now() WHERE id = ?", id);
        notifyShop(s, "SELL_PAYOUT_CONFIRMED", "Payment confirmed",
                String.format("The customer confirmed receiving %s for %s. You can complete the purchase.", ShopPurchaseController.rupees(s.getFinalPrice()), s.getSellNumber()));
        return ResponseEntity.ok(view(id));
    }

    @PostMapping("/{id}/payout/dispute")
    @Transactional
    public ResponseEntity<Map<String, Object>> disputePayout(HttpServletRequest req, @PathVariable UUID id, @RequestBody NoteRequest body) {
        SellOrder s = lock(req, id);
        if (!"PAYMENT_PENDING".equals(s.getStatus())) throw new ConflictException("There's no payment to dispute");
        String note = trim(body == null ? null : body.note(), 500);
        if (note == null) throw new IllegalArgumentException("Tell the shop what's wrong with the payment");
        jdbc.update("UPDATE sell_orders SET payout_dispute_note = ?, payout_recorded_at = NULL, status = 'PRICE_CONFIRMED', updated_at = now() WHERE id = ?", note, id);
        notifyShop(s, "SELL_PAYOUT_DISPUTED", "Payment not received",
                String.format("The customer hasn't received the payment for %s: %s", s.getSellNumber(), note));
        return ResponseEntity.ok(view(id));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> view(UUID id) {
        Map<String, Object> w = jdbc.queryForMap("SELECT * FROM sell_orders WHERE id = ?", id);
        String status = String.valueOf(w.get("status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        out.put("finalPrice", w.get("final_price"));
        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("personName", w.get("pickup_person_name"));
        pickup.put("personPhone", w.get("pickup_person_phone"));
        pickup.put("assignedAt", iso(w.get("pickup_assigned_at")));
        pickup.put("acceptedAt", iso(w.get("pickup_accepted_at")));
        pickup.put("startedAt", iso(w.get("pickup_started_at")));
        pickup.put("pickedUpAt", iso(w.get("picked_up_at")));
        pickup.put("receivedAt", iso(w.get("received_at")));
        pickup.put("otpVerifiedAt", iso(w.get("pickup_otp_verified_at")));
        // The pickup PIN: shown only here (never in a notification), until the pickup person verifies it at the door.
        pickup.put("pin", "OUT_FOR_PICKUP".equals(status) && w.get("pickup_otp_verified_at") == null ? w.get("pickup_otp") : null);
        out.put("pickup", pickup);
        Map<String, Object> inspection = null;
        if (w.get("inspection_json") != null) {
            try {
                Map<String, Object> full = objectMapper.readValue(w.get("inspection_json").toString(), new TypeReference<LinkedHashMap<String, Object>>() {});
                inspection = new LinkedHashMap<>();
                inspection.put("condition", full.get("condition"));
                inspection.put("modelVerified", full.get("modelVerified"));
                inspection.put("checks", full.get("checks"));
                inspection.put("photos", full.get("photos"));
                inspection.put("inspectedAt", iso(w.get("inspected_at")));
            } catch (Exception ignored) {
                // unreadable record: shown as not inspected
            }
        }
        out.put("inspection", inspection);
        if (w.get("revision_status") != null) {
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("amount", w.get("revised_price"));
            rev.put("reason", w.get("revision_reason"));
            rev.put("status", w.get("revision_status"));
            rev.put("note", w.get("revision_note"));
            out.put("revision", rev);
        } else {
            out.put("revision", null);
        }
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("method", w.get("payout_method"));
        payment.put("account", w.get("payout_account"));
        payment.put("reference", w.get("payout_reference"));
        payment.put("amount", w.get("payout_amount"));
        payment.put("recordedAt", iso(w.get("payout_recorded_at")));
        payment.put("paidAt", iso(w.get("payout_confirmed_at")));
        payment.put("status", w.get("payout_confirmed_at") != null ? "PAID" : w.get("payout_recorded_at") != null ? "AWAITING_CONFIRMATION" : "PENDING");
        out.put("payment", payment);
        out.put("completedAt", iso(w.get("completed_at")));
        out.put("cancelReason", w.get("cancel_reason"));
        out.put("cancelledBy", w.get("cancelled_by"));
        List<String> actions = new ArrayList<>();
        boolean doorstepOffer = "OUT_FOR_PICKUP".equals(status) && "SHOP_APPROVED".equals(w.get("revision_status")) && w.get("payout_recorded_at") == null;
        if ("PRICE_REVISION_PENDING".equals(status) || doorstepOffer) { actions.add("APPROVE_REVISION"); actions.add("REJECT_REVISION"); actions.add("NEGOTIATE_REVISION"); }
        if ("PAYMENT_PENDING".equals(status)) { actions.add("CONFIRM_PAYOUT"); actions.add("DISPUTE_PAYOUT"); }
        out.put("allowedActions", actions);
        return out;
    }

    /** The owner-approved final offer after the doorstep inspection, waiting for this customer's answer. */
    private static boolean doorstepOffer(SellOrder s, Map<String, Object> w) {
        return "OUT_FOR_PICKUP".equals(s.getStatus()) && "SHOP_APPROVED".equals(w.get("revision_status")) && w.get("payout_recorded_at") == null;
    }

    private SellOrder owned(HttpServletRequest req, UUID id) {
        Object u = req.getAttribute("userId");
        if (u == null) throw new ForbiddenException("Missing userId");
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        if (!s.getCustomerUserId().equals(UUID.fromString(u.toString()))) throw new ForbiddenException("Not your sell order");
        return s;
    }

    private SellOrder lock(HttpServletRequest req, UUID id) {
        jdbc.queryForList("SELECT id FROM sell_orders WHERE id = ? FOR UPDATE", id);
        return owned(req, id);
    }

    private void notifyShop(SellOrder s, String key, String title, String body) {
        if (s.getShopId() == null) return;
        // Feed row + push for allowlisted kinds only (PartnerNotificationPolicy). Each call is
        // a distinct customer decision under the order's row lock, so no event key.
        partnerNotifications.notify(PartnerNotifications.Event.of(s.getShopId(), "purchase", key, title)
                .body(body).booking(s.getId(), s.getSellNumber()).kind("SELL_PURCHASE"));
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String iso(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant().toString();
        if (o instanceof OffsetDateTime odt) return odt.toInstant().toString();
        if (o instanceof Instant i) return i.toString();
        return o.toString();
    }
}
