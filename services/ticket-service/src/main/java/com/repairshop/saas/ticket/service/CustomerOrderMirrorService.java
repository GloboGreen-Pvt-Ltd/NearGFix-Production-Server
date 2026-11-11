package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.common.notify.PushApp;
import com.repairshop.saas.common.notify.PushService;
import com.repairshop.saas.common.notify.ShopPushService;
import com.repairshop.saas.ticket.entity.*;
import com.repairshop.saas.ticket.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirrors shop-side ticket events into the platform-wide repair_bookings +
 * customer_orders tables that the customer app's "My Orders" feed reads.
 *
 * Why: tickets is per-shop and keyed by customers.id (shop-scoped). The
 * customer app reads customer_orders keyed by customer_users.id (platform).
 * A ticket only reaches the customer's feed when the per-shop customer row
 * carries a platform_user_id link — added by migration 28. This service is
 * the bridge.
 */
@Service
@RequiredArgsConstructor
public class CustomerOrderMirrorService {

    private final TechnicianRepository technicianRepository;
    private final PlatformRepairBookingRepository bookingRepo;
    private final PlatformRepairBookingServiceRepository bookingServiceRepo;
    private final PlatformRepairBookingEventRepository bookingEventRepo;
    private final PlatformCustomerOrderRepository customerOrderRepo;
    private final PlatformCustomerNotificationRepository notificationRepo;
    private final PlatformShopNotificationRepository shopNotificationRepo;
    private final ObjectMapper objectMapper;
    private final ShopPushService shopPush;
    private final PushService pushService;

    /** Ticket-side status → (booking.status, customer_order.status).
     *  customer_order.status stays PENDING through the post-READY billing /
     *  handover substages (INVOICE_GENERATED, INVOICE_READY,
     *  DELIVERED_PROCESSING) and only flips to COMPLETED when the device is
     *  physically delivered. */
    private static final Map<String, String[]> STATUS_MAP = Map.ofEntries(
            Map.entry("CREATED",              new String[]{"ORDER_PLACED",         "PENDING"}),
            Map.entry("IN_DIAGNOSIS",         new String[]{"IN_DIAGNOSIS",         "PENDING"}),
            Map.entry("QUOTED",               new String[]{"QUOTED",               "PENDING"}),
            Map.entry("APPROVED",             new String[]{"SERVICE_ACCEPTED",     "PENDING"}),
            Map.entry("IN_REPAIR",            new String[]{"IN_REPAIR",            "PENDING"}),
            Map.entry("READY",                new String[]{"READY",                "PENDING"}),
            Map.entry("INVOICE_GENERATED",    new String[]{"INVOICE_GENERATED",    "PENDING"}),
            Map.entry("INVOICE_READY",        new String[]{"INVOICE_READY",        "PENDING"}),
            Map.entry("DELIVERED_PROCESSING", new String[]{"DELIVERED_PROCESSING", "PENDING"}),
            Map.entry("DELIVERED",            new String[]{"DELIVERED",            "COMPLETED"}),
            Map.entry("CANCELLED",            new String[]{"CANCELLED",            "CANCELLED"})
    );

    /** Ticket statuses that imply the technician has actively picked the job up.
     * Mirrors the owner UI's ACCEPTED_STATUSES so a single source decides when
     * to emit TECHNICIAN_ACCEPTED to the customer timeline. */
    private static final java.util.Set<String> ACCEPTED_TICKET_STATUSES = java.util.Set.of(
            "IN_DIAGNOSIS", "IN_REPAIR", "QUOTED", "APPROVED", "READY", "DELIVERED"
    );

