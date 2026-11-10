package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.ticket.dto.ReEstimateRevisionDtos;
import com.repairshop.saas.ticket.dto.TicketResponse;
import com.repairshop.saas.ticket.entity.PlatformRepairBookingEvent;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.entity.TicketReEstimateRevision;
import com.repairshop.saas.ticket.exception.ReEstimateException;
import com.repairshop.saas.ticket.exception.ResourceNotFoundException;
import com.repairshop.saas.ticket.repository.PlatformRepairBookingEventRepository;
import com.repairshop.saas.ticket.repository.PlatformRepairBookingRepository;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.repository.TicketReEstimateRevisionRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.repairshop.saas.ticket.exception.ReEstimateException.*;

/**
 * Technician re-estimate workflow (contract: reestimate-contract.md, migration 107).
 *
 *   technician request (REQUESTED) → partner re-estimate (AWAITING_APPROVAL, ticket QUOTED)
 *   → customer decision (APPROVED keeps the revised price / REJECTED restores the snapshot)
 *   → ticket back to IN_REPAIR (or APPROVED) + RE_ESTIMATE_* / REPAIR_WORK_RESUMED events.
 *
 * Kept out of TicketService on purpose: TicketService only calls the hooks
 * (openPartnerRevision / recordPartnerSubmission from reEstimate, onCustomerApproval,
 * assertCompletionAllowed). Tickets that never get a revision row behave exactly as before.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReEstimateWorkflowService {

    public static final String SOURCE_TECHNICIAN = "TECHNICIAN";
    public static final String SOURCE_PARTNER = "PARTNER";

    public static final String REQUESTED = "REQUESTED";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String DISMISSED = "DISMISSED";
    static final List<String> OPEN = List.of(REQUESTED, AWAITING_APPROVAL);

    public static final String VIA_SHOP = "SHOP";
    public static final String VIA_CUSTOMER_APP = "CUSTOMER_APP";

    // repair_booking_events.status keys (labels: defaultProgressLabel + both apps).
    public static final String KEY_REQUESTED = "TECHNICIAN_REESTIMATE_REQUESTED";
    public static final String KEY_APPROVED = "RE_ESTIMATE_APPROVED";
    public static final String KEY_REJECTED = "RE_ESTIMATE_REJECTED";
    public static final String KEY_RESUMED = "REPAIR_WORK_RESUMED";

    /** Ticket statuses a technician may raise a request from. */
    static final Set<String> REQUESTABLE_STATUSES = Set.of("IN_DIAGNOSIS", "APPROVED", "IN_REPAIR");

    /** Steps that close the repair — refused while a re-estimate is open (no invoice either). */
    static final Set<String> BLOCKED_WHILE_OPEN = Set.of(
            "QUALITY_CHECK_COMPLETED", "REPAIR_COMPLETED", "READY",
            "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING", "DELIVERED");

    /** JWT roles allowed to re-estimate / decide / dismiss (owner + shop login + platform admin). */
    static final Set<String> SHOP_ROLES = Set.of("SHOP_OWNER", "OWNER", "ADMIN", "SUPER_ADMIN");
    /** Employee roles that can hold an assignment (auth-service canonicalEmployeeRole). */
    static final Set<String> TECHNICIAN_ROLES = Set.of("TECHNICIAN", "STAFF");

    /** Same ladder as TicketService.LIFECYCLE_ORDER — the decision only ever moves forward. */
    private static final List<String> LIFECYCLE_ORDER = List.of(
            "CREATED", "IN_DIAGNOSIS", "QUOTED", "APPROVED", "IN_REPAIR",
            "READY", "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING", "DELIVERED");

    /** A retried technician request (same body) inside this window returns the stored row. */
    static final Duration RETRY_WINDOW = Duration.ofMinutes(2);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TicketReEstimateRevisionRepository revisionRepository;
    private final TicketRepository ticketRepository;
    private final TechnicianRepository technicianRepository;
    private final PlatformRepairBookingRepository platformRepairBookingRepository;
    private final PlatformRepairBookingEventRepository platformRepairBookingEventRepository;
    private final CustomerOrderMirrorService customerOrderMirrorService;
    private final JdbcTemplate jdbcTemplate;

    /** Snapshot taken by openPartnerRevision before TicketService.reEstimate touches the ticket. */
    public record PendingRevision(TicketReEstimateRevision request,
                                  BigDecimal originalEstimatedPrice,
                                  String originalPriceItemsJson,
                                  String originalStateJson) {}

    // ════════════════════════════════════════════════════════════════════
    // Roles
    // ════════════════════════════════════════════════════════════════════

    public static boolean hasAnyRole(Collection<?> roles, Set<String> wanted) {
        if (roles == null) return false;
        for (Object r : roles) {
            if (r != null && wanted.contains(String.valueOf(r).trim().toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static boolean isShopRole(Collection<?> roles) {
        return hasAnyRole(roles, SHOP_ROLES);
    }

    public static void requireShopRole(Collection<?> roles) {
        if (!isShopRole(roles)) {
            throw forbidden(ROLE_NOT_ALLOWED, "Only the shop can re-estimate or record the customer's decision");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Technician
    // ════════════════════════════════════════════════════════════════════

    /** POST /tickets/{id}/re-estimate-requests */
    @Transactional
    public ReEstimateRevisionDtos.Revision requestReEstimate(UUID shopId, UUID userId, Collection<?> roles,
                                                             UUID ticketId, ReEstimateRevisionDtos.TechnicianRequest body) {
        if (!hasAnyRole(roles, TECHNICIAN_ROLES)) {
            throw forbidden(ROLE_NOT_ALLOWED, "Only the assigned technician can request a re-estimate");
        }
        Ticket t = loadTicket(shopId, ticketId);
        Technician me = requireAssignedTechnician(t, shopId, userId);

        String reason = trimToNull(body == null ? null : body.getReason());
        if (reason == null || reason.length() < 3) {
            throw badRequest("Add the reason for the re-estimate (at least 3 characters)");
        }
        BigDecimal proposed = body.getProposedAmount();
        if (proposed != null && proposed.signum() < 0) throw badRequest("The proposed amount can't be negative");
        String issue = trimToNull(body.getAdditionalIssue());
        String notes = trimToNull(body.getTechnicianNotes());
        if (issue == null && proposed == null && notes == null) {
            throw badRequest("Describe the additional issue, a proposed amount or notes");
        }
        String images = normalizeImagesJson(body.getImagesJson());

        List<TicketReEstimateRevision> open = revisionRepository.lockByTicketIdAndStatusIn(t.getId(), OPEN);
        if (!open.isEmpty()) {
            TicketReEstimateRevision r = open.get(0);
            if (isRetryOf(r, userId, issue, reason, proposed, notes, images)) return toDto(r);
            throw conflict(REESTIMATE_PENDING, AWAITING_APPROVAL.equals(r.getStatus())
                    ? "A re-estimate is already awaiting customer approval"
                    : "A re-estimate request is already pending for this booking");
        }
        if (!REQUESTABLE_STATUSES.contains(upper(t.getStatus()))) {
            throw conflict(INVALID_TRANSITION, "A re-estimate can only be requested while the device is in diagnosis or repair");
        }

        Instant now = Instant.now();
        TicketReEstimateRevision r = TicketReEstimateRevision.builder()
                .ticketId(t.getId())
                .shopId(t.getShopId())
                .source(SOURCE_TECHNICIAN)
                .status(REQUESTED)
                .requestedByUserId(userId)
                .technicianId(me.getId())
                .technicianName(me.getName())
                .requestedAt(now)
                .additionalIssue(issue)
                .reason(reason)
                .proposedAmount(proposed)
                .technicianNotes(notes)
                .requestImagesJson(images)
                .build();
        r = saveNewOpen(r);
        emitOrUpdate(t.getId(), KEY_REQUESTED, requestNote(r), "TECHNICIAN", images, now);
        return toDto(r);
    }

    /** POST /tickets/{id}/re-estimates/{revisionId}/acknowledge — idempotent. */
    @Transactional
    public ReEstimateRevisionDtos.Revision acknowledge(UUID shopId, UUID userId, UUID ticketId, UUID revisionId) {
        Ticket t = loadTicket(shopId, ticketId);
        requireAssignedTechnician(t, shopId, userId);
        TicketReEstimateRevision r = lockRevision(t, revisionId);
        if (r.getTechnicianAcknowledgedAt() == null) {
            r.setTechnicianAcknowledgedAt(Instant.now());
            r = revisionRepository.save(r);
        }
        return toDto(r);
    }

    /** GET /tickets/{id}/re-estimates — owner/shop, or the assigned technician. Newest first. */
    @Transactional
    public List<ReEstimateRevisionDtos.Revision> list(UUID shopId, UUID userId, Collection<?> roles, UUID ticketId) {
        Ticket t = loadTicket(shopId, ticketId);
        if (!isShopRole(roles)) requireAssignedTechnician(t, shopId, userId);
        reconcileCustomerApproval(t);
        return revisionRepository.findByTicketIdOrderByRevisionNoDesc(t.getId()).stream().map(this::toDto).toList();
    }

    // ════════════════════════════════════════════════════════════════════
    // Partner re-estimate (hooks called from TicketService.reEstimate)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Before TicketService.reEstimate mutates the ticket: refuse while another
     * revision awaits approval, resolve the technician request it answers
     * (requestId, or the one open request when omitted) and snapshot the price
     * and scope a rejection must restore.
     */
    @Transactional
    public PendingRevision openPartnerRevision(Ticket t, UUID requestId) {
        reconcileCustomerApproval(t);
        List<TicketReEstimateRevision> open = revisionRepository.lockByTicketIdAndStatusIn(t.getId(), OPEN);
        if (firstWithStatus(open, AWAITING_APPROVAL) != null) {
            throw conflict(REESTIMATE_PENDING, "The previous re-estimate is still awaiting customer approval");
        }
        TicketReEstimateRevision requested = firstWithStatus(open, REQUESTED);
        if (requestId != null && (requested == null || !requestId.equals(requested.getId()))) {
            TicketReEstimateRevision asked = revisionRepository.findById(requestId)
                    .filter(r -> t.getId().equals(r.getTicketId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Re-estimate request not found: " + requestId));
            throw conflict(INVALID_TRANSITION, "This re-estimate request is already "
                    + asked.getStatus().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        return new PendingRevision(requested, t.getEstimatedPrice(), t.getPriceItemsJson(), snapshotState(t));
    }

    /** After the items/price are applied: the revision now awaits the customer. */
    @Transactional
    public void recordPartnerSubmission(PendingRevision pending, Ticket t, UUID submittedBy,
                                        List<Map<String, Object>> added, BigDecimal additionalAmount,
                                        BigDecimal revisedTotal, String reason, String imagesJson) {
        if (pending == null) return;
        TicketReEstimateRevision r = pending.request() != null
                ? pending.request()
                : TicketReEstimateRevision.builder()
                        .ticketId(t.getId())
                        .shopId(t.getShopId())
                        .source(SOURCE_PARTNER)
                        .build();
        r.setStatus(AWAITING_APPROVAL);
        r.setSubmittedByUserId(submittedBy);
        r.setSubmittedByName(userName(t.getShopId(), submittedBy));
        r.setSubmittedAt(Instant.now());
        r.setItemsJson(writeJson(added));
        r.setAdditionalAmount(additionalAmount);
        r.setOriginalEstimatedPrice(pending.originalEstimatedPrice());
        r.setOriginalPriceItemsJson(pending.originalPriceItemsJson());
        r.setOriginalStateJson(pending.originalStateJson());
        r.setRevisedTotal(revisedTotal);
        r.setRevisionReason(reason);
        r.setRevisionImagesJson(imagesJson);
        if (r.getId() == null) saveNewOpen(r);
        else revisionRepository.save(r);
    }

    // ════════════════════════════════════════════════════════════════════
    // Decision
    // ════════════════════════════════════════════════════════════════════

    /** POST /tickets/{id}/re-estimates/{revisionId}/decision — owner/shop only. */
    @Transactional
    public ReEstimateRevisionDtos.Revision decide(UUID shopId, UUID userId, Collection<?> roles, UUID ticketId,
                                                  UUID revisionId, ReEstimateRevisionDtos.Decision body) {
        requireShopRole(roles);
        String decision = upper(body == null ? null : body.getDecision());
        if (!APPROVED.equals(decision) && !REJECTED.equals(decision)) {
            throw badRequest("decision must be APPROVED or REJECTED");
        }
        String reason = trimToNull(body.getReason());
        Ticket t = loadTicket(shopId, ticketId);
        reconcileCustomerApproval(t);
        TicketReEstimateRevision r = lockRevision(t, revisionId);
        if (APPROVED.equals(r.getStatus()) || REJECTED.equals(r.getStatus())) {
            // Same verdict again (double tap / retry) → the stored result; the other one → refused.
            if (decision.equals(r.getStatus())) return toDto(r);
            throw conflict(INVALID_TRANSITION, "This re-estimate was already " + r.getStatus().toLowerCase(Locale.ROOT));
        }
        if (!AWAITING_APPROVAL.equals(r.getStatus())) {
            throw conflict(INVALID_TRANSITION, "Only a re-estimate awaiting customer approval can be approved or rejected");
        }
        finalizeDecision(t, r, decision, reason, userId, userName(shopId, userId), VIA_SHOP, "OWNER", true, Instant.now());
        return toDto(r);
    }

    /** POST /tickets/{id}/re-estimate-requests/{revisionId}/dismiss — owner/shop only. */
    @Transactional
    public ReEstimateRevisionDtos.Revision dismiss(UUID shopId, UUID userId, Collection<?> roles, UUID ticketId,
                                                   UUID revisionId, ReEstimateRevisionDtos.Reason body) {
        requireShopRole(roles);
        Ticket t = loadTicket(shopId, ticketId);
        TicketReEstimateRevision r = lockRevision(t, revisionId);
        if (DISMISSED.equals(r.getStatus())) return toDto(r);
        if (!REQUESTED.equals(r.getStatus())) {
            throw conflict(INVALID_TRANSITION, "Only a pending technician request can be dismissed");
        }
        String reason = trimToNull(body == null ? null : body.getReason());
        r.setStatus(DISMISSED);
        r.setDecision(DISMISSED);
        r.setDecidedAt(Instant.now());
        r.setDecidedByUserId(userId);
        r.setDecidedByName(userName(shopId, userId));
        r.setDecisionReason(reason);
        r.setDecidedVia(VIA_SHOP);
        r = revisionRepository.save(r);
        // The request row keeps its own timestamp; only its note records the outcome.
        appendEventNote(t.getId(), KEY_REQUESTED, " · Dismissed by shop" + (reason == null ? "" : ": " + reason));
        return toDto(r);
    }

    /**
     * Hook from TicketService.onCustomerApproved (shop ticked the approval, or
     * approved at the counter on submit): an AWAITING_APPROVAL revision is
     * approved by it. CUSTOMER_APPROVED itself was already written by the caller.
     */
    @Transactional
    public void onCustomerApproval(Ticket t, String actor) {
        if (t == null || t.getId() == null) return;
        TicketReEstimateRevision r = firstWithStatus(
                revisionRepository.lockByTicketIdAndStatusIn(t.getId(), List.of(AWAITING_APPROVAL)), AWAITING_APPROVAL);
        if (r == null) return;
        boolean customer = "USER".equalsIgnoreCase(actor) || "CUSTOMER".equalsIgnoreCase(actor);
        finalizeDecision(t, r, APPROVED, null, null, customer ? t.getCustomerName() : null,
                customer ? VIA_CUSTOMER_APP : VIA_SHOP, customer ? "USER" : "OWNER", false, Instant.now());
    }

    /**
     * Safety net for the customer-app approval: order-service writes
     * tickets.customer_approval directly. If an older order-service did so
     * without closing the revision, close it here (decided_via CUSTOMER_APP,
     * stamped with the customer's CUSTOMER_APPROVED instant).
     */
    @Transactional
    public void reconcileCustomerApproval(Ticket t) {
        if (t == null || t.getId() == null || !Boolean.TRUE.equals(t.getCustomerApproval())) return;
        TicketReEstimateRevision r = firstWithStatus(
                revisionRepository.lockByTicketIdAndStatusIn(t.getId(), List.of(AWAITING_APPROVAL)), AWAITING_APPROVAL);
        if (r == null) return;
        Instant at = currentCycle(t.getId()).stream()
                .filter(e -> "CUSTOMER_APPROVED".equalsIgnoreCase(e.getStatus()))
                .map(PlatformRepairBookingEvent::getCreatedAt)
                .filter(Objects::nonNull)
                .filter(c -> r.getSubmittedAt() == null || !c.isBefore(r.getSubmittedAt()))
                .findFirst()
                .orElse(Instant.now());
        finalizeDecision(t, r, APPROVED, null, null, t.getCustomerName(), VIA_CUSTOMER_APP, "USER", false, at);
    }

    /** reconcileCustomerApproval for a ticket id (controller read paths). */
    @Transactional
    public void reconcile(UUID shopId, UUID ticketId) {
        ticketRepository.findByShopIdAndId(shopId, ticketId).ifPresent(this::reconcileCustomerApproval);
    }

    // ════════════════════════════════════════════════════════════════════
    // Guards
    // ════════════════════════════════════════════════════════════════════

    /**
     * Hook before a progress step / status is recorded (TicketService.emitProgressStepEvent, updateStatus):
     *   * REPAIR_COMPLETED / QUALITY_CHECK_COMPLETED / READY / invoice / delivery → 409 while a revision is open;
     *   * CUSTOMER_REJECTED / CANCELLED / RETURN_DELIVERY (the booking leaves the repair path) → the open
     *     revision is closed in the same transaction (REQUESTED → DISMISSED; AWAITING_APPROVAL → REJECTED with
     *     the snapshot restored), never blocked.
     */
    @Transactional
    public void onProgressStep(Ticket t, String key) {
        if (t == null || key == null) return;
        String k = key.trim().toUpperCase(Locale.ROOT);
        String exitReason = EXIT_KEYS.get(k);
        if (exitReason != null) {
            closeOpenRevisions(t, exitReason);
        } else if (BLOCKED_WHILE_OPEN.contains(k)) {
            assertNoOpenRevision(t);
        }
    }

    /** Steps that take the booking off the repair path, with the decision_reason they record. */
    static final Map<String, String> EXIT_KEYS = Map.of(
            "CUSTOMER_REJECTED", "Closed — the customer rejected the repair",
            "CANCELLED", "Closed — the booking was cancelled",
            "RETURN_DELIVERY", "Closed — the device is being returned without repair");

    private void closeOpenRevisions(Ticket t, String reason) {
        if (t.getId() == null) return;
        // A customer-app approval that already landed is honoured first (it is then no longer open).
        reconcileCustomerApproval(t);
        for (TicketReEstimateRevision r : revisionRepository.lockByTicketIdAndStatusIn(t.getId(), OPEN)) {
            boolean awaiting = AWAITING_APPROVAL.equals(r.getStatus());
            String outcome = awaiting ? REJECTED : DISMISSED;
            r.setStatus(outcome);
            r.setDecision(outcome);
            r.setDecidedAt(Instant.now());
            r.setDecidedVia(VIA_SHOP);
            r.setDecisionReason(reason);
            revisionRepository.save(r);
            if (awaiting) {
                // The revised charges were never agreed — the booking leaves with the original estimate.
                restoreOriginal(t, r);
                Ticket saved = ticketRepository.save(t);
                customerOrderMirrorService.mirrorOnUpsert(saved != null ? saved : t);
            } else {
                appendEventNote(t.getId(), KEY_REQUESTED, " · " + reason);
            }
        }
    }

    /** Any open revision (REQUESTED or AWAITING_APPROVAL) → 409 REESTIMATE_PENDING. */
    @Transactional
    public void assertNoOpenRevision(Ticket t) {
        if (t == null || t.getId() == null) return;
        reconcileCustomerApproval(t);
        List<TicketReEstimateRevision> open = revisionRepository.findByTicketIdAndStatusIn(t.getId(), OPEN);
        if (open.isEmpty()) return;
        throw conflict(REESTIMATE_PENDING, AWAITING_APPROVAL.equals(open.get(0).getStatus())
                ? "The re-estimate is still awaiting customer approval"
                : "The technician's re-estimate request is still open — re-estimate or dismiss it first");
    }

    /** A revision awaiting approval → 409 (Price Revised would be undone by a rejection's restore). */
    @Transactional
    public void assertNoPendingApproval(Ticket t) {
        if (t == null || t.getId() == null) return;
        reconcileCustomerApproval(t);
        if (!revisionRepository.findByTicketIdAndStatusIn(t.getId(), List.of(AWAITING_APPROVAL)).isEmpty()) {
            throw conflict(REESTIMATE_PENDING, "The re-estimate is still awaiting customer approval");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // GET /tickets/{id}
    // ════════════════════════════════════════════════════════════════════

    /** Adds assignedTechnicianName, activeReEstimate and latestReEstimate. */
    @Transactional(readOnly = true)
    public TicketResponse decorate(TicketResponse resp) {
        if (resp == null || resp.getId() == null) return resp;
        UUID techId = resp.getAssignedTechnicianId();
        if (techId != null) {
            if (resp.getAssignedTechnicianName() == null) {
                technicianRepository.findById(techId).ifPresent(tech -> resp.setAssignedTechnicianName(tech.getName()));
            }
            if (resp.getAssignedTechnicianCode() == null) {
                resp.setAssignedTechnicianCode(techId.toString().substring(0, 8).toUpperCase(Locale.ROOT));
            }
        }
        List<TicketReEstimateRevision> all = revisionRepository.findByTicketIdOrderByRevisionNoDesc(resp.getId());
        resp.setLatestReEstimate(all.isEmpty() ? null : toDto(all.get(0)));
        resp.setActiveReEstimate(all.stream()
                .filter(r -> OPEN.contains(r.getStatus()))
                .findFirst()
                .map(this::toDto)
                .orElse(null));
        return resp;
    }

    // ════════════════════════════════════════════════════════════════════
    // Internals
    // ════════════════════════════════════════════════════════════════════

    /**
     * Close an AWAITING_APPROVAL revision. APPROVED keeps the revised scope and
     * price; REJECTED restores the snapshot exactly. Either way the ticket goes
     * back to IN_REPAIR when repair work had started in this cycle, else
     * APPROVED (forward only — never back to CREATED), and the decision +
     * REPAIR_WORK_RESUMED rows are written.
     */
    private void finalizeDecision(Ticket t, TicketReEstimateRevision r, String decision, String reason,
                                  UUID decidedBy, String decidedByName, String via, String actor,
                                  boolean writeCustomerApproved, Instant at) {
        boolean approved = APPROVED.equals(decision);
        r.setStatus(decision);
        r.setDecision(decision);
        r.setDecidedAt(at);
        r.setDecidedByUserId(decidedBy);
        r.setDecidedByName(decidedByName);
        r.setDecisionReason(reason);
        r.setDecidedVia(via);
        revisionRepository.save(r);

        if (approved) {
            t.setCustomerApproval(Boolean.TRUE);
        } else {
            restoreOriginal(t, r);
        }
        boolean repairStarted = hasEvent(t.getId(), "IN_REPAIR");
        // Rejected → the booking closes (CANCELLED); the original estimate stays
        // restored above for the record and any advance goes through refunds.
        if (approved) moveForward(t, repairStarted ? "IN_REPAIR" : "APPROVED");
        else t.setStatus("CANCELLED");
        Ticket saved = ticketRepository.save(t);
        customerOrderMirrorService.mirrorOnUpsert(saved != null ? saved : t);

        if (approved && writeCustomerApproved) {
            emitOrUpdate(t.getId(), "CUSTOMER_APPROVED", TicketService.NOTE_APPROVED_RE_ESTIMATE, "SHOP", null, at);
        }
        emitOrUpdate(t.getId(), approved ? KEY_APPROVED : KEY_REJECTED, decisionNote(r), actor, null, at);
        if (!approved) {
            emitOrUpdate(t.getId(), "CANCELLED", "Closed — the customer rejected the re-estimate",
                    "SHOP", null, at.plusMillis(1));
        } else if (repairStarted) {
            emitOrUpdate(t.getId(), KEY_RESUMED,
                    "Repair work resumed after the re-estimate was " + (approved ? "approved" : "rejected"),
                    "SHOP", null, at.plusMillis(1));
        }
    }

    /** REJECTED: the revision's charges were never applied — put the ticket back as it was. */
    private void restoreOriginal(Ticket t, TicketReEstimateRevision r) {
        t.setEstimatedPrice(r.getOriginalEstimatedPrice());
        t.setPriceItemsJson(r.getOriginalPriceItemsJson());
        Map<String, Object> s = readMap(r.getOriginalStateJson());
        if (s.containsKey("repairServicesSummary")) t.setRepairServicesSummary(asString(s.get("repairServicesSummary")));
        if (s.containsKey("customerApproval")) t.setCustomerApproval(asBoolean(s.get("customerApproval")));
        if (s.containsKey("estimatedDeliveryAt")) t.setEstimatedDeliveryAt(asInstant(s.get("estimatedDeliveryAt")));
        if (s.containsKey("originalEstimatedDeliveryAt")) {
            t.setOriginalEstimatedDeliveryAt(asInstant(s.get("originalEstimatedDeliveryAt")));
        }
        recomputeBalance(t);
    }

    /** Same rule as TicketService#recomputeBalance (balance = applicable total − collected, floored at 0). */
    private static void recomputeBalance(Ticket t) {
        BigDecimal total = t.getFinalPrice() != null ? t.getFinalPrice()
                : t.getEstimatedPrice() != null ? t.getEstimatedPrice() : BigDecimal.ZERO;
        BigDecimal paid = t.getPaymentAmount() == null ? BigDecimal.ZERO : t.getPaymentAmount();
        BigDecimal balance = total.subtract(paid);
        t.setBalanceAmount(balance.signum() < 0 ? BigDecimal.ZERO : balance);
        if (t.getPaymentStatus() == null) t.setPaymentStatus(t.getPaymentAmount() == null ? "PENDING" : "PAID");
    }

    private static void moveForward(Ticket t, String target) {
        int current = LIFECYCLE_ORDER.indexOf(upper(t.getStatus()));
        int wanted = LIFECYCLE_ORDER.indexOf(target);
        if (current >= 0 && wanted >= 0 && current < wanted) t.setStatus(target);
    }

    private static String snapshotState(Ticket t) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("repairServicesSummary", t.getRepairServicesSummary());
        s.put("customerApproval", t.getCustomerApproval());
        s.put("estimatedDeliveryAt", t.getEstimatedDeliveryAt() == null ? null : t.getEstimatedDeliveryAt().toString());
        s.put("originalEstimatedDeliveryAt", t.getOriginalEstimatedDeliveryAt() == null ? null
                : t.getOriginalEstimatedDeliveryAt().toString());
        return writeJson(s);
    }

    private TicketReEstimateRevision saveNewOpen(TicketReEstimateRevision r) {
        r.setRevisionNo(revisionRepository.maxRevisionNo(r.getTicketId()) + 1);
        try {
            return revisionRepository.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // uq_ticket_reestimate_open: a concurrent request/re-estimate won the race.
            throw conflict(REESTIMATE_PENDING, "Another re-estimate was just opened for this booking — reload and try again");
        }
    }

    private boolean isRetryOf(TicketReEstimateRevision r, UUID userId, String issue, String reason,
                              BigDecimal proposed, String notes, String images) {
        return REQUESTED.equals(r.getStatus())
                && userId != null && userId.equals(r.getRequestedByUserId())
                && r.getRequestedAt() != null && r.getRequestedAt().isAfter(Instant.now().minus(RETRY_WINDOW))
                && Objects.equals(issue, r.getAdditionalIssue())
                && Objects.equals(reason, r.getReason())
                && sameAmount(proposed, r.getProposedAmount())
                && Objects.equals(notes, r.getTechnicianNotes())
                && Objects.equals(images, r.getRequestImagesJson());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private Ticket loadTicket(UUID shopId, UUID ticketId) {
        return ticketRepository.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
    }

    private TicketReEstimateRevision lockRevision(Ticket t, UUID revisionId) {
        return revisionRepository.lockById(revisionId)
                .filter(r -> t.getId().equals(r.getTicketId()))
                .orElseThrow(() -> new ResourceNotFoundException("Re-estimate not found: " + revisionId));
    }

    /** Same rule as TicketService#acceptByTechnician: technicians.user_id → tickets.assigned_technician_id. */
    private Technician requireAssignedTechnician(Ticket t, UUID shopId, UUID userId) {
        if (userId == null) throw forbidden(NOT_ASSIGNED_TECHNICIAN, "Missing user context");
        Technician me = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> forbidden(NOT_ASSIGNED_TECHNICIAN, "You are not a technician of this shop"));
        if (t.getAssignedTechnicianId() == null || !t.getAssignedTechnicianId().equals(me.getId())) {
            throw forbidden(NOT_ASSIGNED_TECHNICIAN, "This booking is assigned to a different technician");
        }
        return me;
    }

    /** Display name for a shop user: their technicians row, else users.name. Null when unknown. */
    private String userName(UUID shopId, UUID userId) {
        if (userId == null) return null;
        try {
            Optional<Technician> tech = technicianRepository.findByShopIdAndUserId(shopId, userId);
            if (tech.isPresent() && trimToNull(tech.get().getName()) != null) return tech.get().getName().trim();
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT name FROM users WHERE id = CAST(? AS UUID)", String.class, userId.toString());
            return names == null || names.isEmpty() ? null : trimToNull(names.get(0));
        } catch (Exception e) {
            log.warn("Could not resolve a name for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private static TicketReEstimateRevision firstWithStatus(List<TicketReEstimateRevision> rows, String status) {
        if (rows == null) return null;
        return rows.stream().filter(r -> status.equals(r.getStatus())).findFirst().orElse(null);
    }

    private static String requestNote(TicketReEstimateRevision r) {
        List<String> parts = new ArrayList<>();
        if (r.getAdditionalIssue() != null) parts.add("Additional issue: " + r.getAdditionalIssue());
        if (r.getProposedAmount() != null) parts.add("Proposed ₹" + plain(r.getProposedAmount()));
        parts.add("Reason: " + r.getReason());
        if (r.getTechnicianNotes() != null) parts.add("Notes: " + r.getTechnicianNotes());
        return String.join(" · ", parts);
    }

    private static String decisionNote(TicketReEstimateRevision r) {
        String head = "Re-estimate #" + r.getRevisionNo();
        String original = r.getOriginalEstimatedPrice() == null ? null : "₹" + plain(r.getOriginalEstimatedPrice());
        String revised = r.getRevisedTotal() == null ? null : "₹" + plain(r.getRevisedTotal());
        String body = APPROVED.equals(r.getStatus())
                ? head + " approved" + (original != null && revised != null ? " · " + original + " → " + revised : "")
                : head + " rejected" + (original != null ? " · price stays " + original : "");
        if (VIA_CUSTOMER_APP.equals(r.getDecidedVia())) body += " · by the customer in the app";
        return r.getDecisionReason() == null ? body : body + " · " + r.getDecisionReason();
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    // ---- Service History rail (repair_booking_events) --------------------
    // Same semantics as TicketService#emitOrUpdateBookingEvent: one row per key
    // in the current cycle, refreshed in place; notifications on first insert.

    private List<PlatformRepairBookingEvent> currentCycle(UUID ticketId) {
        return platformRepairBookingRepository.findByTicketId(ticketId)
                .map(b -> platformRepairBookingEventRepository.findCurrentCycle(b.getId()))
                .orElse(List.of());
    }

    private boolean hasEvent(UUID ticketId, String key) {
        return currentCycle(ticketId).stream().anyMatch(e -> key.equalsIgnoreCase(e.getStatus()));
    }

    void emitOrUpdate(UUID ticketId, String key, String note, String actor, String imagesJson, Instant at) {
        Instant stamp = at != null ? at : Instant.now();
        platformRepairBookingRepository.findByTicketId(ticketId).ifPresent(booking -> {
            Optional<PlatformRepairBookingEvent> existing = platformRepairBookingEventRepository
                    .findCurrentCycle(booking.getId()).stream()
                    .filter(e -> key.equalsIgnoreCase(e.getStatus()))
                    .findFirst();
            if (existing.isPresent()) {
                PlatformRepairBookingEvent e = existing.get();
                e.setNote(note);
                e.setActor(actor);
                e.setAudioUrl(null);
                e.setImagesJson(imagesJson);
                e.setCreatedAt(stamp);
                platformRepairBookingEventRepository.save(e);
            } else {
                platformRepairBookingEventRepository.save(PlatformRepairBookingEvent.builder()
                        .bookingId(booking.getId())
                        .status(key)
                        .note(note)
                        .actor(actor)
                        .imagesJson(imagesJson)
                        .createdAt(stamp)
                        .build());
                customerOrderMirrorService.emitCustomerNotificationForStatus(booking.getId(), key);
                customerOrderMirrorService.emitShopNotificationForStatus(booking.getId(), key, actor);
            }
        });
    }

    private void appendEventNote(UUID ticketId, String key, String suffix) {
        platformRepairBookingRepository.findByTicketId(ticketId).ifPresent(booking ->
                platformRepairBookingEventRepository.findCurrentCycle(booking.getId()).stream()
                        .filter(e -> key.equalsIgnoreCase(e.getStatus()))
                        .findFirst()
                        .ifPresent(e -> {
                            e.setNote((e.getNote() == null ? "" : e.getNote()) + suffix);
                            platformRepairBookingEventRepository.save(e);
                        }));
    }

    // ---- DTO -------------------------------------------------------------

    ReEstimateRevisionDtos.Revision toDto(TicketReEstimateRevision r) {
        return ReEstimateRevisionDtos.Revision.builder()
                .id(r.getId())
                .ticketId(r.getTicketId())
                .revisionNo(r.getRevisionNo())
                .source(r.getSource())
                .status(r.getStatus())
                .technicianName(r.getTechnicianName())
                .requestedAt(r.getRequestedAt())
                .additionalIssue(r.getAdditionalIssue())
                .reason(r.getReason())
                .proposedAmount(r.getProposedAmount())
                .technicianNotes(r.getTechnicianNotes())
                .requestImages(parseImages(r.getRequestImagesJson()))
                .submittedAt(r.getSubmittedAt())
                .items(parseItems(r.getItemsJson()))
                .additionalAmount(r.getAdditionalAmount())
                .originalEstimatedPrice(r.getOriginalEstimatedPrice())
                .revisedTotal(r.getRevisedTotal())
                .revisionReason(r.getRevisionReason())
                .revisionImages(parseImages(r.getRevisionImagesJson()))
                .decidedAt(r.getDecidedAt())
                .decidedByName(r.getDecidedByName())
                .decision(r.getDecision())
                .decisionReason(r.getDecisionReason())
                .decidedVia(r.getDecidedVia())
                .technicianAcknowledgedAt(r.getTechnicianAcknowledgedAt())
                .build();
    }

    private static List<ReEstimateRevisionDtos.Item> parseItems(String json) {
        List<ReEstimateRevisionDtos.Item> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            List<Map<String, Object>> rows = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : rows) {
                if (m == null) continue;
                Object amount = m.get("amount");
                BigDecimal value;
                try {
                    value = amount == null ? null : new BigDecimal(String.valueOf(amount));
                } catch (NumberFormatException e) {
                    value = null;
                }
                out.add(ReEstimateRevisionDtos.Item.builder()
                        .id(asString(m.get("id")))
                        .code(asString(m.get("code")))
                        .label(asString(m.get("label")))
                        .amount(value)
                        .warranty(asString(m.get("warranty")))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Unreadable re-estimate items_json: {}", e.getMessage());
        }
        return out;
    }

    /** JSON array of URL strings (or objects with a url) → list of URLs; never null. */
    static List<String> parseImages(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isBlank()) return urls;
        try {
            List<?> parsed = JSON.readValue(json, List.class);
            for (Object o : parsed) {
                String url = o instanceof Map<?, ?> m ? asString(m.get("url")) : asString(o);
                if (url != null && !url.isBlank()) urls.add(url);
            }
        } catch (Exception ignored) {
            // unreadable → no images
        }
        return urls;
    }

    /** Request imagesJson (string or array) → canonical JSON array string, or null when empty. */
    static String normalizeImagesJson(Object raw) {
        if (raw == null) return null;
        List<String> urls;
        if (raw instanceof Collection<?> c) {
            urls = new ArrayList<>();
            for (Object o : c) {
                String url = o instanceof Map<?, ?> m ? asString(m.get("url")) : asString(o);
                if (url != null && !url.isBlank()) urls.add(url.trim());
            }
        } else {
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) return null;
            if (!s.startsWith("[")) throw badRequest("imagesJson must be a JSON array of image URLs");
            urls = parseImages(s);
        }
        return urls.isEmpty() ? null : writeJson(urls);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the re-estimate", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean asBoolean(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static Instant asInstant(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
