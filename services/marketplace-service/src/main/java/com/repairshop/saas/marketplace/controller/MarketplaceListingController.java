package com.repairshop.saas.marketplace.controller;

import com.repairshop.saas.marketplace.dto.CreateListingRequest;
import com.repairshop.saas.marketplace.dto.ListingView;
import com.repairshop.saas.marketplace.service.MarketplaceListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Buy/Sell board endpoints used by the shop-owner Buy screen and the
 * customer/owner sell flows.
 *
 *  GET  /marketplace/buy/nearby  → AVAILABLE listings within radius
 *  POST /marketplace/listings    → create a new listing (customer or shop seller)
 */
@RestController
@RequestMapping("/marketplace")
@RequiredArgsConstructor
public class MarketplaceListingController {

    private final MarketplaceListingService service;

    @GetMapping("/buy/nearby")
    @ResponseStatus(HttpStatus.OK)
    public List<ListingView> nearby(
            @RequestParam(value = "lat",        required = false) BigDecimal lat,
            @RequestParam(value = "lng",        required = false) BigDecimal lng,
            @RequestParam(value = "radiusKm",   required = false, defaultValue = "20") double radiusKm,
            @RequestParam(value = "q",          required = false) String q,
            @RequestParam(value = "excludeSellerId", required = false) UUID excludeSellerId,
            jakarta.servlet.http.HttpServletRequest request
    ) {
        // shopId is set by JwtAuthFilter only for a valid shop token; anonymous / customer callers get no seller names.
        Object shop = request.getAttribute("shopId");
        UUID viewerShopId = shop == null ? null : UUID.fromString(shop.toString());
        return service.findNearby(lat, lng, radiusKm, q, excludeSellerId, viewerShopId);
    }

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingView create(@Valid @RequestBody CreateListingRequest req) {
        return service.create(req);
    }

    @DeleteMapping("/listings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
