package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.common.notify.PushApp;
import com.repairshop.saas.common.notify.PushService;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.service.TicketNumberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pickup-person feed for the employee app. Lives in ticket-service (not
 * order-service) because the employee JWT is already accepted here, and the
 * shared Postgres database lets us JDBC-query repair_bookings directly.
 *
 * GET /technicians/me/pickup-bookings   — returns repair_bookings rows currently
 *                                         assigned to the calling user as pickup
 *                                         person, scoped to their shop. Optional
 *                                         ?status= filter (e.g. PICKUP_ASSIGNED).
 */
@RestController
@RequestMapping("/technicians")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Pickup Bookings", description = "Bookings assigned to the calling pickup person")
@SecurityRequirement(name = "Bearer")
public class PickupBookingController {

    private final TechnicianRepository technicianRepository;
    private final JdbcTemplate jdbc;
    private final TicketNumberService ticketNumberService;
    private final PushService pushService;
    /** Partner feed row + push, allowlisted (PartnerNotificationPolicy) and once per event. */
    private final com.repairshop.saas.common.notify.PartnerNotifications partnerNotifications;

    // Diagnostic ping — hit this in the browser to confirm the new controller
    // is loaded into the JVM. If this 200s but /me/pickup-bookings 500s, the
    // problem is in the booking handler. If this also 500s or 404s, the
    // controller class itself isn't being registered.
    @GetMapping("/me/pickup-bookings/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true, "controller", "PickupBookingController", "ts", System.currentTimeMillis());
    }

    @GetMapping("/me/pickup-bookings")
    @Operation(summary = "List repair bookings currently assigned to the calling pickup person")
    public List<Map<String, Object>> listMyPickupBookings(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status) {
        log.info("pickup-bookings: ENTER request={}", request.getRequestURI());
        UUID shopId, userId;
        try {
            shopId = shopIdFrom(request);
            userId = userIdFrom(request);
            log.info("pickup-bookings: jwt resolved shop={} user={}", shopId, userId);
        } catch (Exception e) {
            log.error("pickup-bookings: jwt attr parse failed: {}", e.getMessage(), e);
            return List.of();
        }
        if (shopId == null || userId == null) {
            log.warn("pickup-bookings: missing shop or user context (shop={}, user={})", shopId, userId);
            return List.of();
        }

        Technician me;
        try {
            me = technicianRepository.findByShopIdAndUserId(shopId, userId).orElse(null);
        } catch (Exception e) {
            log.error("pickup-bookings: technician lookup failed shop={} user={}: {}", shopId, userId, e.getMessage(), e);
            return List.of();
        }
        if (me == null) {
            log.warn("pickup-bookings: no technician row for shop={} user={}", shopId, userId);
            return List.of();
        }
        UUID technicianId = me.getId();
        log.info("pickup-bookings: shop={} tech={} status={}", shopId, technicianId, status);

        // Use only repair_bookings columns. customer_name/customer_mobile are
        // denormalized on this table (schema line 511-512). Address text is
        // fetched per-row below with its own try/catch so a join failure on
        // customer_addresses doesn't fail the whole endpoint.
        // Device labels (brand/model/RAM/storage/image) are pulled via LEFT
        // JOINs on the master tables so the pickup-person screens can render
        // a device card without a second round-trip. LEFT JOIN so a missing
        // ram/storage FK doesn't drop the booking from the list.
        StringBuilder sql = new StringBuilder(
                "SELECT rb.id, rb.booking_number, rb.shop_id, rb.customer_user_id, " +
                        "       rb.service_mode, rb.status, rb.issue_summary, rb.color, " +
                        "       rb.estimate_amount, rb.final_amount, " +
                        "       rb.front_image_url, rb.back_image_url, rb.video_url, " +
                        "       rb.pickup_date, rb.pickup_slot_start, rb.pickup_slot_end, " +
                        "       rb.pickup_address_id, " +
                        "       rb.assigned_pickup_person_id, rb.pickup_person_name, rb.pickup_person_phone, " +
                        "       rb.customer_name, rb.customer_mobile, " +
                        "       rb.created_at, rb.updated_at, " +
                        "       rb.brand_id, rb.model_id, rb.ram_option_id, rb.storage_option_id, " +
                        "       mb.name AS brand_name, " +
                        "       mm.name AS model_name, " +
                        "       mm.image_url AS model_image_url, " +
                        "       mm.image_base64 AS model_image_base64, " +
                        "       mr.label AS ram_label, " +
                        "       ms.label AS storage_label " +
                        "FROM repair_bookings rb " +
                        "LEFT JOIN master_brands mb ON mb.id = rb.brand_id " +
                        "LEFT JOIN master_models mm ON mm.id = rb.model_id " +
                        "LEFT JOIN master_ram_options mr ON mr.id = rb.ram_option_id " +
                        "LEFT JOIN master_storage_options ms ON ms.id = rb.storage_option_id " +
                        "WHERE rb.shop_id = CAST(? AS UUID) " +
                        "  AND rb.assigned_pickup_person_id = CAST(? AS UUID)");
        List<Object> args = new ArrayList<>();
        args.add(shopId.toString());
        args.add(technicianId.toString());
        if (status != null && !status.isBlank()) {
            sql.append(" AND UPPER(rb.status) = ?");
            args.add(status.toUpperCase());
        }
        sql.append(" ORDER BY rb.created_at DESC");

        List<Map<String, Object>> bookings;
        try {
            bookings = jdbc.query(sql.toString(), (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                String idStr = rs.getString("id");
                UUID bookingId = UUID.fromString(idStr);
                row.put("id", idStr);
                row.put("bookingNumber", rs.getString("booking_number"));
                row.put("shopId", rs.getString("shop_id"));
                row.put("customerUserId", rs.getString("customer_user_id"));
                row.put("serviceMode", rs.getString("service_mode"));
                row.put("status", rs.getString("status"));
                row.put("issueSummary", rs.getString("issue_summary"));
                row.put("color", rs.getString("color"));
                row.put("estimateAmount", rs.getBigDecimal("estimate_amount"));
                row.put("finalAmount", rs.getBigDecimal("final_amount"));
                row.put("frontImageUrl", rs.getString("front_image_url"));
                row.put("backImageUrl", rs.getString("back_image_url"));
                row.put("videoUrl", rs.getString("video_url"));
                row.put("pickupDate", rs.getString("pickup_date"));
                row.put("pickupSlotStart", rs.getString("pickup_slot_start"));
                row.put("pickupSlotEnd", rs.getString("pickup_slot_end"));
                row.put("pickupAddressId", rs.getString("pickup_address_id"));
                row.put("assignedPickupPersonId", rs.getString("assigned_pickup_person_id"));
                row.put("pickupPersonName", rs.getString("pickup_person_name"));
                row.put("pickupPersonPhone", rs.getString("pickup_person_phone"));
                row.put("customerName", rs.getString("customer_name"));
                row.put("customerMobile", rs.getString("customer_mobile"));
                row.put("brandId", rs.getString("brand_id"));
                row.put("modelId", rs.getString("model_id"));
                row.put("ramOptionId", rs.getString("ram_option_id"));
                row.put("storageOptionId", rs.getString("storage_option_id"));
                row.put("brandName", rs.getString("brand_name"));
                row.put("modelName", rs.getString("model_name"));
                row.put("deviceImageUrl", rs.getString("model_image_url"));
                row.put("modelImageUrl", rs.getString("model_image_url"));
                row.put("deviceImageBase64", rs.getString("model_image_base64"));
                row.put("modelImageBase64", rs.getString("model_image_base64"));
                row.put("ramLabel", rs.getString("ram_label"));
                row.put("storageLabel", rs.getString("storage_label"));
                Timestamp created = rs.getTimestamp("created_at");
                Timestamp updated = rs.getTimestamp("updated_at");
                row.put("createdAt", created != null ? created.toInstant().toString() : null);
                row.put("updatedAt", updated != null ? updated.toInstant().toString() : null);
                // Address + events are best-effort enrichments — never let them
                // 500 the request.
                String addrId = rs.getString("pickup_address_id");
                row.put("pickupAddressText", addrId != null ? loadAddressText(addrId) : null);
                row.put("services", loadServices(bookingId));
                row.put("events", loadEvents(bookingId));
                return row;
            }, args.toArray());
        } catch (Exception e) {
            log.error("pickup-bookings: query failed for shop={} tech={}: {}", shopId, technicianId, e.getMessage(), e);
            return List.of();
        }

        log.info("pickup-bookings: returning {} booking(s) for tech={}", bookings.size(), technicianId);
        return bookings;
    }

    // Canonical pickup-status keys the pickup person can advance through.
    // Ordered — each value must follow the previous one (later index can come
    // from any earlier index when explicitly permitted below).
    //
    // REACHED_CUSTOMER_LOCATION is an OPTIONAL step inserted between
    // PICKUP_ON_THE_WAY and REPAIR_ESTIMATE_PROCESSING. It proves (50m GPS
    // gate against customer_addresses) that the pickup person actually
    // arrived at the customer's doorstep. REPAIR_ESTIMATE_PROCESSING still
    // accepts PICKUP_ON_THE_WAY as a predecessor so legacy in-flight bookings
    // (and pickups where the customer's address has no GPS) don't get
    // wedged.
    //
    // REACHED_SHOP requires a GPS check (pickup person must be within
    // SHOP_RADIUS_METERS of the shop's stored lat/lng). RECEIVED_AT_SHOP is
    // the shop-side hand-off — once it fires the booking belongs to the
    // technician-assignment flow.
    private static final List<String> PICKUP_FLOW = List.of(
            "PICKUP_PERSON_ASSIGNED",
            "PICKUP_ON_THE_WAY",
            "REACHED_CUSTOMER_LOCATION",
            "REPAIR_ESTIMATE_PROCESSING",
            "DEVICE_PICKED_UP",
            "REACHED_SHOP",
            "RECEIVED_AT_SHOP"
    );

    // Maximum acceptable distance between the pickup person's GPS reading
    // and the saved coordinates (shop OR customer pickup address) when they
    // tap a location-gated transition. 50m matches a typical building
    // frontage + GPS accuracy floor.
    private static final double SHOP_RADIUS_METERS = 50.0;
    private static final double CUSTOMER_RADIUS_METERS = 50.0;

    // Aliases for the legacy `PICKUP_ASSIGNED` status column / event key so
    // existing in-flight bookings (which were saved before the rename) still
    // satisfy the "current is PICKUP_PERSON_ASSIGNED" precondition.
    private static boolean isAssigned(String currentStatus) {
        if (currentStatus == null) return false;
        String s = currentStatus.toUpperCase();
        return s.equals("PICKUP_PERSON_ASSIGNED")
                || s.equals("PICKUP_ASSIGNED")
                || s.equals("PICKUP_REASSIGNED");
    }

    // Position of `status` in PICKUP_FLOW after collapsing legacy aliases
    // (PICKUP_ASSIGNED/REASSIGNED → PICKUP_PERSON_ASSIGNED, PICKED_UP →
    // DEVICE_PICKED_UP). Returns -1 for non-pickup statuses (ORDER_PLACED,
    // PICKUP_REQUESTED, PICKUP_ACCEPTED, ORDER_SERVICE_CONFIRMED). Used to
    // reject backward transitions that would otherwise pollute the timeline.
    private static int pickupFlowIndex(String status) {
        if (status == null) return -1;
        String s = status.toUpperCase();
        if (s.equals("PICKUP_ASSIGNED") || s.equals("PICKUP_REASSIGNED")) s = "PICKUP_PERSON_ASSIGNED";
        if (s.equals("PICKED_UP")) s = "DEVICE_PICKED_UP";
        return PICKUP_FLOW.indexOf(s);
    }