    /** Customer notification templates keyed by the step-event status. Only
     * statuses present here trigger a customer-facing notification — for
     * everything else we still write the timeline event but stay silent on
     * the customer's Notifications screen. The body has a single "%s" slot
     * for the booking number. Restricting this map is what keeps the screen
     * useful (one row per real-life update) instead of one ping per emit.
     */
    private static final java.util.Map<String, String[]> CUSTOMER_NOTIFICATION_TEMPLATES =
            java.util.Map.ofEntries(
                    java.util.Map.entry("ASSIGNED_TO_TECHNICIAN",
                            new String[]{"Technician assigned",
                                    "We've assigned a technician to your booking %s."}),
                    java.util.Map.entry("REASSIGNED_TO_TECHNICIAN",
                            new String[]{"Technician re-assigned",
                                    "A new technician is now handling your booking %s."}),
                    java.util.Map.entry("TECHNICIAN_ACCEPTED_SERVICE",
                            new String[]{"Technician accepted",
                                    "Your technician has accepted the service for booking %s."}),
                    java.util.Map.entry("TECHNICIAN_WORK_STARTED",
                            new String[]{"Repair started",
                                    "Work has begun on your booking %s."}),
                    java.util.Map.entry("TECHNICIAN_UPLOADED_DEVICE_IMAGES",
                            new String[]{"Device photos added",
                                    "Your technician uploaded photos of your device for booking %s."}),
                    java.util.Map.entry("TECHNICIAN_COMPLIANCE_ISSUE_VERIFIED_UPDATED",
                            new String[]{"Issue verified",
                                    "Your technician has verified the issue on booking %s."}),
                    // Re-estimate workflow (ReEstimateWorkflowService).
                    java.util.Map.entry("RE_ESTIMATE_APPROVED",
                            new String[]{"Re-estimate approved",
                                    "The revised estimate for booking %s is approved."}),
                    java.util.Map.entry("RE_ESTIMATE_REJECTED",
                            new String[]{"Re-estimate rejected",
                                    "The revised estimate for booking %s was rejected — the original estimate stands."}),
                    java.util.Map.entry("REPAIR_WORK_RESUMED",
                            new String[]{"Repair resumed",
                                    "Work has resumed on your booking %s."}),
                    java.util.Map.entry("REPAIR_COMPLETED",
                            new String[]{"Repair completed",
                                    "The repair for booking %s is complete."}),
                    java.util.Map.entry("READY",
                            new String[]{"Ready for delivery",
                                    "Your booking %s is ready. We'll hand it over shortly."}),
                    java.util.Map.entry("READY_FOR_DELIVERY",
                            new String[]{"Ready for delivery",
                                    "Your booking %s is ready for delivery."}),
                    java.util.Map.entry("INVOICE_GENERATED",
                            new String[]{"Invoice generated",
                                    "An invoice has been generated for booking %s."}),
                    java.util.Map.entry("DELIVERED",
                            new String[]{"Delivered",
                                    "Your device for booking %s has been delivered. Enjoy!"}),
                    java.util.Map.entry("CANCELLED",
                            new String[]{"Booking cancelled",
                                    "Your booking %s has been cancelled."}),
                    // Pickup flow updates
                    java.util.Map.entry("PICKUP_PERSON_ASSIGNED",
                            new String[]{"Pickup partner assigned",
                                    "A pickup partner has been assigned to booking %s."}),
                    java.util.Map.entry("PICKUP_ON_THE_WAY",
                            new String[]{"Pickup partner on the way",
                                    "Your pickup partner is heading to you for booking %s."}),
                    java.util.Map.entry("REACHED_CUSTOMER_LOCATION",
                            new String[]{"Pickup partner arrived",
                                    "Your pickup partner has arrived for booking %s."}),
                    java.util.Map.entry("DEVICE_PICKED_UP",
                            new String[]{"Device picked up",
                                    "Your device for booking %s has been picked up."}),
                    java.util.Map.entry("REACHED_SHOP",
                            new String[]{"Device reaching the shop",
                                    "Your device is on its way back to the shop for booking %s."}),
                    java.util.Map.entry("RECEIVED_AT_SHOP",
                            new String[]{"Device received at shop",
                                    "Your device has reached the shop for booking %s."})
            );

