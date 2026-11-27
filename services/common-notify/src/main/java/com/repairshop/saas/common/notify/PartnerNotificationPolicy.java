package com.repairshop.saas.common.notify;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Partner (shop) app's notification allowlist — the single place that
 * decides which business events reach the shop owner's Notifications screen
 * and phone, and which tab they belong to.
 *
 * <p>Routine progress (technician started, pickup on the way, task started …)
 * stays in timelines, booking/pickup/order history and audit tables; it just
 * no longer raises a shop notification. Everything not listed here is
 * suppressed: producers skip the row and the push, and the feed hides older
 * rows of those kinds (they are never deleted).
 *
 * <p>Keyed by (category, status_key): the category comes from the feed row's
 * {@code type}, so the same status (CANCELLED, REPAIR_COMPLETED) can be allowed
 * on one tab and suppressed on another.
 */
public final class PartnerNotificationPolicy {

    public static final String SERVICE = "SERVICE";
    public static final String PICKUP = "PICKUP";
    public static final String SELL = "SELL";
    public static final String BUY = "BUY";
    public static final String TEAM = "TEAM";

    /** Feed row {@code type} → tab. Customer selling to the shop = SELL; customer buying from the shop = BUY. */
    private static final Map<String, String> TYPE_CATEGORY = Map.of(
            "bookings", SERVICE,
            "enquiry", SERVICE,
            "pickups", PICKUP,
            "sell", SELL,
            "purchase", SELL,          // customer-sell price / payment steps (was shown under Buy)
            "sell_enquiry", BUY,       // buyer enquiries, purchase requests, counter-offers on the shop's listings
            "buy", BUY,
            "chat", BUY,
            "team", TEAM);

    /** The 28 approved notification groups, as the status keys that raise them. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            SERVICE, Set.of(
                    "ORDER_PLACED",                     // 1 New Enquiry (type enquiry) · 2 New Booking Received
                    "CUSTOMER_APPROVED",                // 3 Customer Approved Estimate
                    "CUSTOMER_REJECTED",                // 4 Customer Rejected Estimate
                    "TECHNICIAN_REESTIMATE_REQUESTED",  // 5 Re-estimate Requested
                    "REPAIR_COMPLETED",                 // 6 Repair Completed
                    "REPAIR_NOT_COMPLETED",             // 7 Repair Not Completed
                    "CANCELLED"),                       // 8 Booking Cancelled
            PICKUP, Set.of(
                    "PICKUP_REQUESTED",                 // 9 New Pickup Request
                    "DEVICE_PICKED_UP", "PICKED_UP",    // 10 Device Picked Up
                    "HANDOVER_REQUESTED",               // 11 Shop Handover Confirmation Required (TPIN)
                    "CANCELLED"),                       // 12 Pickup Cancelled
            SELL, Set.of(
                    "SELL_REQUESTED",                   // 13 New Sell Request
                    "SELL_CONFIRMED",                   // 14 Quote Accepted
                    "SELL_PICKUP_FAILED", "SELL_PICKUP_DECLINED",              // 15 Pickup Failed / Declined
                    "SELL_PICKUP_INSPECTED",            // 16 Inspection Submitted – Approval Required
                    "SELL_PICKUP_REVISION_AGREED", "SELL_PICKUP_REVISION_DECLINED",
                    "SELL_REVISION_APPROVED", "SELL_REVISION_REJECTED",        // 17 Final Price Accepted / Rejected
                    "SELL_PICKUP_PAID", "SELL_PAYOUT_CONFIRMED"),              // 18 Payment Completed
            BUY, Set.of(
                    "MARKETPLACE_ENQUIRY",              // 19 New Enquiry
                    "CHAT_MESSAGE",                     // 20 New Customer Message (push only)
                    "MARKETPLACE_PURCHASE_REQUEST",     // 21 New Purchase Request
                    "MARKETPLACE_ENQUIRY_COUNTERED",    // 22 New Counter-Offer
                    "BUY_ORDER_PLACED"),                // 23 New Order Placed
            TEAM, Set.of(
                    "ATTENDANCE_CHECKED_IN",            // 24 Employee Checked In
                    "ATTENDANCE_CHECKED_OUT",           // 25 Employee Checked Out
                    "PRESENCE_OUTSIDE_SHOP",            // 26 Employee Outside Shop
                    "PRESENCE_RETURNED",                // 27 Employee Returned to Shop
                    "STAFF_TASK_INCOMPLETE"));          // 28 Task Not Completed

    private PartnerNotificationPolicy() {}

    /** Tab for a feed row; null when the type is unknown (such rows are not shown). */
    public static String categoryOf(String type) {
        if (type == null) return null;
        return TYPE_CATEGORY.get(type.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether a shop notification of this type and status may be created, pushed and listed. */
    public static boolean allows(String type, String statusKey) {
        String category = categoryOf(type);
        if (category == null || statusKey == null) return false;
        return ALLOWED.get(category).contains(statusKey.trim().toUpperCase(Locale.ROOT));
    }
}
