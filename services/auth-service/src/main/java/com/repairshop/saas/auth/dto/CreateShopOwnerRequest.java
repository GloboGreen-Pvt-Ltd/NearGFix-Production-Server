package com.repairshop.saas.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Create a shop OWNER (User row with role SHOP_OWNER) plus one or more shops
 * (Shop rows linked to the owner via shops.owner_user_id).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create a shop owner with one or more shop locations")
public class CreateShopOwnerRequest {

    @NotBlank(message = "Owner name is required")
    private String name;

    @NotBlank(message = "Owner email is required")
    private String email;

    @NotBlank(message = "Owner password is required")
    private String password;

    private String phone;            // primary mobile
    private String secondaryMobile;
    private String avatarUrl;
    private String idProofUrl;

    // Owner KYC documents (stored in users.kyc_document jsonb). Optional at
    // create time — the admin may upload them here, or the owner uploads later
    // from the mobile app.
    private String aadharFrontUrl;
    private String aadharBackUrl;
    private String panUrl;

    // Legacy free-text address (kept for backward compatibility).
    private String personalAddress;

    // Structured personal address.
    private String addrState;
    private String addrDistrict;
    private String addrTaluk;
    private String addrArea;
    private String addrStreet;
    private String addrPincode;

    @Schema(description = "Optional OTP code for OTP login (defaults to 123456 when omitted)")
    private String otpCode;

    @Valid
    @NotEmpty(message = "At least one shop location is required")
    private List<ShopLocationDto> locations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One shop location under this owner")
    public static class ShopLocationDto {
        @NotBlank(message = "Shop name is required")
        private String name;

        private String slug;
        private String mobile;
        private String address;
        private String district;
        private String state;
        private String pincode;
        private String taluk;
        private String area;
        private String street;
        private String gstNumber;
        private java.math.BigDecimal latitude;
        private java.math.BigDecimal longitude;
        private String whatsappNumber;
        private String googlePlaceId;
        private String frontImageUrl;
        private String bannerImageUrl;
        private String gstCertificateUrl;
        private String udyamCertificateUrl;

        // Pickup-service options
        private String pickupFromTime;
        private String pickupToTime;
        private Integer pickupDistanceKm;
        private Boolean pickupEnabled;

        // Shop working hours (admin Edit Business Location form)
        private String workingDays;   // MON_FRI | MON_SAT | MON_SUN
        private String openingTime;   // e.g. "08:00 AM"
        private String closingTime;   // e.g. "07:00 PM"

        /**
         * JSON payload of "what this shop repairs" — see Shop.serviceCategoriesJson.
         * Round-tripped opaquely so the client owns the shape.
         */
        private String serviceCategoriesJson;
    }
}
