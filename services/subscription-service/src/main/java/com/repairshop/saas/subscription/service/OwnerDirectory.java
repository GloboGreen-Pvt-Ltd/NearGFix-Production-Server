package com.repairshop.saas.subscription.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only lookups into tables other services own (auth-service `users` /
 * `shops`, ticket-service `technicians`, order-service `sell_orders`). JDBC,
 * not JPA, for the same reason as common-subscription: mapping a foreign table
 * as an @Entity would tie this service's boot validation to their migrations.
 *
 * <p>Failures return empty results. For ownership that is fail-CLOSED (an
 * unreadable shop list owns nothing); for usage counters it reads as zero.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public record ShopInfo(UUID id, UUID ownerUserId, String name, String slug, String location,
                           boolean active, String mobile) {}

    public record OwnerInfo(UUID id, String name, String email, String phone, String role) {}

    /** What an invoice's "Bill To" block and the GST place of supply need from a shop. */
    public record BillingShop(UUID id, String name, String phone, String mobile, String address, String city,
                              String state, String pincode, String gstNumber) {}

    private static final String SHOP_COLUMNS =
            "id, owner_user_id, name, slug, area, district, state, address, is_active, mobile";

    /** Every shop the owner owns, oldest first. */
    public List<ShopInfo> shopsOf(UUID ownerUserId) {
        if (ownerUserId == null) return List.of();
        try {
            return jdbc.query("SELECT " + SHOP_COLUMNS + " FROM shops WHERE owner_user_id = :owner ORDER BY created_at",
                    new MapSqlParameterSource("owner", ownerUserId), (rs, i) -> shop(rs));
        } catch (Exception e) {
            log.warn("Could not list shops for owner {}: {}", ownerUserId, e.toString());
            return List.of();
        }
    }

    public Map<UUID, ShopInfo> shopsByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        try {
            return jdbc.query("SELECT " + SHOP_COLUMNS + " FROM shops WHERE id IN (:ids)",
                            new MapSqlParameterSource("ids", ids), (rs, i) -> shop(rs))
                    .stream().collect(Collectors.toMap(ShopInfo::id, s -> s, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Could not load shops {}: {}", ids, e.toString());
            return Map.of();
        }
    }

    /** One shop's billing details (invoice Bill To / place of supply); empty when unreadable. */
    public Optional<BillingShop> billingShop(UUID shopId) {
        if (shopId == null) return Optional.empty();
        try {
            List<BillingShop> rows = jdbc.query("""
                    SELECT id, name, phone, mobile, address, COALESCE(NULLIF(city, ''), district) AS city, state,
                           pincode, gst_number
                      FROM shops WHERE id = :id
                    """, new MapSqlParameterSource("id", shopId),
                    (rs, i) -> new BillingShop((UUID) rs.getObject("id"), rs.getString("name"), rs.getString("phone"),
                            rs.getString("mobile"), rs.getString("address"), rs.getString("city"),
                            rs.getString("state"), rs.getString("pincode"), rs.getString("gst_number")));
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.warn("Could not load billing details of shop {}: {}", shopId, e.toString());
            return Optional.empty();
        }
    }

    public Optional<OwnerInfo> owner(UUID userId) {
        if (userId == null) return Optional.empty();
        return Optional.ofNullable(owners(List.of(userId)).get(userId));
    }

    public Map<UUID, OwnerInfo> owners(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        try {
            return jdbc.query("SELECT id, name, email, phone, role FROM users WHERE id IN (:ids)",
                            new MapSqlParameterSource("ids", ids),
                            (rs, i) -> new OwnerInfo((UUID) rs.getObject("id"), rs.getString("name"),
                                    rs.getString("email"), rs.getString("phone"), rs.getString("role")))
                    .stream().collect(Collectors.toMap(OwnerInfo::id, o -> o, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Could not load owners: {}", e.toString());
            return Map.of();
        }
    }

    /** Business name = the owner's first shop's name (there is no separate column). */
    public Map<UUID, String> businessNames(Collection<UUID> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) return Map.of();
        try {
            Map<UUID, String> out = new HashMap<>();
            jdbc.query("""
                    SELECT DISTINCT ON (owner_user_id) owner_user_id, name
                      FROM shops WHERE owner_user_id IN (:ids)
                     ORDER BY owner_user_id, created_at
                    """, new MapSqlParameterSource("ids", ownerIds),
                    rs -> { out.put((UUID) rs.getObject("owner_user_id"), rs.getString("name")); });
            return out;
        } catch (Exception e) {
            log.warn("Could not load business names: {}", e.toString());
            return Map.of();
        }
    }

    /** Active (is_available) employees per shop of this owner — the "per shop" seat count. */
    public Map<UUID, Long> activeEmployeesByShop(UUID ownerUserId) {
        return countByShop("""
                SELECT t.shop_id AS k, COUNT(*) AS n FROM technicians t JOIN shops s ON s.id = t.shop_id
                 WHERE s.owner_user_id = :owner AND t.is_available = true AND t.is_owner = false GROUP BY t.shop_id
                """, ownerUserId);
    }

    /** Sell orders won per shop of this owner (same count the limit engine uses). */
    public Map<UUID, Long> sellOrdersByShop(UUID ownerUserId) {
        return countByShop("""
                SELECT so.shop_id AS k, COUNT(*) AS n FROM sell_orders so JOIN shops s ON s.id = so.shop_id
                 WHERE s.owner_user_id = :owner GROUP BY so.shop_id
                """, ownerUserId);
    }

    /** Active employees per owner, across all their shops (admin list). */
    public Map<UUID, Long> activeEmployeesByOwner(Collection<UUID> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) return Map.of();
        try {
            Map<UUID, Long> out = new HashMap<>();
            jdbc.query("""
                    SELECT s.owner_user_id AS k, COUNT(*) AS n FROM technicians t JOIN shops s ON s.id = t.shop_id
                     WHERE s.owner_user_id IN (:ids) AND t.is_available = true AND t.is_owner = false GROUP BY s.owner_user_id
                    """, new MapSqlParameterSource("ids", ownerIds),
                    rs -> { out.put((UUID) rs.getObject("k"), rs.getLong("n")); });
            return out;
        } catch (Exception e) {
            log.warn("Could not count employees: {}", e.toString());
            return Map.of();
        }
    }

    /**
     * Whether a DIFFERENT owner account that shares this owner's mobile number
     * (users.phone) or one of the given shop mobiles has already had a trial.
     * Emails are unique across users (uq_users_email), so they cannot collide.
     * Fails open (false) — this is an abuse heuristic, not an entitlement.
     */
    public boolean trialUsedByAnotherOwner(UUID ownerUserId, String ownerPhone, Collection<String> shopMobiles) {
        List<String> numbers = Stream.concat(Stream.ofNullable(ownerPhone), shopMobiles == null
                        ? Stream.empty() : shopMobiles.stream())
                .map(OwnerDirectory::last10)
                .filter(n -> n != null)
                .distinct()
                .toList();
        if (ownerUserId == null || numbers.isEmpty()) return false;
        try {
            MapSqlParameterSource p = new MapSqlParameterSource("owner", ownerUserId).addValue("numbers", numbers);
            Long n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM subscriptions sub
                     WHERE sub.owner_user_id <> :owner
                       AND (sub.trial_start_date IS NOT NULL OR UPPER(COALESCE(sub.plan_code, '')) = 'FREE_TRIAL')
                       AND (EXISTS (SELECT 1 FROM users u WHERE u.id = sub.owner_user_id
                                     AND RIGHT(REGEXP_REPLACE(COALESCE(u.phone, ''), '[^0-9]', '', 'g'), 10) IN (:numbers))
                         OR EXISTS (SELECT 1 FROM shops sh WHERE sh.owner_user_id = sub.owner_user_id
                                     AND RIGHT(REGEXP_REPLACE(COALESCE(sh.mobile, ''), '[^0-9]', '', 'g'), 10) IN (:numbers)))
                    """, p, Long.class);
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("Trial duplicate check failed for owner {}: {}", ownerUserId, e.toString());
            return false;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<UUID, Long> countByShop(String sql, UUID ownerUserId) {
        if (ownerUserId == null) return Map.of();
        try {
            Map<UUID, Long> out = new HashMap<>();
            jdbc.query(sql, new MapSqlParameterSource("owner", ownerUserId),
                    rs -> { out.put((UUID) rs.getObject("k"), rs.getLong("n")); });
            return out;
        } catch (Exception e) {
            log.warn("Usage count failed for owner {}: {}", ownerUserId, e.toString());
            return Map.of();
        }
    }

    private static ShopInfo shop(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> parts = new ArrayList<>();
        for (String col : List.of("area", "district", "state")) {
            String v = rs.getString(col);
            if (v != null && !v.isBlank()) parts.add(v.trim());
        }
        String location = parts.isEmpty() ? rs.getString("address") : String.join(", ", parts);
        return new ShopInfo((UUID) rs.getObject("id"), (UUID) rs.getObject("owner_user_id"),
                rs.getString("name"), rs.getString("slug"), location,
                !Boolean.FALSE.equals(rs.getObject("is_active")), rs.getString("mobile"));
    }

    static String last10(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : null;
    }
}
