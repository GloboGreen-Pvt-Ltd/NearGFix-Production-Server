package com.repairshop.saas.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShopNotification {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "shop_id", nullable = false) private UUID shopId;
    @Column(name = "booking_id") private UUID bookingId;
    @Column(name = "booking_number", length = 60) private String bookingNumber;
    // Lets the owner app deep-link a notification straight into the booking's
    // Service History timeline, which is keyed by ticket id (migration 86).
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "status_key", length = 100) private String statusKey;
    @Column(nullable = false, length = 200) private String title;
    @Column(columnDefinition = "TEXT") private String body;
    @Column(length = 30) private String type; // bookings | pickups | system
    /** Record the row opens when it has no booking/ticket (Team rows: the employee). Migration 130. */
    @Column(name = "entity_ref", length = 100, insertable = false, updatable = false) private String entityRef;
    @Column(name = "is_read", nullable = false) private boolean read;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @PrePersist void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (type == null) type = "bookings";
    }
}