    /**
     * Emit a customer-facing notification for a fresh timeline event. Called
     * from every step-event save site after the row commits so the customer
     * sees one Notifications row per real-life update instead of just the
     * initial "Service booking created" entry.
     *
     * Silently skips when:
     *   * the booking has no platform customer linked (walk-in flow)
     *   * the status isn't in CUSTOMER_NOTIFICATION_TEMPLATES — keeps low-signal
     *     transitions (e.g., AWAITING_TECHNICIAN_ACCEPTANCE) off the screen
     *
     * Runs in REQUIRES_NEW so a notification-save failure can't poison the
     * outer event-save transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emitCustomerNotificationForStatus(UUID bookingId, String statusKey) {
        if (bookingId == null || statusKey == null) return;
        String[] template = CUSTOMER_NOTIFICATION_TEMPLATES.get(statusKey.toUpperCase());
        if (template == null) return;
        bookingRepo.findById(bookingId).ifPresent(booking -> {
            UUID userId = booking.getCustomerUserId();
            if (userId == null) return; // walk-in: no app account to notify
            String bookingNumber = booking.getBookingNumber() != null
                    ? booking.getBookingNumber()
                    : "";
            PlatformCustomerNotification n = notificationRepo.save(PlatformCustomerNotification.builder()
                    .customerUserId(userId)
                    .bookingId(booking.getId())
                    .bookingNumber(bookingNumber)
                    .statusKey(statusKey)
                    .title(template[0])
                    .body(String.format(template[1], bookingNumber))
                    .type("orders")
                    .isRead(false)
                    .build());
            pushToCustomer(n, booking.getTicketId());
        });
    }

    /** Shop-owner notification templates keyed by the step-event status.
     *
     * Deliberately NOT the same map as CUSTOMER_NOTIFICATION_TEMPLATES: the two
     * audiences care about opposite halves of the timeline. The customer wants
     * to hear about things the shop does to their device; the shop wants to
     * hear about things that happen to the job while it is out of their hands —
     * i.e. technician progress and anything that needs an owner decision
     * (spare parts to order, a re-estimate to send, a job that came back
     * unrepaired).
     *
     * Statuses the OWNER themselves triggered are absent on purpose. Assigning
     * a technician, generating an invoice or marking a device delivered are all
     * shop actions posted from the owner app — notifying the owner about their
     * own tap is pure noise. CUSTOMER_APPROVED / CANCELLED are likewise absent:
     * order-service already raises those from the customer-facing endpoints
     * (RepairBookingController#customerApproval / #cancel), and emitting here
     * too would double-notify.
     *
     * The body has one "%s" slot for the booking number.
     */
    private static final java.util.Map<String, String[]> SHOP_NOTIFICATION_TEMPLATES =
            java.util.Map.ofEntries(
                    java.util.Map.entry("TECHNICIAN_ACCEPTED_SERVICE",
                            new String[]{"Technician accepted the job",
                                    "Your technician accepted booking %s."}),
                    java.util.Map.entry("TECHNICIAN_WORK_STARTED",
                            new String[]{"Technician work started",
                                    "Work has started on booking %s."}),
                    java.util.Map.entry("TECHNICIAN_UPLOADED_DEVICE_IMAGES",
                            new String[]{"Technician uploaded device images",
                                    "Device photos were added to booking %s."}),
                    java.util.Map.entry("TECHNICIAN_COMPLIANCE_ISSUE_VERIFIED_UPDATED",
                            new String[]{"Technician issue verified & updated",
                                    "The technician verified the issue on booking %s."}),
                    java.util.Map.entry("ISSUE_IDENTIFIED",
                            new String[]{"Issue identified",
                                    "The technician identified the issue on booking %s."}),
                    java.util.Map.entry("TECHNICIAN_REESTIMATE_REQUESTED",
                            new String[]{"Technician requested a re-estimate",
                                    "The technician found additional work on booking %s and requested a re-estimate."}),
                    java.util.Map.entry("RE_ESTIMATED_CONFIRMED",
                            new String[]{"Service re-estimated",
                                    "A revised estimate is ready for booking %s."}),
                    // Owner decisions are written with actor OWNER, so these only
                    // reach the shop feed when the customer decided in the app.
                    java.util.Map.entry("RE_ESTIMATE_APPROVED",
                            new String[]{"Re-estimate approved",
                                    "The customer approved the re-estimate for booking %s."}),
                    java.util.Map.entry("RE_ESTIMATE_REJECTED",
                            new String[]{"Re-estimate rejected",
                                    "The customer rejected the re-estimate for booking %s."}),
                    java.util.Map.entry("WAITING_FOR_CUSTOMER_APPROVAL",
                            new String[]{"Waiting for customer approval",
                                    "Booking %s is held until the customer approves."}),
                    // Single spare-parts status. PARTS_REPLACED was retired with
                    // migration 87 — the wait is one row now, so the shop gets
                    // one alert instead of a pending/replaced pair.
                    java.util.Map.entry("PARTS_REQUIRED",
                            new String[]{"Spare parts waiting",
                                    "Booking %s is waiting on spare parts."}),
                    java.util.Map.entry("QUALITY_CHECK_COMPLETED",
                            new String[]{"Quality check completed",
                                    "Booking %s passed its quality check."}),
                    java.util.Map.entry("REPAIR_COMPLETED",
                            new String[]{"Repair completed",
                                    "The repair for booking %s is complete."}),
                    java.util.Map.entry("READY",
                            new String[]{"Ready for delivery",
                                    "Booking %s is ready to hand over."}),
                    // Return path — the shop needs to know a job is coming back
                    // unrepaired far more urgently than the customer does.
                    java.util.Map.entry("REPAIR_NOT_COMPLETED",
                            new String[]{"Repair not completed",
                                    "Booking %s could not be repaired."}),
                    java.util.Map.entry("CUSTOMER_REJECTED",
                            new String[]{"Customer rejected the estimate",
                                    "The customer rejected the estimate for booking %s."})
            );

