package com.repairshop.saas.common.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The Partner app allowlist (28 groups), its tab mapping, and the notifier built on it. */
class PartnerNotificationPolicyTest {

    @ParameterizedTest(name = "{0}/{1} allowed")
    @CsvSource({
            // Service (8)
            "enquiry,ORDER_PLACED", "bookings,ORDER_PLACED", "bookings,CUSTOMER_APPROVED", "bookings,CUSTOMER_REJECTED",
            "bookings,TECHNICIAN_REESTIMATE_REQUESTED", "bookings,REPAIR_COMPLETED", "bookings,REPAIR_NOT_COMPLETED",
            "bookings,CANCELLED",
            // Pickup (4)
            "pickups,PICKUP_REQUESTED", "pickups,DEVICE_PICKED_UP", "pickups,PICKED_UP", "pickups,HANDOVER_REQUESTED",
            "pickups,CANCELLED",
            // Sell (6)
            "sell,SELL_REQUESTED", "sell,SELL_CONFIRMED", "purchase,SELL_PICKUP_FAILED", "purchase,SELL_PICKUP_DECLINED",
            "purchase,SELL_PICKUP_INSPECTED", "purchase,SELL_PICKUP_REVISION_AGREED", "purchase,SELL_PICKUP_REVISION_DECLINED",
            "purchase,SELL_REVISION_APPROVED", "purchase,SELL_REVISION_REJECTED", "purchase,SELL_PICKUP_PAID",
            "purchase,SELL_PAYOUT_CONFIRMED",
            // Buy (5)
            "sell_enquiry,MARKETPLACE_ENQUIRY", "chat,CHAT_MESSAGE", "sell_enquiry,MARKETPLACE_PURCHASE_REQUEST",
            "sell_enquiry,MARKETPLACE_ENQUIRY_COUNTERED", "buy,BUY_ORDER_PLACED",
            // Team (5)
            "team,ATTENDANCE_CHECKED_IN", "team,ATTENDANCE_CHECKED_OUT", "team,PRESENCE_OUTSIDE_SHOP",
            "team,PRESENCE_RETURNED", "team,STAFF_TASK_INCOMPLETE",
    })
    void approvedGroupsAreAllowed(String type, String statusKey) {
        assertTrue(PartnerNotificationPolicy.allows(type, statusKey));
        assertTrue(PartnerNotificationPolicy.allows(type.toUpperCase(), statusKey.toLowerCase()), "case-insensitive");
    }

    @ParameterizedTest(name = "{0}/{1} suppressed")
    @CsvSource({
            "bookings,TECHNICIAN_ACCEPTED_SERVICE", "bookings,TECHNICIAN_WORK_STARTED", "bookings,TECHNICIAN_UPLOADED_DEVICE_IMAGES",
            "bookings,ISSUE_IDENTIFIED", "bookings,TECHNICIAN_COMPLIANCE_ISSUE_VERIFIED_UPDATED", "bookings,WAITING_FOR_CUSTOMER_APPROVAL",
            "bookings,RE_ESTIMATED_CONFIRMED", "bookings,RE_ESTIMATE_APPROVED", "bookings,RE_ESTIMATE_REJECTED",
            "bookings,PARTS_REQUIRED", "bookings,QUALITY_CHECK_COMPLETED", "bookings,READY",
            "pickups,PICKUP_ACCEPTED", "pickups,PICKUP_PERSON_ASSIGNED", "pickups,PICKUP_ON_THE_WAY",
            "pickups,REACHED_CUSTOMER_LOCATION", "pickups,REPAIR_ESTIMATE_PROCESSING", "pickups,REACHED_SHOP",
            "pickups,RECEIVED_AT_SHOP", "pickups,REPAIR_COMPLETED",
            "purchase,SELL_PICKUP_ACCEPTED", "purchase,SELL_PICKUP_ON_THE_WAY", "purchase,SELL_PICKED_UP",
            "purchase,SELL_DEVICE_HANDED_OVER", "purchase,SELL_PICKUP_OTP_VERIFIED", "purchase,SELL_PICKUP_REVISION",
            "purchase,SELL_REVISION_NEGOTIATION", "purchase,SELL_PAYOUT_DISPUTED", "purchase,SELL_CANCELLED_BY_CUSTOMER",
            "sell_enquiry,MARKETPLACE_ENQUIRY_ACCEPTED",
            "team,STAFF_TASK_IN_PROGRESS", "team,STAFF_TASK_COMPLETED", "team,STAFF_TASK_UPDATED",
            "system,ORDER_PLACED", "bookings,HANDOVER_REQUESTED",
    })
    void everythingElseIsSuppressed(String type, String statusKey) {
        assertFalse(PartnerNotificationPolicy.allows(type, statusKey));
    }

