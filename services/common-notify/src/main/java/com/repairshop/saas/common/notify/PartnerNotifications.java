package com.repairshop.saas.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One shop notification: the Partner app feed row plus the push to the shop's
 * devices, gated by {@link PartnerNotificationPolicy}.
 *
 * <ul>
 *   <li>Suppressed kinds create nothing — no row, no push.</li>
 *   <li>Runs after the caller's transaction commits, on the push executor, so a
 *       rolled-back change never notifies and a failed insert can never abort
 *       the caller's transaction.</li>
 *   <li>With an event key, one business event creates one row however often it
 *       is raised (retries, concurrent requests, re-processing); the push goes
 *       out only when the row was actually created.</li>
 *   <li>Never throws.</li>
 * </ul>
 */
@Service
public class PartnerNotifications {

    private static final Logger log = LoggerFactory.getLogger(PartnerNotifications.class);

    private final ShopNotificationWriter writer;
    private final ShopPushService push;

    public PartnerNotifications(ShopNotificationWriter writer, ShopPushService push) {
        this.writer = writer;
        this.push = push;
    }

    /** A shop notification to raise. Fluent; only shop, type, status key and title are required. */
    public static final class Event {
        private final UUID shopId;
        private final String type;
        private final String statusKey;
        private final String title;
        private String body;
        private UUID bookingId;
        private String bookingNumber;
        private UUID ticketId;
        private String entityRef;
        private String eventKey;
        private String kind;
        private final Map<String, Object> data = new HashMap<>();

        private Event(UUID shopId, String type, String statusKey, String title) {
            this.shopId = shopId;
            this.type = type;
            this.statusKey = statusKey;
            this.title = title;
        }

        public static Event of(UUID shopId, String type, String statusKey, String title) {
            return new Event(shopId, type, statusKey, title);
        }

        public Event body(String v) { body = v; return this; }
        public Event booking(UUID id, String number) { bookingId = id; bookingNumber = number; return this; }
        public Event ticket(UUID v) { ticketId = v; return this; }
        /** Id of the record the row opens when there is no booking/ticket (e.g. the employee). */
        public Event entityRef(String v) { entityRef = v; return this; }
        /** Identity of the business event; repeated raises of the same key create nothing. */
        public Event eventKey(String v) { eventKey = v; return this; }
        /** Partner app push routing key (data.kind). */
        public Event kind(String v) { kind = v; return this; }
        public Event data(String k, Object v) { if (k != null && v != null) data.put(k, String.valueOf(v)); return this; }

        public UUID shopId() { return shopId; }
        public String type() { return type; }
        public String statusKey() { return statusKey; }
        public String title() { return title; }
        public String body() { return body; }
        public String eventKey() { return eventKey; }
        public String kind() { return kind; }
        public Map<String, Object> data() { return Map.copyOf(data); }
    }

    /** Raises {@code e} once the caller's transaction commits. Returns false when the policy suppresses it. */
    public boolean notify(Event e) {
        if (e == null || e.shopId == null || e.title == null) return false;
        if (!PartnerNotificationPolicy.allows(e.type, e.statusKey)) return false;
        push.afterCommit(() -> deliver(e));
        return true;
    }

    private void deliver(Event e) {
        UUID id;
        try {
            id = writer.insertOnce(e.shopId, e.type, e.statusKey, e.title, e.body,
                    e.bookingId, e.bookingNumber, e.ticketId, e.entityRef, e.eventKey);
        } catch (Exception ex) {
            log.warn("Shop notification {} not written: {}", e.statusKey, ex.getMessage());
            return;
        }
        if (id == null) return; // already notified for this event
        Map<String, Object> d = new HashMap<>(e.data);
        if (e.kind != null) d.put("kind", e.kind);
        d.put("notificationId", id.toString());
        d.put("statusKey", e.statusKey);
        d.put("category", PartnerNotificationPolicy.categoryOf(e.type));
        if (e.bookingId != null) d.put("bookingId", e.bookingId.toString());
        if (e.bookingNumber != null) d.put("bookingNumber", e.bookingNumber);
        if (e.ticketId != null) d.put("ticketId", e.ticketId.toString());
        if (e.entityRef != null) d.put("entityRef", e.entityRef);
        push.pushToShop(e.shopId, e.title, e.body, d);
    }
}