    /**
     * Shop-side counterpart of {@link #emitCustomerNotificationForStatus} —
     * puts a Service History status change on the owner app's Notifications
     * screen. Before this the shop feed had exactly three producers, all in
     * order-service (booking created, customer approved, customer cancelled),
     * so nothing a technician did ever reached the owner's bell.
     *
     * Silently skips when:
     *   * the booking has no shopId (customer-initiated pickup, no shop yet)
     *   * the status isn't in SHOP_NOTIFICATION_TEMPLATES — keeps owner-driven
     *     and low-signal transitions off the screen
     *   * the same (booking, status) pair was already notified — a technician
     *     re-saving a compliance note refreshes the timeline row in place, and
     *     that must not raise a second alert
     *
     * Runs in REQUIRES_NEW so a notification-save failure can't poison the
     * outer event-save transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emitShopNotificationForStatus(UUID bookingId, String statusKey) {
        emitShopNotificationForStatus(bookingId, statusKey, null);
    }

    /**
     * Actor-aware overload. {@code actor} is who performed the step, when the
     * caller knows it.
     *
     * OWNER means someone in the shop app tapped this themselves — Quality Check
     * Completed is markable from the owner's Service History screen as well as
     * the technician's checklist — so the shop feed stays silent rather than
     * telling the owner what they just did. That is the same rule the template
     * map above already follows by omitting owner-driven statuses; it just has
     * to be enforced at call time for a status that BOTH sides can raise.
     *
     * A null/unknown actor notifies as before.
     *
     * The row is also pushed to the shop's phones (kind TICKET_STATUS) once
     * this transaction commits — unless the caller is inside
     * {@link ShopPushService#quiet()}, which the read-time back-fill uses so a
     * step that happened long ago doesn't buzz anyone when the job is opened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emitShopNotificationForStatus(UUID bookingId, String statusKey, String actor) {
        if (bookingId == null || statusKey == null) return;
        if (actor != null && "OWNER".equalsIgnoreCase(actor.trim())) return;
        String key = statusKey.toUpperCase();
        String[] template = SHOP_NOTIFICATION_TEMPLATES.get(key);
        if (template == null) return;
        bookingRepo.findById(bookingId).ifPresent(booking -> {
            UUID shopId = booking.getShopId();
            if (shopId == null) return; // no shop has taken the job on yet
            if (shopNotificationRepo.existsByBookingIdAndStatusKey(bookingId, key)) return;
            String bookingNumber = booking.getBookingNumber() != null
                    ? shopFacingNumber(booking.getBookingNumber())
                    : "";
            PlatformShopNotification n = shopNotificationRepo.save(PlatformShopNotification.builder()
                    .shopId(shopId)
                    .bookingId(booking.getId())
                    .bookingNumber(bookingNumber)
                    .ticketId(booking.getTicketId())
                    .statusKey(key)
                    .title(template[0])
                    .body(String.format(template[1], bookingNumber))
                    .type("bookings")
                    .isRead(false)
                    .build());
            Map<String, Object> data = new HashMap<>();
            data.put("kind", "TICKET_STATUS");
            data.put("notificationId", String.valueOf(n.getId()));
            data.put("bookingId", String.valueOf(booking.getId()));
            if (booking.getTicketId() != null) data.put("ticketId", booking.getTicketId().toString());
            data.put("statusKey", key);
            data.put("bookingNumber", bookingNumber);
            shopPush.pushToShop(shopId, n.getTitle(), n.getBody(), data);
        });
    }

    /** Raise both feeds for one timeline status, so a new status can never be
     *  wired to the customer's bell but forgotten on the shop's.
     *
     *  For CALLERS INSIDE THIS CLASS ONLY. Self-invocation bypasses the Spring
     *  proxy, so the two REQUIRES_NEW annotations below do not take effect and
     *  both saves join the caller's transaction — which is already how the
     *  intra-class emit sites behaved before the shop feed existed. External
     *  callers (TicketService) must invoke the two emitters separately through
     *  the injected bean so each keeps its own REQUIRES_NEW and a failure on
     *  one feed can't roll back the other or the outer event save. */
    void emitNotificationsForStatus(UUID bookingId, String statusKey) {
        emitNotificationsForStatus(bookingId, statusKey, null);
    }

    /** As above, with the actor for the shop feed — see the actor-aware
     *  {@link #emitShopNotificationForStatus(UUID, String, String)}. */
    void emitNotificationsForStatus(UUID bookingId, String statusKey, String actor) {
        emitCustomerNotificationForStatus(bookingId, statusKey);
        emitShopNotificationForStatus(bookingId, statusKey, actor);
    }

