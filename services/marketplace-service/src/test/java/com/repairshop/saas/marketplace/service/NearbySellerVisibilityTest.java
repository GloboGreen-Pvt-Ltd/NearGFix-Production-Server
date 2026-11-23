package com.repairshop.saas.marketplace.service;

import com.repairshop.saas.marketplace.dto.ListingView;
import com.repairshop.saas.marketplace.entity.MarketplaceListing;
import com.repairshop.saas.marketplace.repository.MarketplaceListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Seller identity on GET /marketplace/buy/nearby: name for shop viewers only, phone never. */
class NearbySellerVisibilityTest {

    final UUID customerId = UUID.randomUUID();
    final UUID sellerShopId = UUID.randomUUID();
    MarketplaceListingRepository repo;
    ChatSnapshotService snapshots;
    MarketplaceListingService service;

    @BeforeEach
    void setUp() {
        repo = mock(MarketplaceListingRepository.class);
        snapshots = mock(ChatSnapshotService.class);
        when(repo.findByStatusAndQuery(any(), any())).thenReturn(List.of(
                MarketplaceListing.builder().id(UUID.randomUUID()).sellerType("CUSTOMER").sellerId(customerId).productName("iPhone 11 Pro").status("AVAILABLE").build(),
                MarketplaceListing.builder().id(UUID.randomUUID()).sellerType("SHOP").shopId(sellerShopId).productName("Asus 8Z").status("AVAILABLE").build()));
        when(snapshots.loadCustomer(customerId)).thenReturn(new ChatSnapshotService.CustomerSnapshot("Nandha", "8939615914", null, Instant.now()));
        when(snapshots.loadShop(sellerShopId)).thenReturn(new ChatSnapshotService.ShopSnapshot("Globo Green", null, "9000000000", Instant.now()));
        service = new MarketplaceListingService(repo);
        ReflectionTestUtils.setField(service, "snapshots", snapshots);
    }

    @Test
    void shopViewerSeesSellerNames() {
        List<ListingView> v = service.findNearby(null, null, 20, null, null, UUID.randomUUID());
        assertEquals("Nandha", v.get(0).getSellerName());
        assertEquals("Globo Green", v.get(1).getSellerName());
    }

    @Test
    void anonymousOrCustomerViewerGetsNoNamesAndNoLookups() {
        List<ListingView> v = service.findNearby(null, null, 20, null, null, null);
        assertTrue(v.stream().allMatch(x -> x.getSellerName() == null));
        assertTrue(service.findNearby(null, null, 20, null, null).stream().allMatch(x -> x.getSellerName() == null), "legacy overload unchanged");
        verifyNoInteractions(snapshots);
    }

    @Test
    void feedNeverCarriesAPhoneNumber() {
        assertTrue(Arrays.stream(ListingView.class.getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().matches(".*(phone|mobile).*")), "no phone field on the listing payload");
        // Another shop viewing the same listings (cross-shop): still name only.
        ListingView v = service.findNearby(null, null, 20, null, null, UUID.randomUUID()).get(0);
        assertFalse(v.toString().contains("8939615914"));
    }

    @Test
    void nameLookupFailureNeverBreaksTheFeed() {
        when(snapshots.loadCustomer(customerId)).thenThrow(new RuntimeException("db down"));
        List<ListingView> v = service.findNearby(null, null, 20, null, null, UUID.randomUUID());
        assertEquals(2, v.size());
        assertNull(v.get(0).getSellerName());
    }
}
