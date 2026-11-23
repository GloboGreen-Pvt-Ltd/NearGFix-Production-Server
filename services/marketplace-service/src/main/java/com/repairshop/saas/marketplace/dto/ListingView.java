package com.repairshop.saas.marketplace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Buy-screen card payload — listing + derived distance from the caller's location. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingView {
    private UUID id;
    private String sellerType;
    private UUID sellerId;
    private UUID shopId;
    private UUID brandId;
    private UUID modelId;
    private UUID categoryId;
    private String productName;
    private String productImage;
    private String condition;
    private String description;
    private BigDecimal expectedPrice;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String status;
    private Instant createdAt;
    private Double distanceKm;   // computed by service
    /** Seller display name — only for a signed-in shop viewer (never a phone number). */
    private String sellerName;
}