    /** Inline variant — runs in the CALLER'S transaction so it can see a ticket
     *  the caller has just saved-and-flushed but not yet committed. Used by
     *  TicketService.create / update where the ticket and the mirror MUST land
     *  atomically: without this, the booking INSERT runs in a fresh transaction
     *  that can't see the ticket and the FK constraint
     *  repair_bookings_ticket_id_fkey fails. Read-path callers should keep
     *  using {@link #mirrorOnUpsert(Ticket)} which preserves the swallow-
     *  failure semantics they rely on. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void mirrorOnUpsertInline(Ticket ticket) {
        doMirror(ticket, null);
    }

    /** Insert (or update) the platform-side booking + customer order for a ticket.
     *  Runs in its OWN transaction (REQUIRES_NEW) so that read-path callers like
     *  TicketService.getEventsForShop can swallow a mirror failure without the
     *  outer transaction being marked rollback-only — otherwise the subsequent
     *  events read would throw UnexpectedRollbackException. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mirrorOnUpsert(Ticket ticket) {
        doMirror(ticket, null);
    }

    /** Same, for a write whose caller is known. {@code actor} OWNER — the shop
     *  owner changed the status from their own app — keeps a macro-status step
     *  it implies (READY) off the owner's feed and phone: they just did it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mirrorOnUpsert(Ticket ticket, String actor) {
        doMirror(ticket, actor);
    }

    private void doMirror(Ticket ticket, String actor) {
        // platformUserId may be null for walk-in customers. We still create
        // the booking row (with customer_user_id null) so the timeline rail
        // and owner-side history work; the customer-facing feed mirror
        // (customer_orders, notifications) is skipped further down.
        UUID platformUserId = resolvePlatformUserId(ticket);

        String[] mapped = STATUS_MAP.getOrDefault(
                upper(ticket.getStatus()), new String[]{"ORDER_PLACED", "PENDING"});
        String bookingStatus = mapped[0];
        String orderStatus = mapped[1];

        Optional<PlatformRepairBooking> existing = bookingRepo.findByTicketId(ticket.getId());
        PlatformRepairBooking booking = existing.orElseGet(PlatformRepairBooking::new);
        boolean isNew = booking.getId() == null;
        // Snapshot prior state so we can decide which timeline events to emit
        // after the save (the entity fields are about to be overwritten).
        String prevBookingStatus = isNew ? null : booking.getStatus();

        if (isNew) {
            booking.setBookingNumber(makeBookingNumber(ticket));
            booking.setCustomerUserId(platformUserId); // may be null for walk-in
            booking.setShopId(ticket.getShopId());
            booking.setTicketId(ticket.getId());
            booking.setServiceMode("WALK_IN");
        }
        UUID newTechId = ticket.getAssignedTechnicianId();
        Technician tech = newTechId != null
                ? technicianRepository.findById(newTechId).orElse(null)
                : null;
        booking.setTechnicianName(tech != null ? tech.getName() : null);
        // Short display code derived from the tech id (matches the owner UI's
        // "Name - <CODE>" rendering when no explicit code is stored).
        booking.setTechnicianCode(newTechId != null
                ? newTechId.toString().substring(0, 8).toUpperCase()
                : null);
        booking.setBrandId(ticket.getBrandId());
        booking.setModelId(ticket.getModelId());
        booking.setRamOptionId(ticket.getRamOptionId());
        booking.setStorageOptionId(ticket.getStorageOptionId());
        booking.setColor(ticket.getColor());
        booking.setIssueSummary(ticket.getIssueDescription());
        booking.setEstimateAmount(ticket.getEstimatedPrice());
        booking.setFinalAmount(ticket.getFinalPrice());
        booking.setEstimatedReadyAt(ticket.getEstimatedReadyAt());
        booking.setEstimatedDeliveryAt(ticket.getEstimatedDeliveryAt());
        booking.setCustomerApproval(Boolean.TRUE.equals(ticket.getCustomerApproval()) ? "DONE" : null);
        booking.setDevicePin(ticket.getDeviceSecurityValue());
        booking.setMissingDamageParts(formatMissingParts(ticket.getMissingPartsJson()));
        Map<String, String> photos = parseDevicePhotos(ticket.getDevicePhotosJson());
        booking.setFrontImageUrl(photos.get("front"));
        booking.setBackImageUrl(photos.get("back"));
        booking.setVideoUrl(photos.get("video"));
        // Copy the technician's post-acceptance photos onto the booking row.
        // Ticket-side storage is a JSON array; booking-side is CSV — the
        // order-service RepairBookingResponse splits the CSV back to a list
        // for the customer detail screen. Skipping this sync was why the
        // customer's "Technician Photos" card stayed empty even when the
        // owner could see the uploads.
        booking.setTechnicianPhotos(joinTechnicianPhotosForBooking(ticket.getTechnicianPhotosJson()));
        booking.setStatus(bookingStatus);
        booking = bookingRepo.save(booking);

        rebuildBookingServices(booking.getId(), ticket.getPriceItemsJson(),
                ticket.getRepairServicesSummary());

        emitTimelineEvents(booking.getId(), isNew, prevBookingStatus, bookingStatus,
                newTechId, tech, upper(ticket.getStatus()),
                ticket.getTechnicianAcceptedAt() != null, actor);

        // Customer-side feed (My Orders) + notifications only apply when the
        // customer has a platform_user_id link. Walk-in tickets stop here —
        // the booking + timeline events were already saved above.
        if (platformUserId == null) return;

        // Orphan recovery: a prior mirror run may have left a customer_orders row
        // pointing at a now-deleted booking. Re-pointing it at the freshly-created
        // booking avoids hitting the order_number unique constraint and preserves
        // the customer_orders id the customer's My Orders list already knows about.
        // Final ref so the .or() lambda can capture it — `booking` itself was
        // reassigned by bookingRepo.save above and is not effectively final.
        final PlatformRepairBooking savedBooking = booking;
        PlatformCustomerOrder co = customerOrderRepo.findByReferenceId(savedBooking.getId())
                .or(() -> customerOrderRepo.findByOrderNumber(savedBooking.getBookingNumber()))
                .orElseGet(PlatformCustomerOrder::new);
        boolean coIsNew = co.getId() == null;
        if (coIsNew) {
            co.setOrderNumber(booking.getBookingNumber());
            co.setCustomerUserId(platformUserId);
            co.setShopId(ticket.getShopId());
            co.setOrderType("REPAIR");
        }
        co.setReferenceId(booking.getId());
        co.setStatus(orderStatus);
        co.setTotalAmount(ticket.getEstimatedPrice());
        co.setPayloadJson(buildPayloadJson(ticket, booking));
        customerOrderRepo.save(co);

        if (isNew) {
            PlatformCustomerNotification n = notificationRepo.save(PlatformCustomerNotification.builder()
                    .customerUserId(platformUserId)
                    .bookingId(booking.getId())
                    .bookingNumber(booking.getBookingNumber())
                    .statusKey(bookingStatus)
                    .title("Service booking created")
                    .body("Your booking " + booking.getBookingNumber()
                            + " has been created by the shop.")
                    .type("orders")
                    .isRead(false)
                    .build());
            pushToCustomer(n, ticket.getId());
        }
    }

    /** The customer_notifications row, on the customer's phone (after commit; silent under quiet()). */
    private void pushToCustomer(PlatformCustomerNotification n, UUID ticketId) {
        Map<String, Object> data = new HashMap<>();
        data.put("kind", "BOOKING_STATUS");
        data.put("notificationId", n.getId());
        data.put("bookingId", n.getBookingId());
        data.put("bookingNumber", n.getBookingNumber());
        data.put("ticketId", ticketId);
        data.put("statusKey", n.getStatusKey());
        pushService.pushToUser(PushApp.CUSTOMER, n.getCustomerUserId(), n.getTitle(), n.getBody(), data);
    }

