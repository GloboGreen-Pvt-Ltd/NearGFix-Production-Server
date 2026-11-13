package com.repairshop.saas.ticket.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Doorstep return delivery of a ticket's device (migration 118). One row per ticket. */
@Entity
@Table(name = "repair_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepairDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "ticket_id", nullable = false, unique = true) private UUID ticketId;
    @Column(name = "shop_id", nullable = false) private UUID shopId;
    @Column(name = "repair_booking_id") private UUID repairBookingId;
    @Column(name = "customer_user_id") private UUID customerUserId;
    @Column(name = "technician_id") private UUID technicianId;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false) @Builder.Default private int attempt = 1;
    @Column(name = "return_path", nullable = false) private boolean returnPath;
    @Column(columnDefinition = "TEXT") private String address;
    @Column(precision = 10, scale = 7) private BigDecimal latitude;
    @Column(precision = 10, scale = 7) private BigDecimal longitude;
    @Column(name = "scheduled_date") private LocalDate scheduledDate;
    @Column(name = "slot_start") private LocalTime slotStart;
    @Column(name = "slot_end") private LocalTime slotEnd;
    @Column(columnDefinition = "TEXT") private String note;
    @Column(name = "amount_due", nullable = false, precision = 12, scale = 2) @Builder.Default private BigDecimal amountDue = BigDecimal.ZERO;
    @Column(name = "amount_collected", precision = 12, scale = 2) private BigDecimal amountCollected;
    @Column(name = "payment_method", length = 20) private String paymentMethod;
    @Column(name = "payment_ref", length = 120) private String paymentRef;
    @Column(name = "otp_code", length = 10) private String otpCode;
    @Column(name = "otp_expires_at") private Instant otpExpiresAt;
    @Column(name = "otp_sent_count", nullable = false) private int otpSentCount;
    @Column(name = "otp_failed_attempts", nullable = false) private int otpFailedAttempts;
    @Column(name = "collected_identifier", length = 60) private String collectedIdentifier;
    @Column(name = "accessories_json", columnDefinition = "TEXT") private String accessoriesJson;
    @Column(name = "collect_photos_json", columnDefinition = "TEXT") private String collectPhotosJson;
    @Column(name = "proof_photos_json", columnDefinition = "TEXT") private String proofPhotosJson;
    @Column(name = "declined_reason", columnDefinition = "TEXT") private String declinedReason;
    @Column(name = "fail_reason", columnDefinition = "TEXT") private String failReason;
    @Column(name = "accepted_at") private Instant acceptedAt;
    @Column(name = "released_at") private Instant releasedAt;
    @Column(name = "collected_at") private Instant collectedAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "arrived_at") private Instant arrivedAt;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "failed_at") private Instant failedAt;
    @Column(name = "returned_at") private Instant returnedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
