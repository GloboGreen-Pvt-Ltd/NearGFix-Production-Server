package com.repairshop.saas.common.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes shop_notifications rows (the Partner app's in-app feed, read through
 * order-service GET /shop/notifications) for services that do not map the
 * table as an entity: marketplace-service, and order-service's sell fan-out,
 * which writes one row per active shop in a single statement.
 *
 * <p>Column sizes follow database/schema/29_shop_notifications.sql; values are
 * clipped to fit so an over-long product title cannot fail the insert.
 */
@Component
public class ShopNotificationWriter {

    private final JdbcTemplate jdbc;

    public ShopNotificationWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row for one shop. Returns the new row's id. */
    public UUID insert(UUID shopId, String type, String statusKey, String title, String body,
                       UUID bookingId, String bookingNumber, UUID ticketId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO shop_notifications " +
                "(id, shop_id, booking_id, booking_number, ticket_id, status_key, title, body, type, is_read, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, now())",
                id, shopId, bookingId, clip(bookingNumber, 60), ticketId, clip(statusKey, 100),
                clip(title, 200), body, clip(type, 30));
        return id;
    }

    /**
     * One row, at most once per (shop, eventKey) — migration 130's unique index
     * makes a retried / concurrent / re-processed event a no-op. A null
     * eventKey always inserts. Returns the new row's id, or null when this
     * event was already recorded.
     */
    public UUID insertOnce(UUID shopId, String type, String statusKey, String title, String body,
                           UUID bookingId, String bookingNumber, UUID ticketId, String entityRef, String eventKey) {
        UUID id = UUID.randomUUID();
        int n = jdbc.update(
                "INSERT INTO shop_notifications " +
                "(id, shop_id, booking_id, booking_number, ticket_id, status_key, title, body, type, entity_ref, event_key, is_read, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, now()) " +
                "ON CONFLICT (shop_id, event_key) WHERE event_key IS NOT NULL DO NOTHING",
                id, shopId, bookingId, clip(bookingNumber, 60), ticketId, clip(statusKey, 100),
                clip(title, 200), body, clip(type, 30), clip(entityRef, 100), clip(eventKey, 200));
        return n > 0 ? id : null;
    }

    /**
     * The same row for every active shop, as one INSERT ... SELECT. Active means
     * {@code shops.is_active = true} — the same filter the customer app's shop
     * directory uses, so a shop customers cannot see is not asked to quote
     * either. Shops owned by {@code excludeOwnerUserId}, and
     * {@code excludeShopId}, are skipped: a shop user selling a device must not
     * be asked to quote on it. Both may be null. Returns shopId → new row id.
     */
    public Map<UUID, UUID> insertForActiveShops(UUID excludeOwnerUserId, UUID excludeShopId,
                                                String type, String statusKey,
                                                String title, String body, String bookingNumber) {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        jdbc.query(
                "INSERT INTO shop_notifications (id, shop_id, booking_number, status_key, title, body, type, is_read, created_at) " +
                "SELECT gen_random_uuid(), s.id, ?, ?, ?, ?, ?, false, now() FROM shops s " +
                "WHERE s.is_active = true " +
                "AND s.owner_user_id IS DISTINCT FROM CAST(? AS uuid) " +
                "AND s.id IS DISTINCT FROM CAST(? AS uuid) " +
                "RETURNING id, shop_id",
                rs -> { out.put((UUID) rs.getObject("shop_id"), (UUID) rs.getObject("id")); },
                clip(bookingNumber, 60), clip(statusKey, 100), clip(title, 200), body, clip(type, 30),
                excludeOwnerUserId, excludeShopId);
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
