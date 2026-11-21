package com.repairshop.saas.ticket.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.repairshop.saas.ticket.security.TpinGuard;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shop-owner / shop-staff endpoints for the pickup booking workflow. Lives
 * in its own controller so it can register under the bare /shop/...
 * path — the sibling {@link PickupBookingController} is rooted at
 * /technicians/... via a class-level @RequestMapping, so anything added
 * there gets that prefix and would not match the mobile client's URL.
 *
 * The actual database work (status update, event append, ticket mint,
 * customer_orders mirror, user-name lookup) is delegated back to
 * PickupBookingController via its package-private helpers so the two
 * controllers don't drift on the hand-off semantics.
 */
@RestController
@RequestMapping("/shop/pickup-bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Shop Pickup Bookings", description = "Shop-side actions on pickup bookings")
@SecurityRequirement(name = "Bearer")
public class ShopPickupBookingController {

    private final PickupBookingController pickupBookingController;
    private final JdbcTemplate jdbc;
    private final TpinGuard tpinGuard;

    /**
     * Shop staff confirms the device has been physically handed over by
     * the pickup person at the shop counter. This is the second tap in the
     * Reached-Shop → Received-at-Shop pair — the pickup person only proves
     * they arrived (GPS-gated); the shop staff is the actor who actually
     * takes possession. Effects (each idempotent):
     *  - repair_bookings.status → 'RECEIVED_AT_SHOP'
     *  - repair_bookings.received_at_shop_at, received_by_user_id /
     *    _name = caller's user (auditable hand-off)
     *  - event row with actor = 'SHOP_STAFF'
     *  - mints the tickets row (if not already minted) so the booking
     *    shows up in the shop owner's Bookings History and is eligible
     *    for technician assignment
     *  - mirrors customer_orders.status so the customer's Pickup tab
     *    stops reading "PENDING"
     *  - drops a customer notification
     */
    @PostMapping("/{id}/receive-at-shop")
    @Transactional
    @Operation(summary = "Shop staff confirms a reached-shop pickup booking — device on bench")
    public ResponseEntity<Map<String, Object>> receiveAtShop(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            log.info("receive-at-shop: ENTER booking={}", id);
            UUID shopId = readUuidAttr(request, "shopId");
            UUID userId = readUuidAttr(request, "userId");
            if (shopId == null || userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
            }

            Map<String, Object> current;
            try {
                current = jdbc.queryForMap(
                        "SELECT status, shop_id, booking_number, customer_user_id, ticket_id, handover_requested_at, assigned_pickup_person_id " +
                                "FROM repair_bookings WHERE id = CAST(? AS UUID)",
                        id.toString());
            } catch (Exception e) {
                log.warn("receive-at-shop: booking lookup failed for {}: {}", id, e.getMessage());
                return ResponseEntity.status(404).body(Map.of("error", "booking not found"));
            }

            String currentStatus       = PickupBookingController.stringFrom(current, "status");
            String bookingShopStr      = PickupBookingController.stringFrom(current, "shop_id");
            String bookingNumber       = PickupBookingController.stringFrom(current, "booking_number");
            String customerUserStr     = PickupBookingController.stringFrom(current, "customer_user_id");
            String existingTicketId    = PickupBookingController.stringFrom(current, "ticket_id");

            if (bookingShopStr == null || !shopId.toString().equalsIgnoreCase(bookingShopStr)) {
                log.warn("receive-at-shop: shop mismatch booking={} bookingShop={} caller={}",
                        id, bookingShopStr, shopId);
                return ResponseEntity.status(403).body(Map.of("error", "not your shop's booking"));
            }

            // Idempotency: re-tap after the hand-off completed returns the
            // existing ticket without re-firing the event chain.
            // Duplicate confirmation is rejected (the handover is already audited).
            if ("RECEIVED_AT_SHOP".equalsIgnoreCase(currentStatus)) {
                Map<String, Object> dup = new LinkedHashMap<>(pickupBookingController.handoverState(id));
                dup.put("error", "Handover already confirmed.");
                dup.put("code", "ALREADY_CONFIRMED");
                return ResponseEntity.status(409).body(dup);
            }
            if (!"REACHED_SHOP".equalsIgnoreCase(currentStatus)) {
                log.warn("receive-at-shop: bad state booking={} current={}", id, currentStatus);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "device must be Reached Shop before it can be Received at Shop",
                        "currentStatus", currentStatus == null ? "" : currentStatus));
            }

            if (current.get("handover_requested_at") == null) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "The pickup person has not requested the shop handover yet.",
                        "code", "HANDOVER_NOT_REQUESTED"));
            }

            // Mandatory TPIN (even while TPIN_ENFORCED is off): the authorization from
            // /auth/security/tpin/authorize (auto-identifies Owner / Shop / same-shop
            // Employee) is consumed by TpinInterceptor before this handler runs.
            UUID authId = request.getAttribute(TpinGuard.APPROVAL_ATTR) instanceof UUID u ? u : null;
            if (authId == null) {
                return ResponseEntity.status(403).body(Map.of("error", "TPIN confirmation is required.", "code", "TPIN_REQUIRED"));
            }
            Map<String, Object> actor = jdbc.queryForMap(
                    "SELECT actor_type, actor_id, shop_id FROM auth_transaction_authorizations WHERE id = ?", authId);
            String actorType = PickupBookingController.stringFrom(actor, "actor_type");
            UUID actorId = UUID.fromString(PickupBookingController.stringFrom(actor, "actor_id"));
            if (!bookingShopStr.equalsIgnoreCase(PickupBookingController.stringFrom(actor, "shop_id"))) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "wrong shop");
            }
            String role = "EMPLOYEE".equals(actorType) ? "EMPLOYEE" : "SHOP_MOBILE".equals(actorType) ? "SHOP" : "OWNER";
            boolean allowed = "EMPLOYEE".equals(role)
                    ? Boolean.TRUE.equals(jdbc.query("SELECT is_active FROM users WHERE id = ? AND shop_id = CAST(? AS UUID)",
                            rs -> rs.next() ? rs.getBoolean(1) : null, actorId, bookingShopStr))
                    // Owner / Shop login: auth-service only issues these for this session's shop.
                    : "SHOP_OWNER".equals(actorType) || "SHOP_MOBILE".equals(actorType);
            if (!allowed) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "not authorized for this shop");
            // The pickup person can never confirm their own handover.
            String pickupTech = PickupBookingController.stringFrom(current, "assigned_pickup_person_id");
            if ("EMPLOYEE".equals(role) && pickupTech != null && actorId.toString().equalsIgnoreCase(jdbc.query(
                    "SELECT CAST(user_id AS VARCHAR) FROM technicians WHERE id = CAST(? AS UUID)",
                    rs -> rs.next() ? rs.getString(1) : null, pickupTech))) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "The pickup person cannot confirm their own handover.");
            }
            String staffName = PickupBookingController.firstNonBlank(
                    pickupBookingController.lookupUserDisplayName(actorId), "SHOP".equals(role) ? "Shop" : null);
            userId = actorId; // the verified TPIN owner is the receiver of record

            log.info("receive-at-shop: BEFORE UPDATE booking={} shop={} staff={} name={}",
                    id, shopId, userId, staffName);
            try {
                int updated = jdbc.update(
                        "UPDATE repair_bookings " +
                                "SET status = 'RECEIVED_AT_SHOP', " +
                                "    received_at_shop_at = now(), " +
                                "    received_by_user_id = CAST(? AS UUID), " +
                                "    received_by_user_name = COALESCE(?, received_by_user_name), " +
                                "    handover_verified_role = '" + role + "', " +
                                "    updated_at = now() " +
                                "WHERE id = CAST(? AS UUID) AND status = 'REACHED_SHOP'",
                        userId.toString(), staffName, id.toString());
                if (updated == 0) {
                    // Concurrent state change — somebody else moved the
                    // booking between our SELECT and our UPDATE. Bail
                    // cleanly rather than half-applying the hand-off.
                    return ResponseEntity.status(409).body(Map.of(
                            "error", "booking status changed during update — retry",
                            "currentStatus", currentStatus));
                }
            } catch (Exception e) {
                log.error("receive-at-shop: UPDATE failed for {}: {}", id, e.getMessage(), e);
                return ResponseEntity.status(500).body(Map.of(
                        "error", "update failed",
                        "detail", e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            log.info("receive-at-shop: AFTER UPDATE booking={} -> RECEIVED_AT_SHOP", id);
            // Audit history (booking_id UNIQUE → a second confirmation can never be recorded).
            jdbc.update("INSERT INTO pickup_handover_audit (id, booking_id, booking_number, shop_id, pickup_employee_id, " +
                            "verified_user_id, verified_user_name, verified_role, tpin_authorization_id, confirmed_at) " +
                            "VALUES (?, ?, ?, CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, now())",
                    UUID.randomUUID(), id, bookingNumber, bookingShopStr,
                    PickupBookingController.stringFrom(current, "assigned_pickup_person_id"), actorId, staffName, role, authId);

            try {
                pickupBookingController.appendEvent(id, "RECEIVED_AT_SHOP",
                        "Handover confirmed by " + (staffName != null ? staffName : "shop") + " (" + role + ", TPIN)",
                        "SHOP_STAFF");
            } catch (Exception e) {
                log.warn("receive-at-shop: event insert failed for {}: {}", id, e.getMessage());
            }

            String mintedTicketId = existingTicketId;
            if (mintedTicketId == null || mintedTicketId.isBlank()) {
                try {
                    mintedTicketId = pickupBookingController.mintTicketFromBooking(id, shopId, bookingNumber);
                } catch (Exception e) {
                    log.warn("receive-at-shop: ticket mint failed for booking={}: {}", id, e.getMessage(), e);
                }
            }

            pickupBookingController.mirrorCustomerOrderStatus(bookingNumber, "RECEIVED_AT_SHOP");

            if (customerUserStr != null) {
                try {
                    UUID notificationId = UUID.randomUUID();
                    String notifBody = "Booking " + bookingNumber + " — device is on the bench at the shop.";
                    jdbc.update(
                            "INSERT INTO customer_notifications " +
                                    "(id, customer_user_id, booking_id, booking_number, status_key, title, body, type, is_read, created_at) " +
                                    "VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, 'orders', false, now())",
                            notificationId.toString(),
                            customerUserStr,
                            id.toString(),
                            bookingNumber,
                            "RECEIVED_AT_SHOP",
                            "Device received at shop",
                            notifBody);
                    pickupBookingController.pushToCustomer(customerUserStr, notificationId, id, bookingNumber,
                            mintedTicketId, "RECEIVED_AT_SHOP", "Device received at shop", notifBody);
                } catch (Exception e) {
                    log.warn("receive-at-shop: notification insert failed for {}: {}", id, e.getMessage());
                }
            }

            log.info("receive-at-shop: DONE shop={} staff={} booking={} ticket={}",
                    shopId, userId, id, mintedTicketId);

            // Response shape matches the spec the user requested.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pickupBookingId", id.toString());
            data.put("status", "RECEIVED_AT_SHOP");
            data.put("previousStatus", currentStatus);
            data.put("trackingId", bookingNumber);
            data.put("repairBookingId", id.toString());
            if (mintedTicketId != null) data.put("ticketId", mintedTicketId);
            if (staffName != null) data.put("receivedByName", staffName);
            data.put("verifiedByName", staffName);
            data.put("verifiedRole", role);
            data.put("confirmedAt", java.time.Instant.now().toString());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Pickup booking received at shop successfully");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (ResponseStatusException e) {
            throw e; // roll back (TPIN consumption included); status + reason reach the client
        } catch (Exception e) {
            log.error("receive-at-shop: UNHANDLED for booking={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? "internal error" : e.getMessage()));
        }
    }

    /**
     * Shop receiver tapped "Confirm Device Received" (TPIN sheet opens next):
     * the pickup person's app shows "Shop Confirmation in Progress". No state change.
     */
    @PostMapping("/{id}/handover-start")
    @Operation(summary = "Shop started the device-received confirmation (before TPIN)")
    public ResponseEntity<Map<String, Object>> handoverStart(HttpServletRequest request, @PathVariable UUID id) {
        UUID shopId = readUuidAttr(request, "shopId");
        if (shopId == null) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        int n = jdbc.update("UPDATE repair_bookings SET handover_started_at = now() WHERE id = CAST(? AS UUID) " +
                "AND shop_id = CAST(? AS UUID) AND status = 'REACHED_SHOP' AND handover_requested_at IS NOT NULL",
                id.toString(), shopId.toString());
        if (n == 0) return ResponseEntity.status(409).body(Map.of(
                "error", "No pending handover request for this booking.", "code", "HANDOVER_NOT_PENDING"));
        return ResponseEntity.ok(pickupBookingController.handoverState(id));
    }

    /** Partner App: devices waiting for this shop's TPIN handover confirmation. */
    @GetMapping("/handover-pending")
    @Operation(summary = "Pickup bookings waiting for shop handover confirmation")
    public ResponseEntity<Object> handoverPending(HttpServletRequest request) {
        UUID shopId = readUuidAttr(request, "shopId");
        if (shopId == null) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rb.id, rb.booking_number, rb.handover_requested_at, rb.pickup_person_name, rb.color, rb.customer_name, " +
                        "mb.name AS brand_name, mm.name AS model_name, s.name AS shop_name " +
                        "FROM repair_bookings rb " +
                        "LEFT JOIN master_models mm ON mm.id = rb.model_id " +
                        "LEFT JOIN master_brands mb ON mb.id = rb.brand_id " +
                        "LEFT JOIN shops s ON s.id = rb.shop_id " +
                        "WHERE rb.shop_id = CAST(? AS UUID) AND rb.status = 'REACHED_SHOP' AND rb.handover_requested_at IS NOT NULL " +
                        "ORDER BY rb.handover_requested_at DESC", shopId.toString());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bookingId", PickupBookingController.stringFrom(r, "id"));
            m.put("bookingNumber", PickupBookingController.stringFrom(r, "booking_number"));
            String brand = PickupBookingController.stringFrom(r, "brand_name");
            String model = PickupBookingController.stringFrom(r, "model_name");
            m.put("device", PickupBookingController.firstNonBlank(
                    ((brand == null ? "" : brand + " ") + (model == null ? "" : model)).trim(), "Device"));
            m.put("color", PickupBookingController.stringFrom(r, "color"));
            m.put("pickupPersonName", PickupBookingController.stringFrom(r, "pickup_person_name"));
            m.put("customerName", PickupBookingController.stringFrom(r, "customer_name"));
            m.put("shopName", PickupBookingController.stringFrom(r, "shop_name"));
            m.put("requestedAt", PickupBookingController.iso(r.get("handover_requested_at")));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    private static UUID readUuidAttr(HttpServletRequest request, String name) {
        Object raw = request.getAttribute(name);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
