package com.repairshop.saas.ticket.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Timeline row of a repair return delivery (migration 118). */
@Entity
@Table(name = "repair_delivery_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepairDeliveryEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "delivery_id", nullable = false) private UUID deliveryId;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false, length = 120) private String label;
    @Column(columnDefinition = "TEXT") private String note;
    @Column(length = 20) private String actor;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
