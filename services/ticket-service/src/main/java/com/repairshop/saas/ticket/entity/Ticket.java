package com.repairshop.saas.ticket.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets", uniqueConstraints = {
    @UniqueConstraint(columnNames = { "shop_id", "tracking_id" })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** Denormalized customer name/phone so the bookings list can render without
     *  a cross-service lookup. */
    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    /** Single-line postal text snapshot of the pickup address (or shop-side
     *  walk-in address). Snapshot — not an FK — so the owner Booking Details
     *  screen can render the address without joining customer_addresses, and
     *  the row survives if the customer later deletes the saved address. */
    @Column(name = "customer_address", columnDefinition = "TEXT")
    private String customerAddress;

    @Column(name = "assigned_technician_id")
    private UUID assignedTechnicianId;

    /** Timestamp of the assigned technician's explicit Accept action. NULL
     *  while the ticket is waiting in the technician's "Re-Assign" bucket;
     *  set to now() by POST /tickets/{id}/accept. Reset to NULL whenever
     *  assigned_technician_id changes (assign + reassign) so the new
     *  technician sees the Accept button in their queue. */
    @Column(name = "technician_accepted_at")
    private java.time.Instant technicianAcceptedAt;

    @Column(name = "tracking_id", nullable = false, length = 50)
    private String trackingId;

    /** Owner whose sequence numbered this ticket (GTS-001…); null on legacy CSPEN tickets. */
    @Column(name = "partner_id")
    private UUID partnerId;

    /** Parent booking number shared by every device of one booking (GTS-001); null on older tickets. */
    @Column(name = "booking_number", length = 50)
    private String bookingNumber;

    /** A, B, C … when the booking has more than one device; null for a single-device booking. */
    @Column(name = "device_suffix", length = 4)
    private String deviceSuffix;

    @Column(name = "brand_id")
    private UUID brandId;

    @Column(name = "model_id")
    private UUID modelId;

    @Column(name = "ram_option_id")
    private UUID ramOptionId;

    @Column(name = "storage_option_id")
    private UUID storageOptionId;

    @Column(length = 100)
    private String color;

    @Column(length = 50)
    private String imei;

    /** Serial number for non-mobile devices (laptop, tablet, smartwatch, audio) — phones use imei. */
    @Column(name = "serial_number", length = 64)
    private String serialNumber;

    /** Accessories handed over with the device (charger, case, SIM tray …); free text, optional. */
    @Column(name = "accessories", length = 500)
    private String accessories;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "estimated_price", precision = 12, scale = 2)
    private BigDecimal estimatedPrice;

    @Column(name = "final_price", precision = 12, scale = 2)
    private BigDecimal finalPrice;

    // ---- Payment collected at the counter (migration 85) ------------------
    // Deliberately NOT finalPrice or customerApproval: those mean "what the
    // repair cost" and "the customer said go ahead". A deposit is neither.

    /**
     * How the customer paid: ADVANCE (deposit, with the balance due on
     * delivery) or FULL. NULL means nothing was collected up front — every
     * ticket booked before migration 85, and any walk-in that pays on delivery.
     */
    @Column(name = "payment_type", length = 20)
    private String paymentType;

    /** Amount actually collected. */
    @Column(name = "payment_amount", precision = 12, scale = 2)
    private BigDecimal paymentAmount;

    /**
     * Still owed: applicable total minus paymentAmount, never negative. Stored
     * rather than derived per-caller so the rule for which total applies
     * (finalPrice when set, else estimatedPrice) lives in one place —
     * TicketService#recomputeBalance, which every price- or payment-changing
     * path runs before it returns.
     */
    @Column(name = "balance_amount", precision = 12, scale = 2)
    private BigDecimal balanceAmount;

    /** PAID once an amount is recorded, PENDING while nothing has been. */
    @Column(name = "payment_status", length = 20)
    private String paymentStatus;

    /**
     * When the payment was recorded. Server-stamped, never taken from the
     * request — a device clock is not evidence of when cash was taken.
     */
    @Column(name = "payment_paid_at")
    private Instant paymentPaidAt;

    @Column(name = "issue_description", columnDefinition = "TEXT")
    private String issueDescription;

    /** Cloudinary URL for the customer's voice-note recording of the issue.
     *  The shop-app's "Review & Confirm" screen records via expo-av and uploads
     *  to /media/upload (folder=complaint-audio) before submitting the ticket.
     *  Migration 57 added this column. */
    @Column(name = "issue_audio_url", columnDefinition = "TEXT")
    private String issueAudioUrl;

    /**
     * Short, human-friendly name for the device, used directly by mobile UIs.
     * Example: "Galaxy Z Fold7 (16GB 512GB)".
     */
    @Column(name = "device_display_name", length = 200)
    private String deviceDisplayName;

    /**
     * Optional URL of the device image to show in booking, history, and receipt screens.
     */
    @Column(name = "device_image_url", length = 1000)
    private String deviceImageUrl;

    /**
     * Compact summary of selected repair services for this ticket.
     * Example: "Display Screen Combo, Battery".
     */
    @Column(name = "repair_services_summary", length = 500)
    private String repairServicesSummary;

    /**
     * JSON-encoded line items for the price summary.
     * Shape is an array of objects: [{ "label": "...", "amount": 1000 }, ...]
     */
    @Column(name = "price_items_json", columnDefinition = "TEXT")
    private String priceItemsJson;

    /**
     * JSON-encoded missing / damaged parts captured in the "Device Missing Parts" screen.
     */
    @Column(name = "missing_parts_json", columnDefinition = "TEXT")
    private String missingPartsJson;

    /**
     * JSON-encoded device photos metadata (front, back, video, etc.).
     */
    @Column(name = "device_photos_json", columnDefinition = "TEXT")
    private String devicePhotosJson;

    /**
     * JSON-encoded photos uploaded by the technician AFTER they accept the
     * ticket. Separate from device_photos_json (booking-time photos) so the
     * customer-side history can show "before vs after" if it wants to.
     * Shape: ["https://...", "https://..."] — list of URLs.
     */
    @Column(name = "technician_photos_json", columnDefinition = "TEXT")
    private String technicianPhotosJson;

    /**
     * Device security type: NONE, PATTERN, PIN, PASSWORD.
     */
    @Column(name = "device_security_type", length = 20)
    private String deviceSecurityType;

    /**
     * Optional raw value for the selected security type (pattern coordinates, PIN digits, or password).
     */
    @Column(name = "device_security_value", length = 255)
    private String deviceSecurityValue;

    /**
     * Whether the customer has explicitly approved the repair estimate.
     */
    @Column(name = "customer_approval")
    private Boolean customerApproval;

    /**
     * Estimated ready time and delivery time shown in the booking detail and device information screens.
     */
    @Column(name = "estimated_ready_at")
    private Instant estimatedReadyAt;

    @Column(name = "estimated_delivery_at")
    private Instant estimatedDeliveryAt;

    /** First delivery promise, kept when a re-estimate revises estimatedDeliveryAt (migration 103). */
    @Column(name = "original_estimated_delivery_at")
    private Instant originalEstimatedDeliveryAt;

    /** "Booked By": employee/owner whose PIN was verified server-side at submit (migration 120). */
    @Column(name = "booked_by_user_id")
    private UUID bookedByUserId;

    @Column(name = "booked_by_name", length = 150)
    private String bookedByName;

    @Column(name = "booked_by_role", length = 20)
    private String bookedByRole;

    @Column(name = "booked_at")
    private Instant bookedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
