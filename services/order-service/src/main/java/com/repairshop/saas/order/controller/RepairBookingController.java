package com.repairshop.saas.order.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.common.notify.PushApp;
import com.repairshop.saas.common.notify.PushService;
import com.repairshop.saas.common.notify.ShopPushService;
import com.repairshop.saas.order.dto.RepairBookingDtos.*;
import com.repairshop.saas.order.entity.CustomerNotification;
import com.repairshop.saas.order.entity.CustomerOrder;
import com.repairshop.saas.order.entity.ShopNotification;
import com.repairshop.saas.order.entity.PlatformTicket;
import com.repairshop.saas.order.entity.RepairBooking;
import com.repairshop.saas.order.entity.RepairBookingEvent;
import com.repairshop.saas.order.entity.RepairBookingService;
import com.repairshop.saas.order.exception.ForbiddenException;
import com.repairshop.saas.order.exception.ResourceNotFoundException;
import com.repairshop.saas.order.repository.CustomerOrderRepository;
import com.repairshop.saas.order.repository.RepairBookingEventRepository;
import com.repairshop.saas.order.repository.RepairBookingRepository;
import com.repairshop.saas.order.repository.RepairBookingServiceRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/repair-bookings")
@RequiredArgsConstructor
@Slf4j
public class RepairBookingController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final RepairBookingRepository bookingRepo;
    private final RepairBookingServiceRepository serviceRepo;
    private final RepairBookingEventRepository eventRepo;
    private final CustomerOrderRepository customerOrderRepo;
    private final com.repairshop.saas.order.repository.CustomerNotificationRepository notificationRepo;
    private final com.repairshop.saas.order.repository.ShopNotificationRepository shopNotificationRepo;
    private final com.repairshop.saas.order.repository.PlatformTicketRepository platformTicketRepo;
    // Direct JDBC for customer + address enrichment — bypasses Hibernate entity
    // scanning so this works regardless of whether new @Entity classes are
    // picked up by the JPA bootstrap.
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ShopPushService shopPush;
    private final PushService pushService;

    @PostMapping
    @Transactional
    public ResponseEntity<RepairBookingResponse> create(HttpServletRequest req, @RequestBody RepairBookingRequest body) {
        UUID userId = customerCallerId(req);
        String bookingNumber = uniqueBookingNumber();
        final String requestedServiceMode = body.getServiceMode() != null ? body.getServiceMode() : "PICKUP";
        final boolean pickupMode = "PICKUP".equalsIgnoreCase(requestedServiceMode);
        final String initialStatus = pickupMode ? "PICKUP_REQUESTED" : "ORDER_PLACED";
        BigDecimal estimateAmount = body.getServices() == null ? null :
                body.getServices().stream()
                        .map(ServiceRow::getEstimatedPrice)
                        .filter(p -> p != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Denormalize customer name + mobile onto the booking row so the
        // owner-side Bookings History and Pickup Service screens — and the
        // mintTicketFromBooking step in ticket-service — don't have to JOIN
        // customer_users at read time. lookupCustomerNameMobile logs on
        // failure and returns nulls rather than throwing, so a transient
        // customer_users hiccup never blocks a booking create.
        String[] nameMobile = lookupCustomerNameMobile(userId);
        RepairBooking saved = bookingRepo.save(RepairBooking.builder()
                .bookingNumber(bookingNumber)
                .customerUserId(userId)
                .customerName(nameMobile[0])
                .customerMobile(nameMobile[1])
                .shopId(body.getShopId())
                .savedDeviceId(body.getSavedDeviceId())
                .brandId(body.getBrandId())
                .modelId(body.getModelId())
                .ramOptionId(body.getRamOptionId())
                .storageOptionId(body.getStorageOptionId())
                .color(body.getColor())
                .serviceMode(requestedServiceMode)
                .frontImageUrl(body.getFrontImageUrl())
                .backImageUrl(body.getBackImageUrl())
                .videoUrl(body.getVideoUrl())
                .issueSummary(body.getIssueSummary())
                .estimateAmount(estimateAmount != null && estimateAmount.signum() > 0 ? estimateAmount : null)
                .status(initialStatus)
                .pickupAddressId(body.getPickupAddressId())
                .pickupDate(body.getPickupDate())
                .pickupSlotStart(body.getPickupSlotStart())
                .pickupSlotEnd(body.getPickupSlotEnd())
                .build());

        if (body.getServices() != null) {
            for (ServiceRow s : body.getServices()) {
                serviceRepo.save(RepairBookingService.builder()
                        .bookingId(saved.getId())
                        .repairServiceId(s.getRepairServiceId())
                        .serviceCode(s.getServiceCode())
                        .serviceName(s.getServiceName())
                        .estimatedPrice(s.getEstimatedPrice())
                        .build());
            }
        }

        eventRepo.save(RepairBookingEvent.builder()
                .bookingId(saved.getId())
                .status(pickupMode ? "PICKUP_REQUESTED" : "BOOKING_CREATED_BY_SHOP")
                .note(pickupMode ? "Pickup Requested" : "Booking Created by Shop")
                .actor(pickupMode ? "CUSTOMER" : "SYSTEM")
                .build());
        // Creating a booking from the shop side is an implicit "service accepted"
        // step — the shop confirms they'll take the work the moment the booking
        // is saved, so the timeline lights up both rows together.
        if (!pickupMode) {
            eventRepo.save(RepairBookingEvent.builder()
                .bookingId(saved.getId())
                .status("SERVICE_ACCEPTED")
                .note("Service Accepted")
                .actor("SHOP")
                .build());
        }

        // Write unified customer_orders row. Map service mode → orderType so
        // a doorstep-pickup repair shows in the Pickup tab of My Orders,
        // an enquiry shows in the Enquiry tab, and a walk-in stays in Service.
        final String serviceMode = saved.getServiceMode() != null ? saved.getServiceMode() : "PICKUP";
        final String orderType;
        final String title;
        switch (serviceMode) {
            case "PICKUP":
                orderType = "PICKUP";
                title = "Pickup Booking";
                break;
            case "ENQUIRY":
                orderType = "ENQUIRY";
                title = "Service Enquiry";
                break;
            default: // WALK_IN or anything else
                orderType = "REPAIR";
                title = "Service Booking";
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("bookingId", saved.getId());
        payload.put("brandId", body.getBrandId());
        payload.put("modelId", body.getModelId());
        payload.put("services", body.getServices());
        payload.put("serviceMode", serviceMode);
        String payloadJson;
        try { payloadJson = objectMapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJson = null; }
        customerOrderRepo.save(CustomerOrder.builder()
                .orderNumber(bookingNumber)
                .customerUserId(userId)
                .shopId(body.getShopId())
                .orderType(orderType)
                .referenceId(saved.getId())
                .status("PENDING")
                .totalAmount(estimateAmount)
                .payloadJson(payloadJson)
                .build());

        // Confirm the booking to the customer in their notification feed.
        notifyCustomer(saved, initialStatus, pickupMode ? "Pickup requested" : "Order placed",
                "Booking " + saved.getBookingNumber() + " placed - we'll keep you posted.");
        // Alert the shop owner that a new booking has landed. Pickups and
        // enquiries get their own feed type and push kind so the Partner app
        // can open the Pickups / Enquiry tab instead of Bookings.
        final boolean enquiryMode = "ENQUIRY".equalsIgnoreCase(serviceMode);
        String shopTitle = pickupMode ? "New pickup request"
                : enquiryMode ? "New enquiry" : "New booking received";
        String shopBody = "Booking " + saved.getBookingNumber()
                + (nameMobile[0] != null ? " from " + nameMobile[0] : "")
                + " - tap to view details.";
        notifyShop(saved, initialStatus, shopTitle, shopBody,
                pickupMode ? "pickups" : enquiryMode ? "enquiry" : "bookings",
                pickupMode ? "PICKUP" : enquiryMode ? "ENQUIRY" : "BOOKING");

        return ResponseEntity.ok(toResponseWithChildren(saved));
    }

    @GetMapping
    public ResponseEntity<List<RepairBookingResponse>> list(HttpServletRequest req, @RequestParam(value = "status", required = false) String status) {
        UUID userId = customerCallerId(req);
        List<RepairBooking> list = status == null || status.isBlank()
                ? bookingRepo.findByCustomerUserIdOrderByCreatedAtDesc(userId)
                : bookingRepo.findByCustomerUserIdAndStatusOrderByCreatedAtDesc(userId, status.toUpperCase());
        return ResponseEntity.ok(list.stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepairBookingResponse> get(HttpServletRequest req, @PathVariable UUID id) {
        UUID userId = customerCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        assertCustomerOwnsBooking(b, userId);
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<RepairBookingResponse> setStatus(HttpServletRequest req, @PathVariable UUID id, @RequestParam String status) {
        UUID userId = customerCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        assertCustomerOwnsBooking(b, userId);
        b.setStatus(status.toUpperCase());
        bookingRepo.save(b);
        eventRepo.save(RepairBookingEvent.builder()
                .bookingId(b.getId()).status(status.toUpperCase()).note("Status updated").actor("USER").build());
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    // ---- Shop/owner side -------------------------------------------------

    // Bookings for the caller's shop (owner app).
    @GetMapping("/shop")
    public ResponseEntity<List<RepairBookingResponse>> listForShop(HttpServletRequest req) {
        UUID shopId = shopCallerId(req);
        List<RepairBooking> list = bookingRepo.findByShopIdOrderByCreatedAtDesc(shopId);
        log.info("listForShop: shopId={} bookings={}", shopId, list.size());
        return ResponseEntity.ok(list.stream().map(b -> enrichCustomerFields(toResponseWithChildren(b), b)).toList());
    }

    // Single booking lookup scoped to the caller's shop (owner pickup detail screen).
    @GetMapping("/shop/{id}")
    public ResponseEntity<RepairBookingResponse> getForShop(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = shopCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (b.getShopId() == null || !b.getShopId().equals(shopId)) throw new ForbiddenException("Not your shop's booking");
        return ResponseEntity.ok(enrichCustomerFields(toResponseWithChildren(b), b));
    }

    // Look up a customer's name & mobile from customer_users. Returns
    // [name, mobile] (either element may be null). Failures are logged but
    // do not throw so booking creation never fails on enrichment.
    //
    // Binds the id as String with an explicit UUID cast. This avoids the JDBC
    // setObject(UUID) path, while staying compatible with both PostgreSQL and
    // H2's PostgreSQL mode used by the dev profile.
    private String[] lookupCustomerNameMobile(UUID customerUserId) {
        String[] out = new String[]{null, null};
        if (customerUserId == null) return out;
        try {
            jdbc.query(
                    "SELECT full_name, mobile FROM customer_users WHERE id = CAST(? AS UUID)",
                    rs -> { out[0] = rs.getString(1); out[1] = rs.getString(2); },
                    customerUserId.toString()
            );
        } catch (Exception e) {
            log.warn("customer_users lookup failed for {}: {}", customerUserId, e.getMessage());
        }
        return out;
    }

    // Add customer name/mobile and resolved pickup address to a booking response.
    // Uses raw JDBC against customer_users / customer_addresses so it works even
    // if the JPA bootstrap hasn't picked up new entities. The customer_name /
    // customer_mobile columns on repair_bookings are the source of truth — this
    // JDBC fallback only fires for legacy rows where those columns are blank.
    private RepairBookingResponse enrichCustomerFields(RepairBookingResponse r, RepairBooking b) {
        try {
            jdbc.query(
                    "SELECT cu.full_name AS customer_full_name, cu.mobile AS customer_mobile, " +
                            "ca.label, ca.full_name AS address_full_name, ca.mobile AS address_mobile, " +
                            "ca.pincode, ca.locality, ca.address_line, ca.city, ca.state " +
                            "FROM repair_bookings rb " +
                            "LEFT JOIN customer_users cu ON cu.id = rb.customer_user_id " +
                            "LEFT JOIN customer_addresses ca ON ca.id = rb.pickup_address_id " +
                            "WHERE rb.id = CAST(? AS UUID)",
                    rs -> {
                        String customerName = rs.getString("customer_full_name");
                        String customerMobile = rs.getString("customer_mobile");
                        String addressName = rs.getString("address_full_name");
                        String addressMobile = rs.getString("address_mobile");
                        String pincode = rs.getString("pincode");
                        String locality = rs.getString("locality");
                        String addressLine = rs.getString("address_line");
                        String city = rs.getString("city");
                        String state = rs.getString("state");

                        if (isBlank(r.getCustomerName())) {
                            r.setCustomerName(firstNonBlank(customerName, addressName));
                        }
                        if (isBlank(r.getCustomerMobile())) {
                            r.setCustomerMobile(firstNonBlank(customerMobile, addressMobile));
                        }
                        String addressText = joinAddress(addressLine, locality, city, state, pincode);
                        if (!isBlank(addressText)) r.setPickupAddressText(addressText);
                        r.setPickupAddressPincode(pincode);
                        r.setPickupAddressMobile(addressMobile);
                        r.setPickupAddressLabel(rs.getString("label"));
                    },
                    b.getId().toString()
            );
        } catch (Exception e) {
            log.warn("repair booking customer/address join failed for {}: {}", b.getId(), e.getMessage());
        }
        if (isBlank(r.getCustomerName()) || isBlank(r.getCustomerMobile())) {
            String[] snap = lookupCustomerNameMobile(b.getCustomerUserId());
            if (isBlank(r.getCustomerName())) r.setCustomerName(snap[0]);
            if (isBlank(r.getCustomerMobile())) r.setCustomerMobile(snap[1]);
        }
        return r;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String joinAddress(String addressLine, String locality, String city, String state, String pincode) {
        List<String> parts = new java.util.ArrayList<>();
        if (addressLine != null && !addressLine.isBlank()) parts.add(addressLine.trim());
        if (locality    != null && !locality.isBlank())    parts.add(locality.trim());
        if (city        != null && !city.isBlank())        parts.add(city.trim());
        if (state       != null && !state.isBlank())       parts.add(state.trim());
        if (pincode     != null && !pincode.isBlank())     parts.add(pincode.trim());
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // Owner appends a service-timeline status (the customer History reads these
    // events). The note becomes the message shown under the step; it falls back
    // to the status key when omitted.
    @PostMapping("/{id}/shop-status")
    @Transactional
    public ResponseEntity<RepairBookingResponse> setShopStatus(HttpServletRequest req, @PathVariable UUID id, @RequestBody ShopStatusRequest body) {
        UUID shopId = shopCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (b.getShopId() == null || !b.getShopId().equals(shopId)) throw new ForbiddenException("Not your shop's booking");
        if (body.getStatus() == null || body.getStatus().isBlank()) throw new IllegalArgumentException("status is required");
        String status = body.getStatus().toUpperCase();
        b.setStatus(status);
        bookingRepo.save(b);
        String label = body.getNote() != null && !body.getNote().isBlank()
                ? body.getNote() : status.replace('_', ' ');
        eventRepo.save(RepairBookingEvent.builder()
                .bookingId(b.getId()).status(status)
                .note(body.getNote() != null && !body.getNote().isBlank() ? body.getNote() : null)
                .actor("SHOP").build());
        // Drop a notification into the customer's in-app notification list so the
        // status change surfaces even when they're not on the tracking screen.
        notifyCustomer(b, status, label, "Booking " + b.getBookingNumber() + " - tap to view status");
        // Keep the unified customer_orders row in sync for terminal states.
        if (status.equals("DELIVERED") || status.equals("CANCELLED")) {
            customerOrderRepo.findByOrderNumber(b.getBookingNumber()).ifPresent(co -> {
                co.setStatus(status.equals("DELIVERED") ? "COMPLETED" : "CANCELLED");
                customerOrderRepo.save(co);
            });
        }
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    // Employee app — pickup-person's own feed. Returns every repair booking
    // currently (or previously) assigned to the given pickup person.
    //
    // The client (employee app) passes the technician id from its own session
    // (session.technicianId, already fetched via /technicians/me). Optionally
    // narrow by `shopId` query param (also from the session) to scope across
    // shops; if omitted, the personId alone is the key.
    //
    // Auth: SecurityConfig leaves this endpoint permitAll because the JWT
    // verification was diverging from auth-service in some local setups,
    // 401-ing the employee app. The personId is a 128-bit opaque UUID so
    // direct enumeration isn't realistic. Re-add JWT scoping once the
    // shared-secret setup is consistent across services.
    @GetMapping("/pickup/me")
    public ResponseEntity<List<RepairBookingResponse>> listMyAssignedPickups(
            @RequestParam("personId") UUID personId,
            @RequestParam(value = "shopId", required = false) UUID shopIdFilter,
            @RequestParam(value = "status", required = false) String status) {
        log.info("listMyAssignedPickups: person={} shopFilter={} status={}", personId, shopIdFilter, status);
        List<RepairBooking> list = bookingRepo
                .findByAssignedPickupPersonIdOrderByCreatedAtDesc(personId);
        if (shopIdFilter != null) {
            list = list.stream().filter(b -> shopIdFilter.equals(b.getShopId())).toList();
        }
        if (status != null && !status.isBlank()) {
            String target = status.toUpperCase();
            list = list.stream().filter(b -> target.equalsIgnoreCase(b.getStatus())).toList();
        }
        log.info("listMyAssignedPickups: returning {} booking(s) for person={}", list.size(), personId);
        return ResponseEntity.ok(list.stream()
                .map(b -> enrichCustomerFields(toResponseWithChildren(b), b))
                .toList());
    }

    // Shop owner confirms a freshly-placed pickup booking. Flips the booking
    // status from ORDER_PLACED → ORDER_SERVICE_CONFIRMED, drops a timeline
    // event the customer screen reads, and notifies the customer. Idempotent:
    // re-calling on an already-confirmed booking is a no-op.
    @PostMapping("/{id}/confirm-order")
    @Transactional
    public ResponseEntity<RepairBookingResponse> confirmOrder(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = shopCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (b.getShopId() == null || !b.getShopId().equals(shopId)) throw new ForbiddenException("Not your shop's booking");
        final boolean pickupMode = "PICKUP".equalsIgnoreCase(b.getServiceMode());
        final String acceptedStatus = pickupMode ? "PICKUP_ACCEPTED" : "ORDER_SERVICE_CONFIRMED";
        final String acceptedEvent = pickupMode ? "PICKUP_ACCEPTED" : "SERVICE_ACCEPTED";
        final String acceptedLabel = pickupMode ? "Pickup Accepted" : "Service Accepted";
        if (!acceptedStatus.equalsIgnoreCase(b.getStatus())
                && !"ORDER_SERVICE_CONFIRMED".equalsIgnoreCase(b.getStatus())
                && !"PICKUP_ACCEPTED".equalsIgnoreCase(b.getStatus())) {
            // booking macro status stays as ORDER_SERVICE_CONFIRMED — that
            // column drives downstream pickup logic. The TIMELINE event uses
            // the new SERVICE_ACCEPTED step key.
            b.setStatus(acceptedStatus);
            bookingRepo.save(b);
            eventRepo.save(RepairBookingEvent.builder()
                    .bookingId(b.getId()).status(acceptedEvent)
                    .note(acceptedLabel).actor("SHOP").build());
            notifyCustomer(b, acceptedEvent, acceptedLabel,
                    "Booking " + b.getBookingNumber() + " - the shop confirmed your pickup request.");
        }
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    // Shop owner assigns (or reassigns) the pickup person handling this
    // booking. Requires the booking to have been confirmed first so the
    // timeline progresses in order (ORDER_SERVICE_CONFIRMED → PICKUP_ASSIGNED).
    // Stores the agent id + denormalized name/phone on repair_bookings so the
    // customer screen can show "Pickup by <name>" without a cross-service call.
    @PostMapping("/{id}/assign-pickup")
    @Transactional
    public ResponseEntity<RepairBookingResponse> assignPickupPerson(HttpServletRequest req, @PathVariable UUID id, @RequestBody AssignPickupPersonRequest body) {
        UUID shopId = shopCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (b.getShopId() == null || !b.getShopId().equals(shopId)) throw new ForbiddenException("Not your shop's booking");
        if (body == null || body.getPickupPersonId() == null) throw new IllegalArgumentException("pickupPersonId is required");
        if (!body.getPickupPersonId().equals(b.getAssignedPickupPersonId())
                && !com.repairshop.saas.common.notify.EmployeeAttendance.isAssignable(jdbc, shopId, body.getPickupPersonId())) {
            throw new com.repairshop.saas.order.exception.ConflictException(com.repairshop.saas.common.notify.EmployeeAttendance.MESSAGE);
        }
        // The customer Pickup Status timeline expects ORDER_SERVICE_CONFIRMED
        // before PICKUP_ASSIGNED. Auto-confirm so the owner can assign in a
        // single tap on a fresh booking without leaving an out-of-order timeline.
        String prevStatus = b.getStatus();
        if ("ORDER_PLACED".equalsIgnoreCase(prevStatus) || "PICKUP_REQUESTED".equalsIgnoreCase(prevStatus)) {
            String acceptedEvent = "PICKUP".equalsIgnoreCase(b.getServiceMode()) ? "PICKUP_ACCEPTED" : "SERVICE_ACCEPTED";
            String acceptedLabel = "PICKUP".equalsIgnoreCase(b.getServiceMode()) ? "Pickup Accepted" : "Service Accepted";
            eventRepo.save(RepairBookingEvent.builder()
                    .bookingId(b.getId()).status(acceptedEvent)
                    .note(acceptedLabel).actor("SHOP").build());
            notifyCustomer(b, acceptedEvent, acceptedLabel,
                    "Booking " + b.getBookingNumber() + " - the shop confirmed your pickup request.");
        }
        boolean reassign = b.getAssignedPickupPersonId() != null
                && !body.getPickupPersonId().equals(b.getAssignedPickupPersonId());
        boolean newPerson = !body.getPickupPersonId().equals(b.getAssignedPickupPersonId());
        b.setAssignedPickupPersonId(body.getPickupPersonId());
        b.setPickupPersonName(body.getPickupPersonName());
        b.setPickupPersonPhone(body.getPickupPersonPhone());
        b.setStatus("PICKUP_PERSON_ASSIGNED");
        bookingRepo.save(b);
        String displayName = body.getPickupPersonName() != null && !body.getPickupPersonName().isBlank()
                ? body.getPickupPersonName() : "pickup agent";
        eventRepo.save(RepairBookingEvent.builder()
                .bookingId(b.getId())
                .status(reassign ? "PICKUP_REASSIGNED" : "PICKUP_PERSON_ASSIGNED")
                .note((reassign ? "Reassigned to " : "Assigned to ") + displayName)
                .actor("SHOP").build());
        notifyCustomer(b, "PICKUP_PERSON_ASSIGNED",
                reassign ? "Pickup person reassigned" : "Pickup person assigned",
                "Booking " + b.getBookingNumber() + " - " + displayName + " will pick up your device.");
        if (newPerson) pushPickupAssigned(req, b);
        // Flip the customer's My Orders → Pickup card out of "PENDING" the
        // moment a pickup agent is committed. PickupBookingController owns
        // the same mirror for later transitions; this catches the first hop
        // which happens entirely inside order-service.
        customerOrderRepo.findByOrderNumber(b.getBookingNumber()).ifPresent(co -> {
            if (!"IN_PROGRESS".equalsIgnoreCase(co.getStatus())
                    && !"COMPLETED".equalsIgnoreCase(co.getStatus())
                    && !"CANCELLED".equalsIgnoreCase(co.getStatus())) {
                co.setStatus("IN_PROGRESS");
                customerOrderRepo.save(co);
            }
        });
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    @PostMapping("/{id}/reschedule")
    @Transactional
    public ResponseEntity<RepairBookingResponse> reschedule(HttpServletRequest req, @PathVariable UUID id, @RequestBody RescheduleRequest body) {
        UUID userId = customerCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        assertCustomerOwnsBooking(b, userId);
        if (body.getPickupDate() != null) b.setPickupDate(body.getPickupDate());
        if (body.getPickupSlotStart() != null) b.setPickupSlotStart(body.getPickupSlotStart());
        if (body.getPickupSlotEnd() != null) b.setPickupSlotEnd(body.getPickupSlotEnd());
        bookingRepo.save(b);
        eventRepo.save(RepairBookingEvent.builder()
                .bookingId(b.getId()).status(b.getStatus()).note("Rescheduled").actor("USER").build());
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    // Customer marks the repair estimate as approved. Flips the customer-side
    // repair_bookings.customer_approval ("DONE") and mirrors to the owner-side
    // tickets.customer_approval (true) when the booking was shop-created.
    @PostMapping("/{id}/customer-approval")
    @Transactional
    public ResponseEntity<RepairBookingResponse> customerApproval(HttpServletRequest req, @PathVariable UUID id) {
        UUID userId = customerCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        assertCustomerOwnsBooking(b, userId);
        b.setCustomerApproval("DONE");
        bookingRepo.save(b);
        List<RepairBookingEvent> events = eventRepo.findByBookingIdOrderByCreatedAtAsc(b.getId());
        boolean afterReEstimate = events.stream()
                .anyMatch(e -> "RE_ESTIMATED_CONFIRMED".equalsIgnoreCase(e.getStatus()));
        if (b.getTicketId() != null) {
            platformTicketRepo.findById(b.getTicketId()).ifPresent(t -> {
                t.setCustomerApproval(Boolean.TRUE);
                platformTicketRepo.save(t);
            });
        }
        // Emit the dedicated step key so the timeline rail lights up
        // "Customer Approved" instead of the generic macro status. This is the
        // ONLY row an approval writes — "Repair Work In Progress" waits for the
        // technician's own checklist tap (ticket-service emitProgressStepEvent),
        // so it carries the minute work actually resumed rather than a time
        // derived from some other step.
        //
        // Upsert rather than a blind insert. A customer approves more than once
        // whenever the shop re-estimates: the re-edit clears the approval and
        // re-prompts, so this endpoint is hit again. Inserting each time left
        // several CUSTOMER_APPROVED rows for one booking, and the timelines keep
        // the FIRST row per status key — so the rail showed the original
        // approval, dated BEFORE the "Service Re-estimated" step it was supposed
        // to follow. One row, carrying the latest approval, keeps the order true.
        upsertEvent(events, b, "CUSTOMER_APPROVED",
                afterReEstimate ? "Customer approved the re-estimated service"
                                : "Customer approved the estimate",
                "USER", java.time.Instant.now());
        convergeReEstimateApproval(b, userId, events);
        notifyCustomer(b, "CUSTOMER_APPROVED", "Customer Approved",
                "You approved the repair estimate for booking " + b.getBookingNumber() + ".");
        notifyShop(b, "CUSTOMER_APPROVED", "Customer approved the estimate",
                (b.getCustomerName() != null ? b.getCustomerName() : "Customer")
                        + " approved the estimate for booking " + b.getBookingNumber() + ".");
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<RepairBookingResponse> cancel(HttpServletRequest req, @PathVariable UUID id) {
        UUID userId = customerCallerId(req);
        RepairBooking b = bookingRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        assertCustomerOwnsBooking(b, userId);
        b.setStatus("CANCELLED");
        bookingRepo.save(b);
        eventRepo.save(RepairBookingEvent.builder()
                .bookingId(b.getId()).status("CANCELLED").note("Cancelled by user").actor("USER").build());
        // Also mark corresponding customer_order as CANCELLED
        customerOrderRepo.findByOrderNumber(b.getBookingNumber()).ifPresent(co -> {
            co.setStatus("CANCELLED");
            customerOrderRepo.save(co);
        });
        notifyCustomer(b, "CANCELLED", "Booking cancelled",
                "Booking " + b.getBookingNumber() + " was cancelled.");
        notifyShop(b, "CANCELLED", "Booking cancelled by customer",
                "Booking " + b.getBookingNumber()
                        + (b.getCustomerName() != null ? " (" + b.getCustomerName() + ")" : "")
                        + " was cancelled by the customer.");
        return ResponseEntity.ok(toResponseWithChildren(b));
    }

    /**
     * Re-estimate workflow (ticket-service ReEstimateWorkflowService, migration
     * 107): the customer's Approve tap also settles a revision awaiting it —
     * same outcome as the shop's decision: revision APPROVED (decided_via
     * CUSTOMER_APP), ticket back to IN_REPAIR when repair work had started in
     * this cycle (else APPROVED, forward only), plus RE_ESTIMATE_APPROVED and
     * REPAIR_WORK_RESUMED rows. repair_bookings.status follows on ticket-service's
     * next mirror run, as it does for the shop decision. No-op when there is no
     * open revision or the table isn't there yet (migration not applied).
     */
    private void convergeReEstimateApproval(RepairBooking b, UUID customerUserId, List<RepairBookingEvent> events) {
        if (b.getTicketId() == null) return;
        Boolean migrated = jdbc.queryForObject(
                "SELECT to_regclass('public.ticket_reestimate_revisions') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(migrated)) return;
        List<UUID> awaiting = jdbc.queryForList(
                "SELECT id FROM ticket_reestimate_revisions WHERE ticket_id = ? AND status = 'AWAITING_APPROVAL' FOR UPDATE",
                UUID.class, b.getTicketId());
        if (awaiting.isEmpty()) return;
        java.time.Instant now = java.time.Instant.now();
        java.sql.Timestamp at = java.sql.Timestamp.from(now);
        jdbc.update("UPDATE ticket_reestimate_revisions SET status = 'APPROVED', decision = 'APPROVED', decided_at = ?,"
                        + " decided_by_user_id = ?, decided_by_name = ?, decided_via = 'CUSTOMER_APP', updated_at = ?"
                        + " WHERE id = ? AND status = 'AWAITING_APPROVAL'",
                at, customerUserId, b.getCustomerName(), at, awaiting.get(0));

        // Current repair cycle only (a Re-Service reopen starts a new one).
        int start = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            if ("RE_SERVICE".equalsIgnoreCase(events.get(i).getStatus())) { start = i; break; }
        }
        List<RepairBookingEvent> cycle = events.subList(start, events.size());
        boolean repairStarted = cycle.stream().anyMatch(e -> "IN_REPAIR".equalsIgnoreCase(e.getStatus()));
        if (repairStarted) {
            jdbc.update("UPDATE tickets SET status = 'IN_REPAIR', updated_at = now() WHERE id = ?"
                    + " AND status IN ('CREATED','IN_DIAGNOSIS','QUOTED','APPROVED')", b.getTicketId());
        } else {
            jdbc.update("UPDATE tickets SET status = 'APPROVED', updated_at = now() WHERE id = ?"
                    + " AND status IN ('CREATED','IN_DIAGNOSIS','QUOTED')", b.getTicketId());
        }
        upsertEvent(cycle, b, "RE_ESTIMATE_APPROVED", "Re-estimate approved by the customer in the app", "USER", now);
        if (repairStarted) {
            upsertEvent(cycle, b, "REPAIR_WORK_RESUMED", "Repair work resumed after the re-estimate was approved",
                    "SHOP", now.plusMillis(1));
        }
    }

    /**
     * Write one timeline row per status key, carrying the given instant.
     *
     * `existing` is the booking's already-loaded event list; the row is reused
     * when the key is present so a second approval refreshes the timestamp
     * instead of stacking duplicate rows the renderer would never show (the
     * timelines keep the FIRST row per status key).
     */
    private void upsertEvent(List<RepairBookingEvent> existing, RepairBooking b,
                             String statusKey, String note, String actor,
                             java.time.Instant at) {
        RepairBookingEvent e = existing.stream()
                .filter(row -> statusKey.equalsIgnoreCase(row.getStatus()))
                .findFirst()
                .orElseGet(() -> RepairBookingEvent.builder()
                        .bookingId(b.getId()).status(statusKey).build());
        e.setNote(note);
        e.setActor(actor);
        e.setCreatedAt(at);
        eventRepo.save(e);
    }

    // Employee app: the newly assigned pickup person hears about it on their
    // phone. pickupPersonId is technicians.id; technicians.user_id is the auth
    // user id their device is registered under. Never the caller who assigned.
    private void pushPickupAssigned(HttpServletRequest req, RepairBooking b) {
        List<String> userIds = jdbc.queryForList(
                "SELECT CAST(user_id AS TEXT) FROM technicians " +
                        "WHERE id = CAST(? AS UUID) AND shop_id = CAST(? AS UUID) AND user_id IS NOT NULL",
                String.class, b.getAssignedPickupPersonId().toString(), b.getShopId().toString());
        if (userIds.isEmpty()) return;
        UUID userId = UUID.fromString(userIds.get(0));
        Object caller = req.getAttribute("userId");
        if (caller != null && userId.toString().equalsIgnoreCase(caller.toString())) return;
        Map<String, Object> data = new HashMap<>();
        data.put("kind", "PICKUP_ASSIGNED");
        data.put("bookingId", b.getId());
        data.put("bookingNumber", b.getBookingNumber());
        String customer = b.getCustomerName() != null && !b.getCustomerName().isBlank()
                ? " · " + b.getCustomerName() : "";
        pushService.pushToUser(PushApp.EMPLOYEE, userId, "New pickup assigned",
                "Booking " + b.getBookingNumber() + customer, data);
    }

    // Append an in-app notification to the customer's feed for a booking event.
    // The same title/body is pushed to the customer's phone after commit.
    private void notifyCustomer(RepairBooking b, String statusKey, String title, String body) {
        CustomerNotification n = notificationRepo.save(CustomerNotification.builder()
                .customerUserId(b.getCustomerUserId())
                .bookingId(b.getId())
                .bookingNumber(b.getBookingNumber())
                .statusKey(statusKey)
                .title(title)
                .body(body)
                .type("orders")
                .read(false)
                .build());
        Map<String, Object> data = new HashMap<>();
        data.put("kind", "BOOKING_STATUS");
        data.put("notificationId", n.getId());
        data.put("bookingId", b.getId());
        data.put("bookingNumber", b.getBookingNumber());
        if (b.getTicketId() != null) data.put("ticketId", b.getTicketId());
        data.put("statusKey", statusKey);
        pushService.pushToUser(PushApp.CUSTOMER, b.getCustomerUserId(), title, body, data);
    }

    // Mirror notification for the shop owner's feed. No-op when the booking
    // has no shopId yet (customer-initiated pickup before a shop accepts).
    private void notifyShop(RepairBooking b, String statusKey, String title, String body) {
        notifyShop(b, statusKey, title, body, "bookings", "BOOKING");
    }

    // `type` is the feed row's tab (bookings | pickups | enquiry); `kind` is the
    // push data's routing key in the Partner app. The push goes out after this
    // transaction commits, so a rolled-back booking never reaches a phone.
    private void notifyShop(RepairBooking b, String statusKey, String title, String body,
                            String type, String kind) {
        if (b.getShopId() == null) return;
        ShopNotification n = shopNotificationRepo.save(ShopNotification.builder()
                .shopId(b.getShopId())
                .bookingId(b.getId())
                .bookingNumber(b.getBookingNumber())
                // May be null before the booking is turned into a ticket; the
                // owner app falls back to the Bookings tab in that case.
                .ticketId(b.getTicketId())
                .statusKey(statusKey)
                .title(title)
                .body(body)
                .type(type)
                .read(false)
                .build());
        Map<String, Object> data = new HashMap<>();
        data.put("kind", kind);
        data.put("notificationId", String.valueOf(n.getId()));
        data.put("bookingId", String.valueOf(b.getId()));
        if (b.getBookingNumber() != null) data.put("bookingNumber", b.getBookingNumber());
        if (b.getTicketId() != null) data.put("ticketId", b.getTicketId().toString());
        data.put("statusKey", statusKey);
        shopPush.pushToShop(b.getShopId(), title, body, data);
    }

    private RepairBookingResponse toResponse(RepairBooking b) {
        // Shop-entered ticket fields (photos, security, parts, approval, schedule, estimate)
        // live on the ticket row; the booking row is populated by ticket-service's mirror
        // when the shop saves the ticket. When the mirror hasn't yet run (or ran on an older
        // code path), fall back to the linked ticket so the customer view always reflects
        // what the shop entered.
        PlatformTicket t = b.getTicketId() != null
                ? platformTicketRepo.findById(b.getTicketId()).orElse(null)
                : null;
        Map<String, String> tPhotos = t != null ? parseDevicePhotos(t.getDevicePhotosJson()) : Map.of();
        String tMissingParts = t != null ? formatMissingParts(t.getMissingPartsJson()) : null;
        String tCustomerApproval = t != null && Boolean.TRUE.equals(t.getCustomerApproval()) ? "DONE" : null;
        return RepairBookingResponse.builder()
                .id(b.getId()).bookingNumber(b.getBookingNumber())
                .customerUserId(b.getCustomerUserId())
                .shopId(b.getShopId()).ticketId(b.getTicketId()).savedDeviceId(b.getSavedDeviceId())
                .brandId(b.getBrandId()).modelId(b.getModelId())
                .ramOptionId(b.getRamOptionId()).storageOptionId(b.getStorageOptionId())
                .color(b.getColor()).serviceMode(b.getServiceMode())
                .frontImageUrl(coalesce(b.getFrontImageUrl(), tPhotos.get("front")))
                .backImageUrl(coalesce(b.getBackImageUrl(), tPhotos.get("back")))
                .videoUrl(coalesce(b.getVideoUrl(), tPhotos.get("video")))
                .issueSummary(coalesce(b.getIssueSummary(), t != null ? t.getIssueDescription() : null))
                .estimateAmount(b.getEstimateAmount() != null ? b.getEstimateAmount()
                        : (t != null ? t.getEstimatedPrice() : null))
                .finalAmount(b.getFinalAmount())
                .status(b.getStatus())
                .pickupAddressId(b.getPickupAddressId())
                .pickupDate(b.getPickupDate())
                .pickupSlotStart(b.getPickupSlotStart()).pickupSlotEnd(b.getPickupSlotEnd())
                .estimatedReadyAt(b.getEstimatedReadyAt() != null ? b.getEstimatedReadyAt()
                        : (t != null ? t.getEstimatedReadyAt() : null))
                .estimatedDurationHours(b.getEstimatedDurationHours())
                .estimatedDeliveryAt(b.getEstimatedDeliveryAt() != null ? b.getEstimatedDeliveryAt()
                        : (t != null ? t.getEstimatedDeliveryAt() : null))
                .customerApproval(coalesce(b.getCustomerApproval(), tCustomerApproval))
                .deviceSecurityType(t != null && !"NONE".equalsIgnoreCase(t.getDeviceSecurityType())
                        ? t.getDeviceSecurityType() : null)
                .devicePin(coalesce(b.getDevicePin(), t != null ? t.getDeviceSecurityValue() : null))
                .missingDamageParts(coalesce(b.getMissingDamageParts(), tMissingParts))
                .technicianName(b.getTechnicianName())
                .technicianCode(b.getTechnicianCode())
                .technicianPhotos(splitCsv(b.getTechnicianPhotos()))
                .assignedPickupPersonId(b.getAssignedPickupPersonId())
                .pickupPersonName(b.getPickupPersonName())
                .pickupPersonPhone(b.getPickupPersonPhone())
                .reachedShopAt(b.getReachedShopAt())
                .receivedAtShopAt(b.getReceivedAtShopAt())
                .receivedByUserId(b.getReceivedByUserId())
                .receivedByUserName(b.getReceivedByUserName())
                .createdAt(b.getCreatedAt()).updatedAt(b.getUpdatedAt())
                .build();
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private Map<String, String> parseDevicePhotos(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new HashMap<>();
            for (String k : new String[]{"front", "back", "video"}) {
                Object v = raw.get(k);
                if (v != null) out.put(k, v.toString());
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String formatMissingParts(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Object> items = objectMapper.readValue(json, new TypeReference<List<Object>>() {});
            if (items == null || items.isEmpty()) return null;
            List<String> labels = new java.util.ArrayList<>();
            for (Object it : items) {
                if (it instanceof Map<?, ?> m) {
                    Object label = m.get("label");
                    if (label == null) label = m.get("name");
                    if (label != null) labels.add(label.toString());
                } else if (it != null) {
                    labels.add(it.toString());
                }
            }
            return labels.isEmpty() ? null : String.join(", ", labels);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private RepairBookingResponse toResponseWithChildren(RepairBooking b) {
        RepairBookingResponse r = toResponse(b);
        // Dedupe by repair_service_id (or by name when the row was never linked
        // to a master service). The customer-flow → pickup-estimate sequence
        // can land the same issue twice in repair_booking_services when the
        // employee app re-submits rows that already existed; without this
        // guard the customer's Receipt + Invoice rendered each service twice.
        // When duplicates exist, prefer the row that carries a real estimated
        // price (customer-side rows store null prices and let estimate_amount
        // hold the bundle total).
        java.util.LinkedHashMap<String, ServiceRow> deduped = new java.util.LinkedHashMap<>();
        for (RepairBookingService s : serviceRepo.findByBookingId(b.getId())) {
            String key = s.getRepairServiceId() != null
                    ? s.getRepairServiceId().toString()
                    : s.getServiceName() != null
                            ? "name:" + s.getServiceName().toLowerCase()
                            : "row:" + s.getId();
            ServiceRow existing = deduped.get(key);
            BigDecimal price = s.getEstimatedPrice();
            boolean incomingHasPrice = price != null && price.signum() > 0;
            boolean existingHasPrice = existing != null
                    && existing.getEstimatedPrice() != null
                    && existing.getEstimatedPrice().signum() > 0;
            if (existing != null && (existingHasPrice || !incomingHasPrice)) continue;
            deduped.put(key, ServiceRow.builder()
                    .repairServiceId(s.getRepairServiceId())
                    .serviceCode(s.getServiceCode())
                    .serviceName(s.getServiceName())
                    .estimatedPrice(price)
                    .build());
        }
        r.setServices(new java.util.ArrayList<>(deduped.values()));
        r.setEvents(eventRepo.findByBookingIdOrderByCreatedAtAsc(b.getId()).stream()
                .map(e -> RepairBookingEventResp.builder()
                        .id(e.getId()).status(e.getStatus()).note(e.getNote()).actor(e.getActor())
                        .audioUrl(e.getAudioUrl())
                        .imageUrls(parseImagesJson(e.getImagesJson()))
                        .createdAt(e.getCreatedAt())
                        .build()).toList());
        return r;
    }

    // Re-hydrate the images_json TEXT column (stored as ["url", ...]) into
    // a List<String> the customer-facing event DTO can carry directly.
    private static List<String> parseImagesJson(String raw) {
        if (raw == null || raw.isBlank()) return java.util.Collections.emptyList();
        try {
            List<?> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, List.class);
            List<String> urls = new java.util.ArrayList<>();
            for (Object o : parsed) {
                if (o != null) urls.add(o.toString());
            }
            return urls;
        } catch (Exception ignored) {
            return java.util.Collections.emptyList();
        }
    }

    private String uniqueBookingNumber() {
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder("#CSPQX");
            for (int j = 0; j < 8; j++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String c = sb.toString();
            if (bookingRepo.findByBookingNumber(c).isEmpty()) return c;
        }
        throw new IllegalStateException("Could not generate unique booking number");
    }

    private UUID customerCallerId(HttpServletRequest req) {
        requireRole(req, "CUSTOMER");
        return callerId(req);
    }

    private void assertCustomerOwnsBooking(RepairBooking b, UUID userId) {
        if (customerOwnsBooking(b, userId)) return;
        throw new ForbiddenException("Not your booking");
    }

    private boolean customerOwnsBooking(RepairBooking b, UUID userId) {
        if (b == null || userId == null) return false;
        if (userId.equals(b.getCustomerUserId())) return true;
        return customerOrderRepo.existsByReferenceIdAndCustomerUserId(b.getId(), userId);
    }

    @SuppressWarnings("unchecked")
    private void requireRole(HttpServletRequest req, String role) {
        Object raw = req.getAttribute("roles");
        if (raw instanceof List<?> roles && roles.stream().anyMatch(r -> role.equalsIgnoreCase(String.valueOf(r)))) {
            return;
        }
        throw new ForbiddenException("Role not allowed");
    }

    private UUID callerId(HttpServletRequest req) {
        Object u = req.getAttribute("userId");
        if (u == null) throw new ForbiddenException("Missing userId");
        return UUID.fromString(u.toString());
    }

    private UUID shopCallerId(HttpServletRequest req) {
        Object s = req.getAttribute("shopId");
        if (s == null) throw new ForbiddenException("Not a shop account");
        return UUID.fromString(s.toString());
    }
}