    // Mirror of customer_orders.status for the live pickup macro state. The
    // customer's My Orders → Pickup tab filters/displays on this column, so
    // every pickup transition that progresses or terminates the booking
    // updates the mirror.  COMPLETED is reserved for the DELIVERED hand-off
    // that fires on the ticket side (CustomerOrderMirrorService).
    private static String customerOrderMacroFor(String pickupStatus) {
        if (pickupStatus == null) return null;
        return switch (pickupStatus.toUpperCase()) {
            case "PICKUP_PERSON_ASSIGNED", "PICKUP_ASSIGNED", "PICKUP_REASSIGNED",
                 "PICKUP_ON_THE_WAY", "REACHED_CUSTOMER_LOCATION",
                 "REPAIR_ESTIMATE_PROCESSING",
                 "DEVICE_PICKED_UP", "PICKED_UP",
                 "REACHED_SHOP", "RECEIVED_AT_SHOP" -> "IN_PROGRESS";
            case "CANCELLED" -> "CANCELLED";
            default -> null;
        };
    }

    // Package-private so the sibling ShopPickupBookingController can reuse
    // the same mirror logic on the shop-staff hand-off path without
    // duplicating the SQL.
    void mirrorCustomerOrderStatus(String bookingNumber, String pickupStatus) {
        if (bookingNumber == null || pickupStatus == null) return;
        String macro = customerOrderMacroFor(pickupStatus);
        if (macro == null) return;
        try {
            jdbc.update(
                    "UPDATE customer_orders SET status = ?, updated_at = now() WHERE order_number = ?",
                    macro, bookingNumber);
        } catch (Exception e) {
            log.warn("mirrorCustomerOrderStatus: failed for booking={} status={}: {}",
                    bookingNumber, pickupStatus, e.getMessage());
        }
    }

    /**
     * Pickup person advances the pickup status one step at a time.
     * Returns a JSON body with the new status on success, or {"error": "…"}
     * with the actual cause on failure (HTTP code reflects the category).
     */
    // No-op echo endpoint to isolate whether the PATCH route + auth + body
    // parsing work at all. Hit this before the real /status endpoint. If THIS
    // 500s, the problem is in Spring's request handling (CORS / Jackson /
    // filter chain) — not in our DB code.
    @PatchMapping("/me/pickup-bookings/{id}/status/echo")
    public ResponseEntity<Map<String, Object>> echoStatusUpdate(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("bookingId", id.toString());
        resp.put("shopAttr", request.getAttribute("shopId"));
        resp.put("userAttr", request.getAttribute("userId"));
        resp.put("body", body == null ? Map.of() : body);
        return ResponseEntity.ok(resp);
    }

