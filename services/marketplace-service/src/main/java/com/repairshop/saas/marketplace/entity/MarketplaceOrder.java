package com.repairshop.saas.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "marketplace_orders", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"shop_id", "order_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketplaceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "order_number", nullable = false, length = 50)
    private String orderNumber;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    // Fixed-price Buy flow (migration 114).
    @Column(name = "customer_user_id") private UUID customerUserId;
    @Column(name = "product_id") private UUID productId;
    @Column(name = "quantity") private Integer quantity;
    @Column(name = "unit_price", precision = 12, scale = 2) private BigDecimal unitPrice;
    @Column(name = "fulfilment_type", length = 20) private String fulfilmentType; // HOME_DELIVERY | VISIT_SHOP
    @Column(name = "payment_method", length = 20) private String paymentMethod;   // COD | PAY_AT_SHOP
    @Column(name = "payment_status", length = 20) private String paymentStatus;   // PENDING | PAID
    @Column(name = "payment_ref", length = 120) private String paymentRef;
    @Column(name = "delivery_address", columnDefinition = "TEXT") private String deliveryAddress;
    @Column(name = "customer_phone", length = 20) private String customerPhone;
    @Column(name = "idempotency_key", length = 64) private String idempotencyKey;
    @Column(name = "assigned_staff_user_id") private UUID assignedStaffUserId;
    @Column(name = "handover_otp", length = 6) private String handoverOtp;
    @Column(name = "stock_released") private Boolean stockReleased;
    @Column(name = "photos_json", columnDefinition = "TEXT") private String photosJson;
    @Column(name = "note", columnDefinition = "TEXT") private String note;
    @Column(name = "verified_imei", length = 40) private String verifiedImei;
    // Shop Pickup PIN (home delivery): regenerated on assign, cleared after pickup.
    @Column(name = "pickup_pin", length = 6) private String pickupPin;
    @Column(name = "pickup_pin_expires_at") private Instant pickupPinExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "PENDING";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
