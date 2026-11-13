package com.repairshop.saas.ticket.controller;

import com.repairshop.saas.ticket.service.RepairDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Doorstep repair RETURN delivery (RepairDeliveryService).
 *  - Shop owner:  /tickets/{ticketId}/repair-delivery[/schedule|assign|reschedule|release|receive-return]
 *  - Employee:    /technicians/me/repair-deliveries[/{id}[/{action}]]
 *  - Customer:    /tickets/customer/{ticketId}/repair-delivery
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Repair delivery")
@SecurityRequirement(name = "bearer-jwt")
public class RepairDeliveryController {

    private static final java.util.Set<String> EMPLOYEE_ACTIONS = java.util.Set.of(
            "accept", "decline", "collect", "start", "arrive", "complete", "fail", "return");

    private final RepairDeliveryService service;

    // ------------------------------------------------------------------ owner

    @GetMapping("/tickets/{ticketId}/repair-delivery")
    @Operation(summary = "Return-delivery eligibility, delivery and timeline (shop owner)")
    public Map<String, Object> get(@PathVariable UUID ticketId, HttpServletRequest req) {
        return service.getForShop(ownerShop(req), ticketId);
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/schedule")
    public Map<String, Object> schedule(@PathVariable UUID ticketId, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return service.schedule(ownerShop(req), ticketId, body);
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/assign")
    public Map<String, Object> assign(@PathVariable UUID ticketId, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Object raw = body.get("technicianId");
        if (raw == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "technicianId is required");
        return service.assign(ownerShop(req), ticketId, UUID.fromString(String.valueOf(raw)));
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/reschedule")
    public Map<String, Object> reschedule(@PathVariable UUID ticketId, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return service.reschedule(ownerShop(req), ticketId, body);
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/release")
    public Map<String, Object> release(@PathVariable UUID ticketId, HttpServletRequest req) {
        return service.release(ownerShop(req), ticketId);
    }

    @PostMapping("/tickets/{ticketId}/repair-delivery/receive-return")
    public Map<String, Object> receiveReturn(@PathVariable UUID ticketId, HttpServletRequest req) {
        return service.receiveReturn(ownerShop(req), ticketId);
    }

    // --------------------------------------------------------------- employee

    @GetMapping("/technicians/me/repair-deliveries")
    public List<Map<String, Object>> mine(HttpServletRequest req) {
        return service.listMine(shop(req), user(req));
    }

    @GetMapping("/technicians/me/repair-deliveries/{id}")
    public Map<String, Object> mineOne(@PathVariable UUID id, HttpServletRequest req) {
        return service.getMine(shop(req), user(req), id);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/{action}")
    public Map<String, Object> act(@PathVariable UUID id, @PathVariable String action,
                                   @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        if (!EMPLOYEE_ACTIONS.contains(action)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action");
        return service.employeeAction(shop(req), user(req), id, action, body);
    }

    @PostMapping("/technicians/me/repair-deliveries/{id}/otp/resend")
    public Map<String, Object> resendOtp(@PathVariable UUID id, HttpServletRequest req) {
        return service.employeeAction(shop(req), user(req), id, "otp/resend", null);
    }

    // --------------------------------------------------------------- customer

    @GetMapping("/tickets/customer/{ticketId}/repair-delivery")
    public Map<String, Object> forCustomer(@PathVariable UUID ticketId, HttpServletRequest req) {
        if (!hasRole(req, "CUSTOMER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not allowed");
        return service.getForCustomer(user(req), ticketId);
    }

    // ---------------------------------------------------------------- context

    private static UUID shop(HttpServletRequest req) {
        Object sid = req.getAttribute("shopId");
        if (sid == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shop context");
        return UUID.fromString(String.valueOf(sid));
    }

    private static UUID user(HttpServletRequest req) {
        Object uid = req.getAttribute("userId");
        if (uid == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing user context");
        return UUID.fromString(String.valueOf(uid));
    }

    private static UUID ownerShop(HttpServletRequest req) {
        if (!hasRole(req, "SHOP_OWNER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop can manage deliveries");
        return shop(req);
    }

    private static boolean hasRole(HttpServletRequest req, String role) {
        Object raw = req.getAttribute("roles");
        return raw instanceof List<?> roles && roles.stream().anyMatch(r ->
                role.equals(String.valueOf(r).trim().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "")));
    }
}
