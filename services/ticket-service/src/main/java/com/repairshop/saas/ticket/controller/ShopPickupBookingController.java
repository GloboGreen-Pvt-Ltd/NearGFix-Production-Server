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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
    private final com.repairshop.saas.ticket.security.TpinGuard tpinGuard;

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
                        "SELECT status, shop_id, booking_number, customer_user_id, ticket_id " +
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
            if ("RECEIVED_AT_SHOP".equalsIgnoreCase(currentStatus)) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("id", id.toString());
                ok.put("status", "RECEIVED_AT_SHOP");
                ok.put("previousStatus", currentStatus);
                if (existingTicketId != null) ok.put("ticketId", existingTicketId);
                ok.put("message", "Device already received at shop.");
                return ResponseEntity.ok(ok);
            }
            if (!"REACHED_SHOP".equalsIgnoreCase(currentStatus)) {
                log.warn("receive-at-shop: bad state booking={} current={}", id, currentStatus);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "device must be Reached Shop before it can be Received at Shop",
                        "currentStatus", currentStatus == null ? "" : currentStatus));
            }

            String staffName = PickupBookingController.firstNonBlank(
                    PickupBookingController.value(body, "receivedByName"),
                    PickupBookingController.value(body, "receivedByUserName"),
                    pickupBookingController.lookupUserDisplayName(userId));
            // TPIN-verified receiver (owner / shop / same-shop employee) wins over any
            // client-supplied name: id + "Name (Role)" come from the consumed authorization.
            var approver = tpinGuard.approver(request);
            String approverLabel = tpinGuard.approverLabel(request);
            if (approver != null && approver.userId() != null) {
                userId = approver.userId();
                staffName = approverLabel != null ? approverLabel : approver.name();
            }

            log.info("receive-at-shop: BEFORE UPDATE booking={} shop={} staff={} name={}",
                    id, shopId, userId, staffName);
            try {
                int updated = jdbc.update(
                        "UPDATE repair_bookings " +
                                "SET status = 'RECEIVED_AT_SHOP', " +
                                "    received_at_shop_at = now(), " +
                                "    received_by_user_id = CAST(? AS UUID), " +
                                "    received_by_user_name = COALESCE(?, received_by_user_name), " +
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

            try {
                pickupBookingController.appendEvent(id, "RECEIVED_AT_SHOP",
                        approver != null ? "Received by " + staffName + " · TPIN verified"
                                : staffName != null ? "Received by " + staffName : "Device received at shop",
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

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Pickup booking received at shop successfully");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("receive-at-shop: UNHANDLED for booking={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? "internal error" : e.getMessage()));
        }
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
