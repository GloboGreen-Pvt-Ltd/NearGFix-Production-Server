package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.ReEstimateRequest;
import com.repairshop.saas.ticket.dto.ReEstimateRevisionDtos;
import com.repairshop.saas.ticket.dto.TicketResponse;
import com.repairshop.saas.ticket.entity.PlatformRepairBooking;
import com.repairshop.saas.ticket.entity.PlatformRepairBookingEvent;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.entity.TicketReEstimateRevision;
import com.repairshop.saas.ticket.exception.ReEstimateException;
import com.repairshop.saas.ticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Re-estimate workflow end to end through the real TicketService +
 * ReEstimateWorkflowService, with in-memory revision and event stores.
 */
class ReEstimateWorkflowServiceTest {

    private static final String ORIGINAL_ITEMS = "[{\"id\":\"s1\",\"label\":\"Display\",\"amount\":1500}]";
    private static final List<String> OWNER = List.of("SHOP_OWNER");
    private static final List<String> TECH = List.of("TECHNICIAN");

    private final UUID shopId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final UUID techId = UUID.randomUUID();
    private final UUID techUserId = UUID.randomUUID();
    private final UUID otherTechUserId = UUID.randomUUID();
    private final UUID ownerUserId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final Instant originalDelivery = Instant.parse("2026-11-03T10:00:00Z");

    private TicketRepository ticketRepository;
    private PlatformRepairBookingEventRepository eventRepository;
    private TicketReEstimateRevisionRepository revisionRepository;
    private CustomerOrderMirrorService mirror;

    private final Map<UUID, TicketReEstimateRevision> revisions = new LinkedHashMap<>();
    private final List<PlatformRepairBookingEvent> events = new ArrayList<>();

    private Ticket ticket;
    private TicketService ticketService;
    private ReEstimateWorkflowService workflow;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setShopId(shopId);
        ticket.setStatus("IN_REPAIR");
        ticket.setPriceItemsJson(ORIGINAL_ITEMS);
        ticket.setEstimatedPrice(new BigDecimal("1500"));
        ticket.setRepairServicesSummary("Display");
        ticket.setCustomerApproval(true);
        ticket.setCustomerName("Nandha K");
        ticket.setEstimatedDeliveryAt(originalDelivery);
        ticket.setPaymentType("ADVANCE");
        ticket.setPaymentAmount(new BigDecimal("500"));
        ticket.setPaymentStatus("PAID");
        ticket.setBalanceAmount(new BigDecimal("1000"));
        ticket.setAssignedTechnicianId(techId);