    /**
     * Append the customer-visible timeline events for state changes detected in
     * this mirror call. Keys match the SERVICE_PHASES step keys the customer's
     * Service History screen looks up, so each emitted event lights up the
     * corresponding row with a timestamp.
     */
    private void emitTimelineEvents(UUID bookingId, boolean isNew,
                                    String prevBookingStatus, String bookingStatus,
                                    UUID newTechId,
                                    Technician tech, String ticketStatus,
                                    boolean technicianAccepted, String actor) {
        if (isNew) {
            bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                    .bookingId(bookingId).status("BOOKING_CREATED_BY_SHOP")
                    .note("Booking Created by Shop").actor("SHOP").build());
            // Shop creating the booking implies service is accepted in the
            // same step; emit alongside so the timeline rail doesn't skip a row.
            bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                    .bookingId(bookingId).status("SERVICE_ACCEPTED")
                    .note("Service Accepted").actor("SHOP").build());
            // BOOKING_CREATED_BY_SHOP already has its own dedicated notification
            // (the doMirror isNew branch fires the "Service booking created"
            // message) so we don't re-notify here. SERVICE_ACCEPTED is silent
            // by design — bundled with the creation moment.
        } else if (prevBookingStatus != null && !prevBookingStatus.equals(bookingStatus)) {
            // Booking macro status actually changed — emit the corresponding
            // step event from the SHOP_BOOKING_STATUS_OPTIONS list when there
            // is a 1:1 mapping (IN_REPAIR / READY / DELIVERED / CANCELLED
            // are themselves valid step keys). Other transitions are surfaced
            // by their dedicated emits elsewhere.
            String stepKey = mapMacroStatusToStepKey(bookingStatus);
            // The step's own emit (progress step / auto READY / DELIVERED) usually
            // wrote this row already — a second blank SHOP row duplicated it.
            boolean already = stepKey != null && bookingEventRepo.findCurrentCycle(bookingId).stream()
                    .anyMatch(e -> stepKey.equalsIgnoreCase(e.getStatus()));
            if (stepKey != null && !already) {
                bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                        .bookingId(bookingId).status(stepKey).actor("SHOP").build());
                emitNotificationsForStatus(bookingId, stepKey, actor);
            }
        }

        String techName = tech != null ? tech.getName() : "technician";

        if (newTechId != null) {
            List<PlatformRepairBookingEvent> existing =
                    bookingEventRepo.findCurrentCycle(bookingId);
            boolean hadAssignBefore = existing.stream().anyMatch(
                    e -> "ASSIGNED_TO_TECHNICIAN".equalsIgnoreCase(e.getStatus())
                            || "REASSIGNED_TO_TECHNICIAN".equalsIgnoreCase(e.getStatus()));
            boolean hasNotAccepted = existing.stream().anyMatch(
                    e -> "AWAITING_TECHNICIAN_ACCEPTANCE".equalsIgnoreCase(e.getStatus()));

            if (!hadAssignBefore) {
                bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                        .bookingId(bookingId).status("ASSIGNED_TO_TECHNICIAN")
                        .note("Assigned to " + techName).actor("SHOP").build());
                emitNotificationsForStatus(bookingId, "ASSIGNED_TO_TECHNICIAN");
            }
            // Each (re)assignment puts the booking back into a not-accepted
            // state; emit once so the customer sees the awaiting-acceptance
            // step light up with a timestamp. The notification map deliberately
            // skips AWAITING_TECHNICIAN_ACCEPTANCE — it's intermediate noise
            // for the customer.
            if (!hasNotAccepted) {
                bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                        .bookingId(bookingId).status("AWAITING_TECHNICIAN_ACCEPTANCE")
                        .note("Awaiting Technician Acceptance").actor("SHOP").build());
            }
        }

        // Acceptance source of truth is now tickets.technician_accepted_at,
        // set by the technician's explicit POST /tickets/{id}/accept. Only
        // emit the customer-side ACCEPTED / WORK_STARTED rows when that
        // timestamp is non-null; the prior ACCEPTED_TICKET_STATUSES heuristic
        // inferred acceptance from the ticket lifecycle status and auto-fired
        // these rows the moment the owner assigned a technician, which made
        // the technician's queue show the task as already-accepted.
        if (newTechId != null && technicianAccepted) {
            List<PlatformRepairBookingEvent> existing =
                    bookingEventRepo.findCurrentCycle(bookingId);
            boolean hasAccepted = existing.stream().anyMatch(
                    e -> "TECHNICIAN_ACCEPTED_SERVICE".equalsIgnoreCase(e.getStatus()));
            boolean hasWorkStarted = existing.stream().anyMatch(
                    e -> "TECHNICIAN_WORK_STARTED".equalsIgnoreCase(e.getStatus()));
            if (!hasAccepted) {
                bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                        .bookingId(bookingId).status("TECHNICIAN_ACCEPTED_SERVICE")
                        .note(techName + " accepted the service").actor("TECHNICIAN").build());
                emitNotificationsForStatus(bookingId, "TECHNICIAN_ACCEPTED_SERVICE");
            }
            // Accepting the service immediately implies the technician has
            // picked the job up and started work — emit alongside so the
            // two rows on the timeline share the same moment. We deliberately
            // skip the customer notification here so accept + start don't
            // produce two pings back-to-back; the "Technician accepted"
            // message covers both.
            if (!hasWorkStarted) {
                bookingEventRepo.save(PlatformRepairBookingEvent.builder()
                        .bookingId(bookingId).status("TECHNICIAN_WORK_STARTED")
                        .note("Technician Work Started").actor("TECHNICIAN").build());
            }
        }
    }

    /** Map a booking macro status to the step key used by the new timeline,
     *  or null when the macro change is surfaced by a dedicated emit elsewhere. */
    private static String mapMacroStatusToStepKey(String bookingStatus) {
        if (bookingStatus == null) return null;
        switch (bookingStatus.toUpperCase()) {
            case "IN_REPAIR":  return "IN_REPAIR";
            case "READY":      return "READY";
            case "DELIVERED":  return "DELIVERED";
            case "CANCELLED":  return "CANCELLED";
            default:           return null;
        }
    }

    /** Re-build the service rows for a booking from the ticket's priceItemsJson. */
    private void rebuildBookingServices(UUID bookingId, String priceItemsJson, String summary) {
        bookingServiceRepo.deleteByBookingId(bookingId);
        List<Map<String, Object>> items = parsePriceItems(priceItemsJson);
        if (items.isEmpty() && summary != null && !summary.isBlank()) {
            bookingServiceRepo.save(PlatformRepairBookingService.builder()
                    .bookingId(bookingId).serviceName(summary).build());
            return;
        }
        for (Map<String, Object> it : items) {
            Object label = it.get("label");
            Object amount = it.get("amount");
            BigDecimal price = null;
            if (amount != null) {
                try { price = new BigDecimal(amount.toString()); }
                catch (NumberFormatException ignored) {}
            }
            // Carry the warranty across the rebuild — dropping it here wiped the
            // per-service warranty the pickup estimate had saved on the booking.
            Object warranty = it.get("warranty");
            String warrantyText = warranty != null && !warranty.toString().isBlank() ? warranty.toString().trim() : null;
            if (warrantyText != null && warrantyText.length() > 20) warrantyText = warrantyText.substring(0, 20);
            bookingServiceRepo.save(PlatformRepairBookingService.builder()
                    .bookingId(bookingId)
                    .serviceName(label != null ? label.toString() : null)
                    .estimatedPrice(price)
                    .warranty(warrantyText)
                    .build());
        }
    }

    /** Flatten the ticket's missing parts JSON array into a human-readable string. */
    private String formatMissingParts(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Object> items = objectMapper.readValue(json, new TypeReference<List<Object>>() {});
            if (items == null || items.isEmpty()) return null;
            List<String> labels = new java.util.ArrayList<>();
            for (Object it : items) {
                if (it instanceof Map<?, ?> m) {
                    Object label = ((Map<?, ?>) m).get("label");
                    if (label == null) label = ((Map<?, ?>) m).get("name");
                    if (label != null) labels.add(label.toString());
                } else if (it != null) {
                    labels.add(it.toString());
                }
            }
            return labels.isEmpty() ? null : String.join(", ", labels);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Convert tickets.technician_photos_json (JSON array of URLs, or [{url}, ...])
     *  into the CSV format the booking-side technician_photos column stores.
     *  Returns null for empty / invalid input so a re-mirror without photos
     *  doesn't overwrite an existing CSV with an empty string. */
    private String joinTechnicianPhotosForBooking(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Object> items = objectMapper.readValue(json, new TypeReference<List<Object>>() {});
            if (items == null || items.isEmpty()) return null;
            List<String> urls = new java.util.ArrayList<>();
            for (Object it : items) {
                String url = null;
                if (it instanceof String s) url = s;
                else if (it instanceof Map<?, ?> m) {
                    Object v = m.get("url");
                    if (v == null) v = m.get("uri");
                    if (v == null) v = m.get("imageUrl");
                    if (v != null) url = v.toString();
                }
                if (url != null && !url.isBlank()) urls.add(url);
            }
            return urls.isEmpty() ? null : String.join(",", urls);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Extract front/back/video URLs from the ticket's device photos JSON object. */
    private Map<String, String> parseDevicePhotos(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            for (String k : new String[]{"front", "back", "video"}) {
                Object v = raw.get(k);
                if (v != null) out.put(k, v.toString());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<Map<String, Object>> parsePriceItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String buildPayloadJson(Ticket t, PlatformRepairBooking b) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", "Service Booking");
        payload.put("bookingId", b.getId());
        payload.put("ticketId", t.getId());
        payload.put("trackingId", t.getTrackingId());
        payload.put("brandId", t.getBrandId());
        payload.put("modelId", t.getModelId());
        payload.put("serviceMode", "WALK_IN");
        payload.put("deviceName", t.getDeviceDisplayName());
        try { return objectMapper.writeValueAsString(payload); }
        catch (Exception e) { return null; }
    }

    private UUID resolvePlatformUserId(Ticket ticket) {
        // After the customers→customer_users consolidation, tickets.customer_id
        // IS the customer_users.id (no per-shop indirection). Returning it
        // directly is correct; the mirror still treats null as "walk-in
        // without an app account" — the booking row gets created with a
        // null customer_user_id (column was made nullable earlier).
        return ticket.getCustomerId();
    }

    private String makeBookingNumber(Ticket t) {
        String tracking = t.getTrackingId() != null ? t.getTrackingId() : t.getId().toString();
        // Partner-scoped numbers (GTS-001) repeat across partners, but
        // repair_bookings.booking_number and customer_orders.order_number are
        // globally UNIQUE and order-service joins them by value — so tag the
        // ticket's own id on. Legacy CSPEN tickets keep "#CSPEN…" as before.
        if (t.getPartnerId() != null) {
            return "#" + tracking + "-" + t.getId().toString().substring(0, 8).toUpperCase();
        }
        return "#" + tracking;
    }

    /** The shop knows the ticket as "#GTS-001" — drop the uniqueness tag makeBookingNumber adds. */
    private static String shopFacingNumber(String bookingNumber) {
        return bookingNumber.replaceFirst("^(#[A-Z0-9]TS-\\d{3,})-[0-9A-F]{8}$", "$1");
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase();
    }
}
