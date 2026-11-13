package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.ticket.dto.CreateRepairNoteRequest;
import com.repairshop.saas.ticket.dto.CreateSolutionPackRequest;
import com.repairshop.saas.ticket.dto.ImeiAvailabilityResponse;
import com.repairshop.saas.ticket.dto.ReEstimateRevisionDtos;
import com.repairshop.saas.ticket.dto.RepairNoteResponse;
import com.repairshop.saas.ticket.dto.SolutionPackResponse;
import com.repairshop.saas.ticket.dto.TicketEventResponse;
import com.repairshop.saas.ticket.dto.TicketRequest;
import com.repairshop.saas.ticket.dto.TicketResponse;
import com.repairshop.saas.ticket.service.ReEstimateWorkflowService;
import com.repairshop.saas.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "Repair ticket CRUD")
@SecurityRequirement(name = "Bearer")
public class TicketController {

    private final TicketService ticketService;
    private final ReEstimateWorkflowService reEstimateWorkflow;
    private final com.repairshop.saas.ticket.whatsapp.WhatsAppNotificationService whatsApp;
    private final com.repairshop.saas.ticket.service.OwnerTechnicianService ownerTechnician;

    private UUID shopIdFrom(HttpServletRequest request) {
        String sid = (String) request.getAttribute("shopId");
        return sid != null ? UUID.fromString(sid) : null;
    }

    private static List<?> rolesFrom(HttpServletRequest request) {
        Object raw = request.getAttribute("roles");
        return raw instanceof List<?> roles ? roles : List.of();
    }

    private UUID userIdFrom(HttpServletRequest request) {
        String uid = (String) request.getAttribute("userId");
        return uid != null ? UUID.fromString(uid) : null;
    }

