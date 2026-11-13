package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.CreateRepairNoteRequest;
import com.repairshop.saas.ticket.dto.CreateSolutionPackRequest;
import com.repairshop.saas.ticket.dto.RepairNoteResponse;
import com.repairshop.saas.ticket.dto.SolutionPackResponse;
import com.repairshop.saas.ticket.dto.TicketEventResponse;
import com.repairshop.saas.ticket.dto.TicketRequest;
import com.repairshop.saas.ticket.dto.TicketResponse;
import com.repairshop.saas.ticket.entity.Invoice;
import com.repairshop.saas.ticket.entity.PlatformRepairBookingEvent;
import com.repairshop.saas.ticket.entity.RepairNote;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.entity.TicketSolutionPack;
import com.repairshop.saas.ticket.dto.ImeiAvailabilityResponse;
import com.repairshop.saas.ticket.exception.ImeiConflictException;
import com.repairshop.saas.ticket.exception.ResourceNotFoundException;
import com.repairshop.saas.ticket.repository.InvoiceRepository;
import com.repairshop.saas.ticket.repository.MasterTechnicianWorkStatusViewRepository;
import com.repairshop.saas.ticket.repository.PlatformCustomerAddressRepository;
import com.repairshop.saas.ticket.repository.PlatformCustomerUserRepository;
import com.repairshop.saas.ticket.repository.PlatformRepairBookingEventRepository;
import com.repairshop.saas.ticket.repository.PlatformRepairBookingRepository;
import com.repairshop.saas.ticket.repository.RepairNoteRepository;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import com.repairshop.saas.ticket.repository.TicketSolutionPackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TechnicianRepository technicianRepository;
    private final PlatformRepairBookingRepository platformRepairBookingRepository;
    private final PlatformRepairBookingEventRepository platformRepairBookingEventRepository;
    private final PlatformCustomerAddressRepository platformCustomerAddressRepository;
    private final PlatformCustomerUserRepository platformCustomerUserRepository;
    private final RepairNoteRepository repairNoteRepository;
    private final TicketSolutionPackRepository ticketSolutionPackRepository;
    private final MasterTechnicianWorkStatusViewRepository masterWorkStatusRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerOrderMirrorService customerOrderMirrorService;
    private final TicketNumberService ticketNumberService;
    private final ReEstimateWorkflowService reEstimateWorkflow;
    private final com.repairshop.saas.common.notify.ShopPushService shopPush;
    private final com.repairshop.saas.common.notify.PushService pushService;

    @Transactional
    public TicketResponse getById(UUID shopId, UUID id) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        // Self-healing: walk-in tickets created before the mirror was widened
        // to support null customer_user_id won't have a booking row yet, so
        // step events have no booking_id to attach to. Run the mirror first
        // so it creates the booking + the lifecycle events; then sync fills
        // in any step events implied by current ticket state.
        //
        // Errors are caught + logged: the ticket-detail screen fires this
        // read in parallel with getEventsForShop, and both call mirrorOnUpsert
        // in REQUIRES_NEW transactions. A transient race (optimistic lock)
        // must not break the primary read — the next mirror run will heal it.
        //
        // quiet(): whatever this self-heal back-fills already happened, possibly
        // days ago — it still gets its feed row, but no phone push.
        try (var quiet = shopPush.quiet()) {
            try { customerOrderMirrorService.mirrorOnUpsert(t); }
            catch (Exception e) { log.error("mirrorOnUpsert failed for ticket {}", id, e); }
            try { syncStepEventsFromTicketState(t); }
            catch (Exception e) { log.error("syncStepEventsFromTicketState failed for ticket {}", id, e); }
        }
        return toResponse(t);
    }

    /**
     * Service timeline for the owner BookingTimelineScreen. Reads from the
     * mirrored repair_booking_events (same table the customer history reads),
     * scoped to a ticket via repair_bookings.ticket_id. Returns an empty list
     * (not 404) when the mirror booking hasn't been created yet so the screen
     * can render its phase skeleton.
     */
    @Transactional
    public List<TicketEventResponse> getEventsForShop(UUID shopId, UUID ticketId) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        // Same self-heal as getById: create the booking mirror if missing so
        // walk-in tickets gain a timeline on first view. Errors here are
        // caught + logged so a bad mirror state doesn't 500 the whole events
        // endpoint — we still try to return whatever events exist. Quiet for the
        // same reason as getById: back-filled steps are not news.
        try (var quiet = shopPush.quiet()) {
            try { customerOrderMirrorService.mirrorOnUpsert(t); }
            catch (Exception e) { log.error("mirrorOnUpsert failed for ticket {}", ticketId, e); }
            try { syncStepEventsFromTicketState(t); }
            catch (Exception e) { log.error("syncStepEventsFromTicketState failed for ticket {}", ticketId, e); }
        }
        return platformRepairBookingRepository.findByTicketId(t.getId())
                .map(b -> platformRepairBookingEventRepository
                        .findCurrentCycle(b.getId())
                        .stream()
                        .map(e -> TicketEventResponse.builder()
                                .id(e.getId())
                                .status(e.getStatus())
                                .note(e.getNote())
                                .actor(e.getActor())
                                .audioUrl(e.getAudioUrl())
                                .imageUrls(parseImagesJson(e.getImagesJson()))
                                .createdAt(e.getCreatedAt())
                                .build())
                        .toList())
                .orElse(List.of());
    }

    // Emit every "In Service Process" step event that the ticket's current
    // state implies but that hasn't been written yet. Idempotent — the inner
    // emit helpers check for existing rows. Run on every ticket read so
    // pre-existing tickets self-heal without a separate backfill job.
    private void syncStepEventsFromTicketState(Ticket t) {
        if (t == null) return;
        // SERVICE_ACCEPTED follows BOOKING_CREATED_BY_SHOP for every shop-side
        // booking. Self-heals pre-existing tickets that pre-date the auto-emit.
        emitBookingEvent(t.getId(), "SERVICE_ACCEPTED",
                "Service Accepted", "SHOP");
        String status = t.getStatus() == null ? "" : t.getStatus().toUpperCase();
        if (Set.of("IN_REPAIR", "APPROVED", "READY", "DELIVERED").contains(status)) {
            emitBookingEvent(t.getId(), "TECHNICIAN_WORK_STARTED",
                    "Technician Work Started", "TECHNICIAN");
        }
        if (Set.of("QUOTED", "APPROVED", "IN_REPAIR", "READY", "DELIVERED").contains(status)) {
            emitBookingEvent(t.getId(), "WAITING_FOR_CUSTOMER_APPROVAL",
                    "Waiting for Customer Approval", "TECHNICIAN");
        }
        // CUSTOMER_APPROVED is deliberately NOT derived here. It used to be
        // back-filled from tickets.customer_approval (and from a status that had
        // advanced past the gate), which lit "Customer Approved" on the rail for
        // an approval nobody gave: a shop that ticked the approval box while
        // creating the booking got a green Customer Approved stamped at the first
        // page load — minutes before the technician was even assigned, and with
        // "Service Re-estimated" still grey above it.
        //
        // Approval is an event, not a state to be inferred. The only writers are
        // the three real approval actions, each stamping its own instant:
        //   * update() / patch()  — shop ticks "Customer Repair Approval"
        //   * RepairBookingController#customerApproval — customer taps Approve
        // All three route through onCustomerApproved().
        if (hasAtLeastOneUrl(t.getTechnicianPhotosJson())) {
            emitBookingEvent(t.getId(), "TECHNICIAN_UPLOADED_DEVICE_IMAGES",
                    "Technician Uploaded Device Images", "TECHNICIAN");
        }
        // Repair notes / solution packs: peek by existence, no full load.
        boolean hasComplianceNote = repairNoteRepository
                .findByTicketIdOrderByCreatedAtDesc(t.getId()).stream()
                .anyMatch(n -> !Boolean.TRUE.equals(n.getIsInternal()));
        if (hasComplianceNote) {
            emitBookingEvent(t.getId(), KEY_ISSUE_VERIFIED,
                    "Technician Issue Verified & Updated", "TECHNICIAN");
        }
        // "Repair Work In Progress" is not derived at all any more. It used to be
        // paired to "Technician Issue Verified & Updated", carrying that row's
        // exact timestamp — but verifying the issue is diagnosis, not repair, so
        // the rail showed repair work starting at the minute the technician
        // described the fault, before the re-estimate had even been sent. The
        // only writer now is the technician's own checklist tap
        // (emitProgressStepEvent), stamped when they actually start.
        boolean hasNewSolutionPack = !ticketSolutionPackRepository
                .findByTicketIdAndPackTypeOrderByCreatedAtDesc(t.getId(), "NEW").isEmpty();
        if (hasNewSolutionPack) {
            emitBookingEvent(t.getId(), "ISSUE_IDENTIFIED",
                    "Issue identified by technician", "TECHNICIAN");
        }
        // An invoice row IS the record that the step happened, so a booking that
        // has one must carry the step. InvoiceService writes the event itself now;
        // this heals the invoices raised before it did — the client used to post
        // that event separately and a dropped call left a generated invoice with a
        // grey "Invoice Generated" on the rail for good. Stamped with the
        // invoice's own generatedAt, not now(), so the healed row reads as the
        // moment the bill was actually raised. Written only when missing: this
        // runs on every ticket read, and re-writing it each time would churn.
        //
        // After a Re-Service reopen the ticket keeps its one invoice row: the
        // first (delivered) repair's invoice is not this repair's step, and a
        // re-raised one keeps the first generatedAt, so it is stamped with its
        // last write instead — a stamp before the RE_SERVICE marker would fall
        // outside the current cycle and be re-inserted on every read.
        if (!hasBookingEvent(t.getId(), "INVOICE_GENERATED")) {
            invoiceRepository.findByTicketId(t.getId()).ifPresent(inv -> {
                Instant cycleStart = reServiceStartedAt(t.getId());
                if (isEarlierRepairInvoice(inv, cycleStart)) return;
                emitOrUpdateBookingEvent(t.getId(), "INVOICE_GENERATED",
                        InvoiceService.invoiceGeneratedNote(inv), "OWNER",
                        null, null, cycleStart != null ? lastWrite(inv) : inv.getGeneratedAt());
            });
        }
    }

    /**
     * Read a single ticket as the customer who placed it. Ownership is established
     * by ticket.customer_id → customers.platform_user_id == JWT subject. The
     * device security value is masked since the customer already knows their own
     * PIN/pattern and the field is sensitive in transit.
     */
    @Transactional(readOnly = true)
    public TicketResponse getForCustomer(UUID platformUserId, UUID id) {
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        if (t.getCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ticket has no customer");
        }
        // tickets.customer_id is now the customer_users.id directly (the old
        // per-shop customers row + indirect platform_user_id link is gone).
        if (!t.getCustomerId().equals(platformUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your ticket");
        }
        return toCustomerResponse(t);
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> listByShop(UUID shopId, String status, String q, Pageable pageable) {
        String normalizedStatus = status != null && !status.isBlank() ? status : null;
        String normalizedQuery = q != null && !q.isBlank() ? q.trim() : null;
        Page<Ticket> page = normalizedQuery != null
                ? ticketRepository.searchByShop(shopId, normalizedStatus, normalizedQuery, pageable)
                : normalizedStatus != null
                        ? ticketRepository.findByShopIdAndStatus(shopId, normalizedStatus, pageable)
                        : ticketRepository.findByShopId(shopId, pageable);
        return toResponsePage(page);
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> listByAssignedTechnician(UUID technicianId, Pageable pageable) {
        return toResponsePage(ticketRepository.findByAssignedTechnicianId(technicianId, pageable));
    }

    /** For technician "my tickets": resolve user id to technician id then list assigned tickets. */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listByAssignedUser(UUID userId, Pageable pageable) {
        return technicianRepository.findFirstByUserId(userId)
                .map(tech -> toResponsePage(ticketRepository.findByAssignedTechnicianId(tech.getId(), pageable)))
                .orElse(new PageImpl<>(Collections.emptyList(), pageable, 0));
    }

    /** Shop-scoped variant: an owner working in several shops sees only this shop's technician row. */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listByAssignedUser(UUID shopId, UUID userId, Pageable pageable) {
        if (shopId == null) return listByAssignedUser(userId, pageable);
        return technicianRepository.findByShopIdAndUserId(shopId, userId)
                .map(tech -> toResponsePage(ticketRepository.findByAssignedTechnicianId(tech.getId(), pageable)))
                .orElse(new PageImpl<>(Collections.emptyList(), pageable, 0));
    }

    /**
     * Returns booking/ticket counts for the shop (for owner dashboard).
     * Keys: CREATED, IN_DIAGNOSIS, QUOTED, APPROVED, IN_REPAIR, READY,
     * INVOICE_GENERATED, INVOICE_READY, DELIVERED_PROCESSING, DELIVERED,
     * CANCELLED, total, assignedCount.
     *
     * The two invoice substates are here because the owner Home "Ready for
     * Delivery" tile sums the whole post-repair band (READY + the billing /
     * handover substates) to agree with the bookings-list chip it opens.
     * Without them the tile read 0 the moment a booking's invoice was raised.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getCountsByShop(UUID shopId) {
        Map<String, Long> counts = new HashMap<>();
        String[] statuses = { "CREATED", "IN_DIAGNOSIS", "QUOTED", "APPROVED", "IN_REPAIR", "READY",
                "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING", "DELIVERED", "CANCELLED" };
        for (String s : statuses) {
            counts.put(s, ticketRepository.countByShopIdAndStatus(shopId, s));
        }
        counts.put("total", ticketRepository.countByShopId(shopId));
        counts.put("assignedCount", ticketRepository.countByShopIdAndAssignedTechnicianIdNotNull(shopId));
        return counts;
    }

    @Transactional
    public TicketResponse create(UUID shopId, TicketRequest request) {
        // Single device -> GTS-001; first of several -> GTS-001-A; a device
        // added to an existing booking -> that booking's next suffix.
        TicketNumberService.Assigned number = request.getBookingNumber() != null && !request.getBookingNumber().isBlank()
                ? ticketNumberService.nextInBooking(shopId, request.getBookingNumber(), request.getCustomerId())
                : ticketNumberService.next(shopId, Boolean.TRUE.equals(request.getMultiDevice()));
        Ticket ticket = Ticket.builder()
                .shopId(shopId)
                .customerId(request.getCustomerId())
                .customerName(request.getCustomerName())
                .customerPhone(request.getCustomerPhone())
                .brandId(request.getBrandId())
                .modelId(request.getModelId())
                .ramOptionId(request.getRamOptionId())
                .storageOptionId(request.getStorageOptionId())
                .color(request.getColor())
                .imei(request.getImei())
                .serialNumber(request.getSerialNumber())
                .accessories(request.getAccessories())
                .issueDescription(request.getIssueDescription())
                .issueAudioUrl(request.getIssueAudioUrl())
                .estimatedPrice(request.getEstimatedPrice())
                .deviceDisplayName(request.getDeviceDisplayName())
                .deviceImageUrl(request.getDeviceImageUrl())
                .repairServicesSummary(request.getRepairServicesSummary())
                .priceItemsJson(request.getPriceItemsJson())
                .missingPartsJson(request.getMissingPartsJson())
                .devicePhotosJson(request.getDevicePhotosJson())
                .deviceSecurityType(request.getDeviceSecurityType())
                .deviceSecurityValue(request.getDeviceSecurityValue())
                .customerApproval(request.getCustomerApproval())
                .estimatedReadyAt(request.getEstimatedReadyAt())
                .estimatedDeliveryAt(request.getEstimatedDeliveryAt())
                .trackingId(number.trackingId())
                .partnerId(number.partnerId())
                .bookingNumber(number.bookingNumber())
                .deviceSuffix(number.deviceSuffix())
                .status("CREATED")
                .build();
        // After the builder, not inside it: the payment block is derived from
        // the prices set above (balance, and the guard that refuses an amount
        // over the bill), so it needs the ticket assembled first.
        applyPayment(ticket, request.getPaymentType(), request.getPaymentAmount());
        // saveAndFlush + mirrorOnUpsertInline (REQUIRED propagation, not
        // REQUIRES_NEW) — the booking mirror writes repair_bookings.ticket_id
        // with a FK to tickets.id, and PlatformRepairBooking has only a plain
        // UUID (no @ManyToOne) so Hibernate can't infer insert order. Forcing
        // the ticket INSERT to flush first and keeping the mirror in the same
        // transaction means the FK passes.
        ticket = ticketRepository.saveAndFlush(ticket);
        customerOrderMirrorService.mirrorOnUpsertInline(ticket);
        return toResponse(ticket);
    }

    @Transactional
    public TicketResponse update(UUID shopId, UUID id, TicketRequest request) {
        Ticket ticket = ticketRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        // Snapshot the prior approval + estimate state. A re-edit can change
        // priceItemsJson / estimatedPrice (= service re-estimated) or flip
        // customerApproval; both feed the Service History timeline.
        boolean wasApproved = Boolean.TRUE.equals(ticket.getCustomerApproval());
        String oldPriceItemsJson = ticket.getPriceItemsJson();
        BigDecimal oldEstimatedPrice = ticket.getEstimatedPrice();
        Instant oldEstimatedDeliveryAt = ticket.getEstimatedDeliveryAt();
        ticket.setCustomerId(request.getCustomerId());
        if (request.getCustomerName() != null) ticket.setCustomerName(request.getCustomerName());
        if (request.getCustomerPhone() != null) ticket.setCustomerPhone(request.getCustomerPhone());
        ticket.setBrandId(request.getBrandId());
        ticket.setModelId(request.getModelId());
        ticket.setRamOptionId(request.getRamOptionId());
        ticket.setStorageOptionId(request.getStorageOptionId());
        ticket.setColor(request.getColor());
        ticket.setImei(request.getImei());
        // Older app builds omit serialNumber — keep what is stored rather than wipe it.
        if (request.getSerialNumber() != null) ticket.setSerialNumber(request.getSerialNumber());
        if (request.getAccessories() != null) ticket.setAccessories(request.getAccessories());
        ticket.setIssueDescription(request.getIssueDescription());
        ticket.setEstimatedPrice(request.getEstimatedPrice());
        ticket.setDeviceDisplayName(request.getDeviceDisplayName());
        ticket.setDeviceImageUrl(request.getDeviceImageUrl());
        ticket.setRepairServicesSummary(request.getRepairServicesSummary());
        ticket.setPriceItemsJson(request.getPriceItemsJson());
        ticket.setMissingPartsJson(request.getMissingPartsJson());
        ticket.setDevicePhotosJson(request.getDevicePhotosJson());
        ticket.setDeviceSecurityType(request.getDeviceSecurityType());
        ticket.setDeviceSecurityValue(request.getDeviceSecurityValue());
        // The app says which action saved this (TicketRequest.reEstimate). A plain
        // edit (false) must never move the workflow: no re-quote, no status change,
        // and an approval it doesn't mention stays as it was.
        Boolean reEstimateFlag = request.getReEstimate();
        boolean plainEdit = Boolean.FALSE.equals(reEstimateFlag);
        if (!(plainEdit && request.getCustomerApproval() == null)) {
            ticket.setCustomerApproval(request.getCustomerApproval());
        }
        ticket.setEstimatedReadyAt(request.getEstimatedReadyAt());
        ticket.setEstimatedDeliveryAt(request.getEstimatedDeliveryAt());
        // Last, after setEstimatedPrice: a re-estimate changes what the payment
        // is measured against, so the balance this writes has to be computed
        // from the NEW price, not the one the ticket carried in.
        applyPayment(ticket, request.getPaymentType(), request.getPaymentAmount());
        // Re-edit semantics: when the shop edits a ticket the customer had
        // already approved AND the edit didn't carry a fresh approval, treat
        // it as a re-booking — clear the prior approval and refresh the
        // Waiting-for-Approval timeline row so the customer is re-prompted.
        boolean reEditAfterApproval = !plainEdit && wasApproved
                && !Boolean.TRUE.equals(request.getCustomerApproval());
        if (reEditAfterApproval) {
            ticket.setCustomerApproval(null);
        }
        ticket = ticketRepository.save(ticket);
        customerOrderMirrorService.mirrorOnUpsert(ticket);
        if (reEditAfterApproval) {
            emitOrUpdateBookingEvent(ticket.getId(),
                    "WAITING_FOR_CUSTOMER_APPROVAL",
                    "Booking re-edited — waiting for customer approval",
                    "SHOP");
        }
        // Re-estimate: shop added/removed a service or changed the price on an
        // existing booking. Light up the "Service Re-estimated" rail row so the
        // customer + owner timelines reflect the change. emitOrUpdate refreshes
        // the timestamp so multiple re-edits surface as "latest re-estimate".
        // Compared by meaning, not as raw text: a plain edit (customer, colour,
        // IMEI) re-sends the same services in a freshly serialised string, and
        // a byte-level compare flagged that as a re-estimate (GTS-025).
        boolean priceItemsChanged = !samePriceItems(oldPriceItemsJson, ticket.getPriceItemsJson());
        boolean estimateChanged = (oldEstimatedPrice == null ? BigDecimal.ZERO : oldEstimatedPrice)
                .compareTo(ticket.getEstimatedPrice() == null ? BigDecimal.ZERO : ticket.getEstimatedPrice()) != 0;
        // Only the Re-Estimate action re-quotes. Before this flag the change alone
        // decided, so an Edit that touched the device, services or amount walked
        // an IN_REPAIR / APPROVED booking back to QUOTED ("Re-Estimated").
        if (isReEstimate(reEstimateFlag, priceItemsChanged || estimateChanged)) {
            refuseReEstimateAfterReturn(ticket.getId());
            emitOrUpdateBookingEvent(ticket.getId(),
                    "RE_ESTIMATED_CONFIRMED",
                    NOTE_RE_ESTIMATED,
                    "SHOP");
            // Keep the booking's first delivery promise (Service Schedule);
            // estimatedDeliveryAt now carries the revised one.
            if (ticket.getOriginalEstimatedDeliveryAt() == null && oldEstimatedDeliveryAt != null) {
                ticket.setOriginalEstimatedDeliveryAt(oldEstimatedDeliveryAt);
                ticket = ticketRepository.save(ticket);
            }
            // The event alone only feeds the Service History rail. The owner's
            // Re-Estimated list and the card badge both read ticket.status, so
            // without this the booking stayed "Service Accepted" (= CREATED) and
            // the Re-Estimated tile counted 0 while the timeline said otherwise.
            markReEstimated(ticket);
        }
        // Shop-side approval flip (owner ticked "Customer Repair Approval" in
        // the edit flow). Customer-side approval comes in through
        // RepairBookingController#customerApproval; both land on the same
        // handler so the pair of rows it writes is identical either way.
        //
        // The !wasApproved guard means this only runs on a real false -> true
        // transition, so an ordinary re-save can't drag the timestamp forward.
        if (!wasApproved && Boolean.TRUE.equals(ticket.getCustomerApproval())) {
            onCustomerApproved(ticket, "SHOP");
        }
        return toResponse(ticket);
    }

    /**
     * Re-Estimate: ADDITIONAL issues found during repair, priced on top of the
     * current estimate. The original line items and their prices are kept as
     * they are (new items are appended; one already in the estimate is skipped,
     * so a repeated submit adds nothing twice). Saves the reason (+ photos) as
     * the "Service Re-estimated" history step and as its own repair note, moves
     * the ticket to QUOTED through markReEstimated's existing rules, and either
     * records the customer's approval (given at the counter) or leaves the
     * revised estimate awaiting approval. Payment records are not touched (only
     * the balance follows the new total). Technician is not touched.
     */
    @Transactional
    public TicketResponse reEstimate(UUID shopId, UUID id, com.repairshop.saas.ticket.dto.ReEstimateRequest req) {
        return reEstimate(shopId, id, req, null);
    }

    /** As above, recording who submitted it on the re-estimate revision (ReEstimateWorkflowService). */
    @Transactional
    public TicketResponse reEstimate(UUID shopId, UUID id, com.repairshop.saas.ticket.dto.ReEstimateRequest req, UUID submittedBy) {
        Ticket ticket = ticketRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        if (hasCurrentInvoice(ticket.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice already generated — delete the invoice before re-estimating");
        }
        refuseReEstimateAfterReturn(ticket.getId());
        String reason = req == null || req.getReason() == null ? "" : req.getReason().trim();
        if (reason.length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add the reason for the extra work");
        }
        // Re-estimate workflow: 409 while another revision awaits approval; snapshot the price + scope
        // a rejection restores; link the technician's open request (req.requestId).
        ReEstimateWorkflowService.PendingRevision revision = reEstimateWorkflow.openPartnerRevision(ticket, req.getRequestId());
        java.util.List<java.util.Map<String, Object>> items = parsePriceItemList(ticket.getPriceItemsJson());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (java.util.Map<String, Object> it : items) keys.add(priceItemIdentity(it));

        // Original items the revision drops (removed in the app). The snapshot
        // taken by openPartnerRevision restores them on rejection.
        java.util.Set<String> removeKeys = new java.util.HashSet<>();
        if (req.getRemovedItemIds() != null) {
            for (String r : req.getRemovedItemIds()) {
                if (r != null && !r.isBlank()) removeKeys.add(r.trim().toLowerCase());
            }
        }
        java.util.List<java.util.Map<String, Object>> removed = new ArrayList<>();
        BigDecimal removedAmount = BigDecimal.ZERO;
        if (!removeKeys.isEmpty()) {
            java.util.Iterator<java.util.Map<String, Object>> it = items.iterator();
            while (it.hasNext()) {
                java.util.Map<String, Object> m = it.next();
                String k = priceItemIdentity(m);
                if (!removeKeys.contains(k)) continue;
                it.remove();
                keys.remove(k);
                removed.add(m);
                try { removedAmount = removedAmount.add(new BigDecimal(String.valueOf(m.getOrDefault("amount", "0")))); }
                catch (NumberFormatException ignored) { /* non-numeric amount counts as 0 */ }
            }
        }

        java.util.List<java.util.Map<String, Object>> added = new ArrayList<>();
        BigDecimal extra = BigDecimal.ZERO;
        for (com.repairshop.saas.ticket.dto.ReEstimateRequest.Item in : req.getItems() == null ? java.util.List.<com.repairshop.saas.ticket.dto.ReEstimateRequest.Item>of() : req.getItems()) {
            if (in == null || in.getLabel() == null || in.getLabel().isBlank()) continue;
            if (in.getAmount() == null || in.getAmount().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a price (0 or more) for " + in.getLabel().trim());
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", in.getId() == null || in.getId().isBlank() ? null : in.getId().trim());
            row.put("code", in.getCode());
            row.put("label", in.getLabel().trim());
            row.put("amount", in.getAmount());
            row.put("warranty", in.getWarranty());
            if (!keys.add(priceItemIdentity(row))) continue; // already in the estimate
            added.add(row);
            extra = extra.add(in.getAmount());
        }
        if (added.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "These issues are already in the estimate");
        }

        BigDecimal oldTotal = ticket.getEstimatedPrice() != null ? ticket.getEstimatedPrice()
                : items.stream().map(m -> {
                    try { return new BigDecimal(String.valueOf(m.getOrDefault("amount", "0"))); }
                    catch (NumberFormatException e) { return BigDecimal.ZERO; }
                }).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal newTotal = oldTotal.add(extra).subtract(removedAmount).max(BigDecimal.ZERO);
        items.addAll(added);
        try {
            ticket.setPriceItemsJson(PRICE_ITEMS_MAPPER.writeValueAsString(items));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not save the re-estimate", e);
        }
        ticket.setEstimatedPrice(newTotal);
        String addedLabels = added.stream().map(m -> String.valueOf(m.get("label"))).collect(java.util.stream.Collectors.joining(", "));
        String removedLabels = removed.stream().map(m -> String.valueOf(m.get("label"))).collect(java.util.stream.Collectors.joining(", "));
        if (removed.isEmpty()) {
            String summary = ticket.getRepairServicesSummary();
            ticket.setRepairServicesSummary(summary == null || summary.isBlank() ? addedLabels : summary + ", " + addedLabels);
        } else {
            // Rebuild from the revised items so a dropped issue leaves the summary too.
            ticket.setRepairServicesSummary(items.stream().map(m -> String.valueOf(m.get("label")))
                    .collect(java.util.stream.Collectors.joining(", ")));
        }

        // Keep the first delivery promise; the proposed one becomes the estimate.
        Instant oldDelivery = ticket.getEstimatedDeliveryAt();
        if (ticket.getOriginalEstimatedDeliveryAt() == null && oldDelivery != null) {
            ticket.setOriginalEstimatedDeliveryAt(oldDelivery);
        }
        if (req.getEstimatedDeliveryAt() != null) ticket.setEstimatedDeliveryAt(req.getEstimatedDeliveryAt());

        boolean wasApproved = Boolean.TRUE.equals(ticket.getCustomerApproval());
        boolean approvedNow = Boolean.TRUE.equals(req.getCustomerApproved());
        // A revised price needs a fresh approval: either given now, or awaited.
        ticket.setCustomerApproval(approvedNow ? Boolean.TRUE : null);
        recomputeBalance(ticket);
        ticket = ticketRepository.save(ticket);
        customerOrderMirrorService.mirrorOnUpsertInline(ticket);

        String note = "Re-estimated: ₹" + oldTotal.stripTrailingZeros().toPlainString()
                + " + ₹" + extra.stripTrailingZeros().toPlainString()
                + " = ₹" + newTotal.stripTrailingZeros().toPlainString()
                + " · " + addedLabels
                + (removed.isEmpty() ? "" : " · Removed: " + removedLabels + " (−₹" + removedAmount.stripTrailingZeros().toPlainString() + ")")
                + " · Reason: " + reason
                + (approvedNow ? " · Approved by customer" : " · Awaiting customer approval");
        String images = req.getImagesJson() == null || req.getImagesJson().isBlank() ? null : req.getImagesJson();
        emitOrUpdateBookingEvent(ticket.getId(), KEY_RE_ESTIMATED, note, "SHOP", null, images);
        // Permanent per-re-estimate record (the history step above shows the latest one).
        repairNoteRepository.save(RepairNote.builder()
                .ticketId(ticket.getId())
                .note(note)
                .isInternal(true)
                .imagesJson(images)
                .build());
        reEstimateWorkflow.recordPartnerSubmission(revision, ticket, submittedBy, added, extra, newTotal, reason, images);
        markReEstimated(ticket);
        if (approvedNow) {
            onCustomerApproved(ticket, "SHOP");
        } else if (wasApproved) {
            emitOrUpdateBookingEvent(ticket.getId(), "WAITING_FOR_CUSTOMER_APPROVAL",
                    "Re-estimate — waiting for customer approval", "SHOP");
        }
        return toResponse(ticket);
    }

    private java.util.List<java.util.Map<String, Object>> parsePriceItemList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            java.util.List<java.util.Map<String, Object>> list = PRICE_ITEMS_MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Same identity samePriceItems uses: id, else code, else label (lower-case).
    private static String priceItemIdentity(java.util.Map<String, Object> m) {
        for (String k : new String[] { "id", "serviceId", "code", "serviceCode", "label", "serviceName" }) {
            Object v = m.get(k);
            // OTHER / SERVICE_CHARGE are shared codes, not identities: every
            // "Service Charge – <issue>" line has code SERVICE_CHARGE, so keying
            // on it made a 2nd charge line look "already in the estimate".
            if (v != null && !String.valueOf(v).isBlank() && !"OTHER".equalsIgnoreCase(String.valueOf(v))
                    && !"SERVICE_CHARGE".equalsIgnoreCase(String.valueOf(v))) {
                return String.valueOf(v).trim().toLowerCase();
            }
        }
        return "";
    }

    /**
     * Price Revised: change the price of, or cancel, line items ALREADY on the
     * ticket. Each change carries the item's identity and price as loaded; if
     * either no longer matches, nothing is saved (409) — so a concurrent edit is
     * never overwritten and a repeated submit can't apply twice. Not allowed
     * once an invoice exists. Status and technician are never touched (no
     * Re-Estimated). A price increase or a cancellation needs fresh customer
     * approval (given at the counter, or awaited); a pure reduction keeps the
     * current approval. Audit: a "Booking Edited" history step + a permanent
     * repair note with old → new per service, the difference and the reason.
     */
    @Transactional
    public TicketResponse revisePrices(UUID shopId, UUID id, com.repairshop.saas.ticket.dto.PriceRevisionRequest req) {
        Ticket ticket = ticketRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        if (hasCurrentInvoice(ticket.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice already generated — revise the price on the invoice");
        }
        reEstimateWorkflow.assertNoPendingApproval(ticket);
        String reason = req == null || req.getReason() == null ? "" : req.getReason().trim();
        String notes = req == null || req.getNotes() == null ? "" : req.getNotes().trim();
        if (reason.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select the reason for the revision");
        if ("other".equalsIgnoreCase(reason) && notes.length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add a note for the revision");
        }
        java.util.List<com.repairshop.saas.ticket.dto.PriceRevisionRequest.Change> changes =
                req.getChanges() == null ? java.util.List.of() : req.getChanges();
        if (changes.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to revise");

        java.util.List<java.util.Map<String, Object>> items = parsePriceItemList(ticket.getPriceItemsJson());
        java.util.Set<Integer> cancelled = new java.util.HashSet<>();
        java.util.List<String> lines = new ArrayList<>();
        BigDecimal delta = BigDecimal.ZERO;
        boolean increase = false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (com.repairshop.saas.ticket.dto.PriceRevisionRequest.Change c : changes) {
            Integer i = c == null ? null : c.getIndex();
            if (i == null || i < 0 || i >= items.size() || !seen.add(i)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The services changed — reload and try again");
            }
            java.util.Map<String, Object> item = items.get(i);
            BigDecimal current = amountOf(item);
            String identity = c.getIdentity() == null ? "" : c.getIdentity().trim().toLowerCase();
            if (!identity.equals(priceItemIdentity(item))
                    || c.getOldAmount() == null || c.getOldAmount().compareTo(current) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The prices changed — reload and try again");
            }
            String label = String.valueOf(item.getOrDefault("label", item.getOrDefault("serviceName", "Service")));
            if (Boolean.TRUE.equals(c.getCancel())) {
                cancelled.add(i);
                delta = delta.subtract(current);
                lines.add(label + " cancelled (₹" + plain(current) + ")");
                continue;
            }
            if (c.getNewAmount() == null || c.getNewAmount().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a new price (0 or more) for " + label);
            }
            if (c.getNewAmount().compareTo(current) == 0) continue;
            if (c.getNewAmount().compareTo(current) > 0) increase = true;
            delta = delta.add(c.getNewAmount().subtract(current));
            item.put("amount", c.getNewAmount());
            lines.add(label + " ₹" + plain(current) + " → ₹" + plain(c.getNewAmount()));
        }
        if (lines.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No price was changed");
        if (cancelled.size() >= items.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one service must stay on the booking");
        }

        BigDecimal oldTotal = ticket.getEstimatedPrice() != null ? ticket.getEstimatedPrice()
                : items.stream().map(TicketService::amountOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        java.util.List<java.util.Map<String, Object>> active = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (!cancelled.contains(i)) active.add(items.get(i));
        BigDecimal newTotal = oldTotal.add(delta);
        if (newTotal.signum() < 0) newTotal = BigDecimal.ZERO;
        try {
            ticket.setPriceItemsJson(PRICE_ITEMS_MAPPER.writeValueAsString(active));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not save the revision", e);
        }
        ticket.setEstimatedPrice(newTotal);
        ticket.setRepairServicesSummary(active.stream()
                .map(m -> String.valueOf(m.getOrDefault("label", m.getOrDefault("serviceName", ""))))
                .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(", ")));

        boolean wasApproved = Boolean.TRUE.equals(ticket.getCustomerApproval());
        boolean approvedNow = Boolean.TRUE.equals(req.getCustomerApproved());
        boolean needsApproval = increase || !cancelled.isEmpty();
        if (needsApproval) ticket.setCustomerApproval(approvedNow ? Boolean.TRUE : null);
        recomputeBalance(ticket);
        ticket = ticketRepository.save(ticket);
        customerOrderMirrorService.mirrorOnUpsert(ticket);

        String sign = delta.signum() < 0 ? "−₹" + plain(delta.abs()) : "+₹" + plain(delta);
        String note = "Price Revised: " + String.join(" · ", lines)
                + " · Total ₹" + plain(oldTotal) + " → ₹" + plain(newTotal) + " (" + sign + ")"
                + " · Reason: " + reason + (notes.isEmpty() ? "" : " — " + notes)
                + (needsApproval ? (approvedNow ? " · Approved by customer" : " · Awaiting customer approval") : "");
        emitOrUpdateBookingEvent(ticket.getId(), "BOOKING_EDITED", note, "SHOP");
        repairNoteRepository.save(RepairNote.builder().ticketId(ticket.getId()).note(note).isInternal(true).build());
        if (needsApproval) {
            if (approvedNow) onCustomerApproved(ticket, "SHOP");
            else if (wasApproved) emitOrUpdateBookingEvent(ticket.getId(), "WAITING_FOR_CUSTOMER_APPROVAL",
                    "Price revised — waiting for customer approval", "SHOP");
        }
        return toResponse(ticket);
    }

    private static BigDecimal amountOf(java.util.Map<String, Object> m) {
        Object v = m.containsKey("amount") ? m.get("amount") : m.get("price");
        try {
            return v == null || String.valueOf(v).isBlank() ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    /** {@code actor} is OWNER when the shop owner (or a SHOP login) set the
     *  status from their own app, so the step it implies is not announced back
     *  to them in their feed or as a push; null otherwise. */
    @Transactional
    public void updateStatus(UUID shopId, UUID id, String status, String actor) {
        Ticket ticket = ticketRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        reEstimateWorkflow.onProgressStep(ticket, status);
        ticket.setStatus(status);
        ticket = ticketRepository.save(ticket);
        customerOrderMirrorService.mirrorOnUpsert(ticket, actor);
        emitStepEventsForTicketStatus(ticket.getId(), status, actor);
    }

    /**
     * Re-Service: a delivered device is back, so the SAME ticket reopens for a
     * new repair. Status returns to CREATED and the per-repair state (technician,
     * acceptance, final price, approval) is cleared; the app's re-service wizard
     * then PUTs the new issue / services / payment. A RE_SERVICE marker row
     * starts a fresh step cycle: the first repair's rows stay in the table but
     * the shop's timeline and step checks only see the new cycle
     * (findCurrentCycle).
     */
    @Transactional
    public TicketResponse reopen(UUID shopId, UUID id) {
        Ticket ticket = ticketRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        if (!"DELIVERED".equalsIgnoreCase(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a delivered booking can be re-serviced.");
        }
        ticket.setStatus("CREATED");
        ticket.setAssignedTechnicianId(null);
        ticket.setTechnicianAcceptedAt(null);
        ticket.setFinalPrice(null);
        ticket.setCustomerApproval(null);
        // The new repair records its own first delivery promise (re-estimate keeps it).
        ticket.setOriginalEstimatedDeliveryAt(null);
        ticket = ticketRepository.save(ticket);
        customerOrderMirrorService.mirrorOnUpsert(ticket);
        platformRepairBookingRepository.findByTicketId(ticket.getId()).ifPresent(booking ->
                platformRepairBookingEventRepository.save(PlatformRepairBookingEvent.builder()
                        .bookingId(booking.getId())
                        .status(PlatformRepairBookingEventRepository.RE_SERVICE)
                        .note("Re-Service — booking reopened")
                        .actor("SHOP")
                        .build()));
        return toResponse(ticket);
    }

    /**
     * Lightweight PATCH that currently supports assigning technicians and a few optional fields
     * from a generic map payload. This is primarily used by the mobile "Assign Technician" flow.
     */
    @Transactional
    public TicketResponse patch(UUID shopId, UUID id, Map<String, Object> body) {
        return patch(shopId, id, body, null);
    }

    /** {@code actor} as in {@link #updateStatus}: OWNER keeps the steps this
     *  patch implies off the owner's own feed and phone. */
    @Transactional
    public TicketResponse patch(UUID shopId, UUID id, Map<String, Object> body, String actor) {
        return patch(shopId, id, body, actor, null);
    }

    /** {@code callerUserId}: who sent the patch, so a technician is never
     *  pushed about an assignment they made themselves. */
    @Transactional
    public TicketResponse patch(UUID shopId, UUID id, Map<String, Object> body, String actor,
                                UUID callerUserId) {
        boolean assigning = body.containsKey("assignedTechnicianId");
        // Assignment changes take a row lock so two concurrent (re)assigns
        // can't both pass the eligibility / expected-technician checks.
        Ticket ticket = (assigning ? ticketRepository.lockByShopIdAndId(shopId, id)
                        : ticketRepository.findByShopIdAndId(shopId, id))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));

        UUID technicianBeforePatch = ticket.getAssignedTechnicianId();
        boolean wasApprovedBeforePatch = Boolean.TRUE.equals(ticket.getCustomerApproval());
        Technician assignedTechAfterPatch = null;

        // EditBookingScreen (mobile) PATCHes a small set of free-form fields.
        // Honor only the ones backed by a column; quietly ignore unknown keys.
        if (body.containsKey("imei")) {
            Object raw = body.get("imei");
            // Blank clears the field and skips both checks — the gate only ever
            // sends a value, but an edit screen must still be able to wipe a
            // wrong number without tripping format validation on "".
            String imei = raw == null ? null : normalizeImei(String.valueOf(raw));
            if (imei != null) assertImeiAvailable(shopId, id, imei);
            ticket.setImei(imei);
        }
        if (body.containsKey("issueDescription")) {
            Object raw = body.get("issueDescription");
            ticket.setIssueDescription(raw == null ? null : String.valueOf(raw));
        }
        // Edit Booking (booking details only). Status, prices, technician and the
        // delivery schedule are NOT in this list — those have their own actions.
        patchText(body, "customerName", ticket::setCustomerName);
        patchText(body, "customerPhone", ticket::setCustomerPhone);
        patchText(body, "customerAddress", ticket::setCustomerAddress);
        patchUuid(body, "brandId", ticket::setBrandId);
        patchUuid(body, "modelId", ticket::setModelId);
        patchUuid(body, "ramOptionId", ticket::setRamOptionId);
        patchUuid(body, "storageOptionId", ticket::setStorageOptionId);
        patchText(body, "color", ticket::setColor);
        patchText(body, "deviceDisplayName", ticket::setDeviceDisplayName);
        patchText(body, "deviceImageUrl", ticket::setDeviceImageUrl);
        patchText(body, "issueAudioUrl", ticket::setIssueAudioUrl);
        patchText(body, "devicePhotosJson", ticket::setDevicePhotosJson);
        patchText(body, "missingPartsJson", ticket::setMissingPartsJson);
        patchText(body, "deviceSecurityType", ticket::setDeviceSecurityType);
        patchText(body, "deviceSecurityValue", ticket::setDeviceSecurityValue);
        if (body.containsKey("estimatedDeliveryAt")) {
            Object raw = body.get("estimatedDeliveryAt");
            ticket.setEstimatedDeliveryAt(parseInstantOrNull(raw));
        }
        if (body.containsKey("estimatedReadyAt")) {
            Object raw = body.get("estimatedReadyAt");
            ticket.setEstimatedReadyAt(parseInstantOrNull(raw));
        }
        // Accept both shapes the mobile screens send: customerApproved (Edit
        // Booking checkbox) and customerApproval (PUT-aligned name).
        if (body.containsKey("customerApproved") || body.containsKey("customerApproval")) {
            Object raw = body.containsKey("customerApproved")
                    ? body.get("customerApproved")
                    : body.get("customerApproval");
            ticket.setCustomerApproval(parseBooleanOrNull(raw));
        }
        // Payment mode + amount. Either key alone is honored by re-using what
        // the ticket already holds for the other — a PATCH that raises the
        // amount shouldn't have to restate the mode to keep it.
        boolean payingPatch = body.containsKey("paymentType") || body.containsKey("paymentAmount");
        if (payingPatch) {
            String rawType = body.containsKey("paymentType")
                    ? (body.get("paymentType") == null ? null : String.valueOf(body.get("paymentType")))
                    : ticket.getPaymentType();
            BigDecimal rawAmount = body.containsKey("paymentAmount")
                    ? parseAmountOrNull(body.get("paymentAmount"))
                    : ticket.getPaymentAmount();
            applyPayment(ticket, rawType, rawAmount);
        }
        if (assigning) {
            Object raw = body.get("assignedTechnicianId");
            // Optional optimistic check from the app: the technician it showed
            // when the owner opened the picker. A stale screen gets a 409.
            if (body.containsKey("expectedTechnicianId")) {
                Object exp = body.get("expectedTechnicianId");
                UUID expected = exp == null || String.valueOf(exp).isBlank() ? null : UUID.fromString(String.valueOf(exp));
                if (!java.util.Objects.equals(expected, technicianBeforePatch)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This booking's technician was changed meanwhile - refresh and try again.");
                }
            }
            if (raw == null || String.valueOf(raw).isBlank()) {
                if (technicianBeforePatch != null) assertAssignable(ticket);
                ticket.setAssignedTechnicianId(null);
            } else {
                UUID value = UUID.fromString(String.valueOf(raw));
                // Resolve to technician id: auth returns user id, ticket stores technicians.id
                Technician tech = technicianRepository.findByShopIdAndId(shopId, value)
                        .or(() -> technicianRepository.findByShopIdAndUserId(shopId, value))
                        .orElse(null);
                if (tech == null && value.equals(callerUserId)) {
                    // The owner assigning to themselves goes through the
                    // Owner as Technician row, never an ad-hoc employee row.
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Turn on Owner as Technician to assign jobs to yourself.");
                }
                if (tech != null && !tech.getId().equals(technicianBeforePatch)) {
                    assertAssignable(ticket);
                    if (tech.isOwner() && !tech.isOwnerTechEnabled()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Owner as Technician is off - turn it on to assign jobs to yourself.");
                    }
                    if (!tech.isOwner() && !tech.isAvailable()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "This technician is inactive.");
                    }
                }
                if (tech == null) {
                    // Auth may return user id with no technicians row; create one so assignment works
                    tech = technicianRepository.save(Technician.builder()
                            .shopId(shopId)
                            .userId(value)
                            .name("Technician")
                            .build());
                }
                ticket.setAssignedTechnicianId(tech.getId());
                assignedTechAfterPatch = tech;
            }
            // A (re)assignment to a DIFFERENT technician clears the prior
            // acceptance: the new technician must tap Accept themselves before
            // continuing. Re-sending the same technician is a no-op. Allowed
            // only before Out for Delivery (assertAssignable).
            if (!java.util.Objects.equals(technicianBeforePatch, ticket.getAssignedTechnicianId())) {
                assertAssignable(ticket);
                ticket.setTechnicianAcceptedAt(null);
            }
        }

        String statusBeforePatch = ticket.getStatus();
        if (body.containsKey("status")) {
            Object raw = body.get("status");
            ticket.setStatus(raw != null ? String.valueOf(raw) : ticket.getStatus());
        }

        // The technician detail screen PATCHes this whenever it adds a new
        // "Your Side Device Image". The screen sends the full JSON array each
        // time, so we just overwrite — no merge logic on the server.
        boolean photosBecameNonEmpty = false;
        if (body.containsKey("technicianPhotosJson")) {
            Object raw = body.get("technicianPhotosJson");
            String newValue = raw != null ? String.valueOf(raw) : null;
            // "Became non-empty" = the PATCH carries at least one URL. We don't
            // care about the prior value here; the booking-side event lookup
            // below dedupes so a re-submit with the same photos won't double-emit.
            photosBecameNonEmpty = hasAtLeastOneUrl(newValue);
            ticket.setTechnicianPhotosJson(newValue);
        }

        // Unconditional, not only on a paying patch: it is also what backfills
        // the balance and PENDING status on a ticket booked before migration 85
        // that has never been through a payment-aware write.
        recomputeBalance(ticket);
        ticket = ticketRepository.save(ticket);
        customerOrderMirrorService.mirrorOnUpsert(ticket, actor);

        // After the mirror has ensured the booking row exists, drop a
        // TECH_UPLOADED_IMAGES event so the customer/owner Service History
        // screens light up the "Technician uploaded device images" step. This
        // step key matches serviceHistoryPhases.js in repair-shop-mobile and
        // is rendered the same way on the owner side.
        if (photosBecameNonEmpty) {
            emitBookingEvent(ticket.getId(), "TECHNICIAN_UPLOADED_DEVICE_IMAGES",
                    "Technician Uploaded Device Images", "TECHNICIAN", actor);
        }
        // If the patch carried a status change, emit the matching step event(s)
        // (work-started / waiting-for-approval). Use the prior status as a guard
        // so a no-op repaint doesn't churn events.
        if (ticket.getStatus() != null && !ticket.getStatus().equalsIgnoreCase(statusBeforePatch)) {
            emitStepEventsForTicketStatus(ticket.getId(), ticket.getStatus(), actor);
        }
        // Technician assignment changed — light up the customer/owner timeline
        // rows for it.
        //
        // The status keys here MUST be the ones the timelines render
        // (SHOP_BOOKING_STATUS_OPTIONS in serviceHistoryPhases.js:
        // ASSIGNED_TO_TECHNICIAN / REASSIGNED_TO_TECHNICIAN). They used to be
        // TECHNICIAN_ASSIGNED / TECHNICIAN_REASSIGNED, which no rail has a row
        // for: the events were written and then rendered nowhere, so a booking
        // with a technician on it showed a grey "Assigned to Technician" —
        // and a re-assignment showed nothing at all, since the mirror only
        // emits the first assign.
        //
        // Assign is emit-if-absent (the first one is the record); re-assign is
        // emit-or-update, so the row names whoever holds the booking NOW rather
        // than freezing on the first replacement.
        UUID technicianAfterPatch = ticket.getAssignedTechnicianId();
        boolean technicianChanged = !java.util.Objects.equals(technicianBeforePatch, technicianAfterPatch);
        if (technicianChanged && technicianAfterPatch != null) {
            String techName = assignedTechAfterPatch != null && assignedTechAfterPatch.getName() != null
                    ? assignedTechAfterPatch.getName()
                    : "Technician";
            if (technicianBeforePatch == null) {
                emitBookingEvent(ticket.getId(), "ASSIGNED_TO_TECHNICIAN",
                        "Assigned to " + techName, "SHOP");
            } else {
                // One row per re-assignment (history with real timestamps),
                // not one row overwritten each time.
                String oldName = technicianRepository.findById(technicianBeforePatch)
                        .map(Technician::getName).filter(n -> !n.isBlank()).orElse("Technician");
                Object reason = body.get("reassignReason");
                String why = reason == null || String.valueOf(reason).isBlank() ? ""
                        : " · " + String.valueOf(reason).trim();
                appendBookingEvent(ticket.getId(), "REASSIGNED_TO_TECHNICIAN",
                        "Re-assigned from " + oldName + " to " + techName + why, "SHOP");
            }
            // The patch above cleared technician_accepted_at, so the booking is
            // waiting on the new technician's tap — re-stamped rather than
            // emitted once, or a re-assignment would carry the first
            // assignment's "awaiting" timestamp.
            emitOrUpdateBookingEvent(ticket.getId(), "AWAITING_TECHNICIAN_ACCEPTANCE",
                    "Awaiting Technician Acceptance", "SHOP");
            pushJobAssigned(ticket, assignedTechAfterPatch, callerUserId);
        }
        // Owner ticked "Customer Repair Approval" in the Edit Booking screen —
        // same handler as update() and as the customer's own Approve tap, so the
        // approval and the repair-work row it opens are written identically
        // whichever screen triggered them. The !wasApprovedBeforePatch guard
        // keeps a no-op re-save with the flag already true from touching them.
        if (!wasApprovedBeforePatch && Boolean.TRUE.equals(ticket.getCustomerApproval())) {
            onCustomerApproved(ticket, "SHOP");
        }
        return toResponse(ticket);
    }

    // Employee app: the newly (re)assigned technician hears about the job on
    // their phone — technicians.user_id is their auth user id. Skipped when
    // the technician assigned it to themselves. Sent after commit.
    private void pushJobAssigned(Ticket ticket, Technician tech, UUID callerUserId) {
        if (tech == null || tech.getUserId() == null || tech.getUserId().equals(callerUserId)) return;
        String device = ticket.getDeviceDisplayName() != null && !ticket.getDeviceDisplayName().isBlank()
                ? ticket.getDeviceDisplayName() : "Device";
        String body = ticket.getTrackingId() != null ? device + " · " + ticket.getTrackingId() : device;
        Map<String, Object> data = new HashMap<>();
        data.put("kind", "JOB_ASSIGNED");
        data.put("ticketId", ticket.getId());
        data.put("trackingId", ticket.getTrackingId());
        pushService.pushToUser(com.repairshop.saas.common.notify.PushApp.EMPLOYEE, tech.getUserId(),
                "New job assigned", body, data);
    }

    private static java.time.Instant parseInstantOrNull(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try { return java.time.Instant.parse(s); } catch (Exception ignored) {}
        try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
        return null;
    }

    /** PATCH a text column when the key is present; blank clears it. */
    private static void patchText(Map<String, Object> body, String key, java.util.function.Consumer<String> set) {
        if (!body.containsKey(key)) return;
        Object raw = body.get(key);
        String v = raw == null ? null : String.valueOf(raw).trim();
        set.accept(v == null || v.isEmpty() ? null : v);
    }

    /** PATCH a UUID column when the key is present; blank clears it. */
    private static void patchUuid(Map<String, Object> body, String key, java.util.function.Consumer<UUID> set) {
        if (!body.containsKey(key)) return;
        Object raw = body.get(key);
        set.accept(raw == null || String.valueOf(raw).isBlank() ? null : UUID.fromString(String.valueOf(raw)));
    }

    private static Boolean parseBooleanOrNull(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Payment
    //
    // Four of the five payment columns are written only here. The request
    // carries a mode and an amount; status, balance and the paid-at stamp are
    // derived, so a client cannot report money as PAID without an amount or
    // back-date when it was taken.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * ADVANCE | FULL, or null for anything else. Normalizing here rather than
     * bean-validating the request keeps the CHECK constraint added by migration
     * 85 unreachable from user input: an unknown mode degrades to "nothing
     * collected" instead of 500-ing the whole booking submit.
     */
    private static String normalizePaymentType(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase();
        return ("ADVANCE".equals(v) || "FULL".equals(v)) ? v : null;
    }

    /**
     * The total a payment is measured against: what the repair ended up costing
     * once that is known, otherwise the estimate. Both may be absent on a
     * half-filled ticket, hence ZERO rather than null — a payment against no
     * priced work at all is what the over-payment guard should reject.
     */
    private static BigDecimal applicableTotal(Ticket t) {
        if (t.getFinalPrice() != null) return t.getFinalPrice();
        if (t.getEstimatedPrice() != null) return t.getEstimatedPrice();
        return BigDecimal.ZERO;
    }

    /**
     * Writes the whole payment block from a mode + amount, and is the ONLY
     * place that does. Call it after the ticket's prices are set, since the
     * balance and the over-payment guard both read them.
     *
     * An amount is only meaningful next to a mode — a bare number with no
     * ADVANCE/FULL beside it can't be read as either a deposit or a settled
     * bill — so either one missing clears the block to "nothing collected".
     * Negatives and zero clear it too; money out of the shop is a Cash Book
     * entry, not a booking payment.
     *
     * paymentPaidAt is refreshed only when the type or amount actually changes.
     * An unrelated edit (a re-estimate, an IMEI correction) must not re-date a
     * receipt for cash that was taken days earlier.
     */
    private void applyPayment(Ticket ticket, String rawType, BigDecimal rawAmount) {
        String type = normalizePaymentType(rawType);
        BigDecimal amount = (rawAmount == null || rawAmount.signum() <= 0) ? null : rawAmount;

        if (type == null || amount == null) {
            ticket.setPaymentType(null);
            ticket.setPaymentAmount(null);
            ticket.setPaymentPaidAt(null);
            ticket.setPaymentStatus("PENDING");
            recomputeBalance(ticket);
            return;
        }

        BigDecimal total = applicableTotal(ticket);
        if (amount.compareTo(total) > 0) {
            // 400 via GlobalExceptionHandler. Refused rather than clamped: a
            // client that sent more than the bill has a number the shop and the
            // customer do not agree on, and silently keeping the smaller one
            // would hide that from both.
            throw new IllegalArgumentException(
                    "paymentAmount " + amount.toPlainString()
                            + " is more than the amount due " + total.toPlainString());
        }

        boolean changed = !type.equals(ticket.getPaymentType())
                || ticket.getPaymentAmount() == null
                || ticket.getPaymentAmount().compareTo(amount) != 0;

        ticket.setPaymentType(type);
        ticket.setPaymentAmount(amount);
        ticket.setPaymentStatus("PAID");
        if (changed || ticket.getPaymentPaidAt() == null) {
            ticket.setPaymentPaidAt(Instant.now());
        }
        recomputeBalance(ticket);
    }

    /**
     * Balance = applicable total − collected, floored at zero. Runs on every
     * path that can move either side of that subtraction, so a re-estimate can
     * never leave the stored balance quoting the old price.
     */
    private void recomputeBalance(Ticket ticket) {
        BigDecimal total = applicableTotal(ticket);
        BigDecimal paid = ticket.getPaymentAmount() == null ? BigDecimal.ZERO : ticket.getPaymentAmount();
        BigDecimal balance = total.subtract(paid);
        ticket.setBalanceAmount(balance.signum() < 0 ? BigDecimal.ZERO : balance);
        if (ticket.getPaymentStatus() == null) {
            ticket.setPaymentStatus(ticket.getPaymentAmount() == null ? "PENDING" : "PAID");
        }
    }

    private static BigDecimal parseAmountOrNull(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal b) return b;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Technician's explicit Accept action. Sets tickets.technician_accepted_at
     * to now() and emits TECHNICIAN_ACCEPTED_SERVICE + TECHNICIAN_WORK_STARTED
     * once. If the ticket was sitting at CREATED (walk-in flow), it also bumps
     * status to IN_DIAGNOSIS so the rest of the lifecycle plays out as before.
     *
     * Authorisation: the JWT user must be the technician currently assigned
     * to this ticket (technicians.user_id → assigned_technician_id).
     * Idempotent — calling again after acceptance is a no-op.
     */
    @Transactional
    public TicketResponse acceptByTechnician(UUID shopId, UUID userId, UUID ticketId) {
        Ticket ticket = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        if (ticket.getAssignedTechnicianId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ticket has no assigned technician");
        }
        Technician me = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not a technician of this shop"));
        if (!ticket.getAssignedTechnicianId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ticket is assigned to a different technician");
        }
        if (ticket.getTechnicianAcceptedAt() == null) {
            ticket.setTechnicianAcceptedAt(java.time.Instant.now());
            // Walk-in tickets are minted at CREATED; pickup-flow tickets at
            // IN_DIAGNOSIS. Accepting from CREATED also advances status —
            // pickup-flow tickets are already at IN_DIAGNOSIS so the status
            // stays put.
            if ("CREATED".equalsIgnoreCase(ticket.getStatus())) {
                ticket.setStatus("IN_DIAGNOSIS");
            }
            ticket = ticketRepository.save(ticket);
            customerOrderMirrorService.mirrorOnUpsert(ticket);

            // Emit the customer/owner timeline events now that acceptance is
            // proven. emitBookingEvent dedupes so a stale auto-fire from a
            // pre-fix run won't double-write.
            String techName = me.getName() != null ? me.getName() : "Technician";
            // After a re-assignment the earlier technician's acceptance row
            // already exists (dedupe would drop this one), so re-stamp it with
            // the new technician and the real acceptance time.
            if (hasCurrentCycleEvent(ticket.getId(), "TECHNICIAN_ACCEPTED_SERVICE")) {
                emitOrUpdateBookingEvent(ticket.getId(), "TECHNICIAN_ACCEPTED_SERVICE",
                        techName + " accepted the service", "TECHNICIAN");
            } else {
                emitBookingEvent(ticket.getId(), "TECHNICIAN_ACCEPTED_SERVICE",
                        techName + " accepted the service", "TECHNICIAN");
            }
            emitBookingEvent(ticket.getId(), "TECHNICIAN_WORK_STARTED",
                    "Technician Work Started", "TECHNICIAN");
        }
        return toResponse(ticket);
    }

    private static boolean hasAtLeastOneUrl(String json) {
        if (json == null) return false;
        String trimmed = json.trim();
        // Cheap parse: any "http" substring inside the JSON array/object means
        // the technician submitted at least one uploaded media URL. Avoids
        // pulling in ObjectMapper for a one-liner check.
        return !trimmed.isEmpty() && !trimmed.equals("[]") && trimmed.contains("http");
    }

    // DB-stored event codes, not the labels — see serviceHistoryPhases.js
    // SHOP_BOOKING_STATUS_OPTIONS.
    private static final String KEY_ISSUE_VERIFIED = "TECHNICIAN_COMPLIANCE_ISSUE_VERIFIED_UPDATED";
    private static final String KEY_RE_ESTIMATED = "RE_ESTIMATED_CONFIRMED";
    static final String RETURNED_UNREPAIRED_NOTE = "Returned to Customer (Unrepaired)";

    // Notes read on the Service History rail under each step's label. The
    // renderer hides a note identical to the label, so these deliberately say
    // what happened rather than repeating the row title.
    static final String NOTE_RE_ESTIMATED = "Re-estimated service submitted";
    static final String NOTE_APPROVED_RE_ESTIMATE = "Customer approved the re-estimated service";
    static final String NOTE_APPROVED = "Customer approved the estimate";

    /**
     * The customer's verdict landed — from the customer app's Approve tap or
     * from the shop ticking "Customer Repair Approval" on their behalf.
     *
     * Writes CUSTOMER_APPROVED and nothing else. "Repair Work In Progress" is
     * NOT opened here: an approval means the customer agreed to the price, not
     * that a technician has picked the device back up. That row belongs to the
     * technician's own checklist tap (emitProgressStepEvent), which stamps the
     * minute work actually resumed. Nothing on this rail is auto-derived any
     * more — every step waits for the action it describes.
     *
     * emitOrUpdate, not the insert-only emit: a re-estimate clears the prior
     * approval and re-prompts, so the customer approves a SECOND time. Dedupe by
     * status key would keep the first approval's createdAt and leave the rail
     * showing "Service Re-estimated" later than the approval that followed it.
     */
    private void onCustomerApproved(Ticket ticket, String actor) {
        boolean afterReEstimate = hasBookingEvent(ticket.getId(), KEY_RE_ESTIMATED);
        emitOrUpdateBookingEvent(ticket.getId(), "CUSTOMER_APPROVED",
                afterReEstimate ? NOTE_APPROVED_RE_ESTIMATE : NOTE_APPROVED,
                actor, null, null, java.time.Instant.now());
        // An approval also settles a re-estimate awaiting it (approved + back to repair).
        reEstimateWorkflow.onCustomerApproval(ticket, actor);
    }

    /** Steps that explicitly put a booking back on the repair path. */
    private static final Set<String> REOPEN_KEYS = Set.of("REPAIR_WORK_RESUMED", "IN_REPAIR", "RE_SERVICE");

    /** {@code marker} is in the current cycle and no reopen step came after it. */
    boolean openSince(UUID ticketId, String marker) {
        List<PlatformRepairBookingEvent> ev = platformRepairBookingRepository.findByTicketId(ticketId)
                .map(b -> platformRepairBookingEventRepository.findCurrentCycle(b.getId()))
                .orElse(List.of());
        boolean open = false;
        for (PlatformRepairBookingEvent e : ev) {
            String k = e.getStatus() == null ? "" : e.getStatus().toUpperCase();
            if (marker.equals(k)) open = true;
            else if (REOPEN_KEYS.contains(k)) open = false;
        }
        return open;
    }

    private void refuseReEstimateAfterReturn(UUID ticketId) {
        if (openSince(ticketId, "RETURN_DELIVERY")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The device is being returned unrepaired — resume the repair before re-estimating.");
        }
    }

    /** Statuses at/after Out for Delivery, or closed: the technician is frozen. */
    private static final Set<String> ASSIGN_LOCKED_STATUSES = Set.of(
            "DELIVERED", "DELIVERED_PROCESSING", "DELIVERED_TO_CUSTOMER", "CANCELLED", "CLOSED",
            "COMPLETED", "RETURNED", "RETURN_DELIVERY");

    /** Assign / Re-Assign is allowed only before Out for Delivery. */
    void assertAssignable(Ticket ticket) {
        String st = ticket.getStatus() == null ? "" : ticket.getStatus().toUpperCase(java.util.Locale.ROOT);
        if (ASSIGN_LOCKED_STATUSES.contains(st)
                || hasBookingEvent(ticket.getId(), "READY_FOR_DELIVERY")
                || hasBookingEvent(ticket.getId(), "RETURN_DELIVERY")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Technician can't be changed once the booking is out for delivery or closed.");
        }
    }

    /**
     * Stale-access guard for technician actions: a shop employee who has a
     * technicians row may act only on tickets currently assigned to them.
     * Owners (SHOP_OWNER role) are unaffected.
     */
    @Transactional(readOnly = true)
    public void requireCurrentTechnicianUnlessOwner(UUID shopId, UUID ticketId, UUID userId, boolean owner) {
        if (owner || userId == null) return;
        technicianRepository.findByShopIdAndUserId(shopId, userId).ifPresent(me -> {
            Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                    .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
            if (t.getAssignedTechnicianId() == null || !t.getAssignedTechnicianId().equals(me.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This booking is assigned to a different technician");
            }
        });
    }

    private boolean hasBookingEvent(UUID ticketId, String statusKey) {
        return platformRepairBookingRepository.findByTicketId(ticketId)
                .map(booking -> platformRepairBookingEventRepository
                        .findCurrentCycle(booking.getId())
                        .stream()
                        .anyMatch(e -> statusKey.equalsIgnoreCase(e.getStatus())))
                .orElse(false);
    }

    // Idempotent event emit for the customer/owner Service History rail. Looks
    // up the booking mirrored against the ticket; skips if an event with the
    // same status key already exists so re-saves don't double-emit.
    private void emitBookingEvent(UUID ticketId, String statusKey, String note, String actor) {
        emitBookingEvent(ticketId, statusKey, note, actor, actor);
    }

    private boolean hasCurrentCycleEvent(UUID ticketId, String statusKey) {
        return platformRepairBookingRepository.findByTicketId(ticketId)
                .map(b -> platformRepairBookingEventRepository.findCurrentCycle(b.getId()).stream()
                        .anyMatch(e -> statusKey.equalsIgnoreCase(e.getStatus())))
                .orElse(false);
    }

    /** Always inserts a new event row (no dedupe) — for history rows such as
     *  each technician re-assignment. Same notifications as emitBookingEvent. */
    private void appendBookingEvent(UUID ticketId, String statusKey, String note, String actor) {
        platformRepairBookingRepository.findByTicketId(ticketId).ifPresent(booking -> {
            platformRepairBookingEventRepository.save(PlatformRepairBookingEvent.builder()
                    .bookingId(booking.getId())
                    .status(statusKey)
                    .note(note)
                    .actor(actor)
                    .build());
            customerOrderMirrorService.emitCustomerNotificationForStatus(booking.getId(), statusKey);
            customerOrderMirrorService.emitShopNotificationForStatus(booking.getId(), statusKey, actor);
        });
    }

    // `actor` is what the timeline row records (SHOP / TECHNICIAN); `notifyActor`
    // is who actually made the change, for the shop feed — OWNER when the owner
    // did it from their own app, so they are not notified about their own tap.
    private void emitBookingEvent(UUID ticketId, String statusKey, String note, String actor,
                                  String notifyActor) {
        platformRepairBookingRepository.findByTicketId(ticketId).ifPresent(booking -> {
            boolean alreadyEmitted = platformRepairBookingEventRepository
                    .findCurrentCycle(booking.getId())
                    .stream()
                    .anyMatch(e -> statusKey.equalsIgnoreCase(e.getStatus()));
            if (alreadyEmitted) return;
            platformRepairBookingEventRepository.save(PlatformRepairBookingEvent.builder()
                    .bookingId(booking.getId())
                    .status(statusKey)
                    .note(note)
                    .actor(actor)
                    .build());
            // Ping the customer's AND the shop owner's Notifications screens for
            // the same status, so each sees one entry per real-life update
            // (technician uploaded images, issue verified, repair completed,
            // etc.). The mirror keeps a separate template map per audience and
            // silently drops low-signal statuses, walk-ins (no customer_user_id)
            // and bookings no shop has taken on yet. Called as two separate
            // proxied invocations so each keeps its own REQUIRES_NEW — one feed
            // failing must not roll back the other or this event save.
            customerOrderMirrorService.emitCustomerNotificationForStatus(booking.getId(), statusKey);
            customerOrderMirrorService.emitShopNotificationForStatus(booking.getId(), statusKey, notifyActor);
        });
    }

    /** Manual emit endpoint for service-progress checklist rows on the
     *  technician's Ticket Detail screen — Repair Work In Progress, Spare
     *  Parts Waiting, Quality Check Completed, Repair Completed.
     *  Idempotent: re-submitting refreshes the existing row's note +
     *  timestamp instead of inserting a duplicate.
     *
     *  Two step pairs were each collapsed to a single status, and their
     *  retired halves must stay OUT of this set — an old installed build
     *  still posting one has to be rejected, not silently written back into
     *  a table the migration just cleaned:
     *    * PARTS_REQUIRED ("Spare Parts Waiting") replaced PARTS_REQUIRED +
     *      PARTS_REPLACED (migration 87).
     *    * QUALITY_CHECK_COMPLETED replaced QUALITY_CHECK_STARTED +
     *      QUALITY_CHECK_COMPLETED (migration 88). There is no "pending"
     *      half any more: the completion event IS the record, and its
     *      createdAt — stamped server-side in emitOrUpdateBookingEvent — is
     *      the moment the technician marked the check done. */
    private static final java.util.Set<String> ALLOWED_PROGRESS_STEP_KEYS = java.util.Set.of(
            "IN_REPAIR", "PARTS_REQUIRED",
            "QUALITY_CHECK_COMPLETED", "REPAIR_COMPLETED",
            // READY -> billing/handover sub-flow -> DELIVERED. Each substep is
            // its own emit so the customer history rail surfaces the invoice and
            // handover states distinctly instead of skipping straight to
            // "Delivered to Customer".
            "READY", "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING",
            "DELIVERED", "CANCELLED",
            // Return-Device flow. RETURN_DELIVERY is the "device not repaired,
            // returning as-is" counterpart to READY. CUSTOMER_REJECTED is the
            // other way onto that branch — the customer turned the estimate
            // down — and is emitted by the shop's Update Service Status sheet;
            // order-service raises the same key from the customer's own reject
            // tap, so both routes land on one row.
            "RETURN_DELIVERY", "CUSTOMER_REJECTED",
            // REPAIR_NOT_COMPLETED is the technician's "tried but couldn't fix"
            // signal — surfaced on the customer / shop history rail with the
            // canonical "Your repair is not completed" note. Does NOT advance
            // ticket.status; the row is for visibility only.
            "REPAIR_NOT_COMPLETED",
            // BOOKING_EDITED is the shop's "booking details changed" marker
            // (Edit Booking). History only — not a lifecycle or master work
            // code, so ticket.status is left as it was.
            "BOOKING_EDITED",
            // Re-estimate workflow: the decision emits it; the technician may also tap it.
            "REPAIR_WORK_RESUMED");

    // Who may be recorded as having performed a step. The endpoint is shop-scoped
    // rather than role-gated, so an owner token and an employee token can both
    // post here — QUALITY_CHECK_COMPLETED is deliberately raisable from either
    // side (the technician's checklist or the owner's Service History screen),
    // and the actor is what tells the two apps apart afterwards:
    //   TECHNICIAN — the employee app's checklist (the default when unset)
    //   OWNER      — a person tapping in the shop app
    //   SHOP       — auto/derived emits, which the employee checklist ignores
    private static final java.util.Set<String> ALLOWED_PROGRESS_ACTORS = java.util.Set.of(
            "TECHNICIAN", "OWNER", "SHOP");

    @Transactional
    public void emitProgressStepEvent(UUID shopId, UUID ticketId, String statusKey, String note, String actor) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        String key = statusKey == null ? "" : statusKey.trim().toUpperCase();
        if (!ALLOWED_PROGRESS_STEP_KEYS.contains(key)) {
            throw new IllegalArgumentException("Status key not allowed: " + key);
        }
        // The shop records the customer's rejection on their behalf, so it must
        // carry the customer's stated reason. Checked before anything changes.
        if ("CUSTOMER_REJECTED".equals(key) && "OWNER".equalsIgnoreCase(actor == null ? "" : actor.trim())
                && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the customer's reason for rejecting the service.");
        }
        // "Repair not completed" stands until the repair is resumed (Resume / In Repair).
        if ("REPAIR_COMPLETED".equals(key) && openSince(t.getId(), "REPAIR_NOT_COMPLETED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The repair was marked not completed — resume the repair first.");
        }
        // A return already in progress: no second return start (duplicate), and no
        // repaired-path step (conflicting status) until the repair is resumed.
        if (openSince(t.getId(), "RETURN_DELIVERY")) {
            if (RETURN_STARTS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This booking is already being returned to the customer.");
            }
            if (REPAIRED_PATH_KEYS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This booking is being returned unrepaired — resume the repair first.");
            }
        }
        // Repair Completed / QC / Ready / invoice wait for an open re-estimate (409 REESTIMATE_PENDING);
        // Customer Rejected / Cancelled / Return Delivery close it instead.
        reEstimateWorkflow.onProgressStep(t, key);
        // A delivered or cancelled booking is closed: the device is with the
        // customer (or the job was called off) and there is no further status to
        // record. The owner app already hides the picker at that point, but the
        // refusal belongs here — an employee-app checklist tap, a stale screen or
        // a retried request must not reopen a closed booking's flow.
        //
        // Re-posting a step the booking ALREADY carries stays allowed: that is a
        // refresh of an existing row (a corrected invoice re-emitting
        // INVOICE_GENERATED, a double Submit), not a new status.
        String lifecycle = t.getStatus() == null ? "" : t.getStatus().trim().toUpperCase();
        if (("DELIVERED".equals(lifecycle) || "CANCELLED".equals(lifecycle))
                && !hasBookingEvent(t.getId(), key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "DELIVERED".equals(lifecycle)
                            ? "This booking is closed — the device is with the customer."
                            : "This booking is closed — the repair was cancelled.");
        }
        String a = actor == null ? "" : actor.trim().toUpperCase();
        if (!ALLOWED_PROGRESS_ACTORS.contains(a)) a = "TECHNICIAN";
        // Quality Check is one completion, by the employee OR the shop — a second
        // slide (other app, double tap, retry) must not overwrite who/when.
        if ("QUALITY_CHECK_COMPLETED".equals(key) && hasBookingEvent(t.getId(), key)) return;
        String text = note != null && !note.isBlank() ? note.trim() : defaultProgressLabel(key);
        // Handing back a device that went down Return Delivery is a return, not a
        // repaired delivery: keep the DELIVERED key (apps, lifecycle) but say so.
        if ("DELIVERED".equals(key) && text.equals(defaultProgressLabel("DELIVERED"))
                && openSince(t.getId(), "RETURN_DELIVERY")) {
            text = RETURNED_UNREPAIRED_NOTE;
        }
        emitOrUpdateBookingEvent(t.getId(), key, text, a);
        // The BookingsHistory list reads ticket.status directly, so a work-status
        // event alone (Spare Parts Waiting, Repair Completed, Delivered to Customer,
        // ...) used to leave the badge stuck on the previous lifecycle status.
        // Resolve the admin-managed master row for this code and advance
        // ticket.status to its `ticket_status` mapping so the list badge moves
        // forward (IN_REPAIR → READY → DELIVERED). Falls through silently when
        // the code isn't in master (defensive — keeps the event write working).
        advanceTicketStatusForWorkCode(t, key);
        // Quality Check Completed (employee checklist or owner) → the device is
        // ready: record "Ready for Delivery" too and move the booking to READY,
        // so the shop's Delivery Ready tab picks it up without a manual step.
        // Once only — a re-submitted QC leaves an existing READY row alone.
        if ("QUALITY_CHECK_COMPLETED".equals(key) && !hasBookingEvent(t.getId(), "READY")) {
            emitOrUpdateBookingEvent(t.getId(), "READY", defaultProgressLabel("READY"), "SHOP");
            advanceTicketStatusForWorkCode(t, "READY");
        }
        // Invoice Generated → Out for Delivery, automatically and once.
        if ("INVOICE_GENERATED".equals(key) && !hasBookingEvent(t.getId(), "DELIVERED_PROCESSING")) {
            emitOrUpdateBookingEvent(t.getId(), "DELIVERED_PROCESSING", defaultProgressLabel("DELIVERED_PROCESSING"), "SHOP");
            advanceTicketStatusForWorkCode(t, "DELIVERED_PROCESSING");
        }
        // Return flows: technician "Unable to repair" (REPAIR_NOT_COMPLETED) or
        // the shop's Cancel / Return / Close (CUSTOMER_REJECTED, reason required)
        // → Return to Customer → Out for Delivery, automatically and once each.
        // Delivered stays the shop's manual slide (physical handover).
        if (RETURN_STARTS.contains(key)) {
            if (!openSince(t.getId(), "RETURN_DELIVERY")) {
                reEstimateWorkflow.onProgressStep(t, "RETURN_DELIVERY");
                emitOrUpdateBookingEvent(t.getId(), "RETURN_DELIVERY", defaultProgressLabel("RETURN_DELIVERY"), "SHOP");
                advanceTicketStatusForWorkCode(t, "RETURN_DELIVERY");
            }
            if (!hasBookingEvent(t.getId(), "DELIVERED_PROCESSING")) {
                emitOrUpdateBookingEvent(t.getId(), "DELIVERED_PROCESSING", defaultProgressLabel("DELIVERED_PROCESSING"), "SHOP");
                advanceTicketStatusForWorkCode(t, "DELIVERED_PROCESSING");
            }
        }
    }

    // Ways onto the return branch (see emitProgressStepEvent).
    private static final Set<String> RETURN_STARTS = Set.of("REPAIR_NOT_COMPLETED", "CUSTOMER_REJECTED");
    // Repaired-path steps that conflict with an open return.
    private static final Set<String> REPAIRED_PATH_KEYS = Set.of("REPAIR_COMPLETED", "QUALITY_CHECK_COMPLETED", "READY");

    // Only advance forward through the lifecycle — never demote a DELIVERED
    // ticket back to IN_REPAIR because an older code was re-submitted, and
    // never override CANCELLED. The post-READY billing/handover substages
    // (INVOICE_GENERATED, INVOICE_READY, DELIVERED_PROCESSING) sit between
    // READY and DELIVERED so a Ready ticket can't jump straight to Delivered
    // without the invoice + handover steps being recorded first.
    private static final java.util.List<String> LIFECYCLE_ORDER = java.util.List.of(
            "CREATED", "IN_DIAGNOSIS", "QUOTED", "APPROVED", "IN_REPAIR",
            "READY", "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING",
            "DELIVERED");

    private void advanceTicketStatusForWorkCode(Ticket t, String code) {
        String target = lifecycleTargetFor(code);
        if (target == null) return;

        String current = t.getStatus() == null ? "" : t.getStatus().trim().toUpperCase();
        if (target.equals(current)) return;
        // CANCELLED / RETURNED are terminal — don't overwrite them.
        if ("CANCELLED".equals(current) || "RETURNED".equals(current)) return;
        int currentIdx = LIFECYCLE_ORDER.indexOf(current);
        int targetIdx = LIFECYCLE_ORDER.indexOf(target);
        // Both inside the linear ladder → only move forward.
        if (currentIdx >= 0 && targetIdx >= 0 && targetIdx < currentIdx) return;
        t.setStatus(target);
        ticketRepository.save(t);
        customerOrderMirrorService.mirrorOnUpsertInline(t); // same tx: REQUIRES_NEW here waited on this tx's row locks, 2 connections per request
    }

    // Steps that mean the invoice has already gone out with the device. Once one
    // is on the rail the invoice is part of the handover and can't be deleted.
    private static final Set<String> AFTER_INVOICE_KEYS = Set.of(
            "DELIVERED_PROCESSING", "DELIVERED", "DELIVERED_TO_CUSTOMER", "CANCELLED", "RETURNED");

    /**
     * Undo the "Invoice Generated" step when its invoice is deleted. Refused with
     * a 409 once Out for Delivery / Delivered is recorded or the booking is
     * closed. Drops the INVOICE_GENERATED / INVOICE_READY rows from the Service
     * History and walks ticket.status back to where the remaining steps leave it
     * (READY when none of them map below the invoice), so a fresh invoice can be
     * raised. The one deliberate demotion past the forward-only rule above.
     */
    @Transactional
    public void retractInvoiceStep(UUID shopId, UUID ticketId) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        String current = t.getStatus() == null ? "" : t.getStatus().trim().toUpperCase();
        // Current repair only (Re-Service): the first repair's Delivered row must
        // not block the new one, but its invoice — still the ticket's one invoice
        // row until re-raised — was handed over and stays.
        List<PlatformRepairBookingEvent> events = platformRepairBookingRepository.findByTicketId(ticketId)
                .map(b -> platformRepairBookingEventRepository.findCurrentCycle(b.getId()))
                .orElse(List.of());
        Instant cycleStart = reServiceStartedAt(ticketId);
        boolean handedOver = AFTER_INVOICE_KEYS.contains(current) || events.stream()
                .anyMatch(e -> e.getStatus() != null && AFTER_INVOICE_KEYS.contains(e.getStatus().trim().toUpperCase()))
                || invoiceRepository.findByTicketId(ticketId).filter(inv -> isEarlierRepairInvoice(inv, cycleStart)).isPresent();
        if (handedOver) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The invoice can't be deleted once Out for Delivery or Delivered is recorded.");
        }

        Set<String> invoiceKeys = Set.of("INVOICE_GENERATED", "INVOICE_READY");
        List<PlatformRepairBookingEvent> invoiceSteps = new ArrayList<>();
        int invoiceIdx = LIFECYCLE_ORDER.indexOf("INVOICE_GENERATED");
        int restoredIdx = -1;
        for (PlatformRepairBookingEvent e : events) {
            String key = e.getStatus() == null ? "" : e.getStatus().trim().toUpperCase();
            if (invoiceKeys.contains(key)) {
                invoiceSteps.add(e);
                continue;
            }
            String target = lifecycleTargetFor(key);
            int idx = target == null ? -1 : LIFECYCLE_ORDER.indexOf(target);
            if (idx >= 0 && idx < invoiceIdx) restoredIdx = Math.max(restoredIdx, idx);
        }
        platformRepairBookingEventRepository.deleteAll(invoiceSteps);

        if (invoiceKeys.contains(current)) {
            t.setStatus(restoredIdx >= 0 ? LIFECYCLE_ORDER.get(restoredIdx) : "READY");
            ticketRepository.save(t);
            customerOrderMirrorService.mirrorOnUpsertInline(t);
        }
    }

    /** When the booking was last reopened for Re-Service (its RE_SERVICE marker), or null if never. */
    private Instant reServiceStartedAt(UUID ticketId) {
        return platformRepairBookingRepository.findByTicketId(ticketId)
                .map(b -> platformRepairBookingEventRepository.findCurrentCycle(b.getId()))
                .filter(c -> !c.isEmpty()
                        && PlatformRepairBookingEventRepository.RE_SERVICE.equalsIgnoreCase(c.get(0).getStatus()))
                .map(c -> c.get(0).getCreatedAt())
                .orElse(null);
    }

    private static Instant lastWrite(Invoice inv) {
        return inv.getUpdatedAt() != null ? inv.getUpdatedAt() : inv.getGeneratedAt();
    }

    /** Re-Service keeps the ticket's one invoice row: one last written before the reopen is the delivered first repair's. */
    private static boolean isEarlierRepairInvoice(Invoice inv, Instant cycleStart) {
        Instant written = lastWrite(inv);
        return cycleStart != null && written != null && written.isBefore(cycleStart);
    }

    /** An invoice raised for the repair in progress (not the delivered first repair's after a Re-Service). */
    private boolean hasCurrentInvoice(UUID ticketId) {
        return invoiceRepository.findByTicketId(ticketId)
                .filter(inv -> !isEarlierRepairInvoice(inv, reServiceStartedAt(ticketId)))
                .isPresent();
    }

    // The ticket.status a work-status code maps to, or null when it maps to none.
    private String lifecycleTargetFor(String code) {
        if (code == null || code.isBlank()) return null;
        String normalized = code.trim().toUpperCase();

        // Primary: look up the admin-managed master row.
        String target = masterWorkStatusRepository.findByCodeIgnoreCase(normalized)
                .map(row -> row.getTicketStatus() == null ? null : row.getTicketStatus().trim().toUpperCase())
                .filter(s -> !s.isBlank())
                .orElse(null);

        // Fallback: when the work-status key IS itself a lifecycle status
        // (DELIVERED, CANCELLED, READY, …), use it directly. This covers the
        // case where the master row exists under a different code (e.g.
        // DELIVERED_TO_CUSTOMER) so the badge would otherwise be stuck at
        // READY after the technician's "Delivered to Customer" submission.
        if (target == null && (LIFECYCLE_ORDER.contains(normalized) || "CANCELLED".equals(normalized))) {
            target = normalized;
        }
        return target == null || target.isBlank() ? null : target;
    }

    /**
     * Does this update re-estimate the booking? Only when the services / amount
     * really changed AND the caller didn't mark it a plain edit. reEstimateFlag
     * null = an older app that doesn't send TicketRequest.reEstimate: keep
     * inferring from the change, as before.
     */
    static boolean isReEstimate(Boolean reEstimateFlag, boolean priceChanged) {
        return priceChanged && !Boolean.FALSE.equals(reEstimateFlag);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper PRICE_ITEMS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Same services at the same amounts? priceItemsJson is
     * [{id, code, label, amount, warranty}]; each item is reduced to its
     * identity (id, else code, else label) + amount, order ignored. Unparseable
     * JSON falls back to a trimmed text compare; null, blank and [] are equal.
     */
    static boolean samePriceItems(String a, String b) {
        java.util.List<String> ka = priceItemKeys(a);
        java.util.List<String> kb = priceItemKeys(b);
        if (ka == null || kb == null) {
            return java.util.Objects.equals(a == null ? "" : a.trim(), b == null ? "" : b.trim());
        }
        return ka.equals(kb);
    }

    private static java.util.List<String> priceItemKeys(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode root = PRICE_ITEMS_MAPPER.readTree(json);
            if (root == null || root.isNull()) return java.util.List.of();
            if (!root.isArray()) return null;
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode it : root) {
                String id = firstText(it, "id", "serviceId", "code", "serviceCode", "label", "serviceName");
                com.fasterxml.jackson.databind.JsonNode amt = it.has("amount") ? it.get("amount") : it.get("price");
                BigDecimal amount;
                try {
                    amount = (amt == null || amt.isNull() || amt.asText().isBlank())
                            ? BigDecimal.ZERO : new BigDecimal(amt.asText().trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                keys.add(id.toLowerCase() + "|" + amount.stripTrailingZeros().toPlainString());
            }
            java.util.Collections.sort(keys);
            return keys;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String f : fields) {
            com.fasterxml.jackson.databind.JsonNode v = node.get(f);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().trim();
        }
        return "";
    }

    /**
     * Move a re-estimated ticket to QUOTED.
     *
     * Deliberately NOT routed through advanceTicketStatusForWorkCode: that one
     * is forward-only and consults the admin-managed master work-status table,
     * neither of which fits here. A re-estimate is a re-quote — the price the
     * customer agreed to no longer holds, which is why update() already clears
     * customerApproval and re-emits WAITING_FOR_CUSTOMER_APPROVAL on a re-edit
     * after approval. So APPROVED / IN_REPAIR are walked BACK to QUOTED rather
     * than left alone; otherwise the very bookings most in need of re-approval
     * would be the ones missing from the owner's Re-Estimated list.
     *
     * READY and beyond are left untouched: past that point the repair is done
     * and a price change is a billing adjustment, not a re-quote. Demoting
     * would unwind the invoice / handover chain that LIFECYCLE_ORDER gates.
     */
    private void markReEstimated(Ticket t) {
        String current = t.getStatus() == null ? "CREATED" : t.getStatus().trim().toUpperCase();
        if (current.isBlank()) current = "CREATED";
        if ("QUOTED".equals(current)) return;
        // Terminal — a cancelled or returned job is not back in the queue.
        if ("CANCELLED".equals(current) || "RETURNED".equals(current)) return;
        int currentIdx = LIFECYCLE_ORDER.indexOf(current);
        int repairIdx = LIFECYCLE_ORDER.indexOf("IN_REPAIR");
        // Unknown status → leave it be rather than guess where it sits.
        if (currentIdx < 0 || currentIdx > repairIdx) return;
        t.setStatus("QUOTED");
        ticketRepository.save(t);
        // Re-mirror: update() already mirrored above with the pre-re-estimate
        // status, so repair_bookings.status would otherwise stay behind.
        customerOrderMirrorService.mirrorOnUpsertInline(t);
    }

    // Note text written when the caller sends none. These deliberately MATCH the
    // rail's row labels: the timeline hides a note identical to its label, so a
    // matching string renders as a clean row instead of repeating itself
    // underneath. Only REPAIR_NOT_COMPLETED differs on purpose — it carries the
    // sentence the customer should read.
    private static String defaultProgressLabel(String key) {
        switch (key) {
            case "IN_REPAIR":              return "Repair Work In Progress";
            case "PARTS_REQUIRED":         return "Spare Parts Waiting";
            case "QUALITY_CHECK_COMPLETED":return "Quality Check Completed";
            case "REPAIR_COMPLETED":       return "Repair Completed";
            case "REPAIR_NOT_COMPLETED":   return "Your repair is not completed";
            case "BOOKING_EDITED":         return "Booking Edited";
            case "CUSTOMER_REJECTED":      return "Customer Rejected";
            case "RETURN_DELIVERY":        return "Return Delivery";
            case "INVOICE_GENERATED":      return "Invoice Generated";
            case "INVOICE_READY":          return "Invoice Ready";
            case "DELIVERED_PROCESSING":   return "Out for Delivery";
            case "READY":                  return "Ready for Delivery";
            case "DELIVERED":              return "Delivered to Customer";
            case "CANCELLED":              return "Repair Cancelled";
            case "TECHNICIAN_REESTIMATE_REQUESTED": return "Technician Re-Estimate Requested";
            case "RE_ESTIMATE_APPROVED":   return "Re-Estimate Approved by Customer";
            case "RE_ESTIMATE_REJECTED":   return "Re-Estimate Rejected by Customer";
            case "REPAIR_WORK_RESUMED":    return "Repair Work in Progress (Resumed)";
            default:                       return key;
        }
    }

    // Same as emitBookingEvent but refreshes the note text and timestamp on
    // an existing row instead of skipping it. Used for steps whose detail
    // changes on each invocation — e.g. compliance notes (latest note text
    // should display) and re-edit-driven approval requests (latest timestamp).
    private void emitOrUpdateBookingEvent(UUID ticketId, String statusKey, String note, String actor) {
        emitOrUpdateBookingEvent(ticketId, statusKey, note, actor, null, null);
    }

    private void emitOrUpdateBookingEvent(UUID ticketId, String statusKey, String note, String actor,
                                          String audioUrl, String imagesJson) {
        emitOrUpdateBookingEvent(ticketId, statusKey, note, actor, audioUrl, imagesJson, null);
    }

    // `at` pins the event's timestamp instead of stamping now(). Callers pass it
    // when a step has to land on an instant that is already decided — the only
    // case today is the compliance-note submit, which hands the same instant to
    // the paired "Repair Work In Progress" row. Still a server clock value: the
    // caller reads it from Instant.now(), never from the request.
    private void emitOrUpdateBookingEvent(UUID ticketId, String statusKey, String note, String actor,
                                          String audioUrl, String imagesJson, java.time.Instant at) {
        java.time.Instant stamp = at != null ? at : java.time.Instant.now();
        platformRepairBookingRepository.findByTicketId(ticketId).ifPresent(booking -> {
            var existing = ReEstimateWorkflowService.sameRevisionRow(
                    platformRepairBookingEventRepository.findCurrentCycle(booking.getId()), statusKey);
            if (existing.isPresent()) {
                PlatformRepairBookingEvent e = existing.get();
                e.setNote(note);
                e.setActor(actor);
                // Re-emit overwrites media too so editing a compliance note
                // (re-recording the voice clip, swapping an image) lands on
                // the customer/owner timeline row right away.
                e.setAudioUrl(audioUrl);
                e.setImagesJson(imagesJson);
                // Refresh the timestamp so the customer/owner timeline rail
                // reflects this as the most recent action — required because
                // the dedup keyed by status would otherwise keep the original
                // (now stale) createdAt.
                e.setCreatedAt(stamp);
                platformRepairBookingEventRepository.save(e);
            } else {
                platformRepairBookingEventRepository.save(PlatformRepairBookingEvent.builder()
                        .bookingId(booking.getId())
                        .status(statusKey)
                        .note(note)
                        .actor(actor)
                        .audioUrl(audioUrl)
                        .imagesJson(imagesJson)
                        // Set here rather than left to @PrePersist so an insert
                        // and an update of the same step are stamped alike.
                        .createdAt(stamp)
                        .build());
                // First-time emit of this status → ping the customer's and the
                // shop's Notifications screens. Update branch above
                // intentionally skips this so re-emits (e.g., the technician
                // editing a verified note) don't spam the same alert twice.
                // The actor goes to the shop feed so a step the OWNER just
                // performed in their own app doesn't notify them about it.
                customerOrderMirrorService.emitCustomerNotificationForStatus(booking.getId(), statusKey);
                customerOrderMirrorService.emitShopNotificationForStatus(booking.getId(), statusKey, actor);
            }
        });
    }

    /**
     * A status the Service History rail draws a row for under its own name.
     *
     * These are the codes SHOP_BOOKING_STATUS_OPTIONS renders directly, so
     * writing one to ticket.status without emitting the matching event leaves
     * the badge and the rail telling different stories — the card reads
     * "Invoice Generated" while the newest lit step is still "Ready for
     * Delivery". CREATED / IN_DIAGNOSIS / APPROVED are absent on purpose: they
     * have no row of their own, and the switch below already derives the steps
     * they do imply.
     */
    private static final java.util.Set<String> SELF_NAMED_TIMELINE_STATUSES = java.util.Set.of(
            "READY", "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING",
            "DELIVERED", "CANCELLED");

    // Emit the step events implied by a ticket status:
    //   IN_REPAIR    → TECH_WORK_STARTED   ("Technician Work Started")
    //   QUOTED       → WAITING_APPROVAL    ("Waiting for Customer Approval")
    //   APPROVED     → also TECH_WORK_STARTED — when the customer approves the
    //                 quote, work resumes; this ensures the timeline reflects it
    //                 even if updateStatus was skipped server-side.
    // Step keys match serviceHistoryPhases.js (repair-shop-mobile).
    //
    // Anything in SELF_NAMED_TIMELINE_STATUSES additionally emits itself. The
    // handover tail used to fall through to `default: break`, so a status set
    // through PUT /tickets/{id}/status — the Update Service Status sheet's path
    // — moved the badge and left the rail behind. emitBookingEvent is idempotent
    // per (booking, statusKey), so a status that also arrives through
    // /progress-events writes one row, not two, whichever lands first.
    //
    // `notifyActor` is passed through to the shop feed (see emitBookingEvent).
    private void emitStepEventsForTicketStatus(UUID ticketId, String status, String notifyActor) {
        if (status == null) return;
        String s = status.trim().toUpperCase();
        switch (s) {
            case "IN_REPAIR":
            case "APPROVED":
                emitBookingEvent(ticketId, "TECHNICIAN_WORK_STARTED",
                        "Technician Work Started", "TECHNICIAN", notifyActor);
                break;
            case "QUOTED":
                emitBookingEvent(ticketId, "WAITING_FOR_CUSTOMER_APPROVAL",
                        "Waiting for Customer Approval", "TECHNICIAN", notifyActor);
                break;
            default:
                break;
        }
        if (SELF_NAMED_TIMELINE_STATUSES.contains(s)) {
            // SHOP, not TECHNICIAN: this path is only reached from the owner's
            // status sheet. defaultProgressLabel is the same wording
            // /progress-events would have written, so the row reads identically
            // whichever door the status came through.
            emitBookingEvent(ticketId, s, defaultProgressLabel(s), "SHOP", notifyActor);
        }
    }

    /** Customer-facing read: masks PIN/pattern value, joins technician name+code. */
    private TicketResponse toCustomerResponse(Ticket t) {
        TicketResponse base = toResponse(t);
        base.setDeviceSecurityValue(null);
        if (t.getAssignedTechnicianId() != null) {
            technicianRepository.findById(t.getAssignedTechnicianId()).ifPresent(tech -> {
                base.setAssignedTechnicianName(tech.getName());
                base.setAssignedTechnicianCode(
                        t.getAssignedTechnicianId().toString().substring(0, 8).toUpperCase());
            });
        }
        return base;
    }

    private TicketResponse toResponse(Ticket t) {
        return toResponse(t, invoiceRepository.findByTicketId(t.getId()).orElse(null));
    }

    /**
     * Map a whole page, looking the invoices up in ONE query instead of one per
     * row. The bookings list asks for 500 tickets at a time — a per-row lookup
     * there is 500 round trips for a field that decides whether a card shows its
     * Invoice action.
     */
    private Page<TicketResponse> toResponsePage(Page<Ticket> page) {
        Map<UUID, Invoice> byTicket = invoicesByTicketId(page.getContent());
        return page.map(t -> toResponse(t, byTicket.get(t.getId())));
    }

    private Map<UUID, Invoice> invoicesByTicketId(List<Ticket> tickets) {
        List<UUID> ids = tickets.stream().map(Ticket::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<UUID, Invoice> out = new HashMap<>();
        for (Invoice inv : invoiceRepository.findByTicketIdIn(ids)) {
            out.put(inv.getTicketId(), inv);
        }
        return out;
    }

    private TicketResponse toResponse(Ticket t, Invoice invoice) {
        // Resolve the few "booking-side" fields once — these all share the
        // same fallback shape (use the ticket column if present, otherwise
        // pull from the linked repair_booking). Doing the lookup once means
        // we hit platformRepairBookingRepository at most once per ticket
        // render rather than once per field.
        TicketBookingFallback fallback = resolveBookingFallback(t);
        // Latest customer-visible compliance note → flat fields the detail
        // screens render on the "Issue Verified & Updated" card. Internal-
        // only notes are filtered out so private shop chatter never leaks
        // to the customer payload.
        RepairNote complianceNote = repairNoteRepository
                .findByTicketIdOrderByCreatedAtDesc(t.getId()).stream()
                .filter(n -> !Boolean.TRUE.equals(n.getIsInternal()))
                .findFirst()
                .orElse(null);
        List<String> complianceImages = complianceNote != null
                ? parseImagesJson(complianceNote.getImagesJson())
                : Collections.emptyList();
        return TicketResponse.builder()
                .id(t.getId())
                .shopId(t.getShopId())
                .customerId(t.getCustomerId())
                .customerName(fallback.customerName)
                .customerPhone(fallback.customerPhone)
                .customerAddress(fallback.customerAddress)
                .assignedTechnicianId(t.getAssignedTechnicianId())
                .technicianAcceptedAt(t.getTechnicianAcceptedAt())
                .trackingId(t.getTrackingId())
                .bookingNumber(t.getBookingNumber() != null ? t.getBookingNumber() : t.getTrackingId())
                .deviceSuffix(t.getDeviceSuffix())
                .brandId(t.getBrandId())
                .modelId(t.getModelId())
                .ramOptionId(t.getRamOptionId())
                .storageOptionId(t.getStorageOptionId())
                .color(t.getColor())
                .imei(fallback.imei)
                .serialNumber(t.getSerialNumber())
                .accessories(t.getAccessories())
                .status(t.getStatus())
                .estimatedPrice(t.getEstimatedPrice())
                .finalPrice(t.getFinalPrice())
                .paymentType(t.getPaymentType())
                .paymentAmount(t.getPaymentAmount())
                // Derived rather than read straight through, so a ticket last
                // written before migration 85 still answers "what is owed"
                // instead of rendering a blank line in Price Summary.
                .balanceAmount(t.getBalanceAmount() != null
                        ? t.getBalanceAmount()
                        : applicableTotal(t).subtract(
                                t.getPaymentAmount() == null ? BigDecimal.ZERO : t.getPaymentAmount())
                                .max(BigDecimal.ZERO))
                .paymentStatus(t.getPaymentStatus() != null
                        ? t.getPaymentStatus()
                        : (t.getPaymentAmount() != null ? "PAID" : "PENDING"))
                .paymentPaidAt(t.getPaymentPaidAt())
                .issueDescription(t.getIssueDescription())
                .issueAudioUrl(t.getIssueAudioUrl())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .deviceDisplayName(t.getDeviceDisplayName())
                .deviceImageUrl(t.getDeviceImageUrl())
                .repairServicesSummary(t.getRepairServicesSummary())
                .priceItemsJson(t.getPriceItemsJson())
                .missingPartsJson(fallback.missingPartsJson)
                .devicePhotosJson(fallback.devicePhotosJson)
                .technicianPhotosJson(t.getTechnicianPhotosJson())
                .deviceSecurityType(t.getDeviceSecurityType())
                .deviceSecurityValue(fallback.deviceSecurityValue)
                .customerApproval(fallback.customerApproval)
                .estimatedReadyAt(fallback.estimatedReadyAt)
                .estimatedDeliveryAt(fallback.estimatedDeliveryAt)
                .originalEstimatedDeliveryAt(t.getOriginalEstimatedDeliveryAt())
                .invoiceId(invoice != null ? invoice.getId() : null)
                .invoiceNo(invoice != null ? invoice.getInvoiceNo() : null)
                .invoiceGeneratedAt(invoice != null ? invoice.getGeneratedAt() : null)
                .invoiceAmount(invoice != null ? invoice.getFinalPayableAmount() : null)
                .complianceNote(complianceNote != null ? complianceNote.getNote() : null)
                .complianceAudioUrl(complianceNote != null ? complianceNote.getAudioUrl() : null)
                .complianceImageUrls(complianceImages)
                .complianceVerifiedAt(complianceNote != null ? complianceNote.getCreatedAt() : null)
                .build();
    }

    /**
     * Holds the half-dozen fields that the booking detail screens read but
     * that ticket-side columns may not yet have populated (because the
     * pickup person filled them in on the repair_booking row before the
     * shop minted the ticket). Each field prefers the ticket's own value
     * and falls back to the linked PlatformRepairBooking — so tickets minted
     * before mintTicketFromBooking learned to copy these snapshot fields
     * still render correctly on the owner Device Details screen.
     */
    private static final class TicketBookingFallback {
        String customerName;
        String customerPhone;
        String customerAddress;
        String devicePhotosJson;
        String missingPartsJson;
        String deviceSecurityValue;
        String imei;
        Boolean customerApproval;
        Instant estimatedReadyAt;
        Instant estimatedDeliveryAt;
    }

    private TicketBookingFallback resolveBookingFallback(Ticket t) {
        TicketBookingFallback f = new TicketBookingFallback();
        f.customerName = t.getCustomerName();
        f.customerPhone = t.getCustomerPhone();
        f.customerAddress = t.getCustomerAddress();
        f.devicePhotosJson = t.getDevicePhotosJson();
        f.missingPartsJson = t.getMissingPartsJson();
        f.deviceSecurityValue = t.getDeviceSecurityValue();
        f.imei = t.getImei();
        f.customerApproval = t.getCustomerApproval();
        f.estimatedReadyAt = t.getEstimatedReadyAt();
        f.estimatedDeliveryAt = t.getEstimatedDeliveryAt();
        if (!needsBookingFallback(f)) return f;
        platformRepairBookingRepository.findByTicketId(t.getId()).ifPresent(b -> {
            if (isBlankStr(f.customerName) && b.getCustomerName() != null && !b.getCustomerName().isBlank()) {
                f.customerName = b.getCustomerName();
            }
            if (isBlankStr(f.customerPhone) && b.getCustomerMobile() != null && !b.getCustomerMobile().isBlank()) {
                f.customerPhone = b.getCustomerMobile();
            }
            if (isBlankStr(f.imei) && b.getImei() != null && !b.getImei().isBlank()) {
                f.imei = b.getImei();
            }
            if (isBlankStr(f.customerAddress) && b.getPickupAddressId() != null) {
                platformCustomerAddressRepository.findById(b.getPickupAddressId()).ifPresent(addr -> {
                    String joined = joinAddressParts(
                            addr.getAddressLine(), addr.getLocality(),
                            addr.getCity(), addr.getState(), addr.getPincode());
                    if (joined != null) f.customerAddress = joined;
                });
            }
            if (isBlankJson(f.devicePhotosJson)) {
                Map<String, String> photos = new HashMap<>();
                if (b.getFrontImageUrl() != null) photos.put("front", b.getFrontImageUrl());
                if (b.getBackImageUrl()  != null) photos.put("back",  b.getBackImageUrl());
                if (b.getVideoUrl()      != null) photos.put("video", b.getVideoUrl());
                if (!photos.isEmpty()) {
                    try { f.devicePhotosJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(photos); }
                    catch (Exception ignore) {}
                }
            }
            if (isBlankJson(f.missingPartsJson) && b.getMissingDamageParts() != null
                    && !b.getMissingDamageParts().isBlank()) {
                String raw = b.getMissingDamageParts().trim();
                if (raw.startsWith("[")) {
                    f.missingPartsJson = raw;
                } else {
                    List<String> parts = new ArrayList<>();
                    for (String piece : raw.split("[,\\n]")) {
                        String p = piece.trim();
                        if (!p.isEmpty()) parts.add(p);
                    }
                    if (!parts.isEmpty()) {
                        try { f.missingPartsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parts); }
                        catch (Exception ignore) {}
                    }
                }
            }
            if ((f.deviceSecurityValue == null || f.deviceSecurityValue.isBlank())
                    && b.getDevicePin() != null && !b.getDevicePin().isBlank()) {
                f.deviceSecurityValue = b.getDevicePin();
            }
            if (f.customerApproval == null && b.getCustomerApproval() != null) {
                String s = b.getCustomerApproval().trim().toUpperCase();
                if (s.equals("DONE") || s.equals("TRUE") || s.equals("YES") || s.equals("APPROVED")) {
                    f.customerApproval = Boolean.TRUE;
                } else if (s.equals("PENDING") || s.equals("FALSE") || s.equals("NO")) {
                    f.customerApproval = Boolean.FALSE;
                }
            }
            if (f.estimatedReadyAt == null && b.getEstimatedReadyAt() != null) {
                f.estimatedReadyAt = b.getEstimatedReadyAt();
            }
            if (f.estimatedDeliveryAt == null && b.getEstimatedDeliveryAt() != null) {
                f.estimatedDeliveryAt = b.getEstimatedDeliveryAt();
            }
            // Customer-flow pickup bookings (order-service RepairBookingController.create)
            // store only customer_user_id; the denormalized customer_name/customer_mobile
            // columns stay NULL. Without this reach into customer_users, the owner's
            // Bookings History card and the Booking Details "Customer Details" pane
            // both render blank for every customer-placed pickup.
            if ((isBlankStr(f.customerName) || isBlankStr(f.customerPhone))
                    && b.getCustomerUserId() != null) {
                platformCustomerUserRepository.findById(b.getCustomerUserId()).ifPresent(cu -> {
                    if (isBlankStr(f.customerName) && cu.getFullName() != null && !cu.getFullName().isBlank()) {
                        f.customerName = cu.getFullName();
                    }
                    if (isBlankStr(f.customerPhone) && cu.getMobile() != null && !cu.getMobile().isBlank()) {
                        f.customerPhone = cu.getMobile();
                    }
                });
            }
        });
        return f;
    }

    private static boolean needsBookingFallback(TicketBookingFallback f) {
        return isBlankStr(f.customerName)
                || isBlankStr(f.customerPhone)
                || isBlankStr(f.customerAddress)
                || isBlankJson(f.devicePhotosJson)
                || isBlankJson(f.missingPartsJson)
                || isBlankStr(f.deviceSecurityValue)
                || isBlankStr(f.imei)
                || f.customerApproval == null
                || f.estimatedReadyAt == null
                || f.estimatedDeliveryAt == null;
    }

    private static boolean isBlankStr(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isBlankJson(String s) {
        if (s == null) return true;
        String trim = s.trim();
        return trim.isEmpty() || trim.equals("{}") || trim.equals("[]");
    }

    private static String joinAddressParts(String line, String locality, String city, String state, String pincode) {
        List<String> parts = new ArrayList<>();
        if (line     != null && !line.isBlank())     parts.add(line.trim());
        if (locality != null && !locality.isBlank()) parts.add(locality.trim());
        if (city     != null && !city.isBlank())     parts.add(city.trim());
        if (state    != null && !state.isBlank())    parts.add(state.trim());
        if (pincode  != null && !pincode.isBlank())  parts.add(pincode.trim());
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // Booking-side field fallback lives in resolveBookingFallback() above —
    // it covers device_photos_json AND missing_parts_json, device_security_value,
    // customer_approval, estimated_ready_at, estimated_delivery_at in a single
    // booking lookup, so the booking detail screens render the same data
    // whether the ticket was minted before or after mintTicketFromBooking
    // learned to copy these snapshot fields at mint time.

    // ---------- IMEI uniqueness -------------------------------------------

    // A booking past these no longer holds the device, so the same handset can
    // legitimately come back for another repair and reuse its IMEI. Anything
    // else counts as still open and blocks a second booking from claiming it.
    private static final java.util.Set<String> IMEI_RELEASED_STATUSES =
            java.util.Set.of("DELIVERED", "CANCELLED", "RETURNED");

    /**
     * Digits only, 14–17 long (15 is the IMEI proper; 14 is the TAC+serial
     * without the check digit, 16/17 cover IMEISV). Mirrors normaliseImei in
     * the shop app so the two ends agree on what a valid number looks like.
     * Returns null for blank input; throws for a non-blank value that isn't a
     * plausible IMEI.
     */
    private static String normalizeImei(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        if (digits.length() < 14 || digits.length() > 17) {
            throw new IllegalArgumentException(
                    "IMEI must be 14 to 17 digits — got " + digits.length());
        }
        return digits;
    }

    /**
     * Refuse an IMEI that another still-open booking in this shop already
     * holds. The frontend asks first so it can show a proper alert, but this is
     * the check that counts: two staff members can be on the same device at
     * once, and only the write path is ordered.
     */
    private void assertImeiAvailable(UUID shopId, UUID ticketId, String imei) {
        findImeiConflict(shopId, ticketId, imei).ifPresent(other -> {
            throw new ImeiConflictException(
                    "IMEI " + imei + " is already on booking "
                            + (other.getTrackingId() != null ? other.getTrackingId() : other.getId())
                            + ", which is still open.",
                    other.getTrackingId());
        });
    }

    private java.util.Optional<Ticket> findImeiConflict(UUID shopId, UUID ticketId, String imei) {
        return ticketRepository.findByShopIdAndImei(shopId, imei).stream()
                .filter(t -> ticketId == null || !ticketId.equals(t.getId()))
                .filter(t -> !IMEI_RELEASED_STATUSES.contains(
                        t.getStatus() == null ? "" : t.getStatus().trim().toUpperCase()))
                .findFirst();
    }

    /**
     * Read-only counterpart of {@link #assertImeiAvailable}, for the shop app's
     * pre-flight check. Used in BOTH branches of the invoice gate: on a booking
     * that already carries an IMEI there is nothing to save, so this is the only
     * thing standing between a duplicate number and an invoice.
     */
    @Transactional(readOnly = true)
    public ImeiAvailabilityResponse checkImeiAvailability(UUID shopId, UUID ticketId, String rawImei) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        // Fall back to what the ticket already carries so the caller can ask
        // "is my own IMEI still clean?" without repeating it in the query.
        String candidate = rawImei != null && !rawImei.isBlank() ? rawImei : t.getImei();
        String imei;
        try {
            imei = normalizeImei(candidate);
        } catch (IllegalArgumentException e) {
            return ImeiAvailabilityResponse.builder()
                    .imei(candidate).available(false).valid(false).message(e.getMessage()).build();
        }
        if (imei == null) {
            return ImeiAvailabilityResponse.builder()
                    .imei(null).available(false).valid(false)
                    .message("No IMEI on this booking yet.").build();
        }
        return findImeiConflict(shopId, t.getId(), imei)
                .map(other -> ImeiAvailabilityResponse.builder()
                        .imei(imei).available(false).valid(true)
                        .conflictTrackingId(other.getTrackingId())
                        .message("This IMEI number is already associated with another service booking.")
                        .build())
                .orElseGet(() -> ImeiAvailabilityResponse.builder()
                        .imei(imei).available(true).valid(true).build());
    }

    // ---------- Repair notes ----------------------------------------------

    @Transactional
    public RepairNoteResponse addRepairNote(UUID shopId, UUID ticketId, UUID authorId, CreateRepairNoteRequest body) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        // Serialize optional image attachments as a JSON array string in the
        // images_json TEXT column (matches how tickets.device_photos_json /
        // tickets.technician_photos_json are stored). Empty list collapses to
        // null so reads can short-circuit on isBlank.
        String imagesJson = null;
        if (body.getImageUrls() != null && !body.getImageUrls().isEmpty()) {
            try {
                imagesJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(body.getImageUrls());
            } catch (Exception ignored) { /* leave null on malformed input */ }
        }
        String audioUrl = body.getAudioUrl();
        if (audioUrl != null && audioUrl.isBlank()) audioUrl = null;
        RepairNote saved = repairNoteRepository.save(RepairNote.builder()
                .ticketId(t.getId())
                .authorId(authorId)
                .note(body.getNote())
                .isInternal(Boolean.TRUE.equals(body.getIsInternal()))
                .audioUrl(audioUrl)
                .imagesJson(imagesJson)
                .build());
        // Customer-visible compliance notes light up the "Technician Issue
        // Verified & Updated" step. Internal-only notes stay off the timeline
        // so the customer doesn't see private shop chatter. The event note
        // carries the technician's actual text so the customer sees what was
        // verified, not just a canned label.
        if (!Boolean.TRUE.equals(body.getIsInternal())) {
            String noteText = body.getNote() != null && !body.getNote().isBlank()
                    ? body.getNote()
                    : "Technician Issue Verified & Updated";
            // Carry the voice-note + image attachments onto the timeline event
            // so the customer / owner Issue Verified row can render the media
            // inline without a separate fetch from repair_notes.
            // One server clock reading for this submit. Taken here, not in the
            // request and not in the app, so both rows below are stamped from the
            // same instant even if the two writes straddle a tick.
            java.time.Instant verifiedAt = java.time.Instant.now();
            emitOrUpdateBookingEvent(t.getId(),
                    KEY_ISSUE_VERIFIED,
                    noteText, "TECHNICIAN",
                    audioUrl, imagesJson, verifiedAt);
            // Nothing else is emitted here. "Repair Work In Progress" used to be
            // written alongside this row, carrying the identical timestamp — but
            // verifying the issue is diagnosis, and the customer has not yet seen
            // (let alone approved) the re-estimate that follows it. That row is
            // the technician's to raise, from their checklist, when work starts.
        }
        return toNoteResponse(saved);
    }

    /**
     * Edit a note the technician already submitted (Submitted Notes → Edit).
     *
     * Two rules make this behave like a correction rather than a new action:
     *
     *  1. repair_notes.created_at is NOT touched — the note still belongs to the
     *     moment the work was recorded. Only updated_at moves, and the app shows
     *     it as an "Edited" marker.
     *  2. When the edited note is the one currently on the customer / owner
     *     Service History rail, that row is refreshed in place with the new text
     *     and media but pinned to the row's EXISTING timestamp. Letting
     *     emitOrUpdateBookingEvent stamp now() would drag "Technician Issue
     *     Verified & Updated" to the top of the rail — and hand it the NEW badge —
     *     because someone fixed a typo.
     *
     * Editing an OLDER note leaves the rail alone: the row mirrors the latest
     * customer-visible note, and an old note's text must not overwrite it.
     * No notification is raised either way — emitOrUpdateBookingEvent only pings
     * the feeds on a first-time emit.
     */
    @Transactional
    public RepairNoteResponse updateRepairNote(UUID shopId, UUID ticketId, UUID noteId,
                                               CreateRepairNoteRequest body) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        RepairNote note = repairNoteRepository.findByIdAndTicketId(noteId, t.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Note not found: " + noteId));

        String imagesJson = null;
        if (body.getImageUrls() != null && !body.getImageUrls().isEmpty()) {
            try {
                imagesJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(body.getImageUrls());
            } catch (Exception ignored) { /* leave null on malformed input */ }
        }
        String audioUrl = body.getAudioUrl();
        if (audioUrl != null && audioUrl.isBlank()) audioUrl = null;

        // Clearing an attachment is a real edit: an absent/empty imageUrls list
        // means "the technician removed the photos", so null is written rather
        // than the previous value being preserved.
        note.setNote(body.getNote());
        note.setAudioUrl(audioUrl);
        note.setImagesJson(imagesJson);
        if (body.getIsInternal() != null) note.setIsInternal(body.getIsInternal());
        note.setUpdatedAt(java.time.Instant.now());
        RepairNote saved = repairNoteRepository.save(note);

        if (!Boolean.TRUE.equals(saved.getIsInternal()) && isLatestVisibleNote(t.getId(), saved)) {
            String noteText = saved.getNote() != null && !saved.getNote().isBlank()
                    ? saved.getNote()
                    : "Technician Issue Verified & Updated";
            emitOrUpdateBookingEvent(t.getId(), KEY_ISSUE_VERIFIED, noteText, "TECHNICIAN",
                    saved.getAudioUrl(), saved.getImagesJson(),
                    existingEventInstant(t.getId(), KEY_ISSUE_VERIFIED, saved.getCreatedAt()));
        }
        return toNoteResponse(saved);
    }

    /** True when no other customer-visible note on this ticket is newer. */
    private boolean isLatestVisibleNote(UUID ticketId, RepairNote note) {
        return repairNoteRepository.findByTicketIdOrderByCreatedAtDesc(ticketId).stream()
                .filter(n -> !Boolean.TRUE.equals(n.getIsInternal()))
                .findFirst()
                .map(latest -> latest.getId().equals(note.getId()))
                .orElse(true);
    }

    /** Current timestamp of a timeline row, so a refresh can pin it in place. */
    private java.time.Instant existingEventInstant(UUID ticketId, String statusKey,
                                                   java.time.Instant fallback) {
        return platformRepairBookingRepository.findByTicketId(ticketId)
                .flatMap(b -> platformRepairBookingEventRepository
                        .findCurrentCycle(b.getId())
                        .stream()
                        .filter(e -> statusKey.equalsIgnoreCase(e.getStatus()))
                        .findFirst()
                        .map(PlatformRepairBookingEvent::getCreatedAt))
                .orElse(fallback != null ? fallback : java.time.Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RepairNoteResponse> listRepairNotes(UUID shopId, UUID ticketId) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        return repairNoteRepository.findByTicketIdOrderByCreatedAtDesc(t.getId()).stream()
                .map(this::toNoteResponse)
                .toList();
    }

    private RepairNoteResponse toNoteResponse(RepairNote n) {
        return RepairNoteResponse.builder()
                .id(n.getId())
                .ticketId(n.getTicketId())
                .authorId(n.getAuthorId())
                .note(n.getNote())
                .isInternal(n.getIsInternal())
                .audioUrl(n.getAudioUrl())
                .imageUrls(parseImagesJson(n.getImagesJson()))
                .createdAt(n.getCreatedAt())
                .updatedAt(n.getUpdatedAt())
                .build();
    }

    // Re-hydrate the images_json TEXT column (stored as ["url", ...]) into a
    // List<String>. Shared between repair_notes responses and timeline event
    // responses so both surfaces parse the same shape.
    private static List<String> parseImagesJson(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            List<?> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, List.class);
            List<String> urls = new ArrayList<>();
            for (Object o : parsed) {
                if (o != null) urls.add(o.toString());
            }
            return urls;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    // ---------- Solution packs --------------------------------------------

    @Transactional
    public SolutionPackResponse addSolutionPack(UUID shopId, UUID ticketId, UUID uploadedBy, CreateSolutionPackRequest body) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        String type = body.getPackType() == null ? "NEW" : body.getPackType().trim().toUpperCase();
        if (!"REFERENCE".equals(type) && !"NEW".equals(type)) type = "NEW";
        TicketSolutionPack saved = ticketSolutionPackRepository.save(TicketSolutionPack.builder()
                .ticketId(t.getId())
                .shopId(t.getShopId())
                .packType(type)
                .title(body.getTitle())
                .description(body.getDescription())
                .fileUrl(body.getFileUrl())
                .fileName(body.getFileName())
                .uploadedBy(uploadedBy)
                .brandId(body.getBrandId())
                .modelId(body.getModelId())
                .brandName(body.getBrandName())
                .modelName(body.getModelName())
                .issueCategory(body.getIssueCategory())
                .issueSubcategory(body.getIssueSubcategory())
                .issueCategoryId(body.getIssueCategoryId())
                .issueSubcategoryId(body.getIssueSubcategoryId())
                .filesJson(body.getFilesJson())
                .build());
        // A new (technician-uploaded) solution pack signals the "Issue identified"
        // step on the customer/owner history rail. REFERENCE packs are just the
        // tech viewing existing knowledge-base entries, so they don't count.
        if ("NEW".equals(type)) {
            String note = body.getIssueCategory() != null && !body.getIssueCategory().isBlank()
                    ? "Issue identified: " + body.getIssueCategory()
                            + (body.getIssueSubcategory() != null && !body.getIssueSubcategory().isBlank()
                                    ? " — " + body.getIssueSubcategory() : "")
                    : "Issue identified by technician";
            emitBookingEvent(t.getId(), "ISSUE_IDENTIFIED", note, "TECHNICIAN");
        }
        return toPackResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<SolutionPackResponse> searchSolutionPacks(UUID shopId, String packType,
                                                          UUID brandId, UUID modelId,
                                                          UUID issueCategoryId, UUID issueSubcategoryId,
                                                          String issueCategory, String issueSubcategory) {
        String type = packType == null || packType.isBlank() ? null : packType.trim().toUpperCase();
        String cat = issueCategory == null || issueCategory.isBlank() ? null : issueCategory.trim();
        String sub = issueSubcategory == null || issueSubcategory.isBlank() ? null : issueSubcategory.trim();
        return ticketSolutionPackRepository
                .searchByShop(shopId, type, brandId, modelId, issueCategoryId, issueSubcategoryId, cat, sub)
                .stream().map(this::toPackResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SolutionPackResponse> listSolutionPacks(UUID shopId, UUID ticketId, String packType) {
        Ticket t = ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        List<TicketSolutionPack> rows = packType == null || packType.isBlank()
                ? ticketSolutionPackRepository.findByTicketIdOrderByCreatedAtDesc(t.getId())
                : ticketSolutionPackRepository.findByTicketIdAndPackTypeOrderByCreatedAtDesc(t.getId(), packType.trim().toUpperCase());
        return rows.stream().map(this::toPackResponse).toList();
    }

    private SolutionPackResponse toPackResponse(TicketSolutionPack p) {
        return SolutionPackResponse.builder()
                .id(p.getId())
                .ticketId(p.getTicketId())
                .shopId(p.getShopId())
                .packType(p.getPackType())
                .title(p.getTitle())
                .description(p.getDescription())
                .fileUrl(p.getFileUrl())
                .fileName(p.getFileName())
                .uploadedBy(p.getUploadedBy())
                .brandId(p.getBrandId())
                .modelId(p.getModelId())
                .brandName(p.getBrandName())
                .modelName(p.getModelName())
                .issueCategory(p.getIssueCategory())
                .issueSubcategory(p.getIssueSubcategory())
                .issueCategoryId(p.getIssueCategoryId())
                .issueSubcategoryId(p.getIssueSubcategoryId())
                .filesJson(p.getFilesJson())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