    @PatchMapping("/me/pickup-bookings/{id}/status")
    @Operation(summary = "Advance pickup-status for one of the pickup person's bookings")
    public ResponseEntity<Map<String, Object>> updatePickupStatus(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        // Wrap the entire handler so an unexpected throw surfaces as a 200 with
        // an error message rather than an opaque 500 the client can't decode.
        try {
            UUID shopId = shopIdFrom(request);
            UUID userId = userIdFrom(request);
            log.info("pickup-status: ENTER booking={} shop={} user={}", id, shopId, userId);
            if (shopId == null || userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
            }
            Object targetObj = body == null ? null : body.get("status");
            String targetRaw = targetObj == null ? null : targetObj.toString();
            if (targetRaw == null || targetRaw.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
            }
            String target = canonicalPickupStatus(targetRaw.trim().toUpperCase());
            boolean isCancel = target.equals("CANCELLED");
            if (!isCancel && !PICKUP_FLOW.contains(target)) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid status: " + target));
            }
            // Received at Shop is completed only by the shop's TPIN confirmation
            // (POST /shop/pickup-bookings/{id}/receive-at-shop) — never by the pickup person.
            if (target.equals("RECEIVED_AT_SHOP")) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "Shop confirmation with TPIN is required to complete the handover.",
                        "code", "SHOP_CONFIRMATION_REQUIRED"));
            }

            Technician me = technicianRepository.findByShopIdAndUserId(shopId, userId).orElse(null);
            if (me == null) {
                return ResponseEntity.status(403).body(Map.of("error", "not a technician of this shop"));
            }
            UUID technicianId = me.getId();
            log.info("pickup-status: tech={} target={}", technicianId, target);

            // Load just the few fields we need for authz + transition guard.
            // Also pull the booking's shop_id so the REACHED_SHOP radius check
            // can join to shops.latitude/longitude, and the pickup address's
            // coordinates so the REACHED_CUSTOMER_LOCATION gate has somewhere
            // to read its lat/lng from.
            Map<String, Object> current;
            try {
                current = jdbc.queryForMap(
                        "SELECT rb.status, rb.assigned_pickup_person_id, rb.shop_id, rb.booking_number, " +
                                "rb.customer_user_id, rb.estimate_amount, rb.pickup_address_id, " +
                                "s.latitude AS shop_latitude, s.longitude AS shop_longitude, " +
                                "ca.latitude AS customer_latitude, ca.longitude AS customer_longitude " +
                                "FROM repair_bookings rb " +
                                "LEFT JOIN shops s ON s.id = rb.shop_id " +
                                "LEFT JOIN customer_addresses ca ON ca.id = rb.pickup_address_id " +
                                "WHERE rb.id = CAST(? AS UUID)",
                        id.toString());
            } catch (Exception e) {
                log.warn("pickup-status: booking lookup failed for {}: {}", id, e.getMessage());
                return ResponseEntity.status(404).body(Map.of("error", "booking not found"));
            }
            String currentStatus = stringFrom(current, "status");
            String assignedStr = stringFrom(current, "assigned_pickup_person_id");
            String bookingShopStr = stringFrom(current, "shop_id");
            String bookingNumber = stringFrom(current, "booking_number");
            String customerUserStr = stringFrom(current, "customer_user_id");
            String estimateAmount = stringFrom(current, "estimate_amount");
            log.info("pickup-status: current={} assigned={} bookingShop={}",
                    currentStatus, assignedStr, bookingShopStr);

            if (assignedStr == null || !technicianId.toString().equalsIgnoreCase(assignedStr)) {
                return ResponseEntity.status(403).body(Map.of("error", "not your pickup",
                        "assignedTo", assignedStr, "you", technicianId.toString()));
            }
            if (bookingShopStr == null || !shopId.toString().equalsIgnoreCase(bookingShopStr)) {
                return ResponseEntity.status(403).body(Map.of("error", "wrong shop"));
            }

            // Defence-in-depth backward-transition guard. The per-target
            // checks below already enforce the legal "previous" state for
            // each step, but older bookings polluted the timeline with
            // backward jumps (e.g. REACHED_SHOP → PICKUP_ON_THE_WAY) when
            // pre-guard callers existed. Reject any transition that would
            // move the booking earlier in PICKUP_FLOW so the event log
            // stays monotonic regardless of which client called us.
            int currentIdx = pickupFlowIndex(currentStatus);
            int targetIdx = PICKUP_FLOW.indexOf(target);
            if (!isCancel && currentIdx >= 0 && targetIdx >= 0 && targetIdx < currentIdx) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "cannot move backward from " + currentStatus + " to " + target));
            }

            // Validate transition order.
            if (!isCancel) {
                if (target.equals("PICKUP_ON_THE_WAY") && !isAssigned(currentStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "cannot move to PICKUP_ON_THE_WAY from " + currentStatus));
                }
                if (target.equals("REACHED_CUSTOMER_LOCATION")
                        && !"PICKUP_ON_THE_WAY".equalsIgnoreCase(currentStatus)
                        && !"REACHED_CUSTOMER_LOCATION".equalsIgnoreCase(currentStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "cannot move to REACHED_CUSTOMER_LOCATION from " + currentStatus));
                }
                // REPAIR_ESTIMATE_PROCESSING is reachable from either
                // PICKUP_ON_THE_WAY (pickup person skipped the customer-
                // location confirmation because the address has no GPS) OR
                // REACHED_CUSTOMER_LOCATION (the recommended flow). Either
                // predecessor is allowed so bookings whose customer address
                // never had lat/lng saved don't get wedged at the new step.
                if (target.equals("REPAIR_ESTIMATE_PROCESSING")
                        && !"PICKUP_ON_THE_WAY".equalsIgnoreCase(currentStatus)
                        && !"REACHED_CUSTOMER_LOCATION".equalsIgnoreCase(currentStatus)
                        && !"REPAIR_ESTIMATE_PROCESSING".equalsIgnoreCase(currentStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "cannot move to REPAIR_ESTIMATE_PROCESSING from " + currentStatus));
                }
                if (target.equals("DEVICE_PICKED_UP") && !"REPAIR_ESTIMATE_PROCESSING".equalsIgnoreCase(currentStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "submit repair estimate before marking Device Picked Up"));
                }
                if (target.equals("DEVICE_PICKED_UP") && (estimateAmount == null || estimateAmount.isBlank())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "estimated repair value is required before Device Picked Up"));
                }
                if (target.equals("REACHED_SHOP") && !isDevicePickedUp(currentStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "cannot move to REACHED_SHOP from " + currentStatus));
                }
                if (target.equals("RECEIVED_AT_SHOP") && !"REACHED_SHOP".equalsIgnoreCase(currentStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "cannot move to RECEIVED_AT_SHOP from " + currentStatus));
                }
            }

            // GPS radius gate for REACHED_SHOP: the pickup person must be
            // within SHOP_RADIUS_METERS of the shop's stored lat/lng. We
            // require the client to send {latitude, longitude} on this
            // transition and reject (422) with the actual distance so the UI
            // can tell them how far off they are.
            Double pickupLat = null, pickupLng = null;
            Integer distanceMeters = null;
            if (target.equals("REACHED_SHOP")) {
                pickupLat = parseDouble(value(body, "latitude"));
                pickupLng = parseDouble(value(body, "longitude"));
                if (pickupLat == null || pickupLng == null) {
                    return ResponseEntity.status(422).body(Map.of(
                            "error", "location required",
                            "code", "LOCATION_REQUIRED",
                            "message", "Enable location and try again."));
                }
                Double shopLat = parseDouble(stringFrom(current, "shop_latitude"));
                Double shopLng = parseDouble(stringFrom(current, "shop_longitude"));
                if (shopLat == null || shopLng == null) {
                    return ResponseEntity.status(422).body(Map.of(
                            "error", "shop location not configured",
                            "code", "SHOP_LOCATION_MISSING",
                            "message", "Shop has no saved coordinates. Ask owner to update shop profile."));
                }
                double meters = haversineMeters(pickupLat, pickupLng, shopLat, shopLng);
                distanceMeters = (int) Math.round(meters);
                if (meters > SHOP_RADIUS_METERS) {
                    return ResponseEntity.status(422).body(Map.of(
                            "error", "out of radius",
                            "code", "OUT_OF_RADIUS",
                            "distanceMeters", distanceMeters,
                            "radiusMeters", (int) SHOP_RADIUS_METERS,
                            "message", "You are " + distanceMeters
                                    + "m away. Reach the shop (within " + (int) SHOP_RADIUS_METERS + "m) to continue."));
                }
            }

            // GPS radius gate for REACHED_CUSTOMER_LOCATION: same shape as
            // REACHED_SHOP but against customer_addresses. If the saved
            // address has no coordinates we ALLOW the transition without a
            // gate (per product decision) — the pickup person still has to
            // tap the button, and the event row records whatever lat/lng we
            // got from the device for an audit trail.
            if (target.equals("REACHED_CUSTOMER_LOCATION")) {
                pickupLat = parseDouble(value(body, "latitude"));
                pickupLng = parseDouble(value(body, "longitude"));
                Double custLat = parseDouble(stringFrom(current, "customer_latitude"));
                Double custLng = parseDouble(stringFrom(current, "customer_longitude"));
                boolean haveCustomerCoords = custLat != null && custLng != null;
                if (haveCustomerCoords) {
                    // Customer has GPS on the address: enforce the gate.
                    if (pickupLat == null || pickupLng == null) {
                        return ResponseEntity.status(422).body(Map.of(
                                "error", "location required",
                                "code", "LOCATION_REQUIRED",
                                "message", "Enable location and try again."));
                    }
                    double meters = haversineMeters(pickupLat, pickupLng, custLat, custLng);
                    distanceMeters = (int) Math.round(meters);
                    if (meters > CUSTOMER_RADIUS_METERS) {
                        return ResponseEntity.status(422).body(Map.of(
                                "error", "out of radius",
                                "code", "OUT_OF_RADIUS",
                                "distanceMeters", distanceMeters,
                                "radiusMeters", (int) CUSTOMER_RADIUS_METERS,
                                "message", "You are " + distanceMeters
                                        + "m away. Reach the customer address (within " + (int) CUSTOMER_RADIUS_METERS + "m) to continue."));
                    }
                }
                // If the customer address has no GPS we accept the tap as-is.
                // pickupLat / pickupLng may still be set from the body; they
                // get persisted to the event row below for the audit trail.
            }

            // Update repair_bookings.status (+ the corresponding milestone
            // timestamp). now() works in both Postgres and H2.
            try {
                if (target.equals("REACHED_SHOP")) {
                    jdbc.update(
                            "UPDATE repair_bookings SET status = ?, reached_shop_at = now(), updated_at = now() WHERE id = CAST(? AS UUID)",
                            target, id.toString());
                } else if (target.equals("RECEIVED_AT_SHOP")) {
                    jdbc.update(
                            "UPDATE repair_bookings SET status = ?, received_at_shop_at = now(), updated_at = now() WHERE id = CAST(? AS UUID)",
                            target, id.toString());
                } else if (target.equals("REACHED_CUSTOMER_LOCATION")) {
                    jdbc.update(
                            "UPDATE repair_bookings SET status = ?, reached_customer_at = now(), updated_at = now() WHERE id = CAST(? AS UUID)",
                            target, id.toString());
                } else {
                    jdbc.update(
                            "UPDATE repair_bookings SET status = ?, updated_at = now() WHERE id = CAST(? AS UUID)",
                            target, id.toString());
                }
            } catch (Exception e) {
                log.error("pickup-status: status UPDATE failed for {}: {}", id, e.getMessage(), e);
                return ResponseEntity.status(500).body(Map.of("error", "update failed: " + e.getMessage()));
            }

            // Append event row. For REACHED_SHOP / REACHED_CUSTOMER_LOCATION we
            // also persist the GPS reading and the computed distance so the
            // radius check is auditable. Generate UUID in Java to avoid
            // depending on gen_random_uuid() (which isn't available without
            // pgcrypto in older Postgres versions or in H2 dialects).
            String note = body != null && body.get("note") != null
                    ? String.valueOf(body.get("note"))
                    : labelFor(target);
            UUID eventId = UUID.randomUUID();
            boolean isGpsEvent = (target.equals("REACHED_SHOP") || target.equals("REACHED_CUSTOMER_LOCATION"))
                    && pickupLat != null && pickupLng != null;
            try {
                if (isGpsEvent) {
                    jdbc.update(
                            "INSERT INTO repair_booking_events (id, booking_id, status, note, actor, latitude, longitude, distance_meters, created_at) " +
                                    "VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, now())",
                            eventId.toString(), id.toString(), target, note, "PICKUP_PERSON",
                            pickupLat, pickupLng, distanceMeters);
                } else {
                    jdbc.update(
                            "INSERT INTO repair_booking_events (id, booking_id, status, note, actor, created_at) " +
                                    "VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, now())",
                            eventId.toString(), id.toString(), target, note, "PICKUP_PERSON");
                }
            } catch (Exception e) {
                log.error("pickup-status: event INSERT failed for {}: {}", id, e.getMessage(), e);
                return ResponseEntity.status(500).body(Map.of("error", "event insert failed: " + e.getMessage()));
            }

            // Hand the booking off to the shop's ticket pipeline only when
            // the shop has actually taken the device (RECEIVED_AT_SHOP).
            // REACHED_SHOP just means the pickup person is physically at
            // the shop — the device hasn't been handed over yet, and the
            // shop owner's Bookings History must not jump ahead of that
            // hand-off. Idempotent: re-fires are a no-op once ticket_id is
            // set. Best-effort — failure here logs but does not roll back
            // the status transition the pickup person already completed.
            String mintedTicketId = null;
            if (target.equals("RECEIVED_AT_SHOP")) {
                try {
                    mintedTicketId = mintTicketFromBooking(id, shopId, bookingNumber);
                } catch (Exception e) {
                    log.warn("pickup-status: ticket mint failed for booking={}: {}", id, e.getMessage(), e);
                }
            }

            // Mirror the live macro status into customer_orders so the
            // customer's My Orders → Pickup tab card reflects progress
            // (it was previously stuck at "PENDING" forever because the
            // original /buy / /repair-bookings insert wrote PENDING once
            // and nothing updated it).
            mirrorCustomerOrderStatus(bookingNumber, target);

            // Customer notification — best-effort. Don't fail the request if
            // this throws (e.g. table missing in some dev DB).
            if (customerUserStr != null) {
                try {
                    UUID notificationId = UUID.randomUUID();
                    String notifBody = "Booking " + bookingNumber + " — " + labelFor(target);
                    jdbc.update(
                            "INSERT INTO customer_notifications " +
                                    "(id, customer_user_id, booking_id, booking_number, status_key, title, body, type, is_read, created_at) " +
                                    "VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, 'orders', false, now())",
                            notificationId.toString(),
                            customerUserStr,
                            id.toString(),
                            bookingNumber,
                            target,
                            labelFor(target),
                            notifBody);
                    pushToCustomer(customerUserStr, notificationId, id, bookingNumber, mintedTicketId,
                            target, labelFor(target), notifBody);
                } catch (Exception e) {
                    log.warn("pickup-status: notification INSERT failed for {}: {}", id, e.getMessage());
                }
            }

            // Shop owner — the pickup person moved this booking on: a feed row
            // (Pickup tab) + a push to the Partner App (kind PICKUP), only for the
            // allowlisted steps (Device Picked Up, Pickup Cancelled); the rest stay
            // in the pickup timeline. Once per booking + status. Best-effort.
            try {
                String who = me.getName() == null || me.getName().isBlank() ? "Pickup person" : me.getName().trim();
                String shopTitle = labelFor(target);
                String shopBody = who + " · Booking " + bookingNumber + " — " + labelFor(target);
                UUID ticketUuid = mintedTicketId == null ? null : UUID.fromString(mintedTicketId);
                partnerNotifications.notify(com.repairshop.saas.common.notify.PartnerNotifications.Event
                        .of(shopId, "pickups", target, shopTitle).body(shopBody)
                        .booking(id, bookingNumber).ticket(ticketUuid).kind("PICKUP")
                        .eventKey("pickups:" + target + ":" + id));
            } catch (Exception e) {
                log.warn("pickup-status: shop notification failed for {}: {}", id, e.getMessage());
            }

            log.info("pickup-status: shop={} tech={} booking={} {} -> {} (distance={}m)",
                    shopId, technicianId, id, currentStatus, target, distanceMeters);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("id", id.toString());
            ok.put("status", target);
            ok.put("previousStatus", currentStatus == null ? "" : currentStatus);
            if (distanceMeters != null) ok.put("distanceMeters", distanceMeters);
            if (mintedTicketId != null) ok.put("ticketId", mintedTicketId);
            if (target.equals("REACHED_SHOP") || target.equals("RECEIVED_AT_SHOP")
                    || target.equals("REACHED_CUSTOMER_LOCATION")) {
                if (target.equals("REACHED_SHOP")) {
                    ok.put("message", "Pickup person reached the shop successfully.");
                } else if (target.equals("RECEIVED_AT_SHOP")) {
                    ok.put("message", "Device received at shop.");
                } else {
                    ok.put("message", "Reached customer location.");
                }
            }
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            log.error("pickup-status: UNHANDLED for booking={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "internal: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            ));
        }
    }

    /**
     * Pickup person at the shop (REACHED_SHOP) asks the shop to confirm the
     * device handover. Idempotent: a repeat tap keeps the first request time
     * and only re-notifies the shop (retry after a missed push).
     */
    @PostMapping("/me/pickup-bookings/{id}/handover-request")
    @Operation(summary = "Pickup person requests shop handover confirmation (TPIN on Partner App)")
    public ResponseEntity<Map<String, Object>> requestShopHandover(HttpServletRequest request, @PathVariable UUID id) {
        try {
            BookingAccess access = requireAssignedPickup(request, id);
            String status = stringFrom(access.booking, "status");
            if ("RECEIVED_AT_SHOP".equalsIgnoreCase(status)) return ResponseEntity.ok(handoverState(id));
            if (!"REACHED_SHOP".equalsIgnoreCase(status)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Mark Reached Shop before requesting the shop handover.", "currentStatus", status == null ? "" : status));
            }
            int first = jdbc.update("UPDATE repair_bookings SET handover_requested_at = now(), updated_at = now() " +
                    "WHERE id = CAST(? AS UUID) AND handover_requested_at IS NULL", id.toString());
            String bookingNumber = stringFrom(access.booking, "booking_number");
            if (first > 0) appendEvent(id, "HANDOVER_REQUESTED", "Waiting for shop TPIN confirmation", "PICKUP_PERSON");
            // One notification per handover request: a repeat tap keeps the first
            // request time, so its event key (booking + request time) is unchanged.
            try {
                String who = firstNonBlank(technicianRepository.findById(access.technicianId).map(Technician::getName).orElse(null), "Pickup person");
                Map<String, Object> hb = jdbc.queryForMap(
                        "SELECT rb.handover_requested_at, NULLIF(trim(concat_ws(' ', mb.name, mm.name)), '') AS device " +
                        "FROM repair_bookings rb LEFT JOIN master_models mm ON mm.id = rb.model_id " +
                        "LEFT JOIN master_brands mb ON mb.id = rb.brand_id WHERE rb.id = CAST(? AS UUID)", id.toString());
                Object requestedAt = hb.get("handover_requested_at");
                String device = stringFrom(hb, "device");
                String title = "Shop Handover Confirmation Required";
                String msg = (device != null ? device : "A device") + " from pickup #" + bookingNumber
                        + " is awaiting shop handover confirmation (" + who.trim() + "). Confirm with your TPIN.";
                partnerNotifications.notify(com.repairshop.saas.common.notify.PartnerNotifications.Event
                        .of(access.shopId, "pickups", "HANDOVER_REQUESTED", title).body(msg)
                        .booking(id, bookingNumber).kind("PICKUP")
                        .data("pickupPerson", who.trim())
                        .eventKey("HANDOVER_REQUESTED:" + id + ":" + requestedAt));
            } catch (Exception e) {
                log.warn("handover-request: shop notification failed for {}: {}", id, e.getMessage());
            }
            return ResponseEntity.ok(handoverState(id));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    @GetMapping("/me/pickup-bookings/{id}/handover")
    @Operation(summary = "Shop handover confirmation state for the pickup person")
    public ResponseEntity<Map<String, Object>> getShopHandover(HttpServletRequest request, @PathVariable UUID id) {
        try {
            requireAssignedPickup(request, id);
            return ResponseEntity.ok(handoverState(id));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    static String iso(Object v) {
        if (v instanceof java.sql.Timestamp t) return t.toInstant().toString();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant().toString();
        return v == null ? null : String.valueOf(v);
    }

    /** NOT_REQUESTED | PENDING | IN_PROGRESS | COMPLETED (+ verifier name / role / time once confirmed). */
    Map<String, Object> handoverState(UUID id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT rb.status, rb.handover_requested_at, (rb.handover_started_at > now() - interval '2 minutes') AS shop_verifying, rb.received_at_shop_at, rb.received_by_user_name, rb.handover_verified_role, " +
                        "a.verified_user_name, a.verified_role, a.confirmed_at, s.name AS shop_name " +
                        "FROM repair_bookings rb LEFT JOIN pickup_handover_audit a ON a.booking_id = rb.id " +
                        "LEFT JOIN shops s ON s.id = rb.shop_id WHERE rb.id = CAST(? AS UUID)",
                id.toString());
        String status = stringFrom(row, "status");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingId", id.toString());
        out.put("bookingStatus", status);
        boolean done = "RECEIVED_AT_SHOP".equalsIgnoreCase(status);
        // "In progress" only while the shop's TPIN step can still finish (authorization TTL 2 min);
        // a cancelled / abandoned confirmation falls back to PENDING so the employee can resend.
        boolean started = Boolean.TRUE.equals(row.get("shop_verifying"));
        out.put("state", done ? "COMPLETED" : row.get("handover_requested_at") == null ? "NOT_REQUESTED" : started ? "IN_PROGRESS" : "PENDING");
        out.put("shopName", stringFrom(row, "shop_name"));
        if (row.get("handover_requested_at") != null) out.put("requestedAt", iso(row.get("handover_requested_at")));
        if (done) {
            out.put("verifiedByName", firstNonBlank(stringFrom(row, "verified_user_name"), stringFrom(row, "received_by_user_name")));
            out.put("verifiedRole", firstNonBlank(stringFrom(row, "verified_role"), stringFrom(row, "handover_verified_role")));
            Object at = row.get("confirmed_at") != null ? row.get("confirmed_at") : row.get("received_at_shop_at");
            if (at != null) out.put("confirmedAt", iso(at));
        }
        return out;
    }

    // The shop-staff "Mark Received" endpoint lives in the sibling
    // ShopPickupBookingController. It can't live in this class because the
    // class-level @RequestMapping("/technicians") would prepend /technicians
    // to every route here — the original misplacement (which served the
    // endpoint at /technicians/shop/pickup-bookings/.../receive-at-shop
    // instead of /shop/pickup-bookings/.../receive-at-shop) caused the 404
    // the mobile app reported as a 500.

    // Package-private — see ShopPickupBookingController.
    String lookupUserDisplayName(UUID userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT COALESCE(NULLIF(TRIM(name), ''), NULLIF(TRIM(email), ''), NULLIF(TRIM(phone), '')) " +
                            "FROM users WHERE id = CAST(? AS UUID)",
                    String.class, userId.toString());
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/me/pickup-bookings/{id}/repair-estimate")
    @Operation(summary = "Read repair estimate data for the pickup person's booking")
    public ResponseEntity<Map<String, Object>> getRepairEstimate(
            HttpServletRequest request,
            @PathVariable UUID id) {
        try {
            BookingAccess access = requireAssignedPickup(request, id);
            return ResponseEntity.ok(estimateResponse(id, access.booking));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    @PatchMapping("/me/pickup-bookings/{id}/repair-estimate/images")
    @Transactional
    @Operation(summary = "Store pickup-person device image URLs on the shared booking")
    public ResponseEntity<Map<String, Object>> updateRepairEstimateImages(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            BookingAccess access = requireAssignedPickup(request, id);
            String front = firstNonBlank(value(body, "frontImageUrl"), value(body, "frontImage"), value(body, "front"));
            String back = firstNonBlank(value(body, "backImageUrl"), value(body, "backImage"), value(body, "back"));
            String video = firstNonBlank(value(body, "videoUrl"), value(body, "fullCoverageVideoUrl"), value(body, "video"));
            if (front == null && back == null && video == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "at least one image url is required"));
            }
            jdbc.update(
                    "UPDATE repair_bookings SET " +
                            "front_image_url = COALESCE(?, front_image_url), " +
                            "back_image_url = COALESCE(?, back_image_url), " +
                            "video_url = COALESCE(?, video_url), " +
                            "updated_at = now() WHERE id = CAST(? AS UUID)",
                    front, back, video, id.toString());
            Map<String, Object> refreshed = loadBookingSnapshot(id);
            log.info("pickup-estimate-images: shop={} tech={} booking={}", access.shopId, access.technicianId, id);
            return ResponseEntity.ok(estimateResponse(id, refreshed));
        } catch (ResponseStatusException e) {
            return error(e);
        } catch (Exception e) {
            log.error("pickup-estimate-images: failed for booking={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "image update failed: " + e.getMessage()));
        }
    }

    @PostMapping("/me/pickup-bookings/{id}/repair-estimate")
    @Transactional
    @Operation(summary = "Submit pickup-person repair estimate on the shared booking")
    public ResponseEntity<Map<String, Object>> submitRepairEstimate(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            BookingAccess access = requireAssignedPickup(request, id);
            String currentStatus = stringFrom(access.booking, "status");
            // Allow re-submits any time the pickup person is at or past the
            // PICKUP_ON_THE_WAY step. The Edit Repair Estimate flow on the
            // employee app needs to work even after the device has reached
            // the shop and the ticket has been minted — without that, the
            // pickup person can't correct a typo in the estimate once they
            // tap "Reached Shop". Only CANCELLED and the truly-early states
            // (before the pickup person ever started moving) reject.
            String s = currentStatus == null ? "" : currentStatus.toUpperCase();
            if (s.equals("CANCELLED")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "booking is cancelled — estimate can't be edited"));
            }
            int idx = pickupFlowIndex(s);
            // -1 ⇒ pre-pickup statuses (ORDER_PLACED, PICKUP_REQUESTED,
            // PICKUP_ACCEPTED) or junk. 0 ⇒ PICKUP_PERSON_ASSIGNED — still
            // before the on-the-way step we require here. ≥ 1 is fine.
            if (idx < 1) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "repair estimate can be submitted only after Pickup On The Way"));
            }
            BigDecimal estimate = parseAmount(firstNonBlank(
                    value(body, "estimatedRepairValue"),
                    value(body, "estimateAmount"),
                    value(body, "estimatedAmount"),
                    value(body, "repairEstimatedValue")
            ));
            if (estimate == null || estimate.signum() < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "estimated repair value is required"));
            }
            String front = firstNonBlank(value(body, "frontImageUrl"), value(body, "frontImage"), value(body, "front"));
            String back = firstNonBlank(value(body, "backImageUrl"), value(body, "backImage"), value(body, "back"));
            String video = firstNonBlank(value(body, "videoUrl"), value(body, "fullCoverageVideoUrl"), value(body, "video"));
            String issueSummary = firstNonBlank(value(body, "issueSummary"), value(body, "complaintNotes"), value(body, "note"));

            // Pickup-person-confirmed device taxonomy. The customer's initial
            // booking values are overwritten only when the technician sent a
            // non-blank value — otherwise leave whatever the customer entered.
            UUID brandId = parseUuid(value(body, "brandId"));
            UUID modelId = parseUuid(value(body, "modelId"));
            UUID ramOptionId = parseUuid(value(body, "ramOptionId"));
            UUID storageOptionId = parseUuid(value(body, "storageOptionId"));
            String color = firstNonBlank(value(body, "color"));

            // Service schedule alert + device condition fields the pickup
            // person enters on the Repair Estimate Processing screen. Without
            // these the owner's "View Details" card shows "Not yet set" and
            // "Not provided" even after the pickup person submitted the
            // estimate — the COALESCE keeps customer-entered values when the
            // pickup person leaves a field blank.
            Timestamp estimatedReadyAt = parseInstant(firstNonBlank(
                    value(body, "estimatedReadyAt"),
                    value(body, "estimatedReadyIso"),
                    value(body, "serviceScheduleReadyAt")));
            Timestamp estimatedDeliveryAt = parseInstant(firstNonBlank(
                    value(body, "estimatedDeliveryAt"),
                    value(body, "estimatedDeliveryIso"),
                    value(body, "serviceScheduleDeliveryAt")));
            String devicePin = firstNonBlank(
                    value(body, "devicePin"),
                    value(body, "deviceSecurityValue"),
                    value(body, "securityValue"));
            String missingParts = firstNonBlank(
                    value(body, "missingDamageParts"),
                    value(body, "missingParts"),
                    value(body, "damagedParts"));
            // missingParts may arrive as a JSON array; normalize to the
            // comma-separated text the repair_bookings.missing_damage_parts
            // column already stores, so the existing CSV-aware read path in
            // buildMissingPartsJson() keeps working unchanged.
            if (missingParts == null) {
                Object rawMp = body == null ? null : body.get("missingDamageParts");
                if (rawMp == null && body != null) rawMp = body.get("missingParts");
                if (rawMp instanceof List<?>) {
                    StringBuilder sb = new StringBuilder();
                    for (Object item : (List<?>) rawMp) {
                        if (item == null) continue;
                        String piece;
                        if (item instanceof Map<?, ?>) {
                            Object name = ((Map<?, ?>) item).get("name");
                            if (name == null) name = ((Map<?, ?>) item).get("label");
                            piece = name == null ? null : name.toString();
                        } else {
                            piece = item.toString();
                        }
                        if (piece == null || piece.isBlank()) continue;
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(piece.trim());
                    }
                    if (sb.length() > 0) missingParts = sb.toString();
                }
            }
            String customerApproval = mapBooleanToApprovalString(firstNonBlank(
                    value(body, "customerApproval"),
                    value(body, "customerRepairApproval")));
            String imei = firstNonBlank(value(body, "imei"));

            // Only stamp REPAIR_ESTIMATE_PROCESSING when the booking is still
            // in the on-the-way / estimate-processing phase. Once the device
            // has been picked up / reached / received at shop, the booking is
            // owned by the shop-side ticket pipeline and we must NOT regress
            // its lifecycle to "estimate processing" — that would push the
            // customer Pickup tab back to IN_PROGRESS and pull the booking
            // out of the owner's tickets queue.
            jdbc.update(
                    "UPDATE repair_bookings SET " +
                            "estimate_amount = ?, " +
                            "front_image_url = COALESCE(?, front_image_url), " +
                            "back_image_url = COALESCE(?, back_image_url), " +
                            "video_url = COALESCE(?, video_url), " +
                            "issue_summary = COALESCE(?, issue_summary), " +
                            "brand_id = COALESCE(CAST(? AS UUID), brand_id), " +
                            "model_id = COALESCE(CAST(? AS UUID), model_id), " +
                            "ram_option_id = COALESCE(CAST(? AS UUID), ram_option_id), " +
                            "storage_option_id = COALESCE(CAST(? AS UUID), storage_option_id), " +
                            "color = COALESCE(?, color), " +
                            "estimated_ready_at = COALESCE(?, estimated_ready_at), " +
                            "estimated_delivery_at = COALESCE(?, estimated_delivery_at), " +
                            "device_pin = COALESCE(?, device_pin), " +
                            "missing_damage_parts = COALESCE(?, missing_damage_parts), " +
                            "customer_approval = COALESCE(?, customer_approval), " +
                            "imei = COALESCE(?, imei), " +
                            // Promote the booking to REPAIR_ESTIMATE_PROCESSING when
                            // submission happens from any pre-pickup state. Includes
                            // REACHED_CUSTOMER_LOCATION (new optional step between
                            // PICKUP_ON_THE_WAY and the estimate); without that, the
                            // employee app's nextStatusFor sees the stale status and
                            // keeps offering "Repair Estimate" instead of advancing
                            // to "Device Picked Up".
                            "status = CASE WHEN UPPER(status) IN ('PICKUP_ON_THE_WAY','REACHED_CUSTOMER_LOCATION','REPAIR_ESTIMATE_PROCESSING') " +
                            "              THEN 'REPAIR_ESTIMATE_PROCESSING' ELSE status END, " +
                            "updated_at = now() WHERE id = CAST(? AS UUID)",
                    estimate, front, back, video, issueSummary,
                    brandId != null ? brandId.toString() : null,
                    modelId != null ? modelId.toString() : null,
                    ramOptionId != null ? ramOptionId.toString() : null,
                    storageOptionId != null ? storageOptionId.toString() : null,
                    color,
                    estimatedReadyAt, estimatedDeliveryAt, devicePin, missingParts, customerApproval,
                    imei,
                    id.toString());

            // Replace the booking's repair_booking_services rows with whatever
            // the pickup person picked on the Device Services screen. Doing it
            // as DELETE-then-INSERT keeps the table in lockstep with the
            // submitted estimate (customer may have selected the wrong issues
            // originally; the technician's picks are now the source of truth).
            Object servicesObj = body == null ? null : body.get("services");
            if (servicesObj instanceof List<?>) {
                try {
                    jdbc.update("DELETE FROM repair_booking_services WHERE booking_id = CAST(? AS UUID)", id.toString());
                    for (Object item : (List<?>) servicesObj) {
                        if (!(item instanceof Map<?, ?>)) continue;
                        Map<?, ?> svc = (Map<?, ?>) item;
                        UUID serviceUuid = parseUuid(stringValueOf(svc.get("serviceId")));
                        if (serviceUuid == null) serviceUuid = parseUuid(stringValueOf(svc.get("repairServiceId")));
                        String code = firstNonBlank(stringValueOf(svc.get("serviceCode")), stringValueOf(svc.get("code")));
                        String name = firstNonBlank(stringValueOf(svc.get("serviceName")), stringValueOf(svc.get("name")));
                        BigDecimal price = parseAmount(stringValueOf(svc.get("price")));
                        if (price == null) price = parseAmount(stringValueOf(svc.get("estimatedPrice")));
                        String warranty = firstNonBlank(stringValueOf(svc.get("warranty")));
                        jdbc.update(
                                "INSERT INTO repair_booking_services " +
                                        "(id, booking_id, repair_service_id, service_code, service_name, estimated_price, warranty, created_at) " +
                                        "VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, now())",
                                UUID.randomUUID().toString(),
                                id.toString(),
                                serviceUuid != null ? serviceUuid.toString() : null,
                                code,
                                name,
                                price,
                                warranty);
                    }
                } catch (Exception e) {
                    log.warn("pickup-estimate: services replace failed for {}: {}", id, e.getMessage());
                }
            }

            String bookingNumber = stringFrom(access.booking, "booking_number");
            if (bookingNumber != null) {
                try {
                    jdbc.update("UPDATE customer_orders SET total_amount = ?, updated_at = now() WHERE order_number = ?",
                            estimate, bookingNumber);
                } catch (Exception e) {
                    log.warn("pickup-estimate: customer_orders sync failed for {}: {}", bookingNumber, e.getMessage());
                }
            }

            // If a ticket has already been minted for this booking, re-sync
            // the pickup-editable fields onto it so the owner-side Booking
            // Details / Device Details renders the latest estimate. Without
            // this, the TicketService booking fallback only kicks in for
            // fields that are still blank — any field the original mint
            // populated (e.g. issue_description from the first estimate)
            // would shadow the new value forever.
            syncTicketFromBookingEdit(id);

            String note = "Repair estimate submitted";
            appendEvent(id, "REPAIR_ESTIMATE_PROCESSING", note, "PICKUP_PERSON");
            notifyCustomer(access.booking, id, "REPAIR_ESTIMATE_PROCESSING", "Repair Estimate Processing", note);
            Map<String, Object> refreshed = loadBookingSnapshot(id);
            log.info("pickup-estimate: shop={} tech={} booking={} amount={}",
                    access.shopId, access.technicianId, id, estimate);
            return ResponseEntity.ok(estimateResponse(id, refreshed));
        } catch (ResponseStatusException e) {
            return error(e);
        } catch (Exception e) {
            log.error("pickup-estimate: failed for booking={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "estimate submit failed: " + e.getMessage()));
        }
    }

    // queryForMap returns column-name keys in whatever case the driver hands
    // back (lowercase on Postgres, uppercase on H2). Pull the value tolerantly.
    // Package-private — shared with ShopPickupBookingController.
    static String stringFrom(Map<String, Object> row, String col) {
        if (row == null) return null;
        Object v = row.get(col);
        if (v == null) v = row.get(col.toUpperCase());
        if (v == null) v = row.get(col.toLowerCase());
        return v == null ? null : v.toString();
    }

    private static String labelFor(String code) {
        switch (code) {
            case "PICKUP_REQUESTED":        return "Pickup Requested";
            case "PICKUP_ACCEPTED":         return "Pickup Accepted";
            case "PICKUP_PERSON_ASSIGNED":  return "Pickup Person Assigned";
            case "PICKUP_ON_THE_WAY":       return "Pickup Person On The Way";
            case "REACHED_CUSTOMER_LOCATION": return "Reached Customer Location";
            case "REPAIR_ESTIMATE_PROCESSING": return "Repair Estimate Processing";
            case "DEVICE_PICKED_UP":        return "Device Picked Up";
            case "PICKED_UP":               return "Device Picked Up";
            case "REACHED_SHOP":            return "Reached Shop";
            case "RECEIVED_AT_SHOP":        return "Received at Shop";
            case "ESTIMATE_SENT_TO_CUSTOMER": return "Estimate Sent To Customer";
            case "CUSTOMER_APPROVED":       return "Customer Approved";
            case "REPAIR_IN_PROGRESS":      return "Repair In Progress";
            case "REPAIR_COMPLETED":        return "Repair Completed";
            case "READY_FOR_DELIVERY":      return "Ready For Delivery";
            case "CANCELLED":               return "Pickup Cancelled";
            default:                        return code.replace('_', ' ');
        }
    }

    private static String canonicalPickupStatus(String status) {
        if (status == null) return null;
        String s = status.toUpperCase();
        if (s.equals("PICKED_UP") || s.equals("DEVICE_RECEIVED")) return "DEVICE_PICKED_UP";
        if (s.equals("ESTIMATE_PROCESSING") || s.equals("ESTIMATE_SUBMITTED")) return "REPAIR_ESTIMATE_PROCESSING";
        return s;
    }

    private static boolean isDevicePickedUp(String status) {
        if (status == null) return false;
        String s = status.toUpperCase();
        return s.equals("DEVICE_PICKED_UP") || s.equals("PICKED_UP") || s.equals("DEVICE_RECEIVED");
    }

    // Package-private — shared with ShopPickupBookingController.
    static String value(Map<String, Object> body, String key) {
        if (body == null || key == null) return null;
        Object v = body.get(key);
        if (v == null) v = body.get(key.toUpperCase());
        if (v == null) v = body.get(key.toLowerCase());
        return v == null ? null : v.toString();
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Double.parseDouble(raw.trim()); } catch (Exception e) { return null; }
    }

    // Great-circle distance in metres between two WGS-84 coordinates.
    // Uses the standard haversine formula — accurate to ~0.5% well below the
    // 50m radius threshold the shop-arrival check cares about.
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double EARTH_RADIUS_M = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.replace(",", "").replace("\u20B9", "").trim();
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse an ISO-8601 timestamp the client sends for service-schedule
     * fields (estimated_ready_at / estimated_delivery_at). Accepts both
     * full "2026-06-13T12:01:00Z" and the bare "2026-06-13T12:01:00" forms
     * the mobile app's Date.toISOString() produces. Returns a JDBC-friendly
     * Timestamp so the COALESCE update binds cleanly without a manual cast.
     */
    private static Timestamp parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            // Try ISO instant first (covers "...Z" suffix).
            return Timestamp.from(java.time.Instant.parse(s));
        } catch (Exception ignore) { /* fall through */ }
        try {
            // Naive local-datetime — assume UTC. The mobile app sometimes
            // strips the trailing Z when constructing dates manually.
            return Timestamp.from(java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC));
        } catch (Exception ignore) { /* fall through */ }
        try {
            // OffsetDateTime (with explicit zone offset).
            return Timestamp.from(java.time.OffsetDateTime.parse(s).toInstant());
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Translate the boolean/string the customer-approval submit sends into
     * the "DONE"/"PENDING" VARCHAR the repair_bookings.customer_approval
     * column stores. The shop-side ticket conversion (mapCustomerApproval)
     * round-trips this back to BOOLEAN for the ticket layer, so both
     * representations stay in sync.
     */
    private static String mapBooleanToApprovalString(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if (s.equals("TRUE") || s.equals("YES") || s.equals("DONE") || s.equals("APPROVED") || s.equals("1")) return "DONE";
        if (s.equals("FALSE") || s.equals("NO") || s.equals("PENDING") || s.equals("0")) return "PENDING";
        return null;
    }

    private static String stringValueOf(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    // Marker the employee pickup-estimate flow appends to issueSummary
    // (`<complaint>\n---PICKUP_ESTIMATE_META---{json}`). We strip it before
    // putting the issue on the ticket so the JSON doesn't leak into the
    // owner's Bookings History card or any other ticket-driven UI.
    private static final String PICKUP_META_MARKER = "---PICKUP_ESTIMATE_META---";

    private static String stripPickupMeta(String issueSummary) {
        if (issueSummary == null) return null;
        int idx = issueSummary.indexOf(PICKUP_META_MARKER);
        if (idx == -1) return issueSummary.trim();
        return issueSummary.substring(0, idx).replaceAll("\\s+$", "");
    }

    /**
     * Push the pickup-person-editable fields from repair_bookings onto the
     * already-minted tickets row. No-op when the booking has no ticket yet
     * (the next mint will write fresh values anyway). Best-effort — a sync
     * failure should not bubble up and fail the estimate submit.
     *
     * Mirrors the same field set the original mint writes (price, issue,
     * device taxonomy, schedule, security, approval) plus the rebuilt
     * snapshot JSON columns (price_items_json, device_photos_json,
     * missing_parts_json, repair_services_summary) so the owner-side
     * Booking Details / Device Details refresh end-to-end.
     */
    private void syncTicketFromBookingEdit(UUID bookingId) {
        Map<String, Object> bk;
        try {
            bk = jdbc.queryForMap(
                    "SELECT rb.ticket_id, rb.brand_id, rb.model_id, rb.ram_option_id, rb.storage_option_id, " +
                            "       rb.color, rb.estimate_amount, rb.issue_summary, " +
                            "       rb.front_image_url, rb.back_image_url, rb.video_url, " +
                            "       rb.device_pin, rb.missing_damage_parts, " +
                            "       rb.customer_approval, " +
                            "       rb.estimated_ready_at, rb.estimated_delivery_at, " +
                            "       mm.name AS model_name, mm.image_url AS model_image_url, " +
                            "       mb.name AS brand_name " +
                            "FROM repair_bookings rb " +
                            "LEFT JOIN master_models mm ON mm.id = rb.model_id " +
                            "LEFT JOIN master_brands mb ON mb.id = rb.brand_id " +
                            "WHERE rb.id = CAST(? AS UUID)",
                    bookingId.toString());
        } catch (Exception e) {
            log.warn("syncTicketFromBookingEdit: booking lookup failed for {}: {}", bookingId, e.getMessage());
            return;
        }
        String ticketId = stringFrom(bk, "ticket_id");
        if (ticketId == null || ticketId.isBlank()) return;

        Object estimateAmount = bk.get("estimate_amount");
        String issueDescription = stripPickupMeta(stringFrom(bk, "issue_summary"));
        String deviceDisplayName = buildDeviceDisplayName(
                stringFrom(bk, "brand_name"), stringFrom(bk, "model_name"));
        String repairServicesSummary = buildServicesSummary(bookingId);
        String priceItemsJson = buildPriceItemsJson(bookingId, estimateAmount);
        String devicePhotosJson = buildDevicePhotosJson(
                stringFrom(bk, "front_image_url"),
                stringFrom(bk, "back_image_url"),
                stringFrom(bk, "video_url"));
        String missingPartsJson = buildMissingPartsJson(stringFrom(bk, "missing_damage_parts"));
        Boolean customerApproval = mapCustomerApproval(stringFrom(bk, "customer_approval"));

        try {
            jdbc.update(
                    "UPDATE tickets SET " +
                            "    brand_id = COALESCE(CAST(? AS UUID), brand_id), " +
                            "    model_id = COALESCE(CAST(? AS UUID), model_id), " +
                            "    ram_option_id = COALESCE(CAST(? AS UUID), ram_option_id), " +
                            "    storage_option_id = COALESCE(CAST(? AS UUID), storage_option_id), " +
                            "    color = COALESCE(?, color), " +
                            "    estimated_price = ?, " +
                            "    issue_description = COALESCE(?, issue_description), " +
                            "    device_display_name = COALESCE(?, device_display_name), " +
                            "    device_image_url = COALESCE(?, device_image_url), " +
                            "    repair_services_summary = COALESCE(?, repair_services_summary), " +
                            "    price_items_json = COALESCE(?, price_items_json), " +
                            "    device_photos_json = COALESCE(?, device_photos_json), " +
                            "    missing_parts_json = COALESCE(?, missing_parts_json), " +
                            "    device_security_value = COALESCE(?, device_security_value), " +
                            "    customer_approval = COALESCE(?, customer_approval), " +
                            "    estimated_ready_at = COALESCE(?, estimated_ready_at), " +
                            "    estimated_delivery_at = COALESCE(?, estimated_delivery_at), " +
                            "    updated_at = now() " +
                            "WHERE id = CAST(? AS UUID)",
                    stringFrom(bk, "brand_id"),
                    stringFrom(bk, "model_id"),
                    stringFrom(bk, "ram_option_id"),
                    stringFrom(bk, "storage_option_id"),
                    stringFrom(bk, "color"),
                    estimateAmount,
                    issueDescription,
                    deviceDisplayName,
                    stringFrom(bk, "model_image_url"),
                    repairServicesSummary,
                    priceItemsJson,
                    devicePhotosJson,
                    missingPartsJson,
                    stringFrom(bk, "device_pin"),
                    customerApproval,
                    bk.get("estimated_ready_at"),
                    bk.get("estimated_delivery_at"),
                    ticketId);
            log.info("syncTicketFromBookingEdit: booking={} ticket={} synced", bookingId, ticketId);
        } catch (Exception e) {
            log.warn("syncTicketFromBookingEdit: UPDATE failed for ticket={} booking={}: {}",
                    ticketId, bookingId, e.getMessage());
        }
    }

    /**
     * Hand a customer-placed pickup booking off to the shop's ticket pipeline.
     * On RECEIVED_AT_SHOP the device is on the shop bench; the booking now
     * needs a tickets row so the owner's Bookings History (which reads
     * /tickets) and the existing technician-assign flow (PATCH /tickets/{id})
     * can pick it up. Returns the new tickets.id, or the existing one if a
     * mint has already happened (idempotent).
     *
     * The ticket starts at IN_DIAGNOSIS — the technician hasn't been assigned
     * yet but the shop has the device, so CREATED would understate progress.
     * The CustomerOrderMirrorService bridge (Ticket → repair_booking_events)
     * keeps the customer-side timeline in sync from here on.
     */
    // Package-private — see ShopPickupBookingController which calls this on
    // the shop-staff "Mark Received" hand-off path.
    String mintTicketFromBooking(UUID bookingId, UUID shopId, String bookingNumber) {
        // Idempotency: if the booking already carries a ticket_id, return it
        // unchanged. Catches double-fire (two PATCHes racing, or a retry from
        // a flaky network).
        String existingTicketId = null;
        try {
            existingTicketId = jdbc.queryForObject(
                    "SELECT ticket_id::text FROM repair_bookings WHERE id = CAST(? AS UUID)",
                    String.class, bookingId.toString());
        } catch (Exception ignore) { /* not found returns null below */ }
        if (existingTicketId != null && !existingTicketId.isBlank()) {
            log.info("mintTicketFromBooking: booking={} already linked to ticket={}", bookingId, existingTicketId);
            return existingTicketId;
        }

        // Pull every field the ticket needs in one round-trip. Device labels
        // come from master tables so the Bookings History card can render
        // model + image without a follow-up join.
        //
        // customer_users JOIN: repair_bookings.customer_name / customer_mobile
        // are written by the shop-side booking flow only; the customer-side
        // pickup booking flow (order-service RepairBookingController.create)
        // historically stored only customer_user_id, leaving the denormalized
        // columns NULL. Without COALESCE to customer_users.full_name /
        // .mobile, the minted ticket ends up with NULL customer_name and the
        // Bookings History card renders "-" / no mobile.
        Map<String, Object> bk;
        try {
            bk = jdbc.queryForMap(
                    "SELECT rb.id, rb.booking_number, rb.shop_id, rb.customer_user_id, " +
                            "       COALESCE(NULLIF(TRIM(rb.customer_name), ''),   cu.full_name) AS customer_name, " +
                            "       COALESCE(NULLIF(TRIM(rb.customer_mobile), ''), cu.mobile)    AS customer_mobile, " +
                            "       rb.color, " +
                            "       rb.brand_id, rb.model_id, rb.ram_option_id, rb.storage_option_id, " +
                            "       rb.estimate_amount, rb.issue_summary, " +
                            "       rb.front_image_url, rb.back_image_url, rb.video_url, " +
                            "       rb.device_pin, rb.missing_damage_parts, rb.imei, " +
                            "       rb.customer_approval, rb.pickup_address_id, " +
                            "       rb.estimated_ready_at, rb.estimated_delivery_at, " +
                            "       mm.name AS model_name, mm.image_url AS model_image_url, " +
                            "       mb.name AS brand_name, " +
                            "       ca.address_line, ca.locality, ca.city, ca.state, ca.pincode " +
                            "FROM repair_bookings rb " +
                            "LEFT JOIN master_models mm ON mm.id = rb.model_id " +
                            "LEFT JOIN master_brands mb ON mb.id = rb.brand_id " +
                            "LEFT JOIN customer_users cu ON cu.id = rb.customer_user_id " +
                            "LEFT JOIN customer_addresses ca ON ca.id = rb.pickup_address_id " +
                            "WHERE rb.id = CAST(? AS UUID)",
                    bookingId.toString());
        } catch (Exception e) {
            log.warn("mintTicketFromBooking: booking lookup failed for {}: {}", bookingId, e.getMessage());
            return null;
        }

        String customerUserId = stringFrom(bk, "customer_user_id");
        String customerName = stringFrom(bk, "customer_name");
        String customerPhone = stringFrom(bk, "customer_mobile");
        // tickets.customer_id is the platform customer_users.id directly — the
        // old per-shop customers row + indirect platform_user_id link is gone
        // (see TicketService.getForCustomer). The pickup booking already
        // carries customer_user_id from the customer flow, so we use it
        // verbatim; resolvePerShopCustomerId is kept only for any legacy
        // caller that still relies on the find-or-create behaviour.
        if (customerUserId == null || customerUserId.isBlank()) {
            log.warn("mintTicketFromBooking: booking {} has no customer_user_id; skipping mint", bookingId);
            return null;
        }
        String customerId = customerUserId;

        // Same partner sequence as a counter booking (GTS-001…) — TicketNumberService.
        TicketNumberService.Assigned number = ticketNumberService.next(shopId);
        String trackingId = number.trackingId();
        String deviceDisplayName = buildDeviceDisplayName(
                stringFrom(bk, "brand_name"), stringFrom(bk, "model_name"));
        String repairServicesSummary = buildServicesSummary(bookingId);
        String issueDescription = stripPickupMeta(stringFrom(bk, "issue_summary"));
        // Build the Price Summary line items the shop-owner Booking Detail
        // screen renders. tickets.price_items_json is the source of truth for
        // that section; without it the screen shows "No service items
        // recorded." even though we have estimate_amount + service rows.
        Object estimateAmount = bk.get("estimate_amount");
        String priceItemsJson = buildPriceItemsJson(bookingId, estimateAmount);

        // Build snapshot fields for the booking-detail screens. These have to
        // round-trip into tickets columns so the owner's "View Details" reads
        // them straight from /tickets/{id} without joining repair_bookings:
        //   - devicePhotosJson — front/back/video URLs the customer & pickup
        //     person captured; renders the Device Photos grid.
        //   - missingPartsJson — CSV/text the customer/pickup-person entered;
        //     renders the "Device Missing / Damage Parts" card.
        //   - customerAddress  — assembled from customer_addresses join so
        //     the Customer Details card shows the pickup address.
        //   - customerApproval — repair_bookings stores "DONE"/null as a
        //     VARCHAR; tickets stores BOOLEAN. Translate so the "Customer
        //     Repair Approval" row reads "Done"/"Pending" correctly.
        String devicePhotosJson  = buildDevicePhotosJson(
                stringFrom(bk, "front_image_url"),
                stringFrom(bk, "back_image_url"),
                stringFrom(bk, "video_url"));
        String missingPartsJson  = buildMissingPartsJson(stringFrom(bk, "missing_damage_parts"));
        String customerAddress   = joinAddress(
                stringFrom(bk, "address_line"),
                stringFrom(bk, "locality"),
                stringFrom(bk, "city"),
                stringFrom(bk, "state"),
                stringFrom(bk, "pincode"));
        Boolean customerApproval = mapCustomerApproval(stringFrom(bk, "customer_approval"));

        UUID ticketId = UUID.randomUUID();
        try {
            // Nullable UUID columns: only emit the cast when we have a value
            // (CAST(NULL AS UUID) is harmless, so use a uniform expression).
            jdbc.update(
                    "INSERT INTO tickets (id, shop_id, customer_id, customer_name, customer_phone, customer_address, " +
                            "    tracking_id, brand_id, model_id, ram_option_id, storage_option_id, " +
                            "    color, imei, status, estimated_price, issue_description, " +
                            "    device_display_name, device_image_url, repair_services_summary, " +
                            "    price_items_json, device_photos_json, missing_parts_json, " +
                            "    device_security_value, customer_approval, " +
                            "    estimated_ready_at, estimated_delivery_at, partner_id, " +
                            "    created_at, updated_at) " +
                            "VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, " +
                            "    ?, CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), " +
                            "    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS UUID), now(), now())",
                    ticketId.toString(), shopId.toString(), customerId, customerName, customerPhone, customerAddress,
                    trackingId,
                    stringFrom(bk, "brand_id"),
                    stringFrom(bk, "model_id"),
                    stringFrom(bk, "ram_option_id"),
                    stringFrom(bk, "storage_option_id"),
                    stringFrom(bk, "color"),
                    stringFrom(bk, "imei"),
                    "IN_DIAGNOSIS",
                    estimateAmount,
                    issueDescription,
                    deviceDisplayName,
                    stringFrom(bk, "model_image_url"),
                    repairServicesSummary,
                    priceItemsJson,
                    devicePhotosJson,
                    missingPartsJson,
                    stringFrom(bk, "device_pin"),
                    customerApproval,
                    bk.get("estimated_ready_at"),
                    bk.get("estimated_delivery_at"),
                    number.partnerId().toString());
        } catch (Exception e) {
            log.error("mintTicketFromBooking: ticket INSERT failed for booking={}: {}", bookingId, e.getMessage(), e);
            return null;
        }

        // Wire the booking back to its ticket so future reads can join.
        try {
            jdbc.update(
                    "UPDATE repair_bookings SET ticket_id = CAST(? AS UUID), updated_at = now() WHERE id = CAST(? AS UUID)",
                    ticketId.toString(), bookingId.toString());
        } catch (Exception e) {
            log.warn("mintTicketFromBooking: backlink UPDATE failed for booking={}: {}", bookingId, e.getMessage());
        }

        log.info("mintTicketFromBooking: shop={} booking={} -> ticket={} tracking={}",
                shopId, bookingNumber, ticketId, trackingId);
        return ticketId.toString();
    }

    /**
     * Find-or-create the per-shop customers row that satisfies the
     * tickets.customer_id FK. Lookup by platform_user_id, then by phone, then
     * inserts a new row. Returns null if there's no usable identity at all
     * (no platform_user_id AND no phone) — caller treats that as "skip mint".
     */
    private String resolvePerShopCustomerId(String shopId, String platformUserId, String name, String phone) {
        if (shopId == null) return null;
        if (platformUserId != null && !platformUserId.isBlank()) {
            try {
                String existing = jdbc.queryForObject(
                        "SELECT id::text FROM customers WHERE shop_id = CAST(? AS UUID) AND platform_user_id = CAST(? AS UUID) LIMIT 1",
                        String.class, shopId, platformUserId);
                if (existing != null && !existing.isBlank()) return existing;
            } catch (Exception ignore) { /* fall through to phone lookup / insert */ }
        }
        if (phone != null && !phone.isBlank()) {
            try {
                String existing = jdbc.queryForObject(
                        "SELECT id::text FROM customers WHERE shop_id = CAST(? AS UUID) AND phone = ? LIMIT 1",
                        String.class, shopId, phone);
                if (existing != null && !existing.isBlank()) {
                    // Opportunistically backfill the platform link so the
                    // next lookup uses the faster index path.
                    if (platformUserId != null && !platformUserId.isBlank()) {
                        try {
                            jdbc.update(
                                    "UPDATE customers SET platform_user_id = CAST(? AS UUID), updated_at = now() " +
                                            "WHERE id = CAST(? AS UUID) AND platform_user_id IS NULL",
                                    platformUserId, existing);
                        } catch (Exception ignore) { /* best effort */ }
                    }
                    return existing;
                }
            } catch (Exception ignore) { /* fall through to insert */ }
        }
        // No existing row — create one. Customers requires (shop_id, name, phone)
        // NOT NULL; supply blanks if either is missing (better than failing
        // the entire ticket mint over a missing phone).
        if ((name == null || name.isBlank()) && (phone == null || phone.isBlank())) return null;
        String newId = UUID.randomUUID().toString();
        try {
            jdbc.update(
                    "INSERT INTO customers (id, shop_id, name, phone, platform_user_id, created_at, updated_at) " +
                            "VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, " +
                            (platformUserId != null && !platformUserId.isBlank() ? "CAST(? AS UUID)" : "NULL") +
                            ", now(), now())",
                    platformUserId != null && !platformUserId.isBlank()
                            ? new Object[]{newId, shopId, name == null ? "Customer" : name, phone == null ? "" : phone, platformUserId}
                            : new Object[]{newId, shopId, name == null ? "Customer" : name, phone == null ? "" : phone});
            return newId;
        } catch (Exception e) {
            log.warn("resolvePerShopCustomerId: insert failed shop={} phone={}: {}", shopId, phone, e.getMessage());
            return null;
        }
    }

    private static String buildDeviceDisplayName(String brand, String model) {
        if (brand == null && model == null) return null;
        if (brand == null) return model;
        if (model == null) return brand;
        return brand + " " + model;
    }

    private String buildServicesSummary(UUID bookingId) {
        try {
            List<String> names = jdbc.query(
                    "SELECT service_name FROM repair_booking_services WHERE booking_id = CAST(? AS UUID) ORDER BY created_at ASC",
                    (rs, rn) -> rs.getString("service_name"),
                    bookingId.toString());
            if (names == null || names.isEmpty()) return null;
            return String.join(", ", names);
        } catch (Exception e) {
            log.warn("buildServicesSummary: lookup failed for booking={}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    /**
     * Build the JSON array the shop-owner Booking Detail "Price Summary"
     * section renders. Each entry mirrors the shape the walk-in booking
     * flow writes: {id, code, label, amount, warranty}. When the source
     * repair_booking_services rows have no per-item price (customer flow
     * just records the service name + a single bundle estimate), we fall
     * back to splitting the booking-level estimate_amount evenly across
     * the rows so the section isn't empty.
     */
    private String buildPriceItemsJson(UUID bookingId, Object bookingEstimate) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.query(
                    "SELECT id, service_code, service_name, repair_service_id, estimated_price, warranty " +
                            "FROM repair_booking_services WHERE booking_id = CAST(? AS UUID) ORDER BY created_at ASC",
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", rs.getString("id"));
                        r.put("code", rs.getString("service_code"));
                        r.put("label", rs.getString("service_name"));
                        r.put("repairServiceId", rs.getString("repair_service_id"));
                        BigDecimal price = rs.getBigDecimal("estimated_price");
                        if (price != null) r.put("amount", price);
                        // The pickup estimate's per-service warranty — the invoice /
                        // receipt lines read it from the ticket's price items.
                        String warranty = rs.getString("warranty");
                        if (warranty != null && !warranty.isBlank()) r.put("warranty", warranty);
                        return r;
                    },
                    bookingId.toString());
        } catch (Exception e) {
            log.warn("buildPriceItemsJson: lookup failed for booking={}: {}", bookingId, e.getMessage());
            return null;
        }
        if (rows == null || rows.isEmpty()) return null;

        // Dedupe by repair_service_id (or label when the row was never linked
        // to a master service). The customer-flow → pickup-estimate sequence
        // could land the same issue twice in repair_booking_services when the
        // employee app's submit re-sent rows that already existed; without
        // this guard the customer's Price Summary showed each issue twice.
        {
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.List<Map<String, Object>> deduped = new java.util.ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                Object idKey = r.get("repairServiceId");
                Object labelKey = r.get("label");
                String key = idKey != null ? idKey.toString()
                        : labelKey != null ? ("label:" + labelKey.toString().toLowerCase()) : null;
                if (key == null || !seen.add(key)) continue;
                deduped.add(r);
            }
            if (!deduped.isEmpty()) rows = deduped;
        }

        BigDecimal totalFromRows = rows.stream()
                .map(r -> r.get("amount"))
                .filter(a -> a instanceof BigDecimal)
                .map(a -> (BigDecimal) a)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean anyRowPriced = totalFromRows.signum() > 0;

        // Customer-side bookings store one bundle price (estimate_amount) and
        // leave per-row prices null. Spread the bundle across rows so the
        // Price Summary table renders an amount on each line without making
        // up data — split equally when there's no per-row price at all.
        if (!anyRowPriced && bookingEstimate != null) {
            BigDecimal bundle;
            try {
                bundle = bookingEstimate instanceof BigDecimal
                        ? (BigDecimal) bookingEstimate
                        : new BigDecimal(bookingEstimate.toString());
            } catch (Exception e) { bundle = null; }
            if (bundle != null && bundle.signum() > 0 && !rows.isEmpty()) {
                BigDecimal per = bundle.divide(BigDecimal.valueOf(rows.size()), 2, java.math.RoundingMode.HALF_UP);
                for (Map<String, Object> r : rows) r.put("amount", per);
            }
        }

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("buildPriceItemsJson: serialize failed for booking={}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    /**
     * Serialize the pickup booking's front/back/video URLs into the shape
     * the DeviceDetailScreen.parseDevicePhotos() expects:
     *   { "front": "...", "back": "...", "video": "..." }
     * Returns null when no photo has been captured yet so the screen renders
     * the "missing photos" CTA instead of showing empty slot URIs.
     */
    private static String buildDevicePhotosJson(String front, String back, String video) {
        Map<String, String> photos = new LinkedHashMap<>();
        if (front != null && !front.isBlank()) photos.put("front", front);
        if (back  != null && !back.isBlank())  photos.put("back",  back);
        if (video != null && !video.isBlank()) photos.put("video", video);
        if (photos.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(photos);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert the free-text/CSV missing_damage_parts string the booking flow
     * stores into the JSON-array shape DeviceDetailScreen.parseMissingParts()
     * reads. Accepts either a comma- or newline-separated list, or a single
     * line (renders as a one-element list). Returns null for empty/blank so
     * the card renders "Nill" rather than an empty bullet.
     */
    private static String buildMissingPartsJson(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // If the booking already stored a JSON array, pass it through
        // unchanged so we don't double-encode.
        if (trimmed.startsWith("[")) return trimmed;
        List<String> parts = new ArrayList<>();
        for (String piece : trimmed.split("[,\\n]")) {
            String p = piece.trim();
            if (!p.isEmpty()) parts.add(p);
        }
        if (parts.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parts);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Translate the customer_approval VARCHAR ("DONE"/"PENDING"/null) the
     * repair_bookings table uses into the tickets.customer_approval BOOLEAN.
     * Anything explicitly "DONE" → true; explicitly "PENDING"/empty → false
     * so the Service Schedule card renders "Pending" instead of leaving the
     * field absent (which would imply "not collected yet").
     */
    private static Boolean mapCustomerApproval(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if (s.equals("DONE") || s.equals("TRUE") || s.equals("YES") || s.equals("APPROVED")) return Boolean.TRUE;
        if (s.equals("PENDING") || s.equals("FALSE") || s.equals("NO")) return Boolean.FALSE;
        return null;
    }

    // Package-private — see ShopPickupBookingController.
    void appendEvent(UUID bookingId, String status, String note, String actor) {
        jdbc.update(
                "INSERT INTO repair_booking_events (id, booking_id, status, note, actor, created_at) " +
                        "VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, now())",
                UUID.randomUUID().toString(), bookingId.toString(), status, note, actor);
    }

    private void notifyCustomer(Map<String, Object> booking, UUID bookingId, String status, String title, String body) {
        String customerUserStr = stringFrom(booking, "customer_user_id");
        if (customerUserStr == null) return;
        try {
            UUID notificationId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO customer_notifications " +
                            "(id, customer_user_id, booking_id, booking_number, status_key, title, body, type, is_read, created_at) " +
                            "VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, 'orders', false, now())",
                    notificationId.toString(),
                    customerUserStr,
                    bookingId.toString(),
                    stringFrom(booking, "booking_number"),
                    status,
                    title,
                    body);
            pushToCustomer(customerUserStr, notificationId, bookingId, stringFrom(booking, "booking_number"),
                    null, status, title, body);
        } catch (Exception e) {
            log.warn("pickup-notification: insert failed for {}: {}", bookingId, e.getMessage());
        }
    }

    // Same title/body as the customer_notifications row, on the customer's
    // phone (customer_user_id is the auth user id). Sent after commit.
    // Package-private — see ShopPickupBookingController.
    void pushToCustomer(String customerUserStr, UUID notificationId, UUID bookingId, String bookingNumber,
                        String ticketId, String statusKey, String title, String body) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "BOOKING_STATUS");
        data.put("notificationId", notificationId);
        data.put("bookingId", bookingId);
        data.put("bookingNumber", bookingNumber);
        data.put("ticketId", ticketId);
        data.put("statusKey", statusKey);
        pushService.pushToUser(PushApp.CUSTOMER, UUID.fromString(customerUserStr), title, body, data);
    }

    private Map<String, Object> loadBookingSnapshot(UUID id) {
        return jdbc.queryForMap(
                "SELECT rb.id, rb.booking_number, rb.shop_id, rb.customer_user_id, rb.status, rb.issue_summary, " +
                        "rb.color, rb.estimate_amount, rb.final_amount, rb.front_image_url, rb.back_image_url, rb.video_url, " +
                        "rb.brand_id, rb.model_id, rb.ram_option_id, rb.storage_option_id, " +
                        "rb.assigned_pickup_person_id, rb.updated_at, " +
                        // Customer identity + pickup address — surfaced so the
                        // PickupEstimateDetail "Customer Details" card has data
                        // to render. COALESCE against customer_users handles
                        // older bookings whose denormalized columns were never
                        // backfilled (migration 47 only ran once); without this
                        // the screen renders "—" even though the customer
                        // identity is one JOIN away.
                        "COALESCE(NULLIF(rb.customer_name, ''),   cu.full_name) AS customer_name, " +
                        "COALESCE(NULLIF(rb.customer_mobile, ''), cu.mobile)    AS customer_mobile, " +
                        "rb.pickup_address_id, " +
                        // Schedule + condition columns the pickup-person estimate
                        // submit now writes; re-loaded here so the screen's GET
                        // prefill round-trips the values when the pickup person
                        // returns to edit a submitted estimate.
                        "rb.estimated_ready_at, rb.estimated_duration_hours, rb.estimated_delivery_at, " +
                        "rb.device_pin, rb.missing_damage_parts, rb.customer_approval, rb.imei, " +
                        "mb.name AS brand_name, " +
                        "mm.name AS model_name, " +
                        "mm.image_url AS model_image_url, " +
                        "mm.image_base64 AS model_image_base64, " +
                        "mr.label AS ram_label, " +
                        "ms.label AS storage_label " +
                        "FROM repair_bookings rb " +
                        "LEFT JOIN customer_users cu ON cu.id = rb.customer_user_id " +
                        "LEFT JOIN master_brands mb ON mb.id = rb.brand_id " +
                        "LEFT JOIN master_models mm ON mm.id = rb.model_id " +
                        "LEFT JOIN master_ram_options mr ON mr.id = rb.ram_option_id " +
                        "LEFT JOIN master_storage_options ms ON ms.id = rb.storage_option_id " +
                        "WHERE rb.id = CAST(? AS UUID)",
                id.toString());
    }

    private Map<String, Object> estimateResponse(UUID id, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("bookingNumber", stringFrom(row, "booking_number"));
        out.put("status", stringFrom(row, "status"));
        out.put("issueSummary", stringFrom(row, "issue_summary"));
        out.put("color", stringFrom(row, "color"));
        out.put("estimateAmount", stringFrom(row, "estimate_amount"));
        out.put("estimatedRepairValue", stringFrom(row, "estimate_amount"));
        out.put("frontImageUrl", stringFrom(row, "front_image_url"));
        out.put("backImageUrl", stringFrom(row, "back_image_url"));
        out.put("videoUrl", stringFrom(row, "video_url"));
        // Customer card on PickupEstimateDetail reads these three keys.
        out.put("customerName", stringFrom(row, "customer_name"));
        out.put("customerMobile", stringFrom(row, "customer_mobile"));
        String addrId = stringFrom(row, "pickup_address_id");
        out.put("pickupAddressText", addrId != null ? loadAddressText(addrId) : null);
        out.put("brandId", stringFrom(row, "brand_id"));
        out.put("modelId", stringFrom(row, "model_id"));
        out.put("ramOptionId", stringFrom(row, "ram_option_id"));
        out.put("storageOptionId", stringFrom(row, "storage_option_id"));
        out.put("brandName", stringFrom(row, "brand_name"));
        out.put("modelName", stringFrom(row, "model_name"));
        out.put("deviceImageUrl", stringFrom(row, "model_image_url"));
        out.put("modelImageUrl", stringFrom(row, "model_image_url"));
        out.put("deviceImageBase64", stringFrom(row, "model_image_base64"));
        out.put("modelImageBase64", stringFrom(row, "model_image_base64"));
        out.put("ramLabel", stringFrom(row, "ram_label"));
        out.put("storageLabel", stringFrom(row, "storage_label"));
        // Schedule alert + device condition fields the pickup-person screen
        // pre-fills from on re-entry. Surfacing them on the estimate GET
        // means an editor session reads back exactly what was submitted.
        out.put("estimatedReadyAt", row == null ? null : row.get("estimated_ready_at"));
        out.put("estimatedDurationHours", row == null ? null : row.get("estimated_duration_hours"));
        out.put("estimatedDeliveryAt", row == null ? null : row.get("estimated_delivery_at"));
        out.put("devicePin", stringFrom(row, "device_pin"));
        out.put("deviceSecurityValue", stringFrom(row, "device_pin"));
        out.put("missingDamageParts", stringFrom(row, "missing_damage_parts"));
        out.put("customerApproval", stringFrom(row, "customer_approval"));
        out.put("imei", stringFrom(row, "imei"));
        out.put("services", loadServices(id));
        out.put("events", loadEvents(id));
        return out;
    }

    private BookingAccess requireAssignedPickup(HttpServletRequest request, UUID id) {
        UUID shopId = shopIdFrom(request);
        UUID userId = userIdFrom(request);
        if (shopId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        Technician me = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not a technician of this shop"));
        Map<String, Object> row;
        try {
            row = loadBookingSnapshot(id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "booking not found");
        }
        String assignedStr = stringFrom(row, "assigned_pickup_person_id");
        String bookingShopStr = stringFrom(row, "shop_id");
        if (assignedStr == null || !me.getId().toString().equalsIgnoreCase(assignedStr)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your pickup");
        }
        if (bookingShopStr == null || !shopId.toString().equalsIgnoreCase(bookingShopStr)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "wrong shop");
        }
        return new BookingAccess(shopId, userId, me.getId(), row);
    }

    private ResponseEntity<Map<String, Object>> error(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        return ResponseEntity.status(status != null ? status : HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getReason() == null ? "request failed" : e.getReason()));
    }

    private static class BookingAccess {
        final UUID shopId;
        final UUID userId;
        final UUID technicianId;
        final Map<String, Object> booking;

        BookingAccess(UUID shopId, UUID userId, UUID technicianId, Map<String, Object> booking) {
            this.shopId = shopId;
            this.userId = userId;
            this.technicianId = technicianId;
            this.booking = booking;
        }
    }

    private String loadAddressText(String addressId) {
        try {
            return jdbc.query(
                    "SELECT address_line, locality, city, state, pincode " +
                            "FROM customer_addresses WHERE id = CAST(? AS UUID)",
                    rs -> rs.next() ? joinAddress(
                            rs.getString("address_line"),
                            rs.getString("locality"),
                            rs.getString("city"),
                            rs.getString("state"),
                            rs.getString("pincode")
                    ) : null,
                    addressId);
        } catch (Exception e) {
            log.warn("address lookup failed for {}: {}", addressId, e.getMessage());
            return null;
        }
    }

    // Booking event log — required so the screen can bucket PICKUP_REASSIGNED
    // vs PICKUP_ASSIGNED. Wrapped in try/catch so a missing table or schema
    // drift doesn't 500 the whole endpoint.
    private List<Map<String, Object>> loadServices(UUID bookingId) {
        // Best-effort SELECT — warranty column is new (added by migration 25),
        // so older deployments may not have it yet. Try the rich query first
        // and fall back to the legacy column list on a SQL error rather than
        // killing the whole endpoint.
        try {
            return jdbc.query(
                    "SELECT id, repair_service_id, service_code, service_name, estimated_price, warranty, created_at " +
                            "FROM repair_booking_services WHERE booking_id = CAST(? AS UUID) ORDER BY created_at ASC",
                    (rs, rn) -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        String repairServiceId = rs.getString("repair_service_id");
                        BigDecimal estimatedPrice = rs.getBigDecimal("estimated_price");
                        s.put("id", rs.getString("id"));
                        s.put("serviceId", repairServiceId);
                        s.put("repairServiceId", repairServiceId);
                        s.put("serviceCode", rs.getString("service_code"));
                        s.put("code", rs.getString("service_code"));
                        s.put("serviceName", rs.getString("service_name"));
                        s.put("name", rs.getString("service_name"));
                        s.put("estimatedPrice", estimatedPrice);
                        s.put("price", estimatedPrice);
                        s.put("warranty", rs.getString("warranty"));
                        Timestamp created = rs.getTimestamp("created_at");
                        s.put("createdAt", created != null ? created.toInstant().toString() : null);
                        return s;
                    },
                    bookingId.toString());
        } catch (Exception e) {
            log.warn("services lookup failed for booking {}: {}", bookingId, e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> loadEvents(UUID bookingId) {
        try {
            return jdbc.query(
                    "SELECT id, status, note, actor, latitude, longitude, distance_meters, created_at " +
                            "FROM repair_booking_events " +
                            "WHERE booking_id = CAST(? AS UUID) ORDER BY created_at ASC",
                    (rs, rn) -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("id", rs.getString("id"));
                        e.put("status", rs.getString("status"));
                        e.put("note", rs.getString("note"));
                        e.put("actor", rs.getString("actor"));
                        BigDecimal lat = rs.getBigDecimal("latitude");
                        BigDecimal lng = rs.getBigDecimal("longitude");
                        if (lat != null) e.put("latitude", lat);
                        if (lng != null) e.put("longitude", lng);
                        int dm = rs.getInt("distance_meters");
                        if (!rs.wasNull()) e.put("distanceMeters", dm);
                        Timestamp t = rs.getTimestamp("created_at");
                        e.put("createdAt", t != null ? t.toInstant().toString() : null);
                        return e;
                    },
                    bookingId.toString());
        } catch (Exception e) {
            log.warn("events lookup failed for booking {}: {}", bookingId, e.getMessage());
            return List.of();
        }
    }

    private UUID shopIdFrom(HttpServletRequest request) {
        String sid = (String) request.getAttribute("shopId");
        return sid != null ? UUID.fromString(sid) : null;
    }

    private UUID userIdFrom(HttpServletRequest request) {
        String uid = (String) request.getAttribute("userId");
        return uid != null ? UUID.fromString(uid) : null;
    }

    // Package-private — shared with ShopPickupBookingController.
    static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String joinAddress(String line, String locality, String city, String state, String pincode) {
        List<String> parts = new ArrayList<>();
        if (line     != null && !line.isBlank())     parts.add(line.trim());
        if (locality != null && !locality.isBlank()) parts.add(locality.trim());
        if (city     != null && !city.isBlank())     parts.add(city.trim());
        if (state    != null && !state.isBlank())    parts.add(state.trim());
        if (pincode  != null && !pincode.isBlank())  parts.add(pincode.trim());
        return parts.isEmpty() ? null : String.join(", ", parts);
    }
}