    @Test
    void unknownOrMissingValuesAreNotAllowed() {
        assertFalse(PartnerNotificationPolicy.allows(null, "ORDER_PLACED"));
        assertFalse(PartnerNotificationPolicy.allows("bookings", null));
        assertNull(PartnerNotificationPolicy.categoryOf("system"));
    }

    @Test
    void tabsFollowWhoIsSellingToWhom() {
        assertEquals("SERVICE", PartnerNotificationPolicy.categoryOf("bookings"));
        assertEquals("SERVICE", PartnerNotificationPolicy.categoryOf("enquiry"));
        assertEquals("PICKUP", PartnerNotificationPolicy.categoryOf("pickups"));
        // Customer selling a device to the shop (was wrongly shown under Buy).
        assertEquals("SELL", PartnerNotificationPolicy.categoryOf("sell"));
        assertEquals("SELL", PartnerNotificationPolicy.categoryOf("purchase"));
        // Customer buying from the shop.
        assertEquals("BUY", PartnerNotificationPolicy.categoryOf("sell_enquiry"));
        assertEquals("BUY", PartnerNotificationPolicy.categoryOf("buy"));
        assertEquals("TEAM", PartnerNotificationPolicy.categoryOf("team"));
    }

    // ── PartnerNotifications ────────────────────────────────────────────────

    private final ShopNotificationWriter writer = mock(ShopNotificationWriter.class);
    private final ShopPushService push = mock(ShopPushService.class);
    private final PartnerNotifications notifications = new PartnerNotifications(writer, push);
    private final UUID shop = UUID.randomUUID();

    {
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(push).afterCommit(any());
    }

    @Test
    void suppressedEventCreatesNoRowAndNoPush() {
        assertFalse(notifications.notify(PartnerNotifications.Event.of(shop, "bookings", "TECHNICIAN_WORK_STARTED", "Technician work started")));
        verifyNoInteractions(writer);
        verify(push, never()).pushToShop(any(), any(), any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowedEventWritesOneRowThenPushesItAfterCommit() {
        UUID row = UUID.randomUUID();
        UUID tech = UUID.randomUUID();
        when(writer.insertOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(row);

        assertTrue(notifications.notify(PartnerNotifications.Event.of(shop, "team", "ATTENDANCE_CHECKED_IN", "Employee Checked In")
                .body("Employee Arun checked in at 09:15 AM.").entityRef(tech.toString()).kind("TEAM_ATTENDANCE")
                .data("technicianId", tech).eventKey("ATTENDANCE_CHECKED_IN:a1:first")));

        verify(push).afterCommit(any());
        verify(writer).insertOnce(eq(shop), eq("team"), eq("ATTENDANCE_CHECKED_IN"), eq("Employee Checked In"),
                eq("Employee Arun checked in at 09:15 AM."), isNull(), isNull(), isNull(), eq(tech.toString()),
                eq("ATTENDANCE_CHECKED_IN:a1:first"));
        var data = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(push).pushToShop(eq(shop), eq("Employee Checked In"), eq("Employee Arun checked in at 09:15 AM."), data.capture());
        assertEquals("TEAM_ATTENDANCE", data.getValue().get("kind"));
        assertEquals("TEAM", data.getValue().get("category"));
        assertEquals(row.toString(), data.getValue().get("notificationId"));
        assertEquals(tech.toString(), data.getValue().get("technicianId"));
    }

    @Test
    void repeatedEventIsNotPushedAgain() {
        // insertOnce returns null when the (shop, event key) row already exists.
        when(writer.insertOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(null);
        notifications.notify(PartnerNotifications.Event.of(shop, "pickups", "HANDOVER_REQUESTED", "Shop Handover Confirmation Required")
                .eventKey("HANDOVER_REQUESTED:b1:t1"));
        verify(push, never()).pushToShop(any(), any(), any(), anyMap());
    }

    @Test
    void aFailedInsertNeverThrowsToTheCaller() {
        when(writer.insertOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> notifications.notify(
                PartnerNotifications.Event.of(shop, "bookings", "REPAIR_COMPLETED", "Repair completed")));
        verify(push, never()).pushToShop(any(), any(), any(), anyMap());
    }
}
