package com.repairshop.saas.marketplace.service;

import com.repairshop.saas.common.notify.PartnerNotifications;
import com.repairshop.saas.marketplace.dto.EnquiryResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Owner Dashboard bell rows (shop_notifications, migration 29) for marketplace
 * enquiries, written through common-notify's PartnerNotifications: allowlisted kinds
 * only (New Enquiry, New Purchase Request, New Counter-Offer — "Offer accepted" stays
 * in the enquiry thread), plus the push to the shop's devices. type='sell_enquiry'
 * puts the row on the Partner app's Buy tab (a customer buying from the shop) and
 * opens Buyer Enquiries.
 *
 * Call only AFTER the enquiry transaction has committed (the controllers do), so
 * a failed insert can never roll back or abort the enquiry change.
 */
@Component("marketplaceShopNotificationWriter") // named: common.notify has a same-named bean
@RequiredArgsConstructor
public class ShopNotificationWriter {

    private static final Logger log = LoggerFactory.getLogger(ShopNotificationWriter.class);

    private final PartnerNotifications partnerNotifications;

    public void enquiryCreated(EnquiryResponse e) {
        boolean purchase = EnquiryService.PURCHASE_REQUEST.equals(e.getKind());
        String body = buyer(e) + (purchase ? " wants to buy " : " asked about ") + product(e)
                + (e.getLastOfferAmount() != null ? " and offered " + money(e.getLastOfferAmount()) : "") + ".";
        String key = purchase ? "MARKETPLACE_PURCHASE_REQUEST" : "MARKETPLACE_ENQUIRY";
        // One per enquiry, however often the create is retried.
        insert(e, key, purchase ? "New purchase request" : "New enquiry", body,
                e.getId() == null ? null : key + ":" + e.getId());
    }

    public void counterOffer(EnquiryResponse e) {
        // Each counter-offer is a new event (no key): a negotiation can have several.
        insert(e, "MARKETPLACE_ENQUIRY_COUNTERED", "New counter-offer",
                buyer(e) + " offered " + money(e.getLastOfferAmount()) + " for " + product(e) + ".", null);
    }

    public void offerAccepted(EnquiryResponse e) {
        // Not on the Partner allowlist: the policy drops it (kept so the allowlist alone decides).
        insert(e, "MARKETPLACE_ENQUIRY_ACCEPTED", "Offer accepted",
                buyer(e) + " accepted " + money(e.getAgreedAmount()) + " for " + product(e)
                        + ". Confirm the sale to update your stock.", null);
    }

    private void insert(EnquiryResponse e, String statusKey, String title, String body, String eventKey) {
        if (e.getShopId() == null) return;
        try {
            partnerNotifications.notify(PartnerNotifications.Event.of(e.getShopId(), "sell_enquiry", statusKey, title)
                    .body(body).kind("BUY_ENQUIRY").data("enquiryId", e.getId()).eventKey(eventKey));
        } catch (RuntimeException ex) {
            log.warn("marketplace shop notification skipped for shop {}: {}", e.getShopId(), ex.getMessage());
        }
    }

    private static String buyer(EnquiryResponse e) {
        String name = e.getBuyer() != null ? e.getBuyer().getName() : null;
        return name == null || name.isBlank() ? "A buyer" : name.trim();
    }

    private static String product(EnquiryResponse e) {
        String title = e.getProduct() != null ? e.getProduct().getTitle() : null;
        return title == null || title.isBlank() ? "your listing" : "\"" + title.trim() + "\"";
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "an amount" : "₹" + amount.stripTrailingZeros().toPlainString();
    }
}
