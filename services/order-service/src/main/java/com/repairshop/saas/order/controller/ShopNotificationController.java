package com.repairshop.saas.order.controller;

import com.repairshop.saas.common.notify.PartnerNotificationPolicy;
import com.repairshop.saas.order.dto.NotificationResponse;
import com.repairshop.saas.order.entity.ShopNotification;
import com.repairshop.saas.order.exception.ForbiddenException;
import com.repairshop.saas.order.exception.ResourceNotFoundException;
import com.repairshop.saas.order.repository.ShopNotificationRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shop-owner-side in-app notification feed. Mirrors NotificationController
 * (customer side) but scoped by the shopId claim on the JWT — set by
 * JwtAuthFilter for SHOP_OWNER / SHOP_LOGIN tokens.
 */
@RestController
@RequestMapping("/shop/notifications")
@RequiredArgsConstructor
public class ShopNotificationController {

    private final ShopNotificationRepository repo;

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(HttpServletRequest req) {
        UUID shopId = shopCallerId(req);
        return ResponseEntity.ok(repo.findByShopIdOrderByCreatedAtDesc(shopId).stream()
                .filter(ShopNotificationController::supported).map(this::toResponse).toList());
    }

    /** Unread rows the feed actually shows — suppressed (older) kinds never count. */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(HttpServletRequest req) {
        UUID shopId = shopCallerId(req);
        return ResponseEntity.ok(Map.of("count",
                repo.findByShopIdAndReadFalse(shopId).stream().filter(ShopNotificationController::supported).count()));
    }

    /** Partner allowlist: rows of suppressed kinds stay in the table but are not listed or counted. */
    private static boolean supported(ShopNotification n) {
        return PartnerNotificationPolicy.allows(n.getType(), n.getStatusKey());
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<NotificationResponse> markRead(HttpServletRequest req, @PathVariable UUID id) {
        UUID shopId = shopCallerId(req);
        ShopNotification n = repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!n.getShopId().equals(shopId)) throw new ForbiddenException("Not your notification");
        n.setRead(true);
        repo.save(n);
        return ResponseEntity.ok(toResponse(n));
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Integer>> markAllRead(HttpServletRequest req) {
        UUID shopId = shopCallerId(req);
        List<ShopNotification> all = repo.findByShopIdOrderByCreatedAtDesc(shopId);
        int n = 0;
        for (ShopNotification x : all) {
            if (!x.isRead()) { x.setRead(true); n++; }
        }
        repo.saveAll(all);
        return ResponseEntity.ok(Map.of("updated", n));
    }

    private NotificationResponse toResponse(ShopNotification n) {
        return NotificationResponse.builder()
                .id(n.getId()).bookingId(n.getBookingId()).bookingNumber(n.getBookingNumber())
                .ticketId(n.getTicketId())
                .statusKey(n.getStatusKey()).title(n.getTitle()).body(n.getBody())
                .type(n.getType()).category(PartnerNotificationPolicy.categoryOf(n.getType()))
                .entityRef(n.getEntityRef()).read(n.isRead()).createdAt(n.getCreatedAt())
                .build();
    }

    private UUID shopCallerId(HttpServletRequest req) {
        Object s = req.getAttribute("shopId");
        if (s == null) throw new ForbiddenException("Missing shopId — sign in as a shop owner");
        return UUID.fromString(s.toString());
    }
}