        ticketRepository = mock(TicketRepository.class);
        when(ticketRepository.findByShopIdAndId(shopId, ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TechnicianRepository technicianRepository = mock(TechnicianRepository.class);
        Technician tech = Technician.builder().id(techId).shopId(shopId).userId(techUserId).name("Ravi").build();
        Technician other = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(otherTechUserId).name("Kumar").build();
        when(technicianRepository.findByShopIdAndUserId(shopId, techUserId)).thenReturn(Optional.of(tech));
        when(technicianRepository.findByShopIdAndUserId(shopId, otherTechUserId)).thenReturn(Optional.of(other));
        when(technicianRepository.findById(techId)).thenReturn(Optional.of(tech));

        PlatformRepairBookingRepository bookingRepository = mock(PlatformRepairBookingRepository.class);
        PlatformRepairBooking booking = PlatformRepairBooking.builder().id(bookingId).ticketId(ticketId).build();
        when(bookingRepository.findByTicketId(ticketId)).thenReturn(Optional.of(booking));

        eventRepository = mock(PlatformRepairBookingEventRepository.class);
        when(eventRepository.findCurrentCycle(bookingId)).thenAnswer(inv -> sortedEvents());
        when(eventRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenAnswer(inv -> sortedEvents());
        when(eventRepository.save(any(PlatformRepairBookingEvent.class))).thenAnswer(inv -> {
            PlatformRepairBookingEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now());
            if (events.stream().noneMatch(x -> x == e)) events.add(e);
            return e;
        });

        revisionRepository = mock(TicketReEstimateRevisionRepository.class);
        when(revisionRepository.lockByTicketIdAndStatusIn(eq(ticketId), anyCollection()))
                .thenAnswer(inv -> byStatus(inv.getArgument(1)));
        when(revisionRepository.findByTicketIdAndStatusIn(eq(ticketId), anyCollection()))
                .thenAnswer(inv -> byStatus(inv.getArgument(1)));
        when(revisionRepository.lockById(any())).thenAnswer(inv -> Optional.ofNullable(revisions.get(inv.<UUID>getArgument(0))));
        when(revisionRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(revisions.get(inv.<UUID>getArgument(0))));
        when(revisionRepository.findByTicketIdOrderByRevisionNoDesc(ticketId)).thenAnswer(inv -> revisions.values().stream()
                .sorted(Comparator.comparing(TicketReEstimateRevision::getRevisionNo).reversed()).toList());
        when(revisionRepository.maxRevisionNo(ticketId)).thenAnswer(inv -> revisions.values().stream()
                .mapToInt(TicketReEstimateRevision::getRevisionNo).max().orElse(0));
        when(revisionRepository.save(any(TicketReEstimateRevision.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(revisionRepository.saveAndFlush(any(TicketReEstimateRevision.class))).thenAnswer(inv -> store(inv.getArgument(0)));

        mirror = mock(CustomerOrderMirrorService.class);
        workflow = new ReEstimateWorkflowService(revisionRepository, ticketRepository, technicianRepository,
                bookingRepository, eventRepository, mirror, mock(JdbcTemplate.class));
        ticketService = new TicketService(ticketRepository, technicianRepository, bookingRepository, eventRepository,
                mock(PlatformCustomerAddressRepository.class), mock(PlatformCustomerUserRepository.class),
                mock(RepairNoteRepository.class), mock(TicketSolutionPackRepository.class),
                mock(MasterTechnicianWorkStatusViewRepository.class), mock(InvoiceRepository.class),
                mirror, mock(TicketNumberService.class), workflow,
                mock(com.repairshop.saas.common.notify.ShopPushService.class),
                mock(com.repairshop.saas.common.notify.PushService.class));

        // Repair work had started before the extra issue was found.
        event("IN_REPAIR", Instant.now().minusSeconds(3600));
    }

    // ---- in-memory stores ------------------------------------------------

    private List<PlatformRepairBookingEvent> sortedEvents() {
        return events.stream().sorted(Comparator.comparing(PlatformRepairBookingEvent::getCreatedAt))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<TicketReEstimateRevision> byStatus(Collection<String> statuses) {
        return revisions.values().stream().filter(r -> statuses.contains(r.getStatus())).toList();
    }

    /** Mirrors uq_ticket_reestimate_open: one REQUESTED/AWAITING row per ticket. */
    private TicketReEstimateRevision store(TicketReEstimateRevision r) {
        if (r.getId() == null) r.setId(UUID.randomUUID());
        boolean open = ReEstimateWorkflowService.OPEN.contains(r.getStatus());
        if (open && revisions.values().stream().anyMatch(o -> !o.getId().equals(r.getId())
                && o.getTicketId().equals(r.getTicketId()) && ReEstimateWorkflowService.OPEN.contains(o.getStatus()))) {
            throw new DataIntegrityViolationException("uq_ticket_reestimate_open");
        }
        revisions.put(r.getId(), r);
        return r;
    }

    private void event(String key, Instant at) {
        events.add(PlatformRepairBookingEvent.builder().id(UUID.randomUUID()).bookingId(bookingId)
                .status(key).actor("TECHNICIAN").createdAt(at).build());
    }

    private Set<String> eventKeys() {
        return events.stream().map(PlatformRepairBookingEvent::getStatus).collect(Collectors.toSet());
    }

    // ---- request builders ------------------------------------------------

    private ReEstimateRevisionDtos.TechnicianRequest techRequest(String issue, String reason, String amount) {
        return new ReEstimateRevisionDtos.TechnicianRequest(issue, reason,
                amount == null ? null : new BigDecimal(amount), null, "[\"https://cdn/x.jpg\"]");
    }

    private ReEstimateRequest partnerRevision(UUID requestId, Boolean approvedNow) {
        ReEstimateRequest r = new ReEstimateRequest();
        ReEstimateRequest.Item it = new ReEstimateRequest.Item();
        it.setId("s2");
        it.setLabel("Charging Port");
        it.setAmount(new BigDecimal("800"));
        r.setItems(List.of(it));
        r.setReason("Charging port damage found");
        r.setRequestId(requestId);
        r.setCustomerApproved(approvedNow);
        r.setEstimatedDeliveryAt(originalDelivery.plusSeconds(86400));
        return r;
    }

    private ReEstimateRevisionDtos.Revision submitAwaiting() {
        ticketService.reEstimate(shopId, ticketId, partnerRevision(null, null), ownerUserId);
        return workflow.decorate(TicketResponse.builder().id(ticketId).build()).getActiveReEstimate();
    }

    private ReEstimateException expectCode(int status, String code, org.junit.jupiter.api.function.Executable call) {
        ReEstimateException ex = assertThrows(ReEstimateException.class, call);
        assertEquals(status, ex.getStatus().value());
        assertEquals(code, ex.getCode());
        return ex;
    }

    // ---- technician request ---------------------------------------------

    @Test
    void requestByTechnicianNotAssignedIsForbidden() {
        expectCode(403, ReEstimateException.NOT_ASSIGNED_TECHNICIAN, () -> workflow.requestReEstimate(
                shopId, otherTechUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800")));
        assertTrue(revisions.isEmpty());
    }

    @Test
    void requestNeedsReasonAndSomethingToPrice() {
        expectCode(400, ReEstimateException.VALIDATION, () -> workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "no", "800")));
        expectCode(400, ReEstimateException.VALIDATION, () -> workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest(null, "Found while testing", null)));
        expectCode(400, ReEstimateException.VALIDATION, () -> workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port", "Found while testing", "-1")));
    }

    @Test
    void requestOnlyWhileInDiagnosisOrRepair() {
        ticket.setStatus("READY");
        expectCode(409, ReEstimateException.INVALID_TRANSITION, () -> workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800")));
    }

    @Test
    void requestEmitsEventDuplicateIs409AndRetryIsIdempotent() {
        ReEstimateRevisionDtos.Revision first = workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800"));
        assertEquals("REQUESTED", first.getStatus());
        assertEquals("TECHNICIAN", first.getSource());
        assertEquals("Ravi", first.getTechnicianName());
        assertEquals(List.of("https://cdn/x.jpg"), first.getRequestImages());
        assertEquals("IN_REPAIR", ticket.getStatus(), "a request alone doesn't move the ticket");
        assertTrue(eventKeys().contains(ReEstimateWorkflowService.KEY_REQUESTED));

        ReEstimateRevisionDtos.Revision retry = workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800"));
        assertEquals(first.getId(), retry.getId());

        expectCode(409, ReEstimateException.REESTIMATE_PENDING, () -> workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Battery swollen", "Found while testing", "900")));
        assertEquals(1, revisions.size());
    }

    // ---- partner submit --------------------------------------------------

    @Test
    void partnerSubmitLinksTheRequestAndSnapshotsTheOriginal() {
        ReEstimateRevisionDtos.Revision req = workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800"));

        ticketService.reEstimate(shopId, ticketId, partnerRevision(req.getId(), null), ownerUserId);

        assertEquals(1, revisions.size(), "the request row is converted, not duplicated");
        TicketReEstimateRevision r = revisions.get(req.getId());
        assertEquals("AWAITING_APPROVAL", r.getStatus());
        assertEquals("TECHNICIAN", r.getSource());
        assertEquals(0, new BigDecimal("1500").compareTo(r.getOriginalEstimatedPrice()));
        assertEquals(ORIGINAL_ITEMS, r.getOriginalPriceItemsJson());
        assertEquals(0, new BigDecimal("800").compareTo(r.getAdditionalAmount()));
        assertEquals(0, new BigDecimal("2300").compareTo(r.getRevisedTotal()));
        assertEquals(ownerUserId, r.getSubmittedByUserId());
        assertEquals("QUOTED", ticket.getStatus());
        assertEquals(0, new BigDecimal("2300").compareTo(ticket.getEstimatedPrice()));

        TicketResponse detail = workflow.decorate(TicketResponse.builder().id(ticketId).assignedTechnicianId(techId).build());
        assertEquals("Ravi", detail.getAssignedTechnicianName());
        assertEquals(req.getId(), detail.getActiveReEstimate().getId());
        assertEquals("Charging Port", detail.getLatestReEstimate().getItems().get(0).getLabel());
    }

    @Test
    void secondPartnerRevisionWhileAwaitingIs409() {
        submitAwaiting();
        expectCode(409, ReEstimateException.REESTIMATE_PENDING,
                () -> ticketService.reEstimate(shopId, ticketId, partnerRevision(null, null), ownerUserId));
    }

    @Test
    void approvedAtTheCounterClosesTheRevisionAndResumes() {
        ticketService.reEstimate(shopId, ticketId, partnerRevision(null, true), ownerUserId);
        TicketReEstimateRevision r = revisions.values().iterator().next();
        assertEquals("APPROVED", r.getStatus());
        assertEquals("SHOP", r.getDecidedVia());
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertTrue(eventKeys().containsAll(Set.of("RE_ESTIMATE_APPROVED", "REPAIR_WORK_RESUMED", "CUSTOMER_APPROVED")));
    }

    // ---- decision --------------------------------------------------------

    @Test
    void approveKeepsRevisedPriceAndResumesRepair() {
        ReEstimateRevisionDtos.Revision open = submitAwaiting();

        ReEstimateRevisionDtos.Revision done = workflow.decide(shopId, ownerUserId, OWNER, ticketId, open.getId(),
                new ReEstimateRevisionDtos.Decision("APPROVED", null));

        assertEquals("APPROVED", done.getStatus());
        assertEquals("APPROVED", done.getDecision());
        assertEquals("SHOP", done.getDecidedVia());
        assertNotNull(done.getDecidedAt());
        assertEquals(0, new BigDecimal("2300").compareTo(ticket.getEstimatedPrice()));
        assertTrue(ticket.getPriceItemsJson().contains("Charging Port"));
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval());
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertTrue(eventKeys().containsAll(Set.of("RE_ESTIMATE_APPROVED", "REPAIR_WORK_RESUMED", "CUSTOMER_APPROVED")));
        Instant approvedAt = eventAt("RE_ESTIMATE_APPROVED");
        assertTrue(eventAt("REPAIR_WORK_RESUMED").isAfter(approvedAt), "resumed row follows the decision");
        assertNull(workflow.decorate(TicketResponse.builder().id(ticketId).build()).getActiveReEstimate());
    }

    @Test
    void rejectRestoresOriginalPriceAndItemsExactly() {
        ReEstimateRevisionDtos.Revision open = submitAwaiting();
        assertNull(ticket.getCustomerApproval(), "a revised price awaits a fresh approval");

        ReEstimateRevisionDtos.Revision done = workflow.decide(shopId, ownerUserId, OWNER, ticketId, open.getId(),
                new ReEstimateRevisionDtos.Decision("REJECTED", "Customer declined the port repair"));

        assertEquals("REJECTED", done.getStatus());
        assertEquals("Customer declined the port repair", done.getDecisionReason());
        assertEquals(ORIGINAL_ITEMS, ticket.getPriceItemsJson());
        assertEquals(new BigDecimal("1500"), ticket.getEstimatedPrice());
        assertEquals("Display", ticket.getRepairServicesSummary());
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval(), "pre-revision approval restored");
        assertEquals(originalDelivery, ticket.getEstimatedDeliveryAt());
        assertEquals(0, new BigDecimal("1000").compareTo(ticket.getBalanceAmount()));
        // A rejected re-estimate closes the booking (no repair resume).
        assertEquals("CANCELLED", ticket.getStatus());
        assertTrue(eventKeys().containsAll(Set.of("RE_ESTIMATE_REJECTED", "CANCELLED")));
        assertFalse(eventKeys().contains("REPAIR_WORK_RESUMED"));
        assertFalse(eventKeys().contains("CUSTOMER_REJECTED"));
        assertFalse(eventKeys().contains("RE_ESTIMATE_APPROVED"));
        verify(mirror, atLeastOnce()).mirrorOnUpsertInline(ticket);
    }

    @Test
    void decisionWithoutRepairStartedLandsOnApproved() {
        events.clear();
        ticket.setStatus("IN_DIAGNOSIS");
        ReEstimateRevisionDtos.Revision open = submitAwaiting();
        assertEquals("QUOTED", ticket.getStatus());
        workflow.decide(shopId, ownerUserId, OWNER, ticketId, open.getId(),
                new ReEstimateRevisionDtos.Decision("APPROVED", null));
        assertEquals("APPROVED", ticket.getStatus());
        assertFalse(eventKeys().contains("REPAIR_WORK_RESUMED"));
    }

    @Test
    void sameDecisionTwiceReturnsStoredResultAndConflictingOneIs409() {
        ReEstimateRevisionDtos.Revision open = submitAwaiting();
        ReEstimateRevisionDtos.Revision first = workflow.decide(shopId, ownerUserId, OWNER, ticketId, open.getId(),
                new ReEstimateRevisionDtos.Decision("APPROVED", null));
        int eventsAfterFirst = events.size();

        ReEstimateRevisionDtos.Revision again = workflow.decide(shopId, ownerUserId, OWNER, ticketId, open.getId(),
                new ReEstimateRevisionDtos.Decision("APPROVED", null));
        assertEquals(first.getDecidedAt(), again.getDecidedAt());
        assertEquals(eventsAfterFirst, events.size());

        expectCode(409, ReEstimateException.INVALID_TRANSITION, () -> workflow.decide(shopId, ownerUserId, OWNER,
                ticketId, open.getId(), new ReEstimateRevisionDtos.Decision("REJECTED", null)));
        assertEquals(0, new BigDecimal("2300").compareTo(ticket.getEstimatedPrice()));
    }

    @Test
    void technicianCannotDecideAndRequestCannotBeDecided() {
        ReEstimateRevisionDtos.Revision req = workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800"));
        expectCode(403, ReEstimateException.ROLE_NOT_ALLOWED, () -> workflow.decide(shopId, techUserId, TECH,
                ticketId, req.getId(), new ReEstimateRevisionDtos.Decision("APPROVED", null)));
        expectCode(409, ReEstimateException.INVALID_TRANSITION, () -> workflow.decide(shopId, ownerUserId, OWNER,
                ticketId, req.getId(), new ReEstimateRevisionDtos.Decision("APPROVED", null)));
    }

    @Test
    void customerAppApprovalConvergesOnTheRevision() {
        ReEstimateRevisionDtos.Revision open = submitAwaiting();
        // order-service#customerApproval flips the flag and writes CUSTOMER_APPROVED.
        ticket.setCustomerApproval(true);
        Instant tapped = Instant.now();
        event("CUSTOMER_APPROVED", tapped);

        workflow.reconcile(shopId, ticketId);

        TicketReEstimateRevision r = revisions.get(open.getId());
        assertEquals("APPROVED", r.getStatus());
        assertEquals("CUSTOMER_APP", r.getDecidedVia());
        assertEquals(tapped, r.getDecidedAt());
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertTrue(eventKeys().contains("RE_ESTIMATE_APPROVED"));
    }

    // ---- automatic transitions -------------------------------------------

    @Test
    void qualityCheckAutoAddsReadyAndIsRecordedOnce() {
        ticketService.emitProgressStepEvent(shopId, ticketId, "QUALITY_CHECK_COMPLETED", null, "TECHNICIAN");
        assertTrue(eventKeys().containsAll(Set.of("QUALITY_CHECK_COMPLETED", "READY")));
        long before = events.size();
        // The shop sliding QC afterwards is a no-op: one completion record only.
        ticketService.emitProgressStepEvent(shopId, ticketId, "QUALITY_CHECK_COMPLETED", null, "OWNER");
        assertEquals(before, events.size());
    }

    @Test
    void invoiceGeneratedAutoAddsOutForDelivery() {
        ticketService.emitProgressStepEvent(shopId, ticketId, "INVOICE_GENERATED", null, "OWNER");
        assertTrue(eventKeys().containsAll(Set.of("INVOICE_GENERATED", "DELIVERED_PROCESSING")));
    }

    // ---- completion gate -------------------------------------------------

    @Test
    void completionIsBlockedWhileARevisionIsOpen() {
        ReEstimateRevisionDtos.Revision req = workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800"));
        for (String key : List.of("REPAIR_COMPLETED", "QUALITY_CHECK_COMPLETED", "READY")) {
            expectCode(409, ReEstimateException.REESTIMATE_PENDING,
                    () -> ticketService.emitProgressStepEvent(shopId, ticketId, key, null, "TECHNICIAN"));
        }
        assertFalse(eventKeys().contains("REPAIR_COMPLETED"));

        workflow.dismiss(shopId, ownerUserId, OWNER, ticketId, req.getId(), new ReEstimateRevisionDtos.Reason("Not needed"));
        assertEquals("DISMISSED", revisions.get(req.getId()).getStatus());
        ticketService.emitProgressStepEvent(shopId, ticketId, "REPAIR_COMPLETED", null, "TECHNICIAN");
        assertTrue(eventKeys().contains("REPAIR_COMPLETED"));
    }

    @Test
    void customerRejectingTheRepairClosesTheAwaitingRevisionAndRestoresThePrice() {
        ReEstimateRevisionDtos.Revision open = submitAwaiting();

        // The shop must give the customer's reason; without one nothing changes.
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> ticketService.emitProgressStepEvent(shopId, ticketId, "CUSTOMER_REJECTED", null, "OWNER"));
        assertEquals("AWAITING_APPROVAL", revisions.get(open.getId()).getStatus());

        ticketService.emitProgressStepEvent(shopId, ticketId, "CUSTOMER_REJECTED", "Customer declined the cost", "OWNER");

        TicketReEstimateRevision r = revisions.get(open.getId());
        assertEquals("REJECTED", r.getStatus());
        assertTrue(r.getDecisionReason().contains("rejected the repair"));
        assertEquals(ORIGINAL_ITEMS, ticket.getPriceItemsJson());
        assertEquals(new BigDecimal("1500"), ticket.getEstimatedPrice());
        assertTrue(eventKeys().contains("CUSTOMER_REJECTED"));
        assertFalse(eventKeys().contains("REPAIR_WORK_RESUMED"));
        assertFalse(eventKeys().contains("RE_ESTIMATE_REJECTED"));
    }

    @Test
    void cancellingTheBookingDismissesAPendingRequest() {
        ReEstimateRevisionDtos.Revision req = workflow.requestReEstimate(
                shopId, techUserId, TECH, ticketId, techRequest("Port damaged", "Found while testing", "800"));

        ticketService.updateStatus(shopId, ticketId, "CANCELLED", null);

        assertEquals("DISMISSED", revisions.get(req.getId()).getStatus());
        assertEquals("CANCELLED", ticket.getStatus());
        assertNull(workflow.decorate(TicketResponse.builder().id(ticketId).build()).getActiveReEstimate());
    }

    @Test
    void normalRepairCompletionIsUnaffected() {
        ticketService.emitProgressStepEvent(shopId, ticketId, "REPAIR_COMPLETED", null, "TECHNICIAN");
        ticketService.emitProgressStepEvent(shopId, ticketId, "READY", null, "TECHNICIAN");
        assertTrue(eventKeys().containsAll(Set.of("REPAIR_COMPLETED", "READY")));
        assertEquals("READY", ticket.getStatus());
        assertTrue(revisions.isEmpty());
    }

    @Test
    void acknowledgeIsIdempotentAndAssignedTechnicianOnly() {
        ReEstimateRevisionDtos.Revision open = submitAwaiting();
        workflow.decide(shopId, ownerUserId, OWNER, ticketId, open.getId(), new ReEstimateRevisionDtos.Decision("APPROVED", null));
        Instant first = workflow.acknowledge(shopId, techUserId, ticketId, open.getId()).getTechnicianAcknowledgedAt();
        assertNotNull(first);
        assertEquals(first, workflow.acknowledge(shopId, techUserId, ticketId, open.getId()).getTechnicianAcknowledgedAt());
        expectCode(403, ReEstimateException.NOT_ASSIGNED_TECHNICIAN,
                () -> workflow.acknowledge(shopId, otherTechUserId, ticketId, open.getId()));
        assertEquals(1, workflow.list(shopId, techUserId, TECH, ticketId).size());
    }

    private Instant eventAt(String key) {
        return events.stream().filter(e -> key.equals(e.getStatus())).findFirst().orElseThrow().getCreatedAt();
    }
}
