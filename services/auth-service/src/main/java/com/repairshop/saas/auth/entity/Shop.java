package com.repairshop.saas.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shop {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(length = 255)
    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 50)
    private String mobile;

    @Column(name = "mobile_password_hash", columnDefinition = "TEXT")
    private String mobilePasswordHash;

    @Column(name = "mobile_otp_code", length = 8)
    private String mobileOtpCode;

    @Column(length = 120)
    private String district;

    @Column(length = 120)
    private String state;

    @Column(length = 20)
    private String pincode;

    @Column(precision = 10, scale = 7)
    private java.math.BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private java.math.BigDecimal longitude;

    @Column(name = "front_image_url", length = 1000)
    private String frontImageUrl;

    @Column(name = "banner_image_url", length = 1000)
    private String bannerImageUrl;

    @Column(name = "gst_certificate_url", length = 1000)
    private String gstCertificateUrl;

    @Column(name = "udyam_certificate_url", length = 1000)
    private String udyamCertificateUrl;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "gst_number", length = 50)
    private String gstNumber;

    @Column(length = 120)
    private String taluk;

    @Column(length = 160)
    private String area;

    @Column(length = 200)
    private String street;

    @Column(length = 50)
    private String timezone;

    @Column(name = "whatsapp_number", length = 20)
    private String whatsappNumber;

    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    @Column(name = "pickup_from_time", length = 16)
    private String pickupFromTime;

    @Column(name = "pickup_to_time", length = 16)
    private String pickupToTime;

    @Column(name = "pickup_distance_km")
    private Integer pickupDistanceKm;

    @Column(name = "pickup_enabled", nullable = false)
    private Boolean pickupEnabled = false;

    /** Preset day range: MON_FRI | MON_SAT | MON_SUN. */
    @Column(name = "working_days", length = 20)
    private String workingDays;

    /** Human-readable open time (e.g. "08:00 AM"), matches pickup_from_time shape. */
    @Column(name = "opening_time", length = 16)
    private String openingTime;

    /** Human-readable close time (e.g. "07:00 PM"). */
    @Column(name = "closing_time", length = 16)
    private String closingTime;

    /**
     * Snapshot of "what this shop repairs" — populated from the Shop Information
     * screen's two-column Android / Apple grid. JSON shape:
     *   {"android":["Screen Repair","Battery Replacement"], "apple":[...]}
     * NULL until the owner first saves the form. Backend treats the value as
     * opaque text and just round-trips it to the client.
     */
    @Column(name = "service_categories_json", columnDefinition = "TEXT")
    private String serviceCategoriesJson;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (timezone == null || timezone.isBlank()) timezone = "Asia/Kolkata";
        if (isActive == null) isActive = Boolean.TRUE;
        if (pickupEnabled == null) pickupEnabled = Boolean.FALSE;
        if (mobileOtpCode == null || mobileOtpCode.isBlank()) mobileOtpCode = "123456";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
