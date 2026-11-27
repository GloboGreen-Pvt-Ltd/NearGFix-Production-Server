package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.ticket.dto.InvoiceRequest;
import com.repairshop.saas.ticket.dto.InvoiceResponse;
import com.repairshop.saas.ticket.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * One latest invoice per ticket. POST upserts (same row each time); GET returns
 * the stored snapshot — used by both the Invoice Generator (prefill on revisit)
 * and the Deliver Invoice Report screen.
 */
@RestController
@RequestMapping("/tickets/{ticketId}/invoice")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Per-ticket invoice snapshot")
@SecurityRequirement(name = "Bearer")
public class InvoiceController {

    private final InvoiceService invoiceService;

    private UUID userIdFrom(HttpServletRequest request) {
        String uid = (String) request.getAttribute("userId");
        try {
            return uid != null ? UUID.fromString(uid) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Operation(summary = "Upsert the invoice snapshot for a ticket")
    @PostMapping
    public InvoiceResponse upsert(@PathVariable UUID ticketId,
                                  @RequestBody InvoiceRequest body,
                                  HttpServletRequest request) {
        return invoiceService.upsert(ticketId, body, userIdFrom(request), shopIdFrom(request));
    }

    @Operation(summary = "Get the invoice snapshot for a ticket")
    @GetMapping
    public ResponseEntity<InvoiceResponse> getByTicket(@PathVariable UUID ticketId, HttpServletRequest request) {
        return invoiceService.findByTicket(ticketId, shopIdFrom(request))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Shop of the caller's token; required — invoices are shop data. */
    private static UUID shopIdFrom(HttpServletRequest request) {
        Object sid = request.getAttribute("shopId");
        if (sid == null || String.valueOf(sid).isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shop context");
        return UUID.fromString(String.valueOf(sid));
    }

    @Operation(summary = "Delete the invoice for a ticket (only before Out for Delivery)")
    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable UUID ticketId, HttpServletRequest request) {
        String sid = (String) request.getAttribute("shopId");
        if (sid == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shop context");
        invoiceService.delete(ticketId, UUID.fromString(sid));
        return ResponseEntity.noContent().build();
    }
}