    // OWNER when the caller is the shop owner — an owner or SHOP-login token,
    // both of which carry the SHOP_OWNER role. The service keeps the steps such
    // a change implies off the owner's own notification feed and phone; an
    // employee-app (technician) caller still notifies the owner as before.
    private String ownerActorFrom(HttpServletRequest request) {
        Object raw = request.getAttribute("roles");
        boolean owner = raw instanceof List<?> roles
                && roles.stream().anyMatch(r -> "SHOP_OWNER".equals(
                        String.valueOf(r).trim().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "")));
        return owner ? "OWNER" : null;
    }

    // ---------- Owner as Technician ----------------------------------------

    private UUID requireOwner(HttpServletRequest request) {
        if (ownerActorFrom(request) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop owner can do this");
        }
        UUID userId = userIdFrom(request);
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing user context");
        return userId;
    }

    @GetMapping("/owner-technician")
    @Operation(summary = "Owner as Technician: setting, technician id and the owner's own job counts")
    public Map<String, Object> getOwnerTechnician(HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ownerTechnician.get(shopId, requireOwner(request));
    }

    @PutMapping("/owner-technician")
    @Operation(summary = "Owner as Technician ON/OFF (OFF is refused while the owner has active jobs)")
    public Map<String, Object> setOwnerTechnician(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        Object name = body.get("name");
        return ownerTechnician.set(shopId, requireOwner(request), enabled, name == null ? null : String.valueOf(name));
    }

    @GetMapping("/counts")
    @Operation(summary = "Get booking/ticket counts for shop (dashboard)")
    public Map<String, Long> getCounts(HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.getCountsByShop(shopId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get ticket by ID")
    public TicketResponse getById(@PathVariable UUID id, HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        reconcileQuietly(shopId, id);
        return reEstimateWorkflow.decorate(ticketService.getById(shopId, id));
    }

    @GetMapping("/customer/{id}")
    @Operation(summary = "Get ticket by ID for the customer who placed it (mobile My Orders → Service → View Details)")
    public TicketResponse getForCustomer(@PathVariable UUID id, HttpServletRequest request) {
        requireRole(request, "CUSTOMER");
        UUID userId = userIdFrom(request);
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing user context");
        return ticketService.getForCustomer(userId, id);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Service timeline events for a ticket (owner BookingTimelineScreen)")
    public List<TicketEventResponse> getEvents(@PathVariable UUID id, HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        reconcileQuietly(shopId, id);
        return ticketService.getEventsForShop(shopId, id);
    }

    @SuppressWarnings("unchecked")
    private void requireRole(HttpServletRequest request, String role) {
        Object raw = request.getAttribute("roles");
        if (raw instanceof List<?> roles
                && roles.stream().anyMatch(r -> role.equalsIgnoreCase(String.valueOf(r).trim().toUpperCase(Locale.ROOT)))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not allowed");
    }

    @GetMapping
    @Operation(summary = "List tickets (paginated). Use assignedToMe=true for technician's assigned tickets.")
    public Page<TicketResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean assignedToMe,
            Pageable pageable,
            HttpServletRequest request) {
        if (assignedToMe) {
            UUID userId = userIdFrom(request);
            if (userId == null) throw new IllegalStateException("Missing user context");
            return ticketService.listByAssignedUser(shopIdFrom(request), userId, pageable);
        }
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return reEstimateWorkflow.decorateOpenReEstimates(ticketService.listByShop(shopId, status, q, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create ticket")
    public TicketResponse create(@Valid @RequestBody TicketRequest body, HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        TicketResponse created = ticketService.create(shopId, body);
        // The booking has committed: queue its WhatsApp confirmation (one per
        // booking, sent later by the outbox worker). Never fails the booking.
        whatsApp.enqueueBookingConfirmed(shopId, created.getId(), body.getWhatsappOptIn());
        return created;
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update ticket")
    public TicketResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody TicketRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.update(shopId, id, body);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Patch ticket (currently supports technician assignment and simple fields)")
    public TicketResponse patch(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        if (body.containsKey("technicianPhotosJson")) {
            ticketService.requireCurrentTechnicianUnlessOwner(shopId, id, userIdFrom(request), ownerActorFrom(request) != null);
        }
        return ticketService.patch(shopId, id, body, ownerActorFrom(request), userIdFrom(request));
    }

    @PostMapping("/{id}/re-estimate")
    @Operation(summary = "Re-estimate: add issues + charges found during repair (originals unchanged); awaits customer approval unless approved now")
    public TicketResponse reEstimate(
            @PathVariable UUID id,
            @RequestBody com.repairshop.saas.ticket.dto.ReEstimateRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        ReEstimateWorkflowService.requireShopRole(rolesFrom(request));
        return ticketService.reEstimate(shopId, id, body, userIdFrom(request));
    }

    @PostMapping("/{id}/price-revision")
    @Operation(summary = "Price Revised: change or cancel existing services (no new services, no status change)")
    public TicketResponse revisePrices(
            @PathVariable UUID id,
            @RequestBody com.repairshop.saas.ticket.dto.PriceRevisionRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.revisePrices(shopId, id, body);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update ticket status")
    public void updateStatus(
            @PathVariable UUID id,
            @RequestParam String status,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        ticketService.updateStatus(shopId, id, status, ownerActorFrom(request));
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Re-Service: reopen a delivered ticket for a new repair")
    public TicketResponse reopen(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.reopen(shopId, id);
    }

    @PostMapping("/{id}/accept")
    @Operation(summary = "Assigned technician accepts the ticket and starts work")
    public TicketResponse acceptByTechnician(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        UUID userId = userIdFrom(request);
        if (shopId == null || userId == null) throw new IllegalStateException("Missing auth context");
        return ticketService.acceptByTechnician(shopId, userId, id);
    }

    // Pre-flight for the shop app's invoice gate: is this IMEI well-formed, and
    // is it free? Omit the query param to check whatever the booking already
    // carries. Advisory only — PATCH /tickets/{id} enforces the same rule on
    // write, so a race between two staff members still ends in one 409.
    @GetMapping("/{id}/imei-availability")
    @Operation(summary = "Check whether an IMEI is valid and not held by another open booking")
    public ImeiAvailabilityResponse checkImeiAvailability(
            @PathVariable UUID id,
            @RequestParam(required = false) String imei,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.checkImeiAvailability(shopId, id, imei);
    }

    // ---------- Repair notes ----------------------------------------------

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a repair note to a ticket (used by the tech detail screen's Compliance Notes submit)")
    public RepairNoteResponse addNote(
            @PathVariable UUID id,
            @Valid @RequestBody CreateRepairNoteRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.addRepairNote(shopId, id, userIdFrom(request), body);
    }

    // Submitted Notes → Edit on the technician Ticket Detail screen. Same body
    // as the create call: the note text plus the full attachment set it should
    // end up with, so dropping a photo is just sending the shorter list.
    @PutMapping("/{id}/notes/{noteId}")
    @Operation(summary = "Edit a repair note already submitted on this ticket")
    public RepairNoteResponse updateNote(
            @PathVariable UUID id,
            @PathVariable UUID noteId,
            @Valid @RequestBody CreateRepairNoteRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.updateRepairNote(shopId, id, noteId, body);
    }

    @GetMapping("/{id}/notes")
    @Operation(summary = "List repair notes attached to a ticket")
    public List<RepairNoteResponse> listNotes(@PathVariable UUID id, HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.listRepairNotes(shopId, id);
    }

    // ---------- Solution packs --------------------------------------------

    @GetMapping("/{id}/solution-packs")
    @Operation(summary = "List solution packs for a ticket (filter by packType: REFERENCE | NEW)")
    public List<SolutionPackResponse> listSolutionPacks(
            @PathVariable UUID id,
            @RequestParam(required = false) String packType,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.listSolutionPacks(shopId, id, packType);
    }

    @PostMapping("/{id}/solution-packs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a new solution pack to a ticket (file URL produced by /media/upload first)")
    public SolutionPackResponse addSolutionPack(
            @PathVariable UUID id,
            @Valid @RequestBody CreateSolutionPackRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return ticketService.addSolutionPack(shopId, id, userIdFrom(request), body);
    }

    // Service-progress checklist on the technician Ticket Detail screen.
    // Each Submit click POSTs here with the row's status key; backend emits
    // (or refreshes) the matching repair_booking_events row.
    @PostMapping("/{id}/progress-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Emit a service-progress step event (Spare Parts Waiting, Quality Check, ...)")
    public void emitProgressEvent(
            @PathVariable UUID id,
            @RequestBody ProgressEventRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        ticketService.requireCurrentTechnicianUnlessOwner(shopId, id, userIdFrom(request), ownerActorFrom(request) != null);
        ticketService.emitProgressStepEvent(shopId, id, body.getStatusKey(), body.getNote(), body.getActor());
    }

    // ---------- Re-estimate workflow (ReEstimateWorkflowService) ---------

    /** Read paths must not fail on the approval self-heal — the next read retries it. */
    private void reconcileQuietly(UUID shopId, UUID id) {
        try { reEstimateWorkflow.reconcile(shopId, id); }
        catch (Exception e) { org.slf4j.LoggerFactory.getLogger(TicketController.class)
                .warn("re-estimate reconcile failed for ticket {}: {}", id, e.getMessage()); }
    }

    @PostMapping("/{id}/re-estimate-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assigned technician requests a re-estimate (additional issue / proposed amount)")
    public ReEstimateRevisionDtos.Revision requestReEstimate(
            @PathVariable UUID id,
            @RequestBody ReEstimateRevisionDtos.TechnicianRequest body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return reEstimateWorkflow.requestReEstimate(shopId, userIdFrom(request), rolesFrom(request), id, body);
    }

    @GetMapping("/{id}/re-estimates")
    @Operation(summary = "Re-estimate history (all revisions, newest first) — owner/shop or the assigned technician")
    public List<ReEstimateRevisionDtos.Revision> listReEstimates(@PathVariable UUID id, HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return reEstimateWorkflow.list(shopId, userIdFrom(request), rolesFrom(request), id);
    }

    @PostMapping("/{id}/re-estimates/{revisionId}/decision")
    @Operation(summary = "Record the customer's decision on a re-estimate awaiting approval (APPROVED | REJECTED) — owner/shop only")
    public ReEstimateRevisionDtos.Revision decideReEstimate(
            @PathVariable UUID id,
            @PathVariable UUID revisionId,
            @RequestBody ReEstimateRevisionDtos.Decision body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return reEstimateWorkflow.decide(shopId, userIdFrom(request), rolesFrom(request), id, revisionId, body);
    }

    @PostMapping("/{id}/re-estimate-requests/{revisionId}/dismiss")
    @Operation(summary = "Decline a technician's re-estimate request without re-estimating — owner/shop only")
    public ReEstimateRevisionDtos.Revision dismissReEstimateRequest(
            @PathVariable UUID id,
            @PathVariable UUID revisionId,
            @RequestBody(required = false) ReEstimateRevisionDtos.Reason body,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return reEstimateWorkflow.dismiss(shopId, userIdFrom(request), rolesFrom(request), id, revisionId, body);
    }

    @PostMapping("/{id}/re-estimates/{revisionId}/acknowledge")
    @Operation(summary = "Assigned technician acknowledges the re-estimate outcome (idempotent)")
    public ReEstimateRevisionDtos.Revision acknowledgeReEstimate(
            @PathVariable UUID id,
            @PathVariable UUID revisionId,
            HttpServletRequest request) {
        UUID shopId = shopIdFrom(request);
        if (shopId == null) throw new IllegalStateException("Missing shop context");
        return reEstimateWorkflow.acknowledge(shopId, userIdFrom(request), id, revisionId);
    }

    @lombok.Data
    public static class ProgressEventRequest {
        private String statusKey;
        private String note;
        /** Caller-supplied actor — TECHNICIAN (default) or OWNER. Frontend uses
         *  this to distinguish manually-submitted rows from auto-emitted ones. */
        private String actor;
    }
}
