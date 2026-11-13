package com.repairshop.saas.common.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reusable subscription entitlement engine — the backend's final authority
 * on every plan allowance and every on/off feature.
 *
 * <p>What this class owns is the <em>policy</em>: which plan applies, whether it
 * is in date, what each allowance is, how usage is counted, and how a refusal is
 * worded. Adding a new metered feature means adding a constant to
 * {@link SubscriptionFeature}, a usage query here, and one call to
 * {@link #requireCapacity} at the create site — not a new copy of this logic.
 *
 * <p>Usage counting lives here rather than in each caller because <em>what
 * counts</em> is policy too: only active employees consume a seat, only shops
 * that are not soft-deleted consume the shop allowance. Several services
 * enforce the same limits, and a count written twice is a count that can be
 * written differently twice.
 *
 * <h2>Fail-open cases</h2>
 * Three situations allow the action through rather than blocking it:
 * <ul>
 *   <li><b>No subscription row.</b> Owners created before the subscription
 *       feature shipped have no row. Blocking them would break working shops
 *       for a data-migration reason, so they are allowed and logged. Run the
 *       migration-92 backfill to bring them under a plan.</li>
 *   <li><b>Unresolvable plan code.</b> A typo in one row should not lock an
 *       owner out of their own staff list.</li>
 *   <li><b>{@code ggfix.subscription.enforcement.enabled=false}.</b> A kill
 *       switch, so a mis-scoped limit can be disabled from config rather than
 *       needing a rebuild and redeploy of every affected service.</li>
 * </ul>
 */
@Service
public class SubscriptionLimitService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLimitService.class);

    private final JdbcTemplate jdbc;
    private final boolean enforcementEnabled;

    public SubscriptionLimitService(
            JdbcTemplate jdbc,
            @Value("${ggfix.subscription.enforcement.enabled:true}") boolean enforcementEnabled) {
        this.jdbc = jdbc;
        this.enforcementEnabled = enforcementEnabled;
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    // ── Resolution ──────────────────────────────────────────────────────────

    /**
     * The owner behind a shop. Subscriptions are keyed by owner, but every
     * request the apps make carries a shopId, so nearly every check starts here.
     */
    public UUID ownerOfShop(UUID shopId) {
        if (shopId == null) return null;
        try {
            List<UUID> rows = jdbc.query(
                    "SELECT owner_user_id FROM shops WHERE id = ?",
                    (rs, i) -> (UUID) rs.getObject("owner_user_id"),
                    shopId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("Could not resolve owner for shop {}: {}", shopId, e.toString());
            return null;
        }
    }

    /**
     * Load and interpret the owner's subscription. Ordered newest-first so an
     * account that somehow accumulated two rows is judged on the current one
     * rather than on whichever the database happened to return.
     */
    public OwnerSubscription subscriptionOf(UUID ownerUserId) {
        if (ownerUserId == null) return OwnerSubscription.absent(null);
        try {
            List<OwnerSubscription> rows = jdbc.query(
                    """
                    SELECT subscription_type, plan_code, status, shop_count,
                           active_date, started_at, trial_start_date, subscription_start_date,
                           inactive_date, trial_end_date, subscription_end_date,
                           shop_limit, employee_limit, sell_limit, pickup_service_enabled
                      FROM subscriptions
                     WHERE owner_user_id = ?
                     ORDER BY created_at DESC NULLS LAST
                     LIMIT 1
                    """,
                    (rs, i) -> fromRow(rs, ownerUserId),
                    ownerUserId);
            return rows.isEmpty() ? OwnerSubscription.absent(ownerUserId) : resolveStoredPlan(rows.get(0));
        } catch (Exception e) {
            // Same reasoning as the client-side gating: a subscription lookup
            // failure must never be the thing that stops a shop working.
            log.warn("Subscription lookup failed for owner {} — allowing through: {}", ownerUserId, e.toString());
            return OwnerSubscription.absent(ownerUserId);
        }
    }

    /**
     * Interpret one subscriptions row. Enum plans (FREE_TRIAL / BASIC) resolve
     * exactly as before v2. Any other non-blank code becomes a candidate
     * {@link StoredPlan} built from the row's own limit columns; it is kept only
     * if {@link #resolveStoredPlan} finds the code in the plan master, so a
     * typo'd code still fails open as it always has.
     */
    OwnerSubscription fromRow(ResultSet rs, UUID ownerUserId) throws SQLException {
        // subscription_type is the migration-66 column and the one
        // the service writes; plan_code is the older base column.
        SubscriptionPlan plan = SubscriptionPlan.from(rs.getString("subscription_type"));
        if (plan == null) plan = SubscriptionPlan.from(rs.getString("plan_code"));

        String status = rs.getString("status");
        Integer shopCount = (Integer) rs.getObject("shop_count");

        Instant startsAt = firstNonNull(
                toInstant(rs.getTimestamp("active_date")),
                toInstant(rs.getTimestamp("subscription_start_date")),
                toInstant(rs.getTimestamp("trial_start_date")),
                toInstant(rs.getTimestamp("started_at")));
        Instant endsAt = firstNonNull(
                toInstant(rs.getTimestamp("inactive_date")),
                toInstant(rs.getTimestamp("subscription_end_date")),
                toInstant(rs.getTimestamp("trial_end_date")));

        boolean expired =
                "EXPIRED".equalsIgnoreCase(status)
                        || "CANCELLED".equalsIgnoreCase(status)
                        || (endsAt != null && endsAt.isBefore(Instant.now()));

        StoredPlan stored = null;
        if (plan == null) {
            String code = firstNonBlank(rs.getString("plan_code"), rs.getString("subscription_type"));
            if (code != null) {
                stored = new StoredPlan(
                        code.trim().toUpperCase(),
                        StoredPlan.prettify(code),
                        (Integer) rs.getObject("shop_limit"),
                        (Integer) rs.getObject("employee_limit"),
                        (Integer) rs.getObject("sell_limit"),
                        Boolean.TRUE.equals(rs.getObject("pickup_service_enabled")),
                        true);
            }
        }

        return new OwnerSubscription(
                ownerUserId, plan, status, startsAt, endsAt, expired, shopCount, true, stored);
    }

    /**
     * Confirm a candidate v2 plan against {@code subscription_plans} and take
     * its display name and booking flag from there. Unknown code, or a database
     * without the plan master yet, drops the candidate — the row is then
     * "unrecognised" and fails open exactly as it did before v2.
     */
    OwnerSubscription resolveStoredPlan(OwnerSubscription sub) {
        StoredPlan candidate = sub.storedPlan();
        if (candidate == null) return sub;
        StoredPlan resolved = null;
        try {
            List<StoredPlan> master = jdbc.query(
                    "SELECT name, service_booking_enabled FROM subscription_plans WHERE UPPER(code) = ?",
                    (rs, i) -> new StoredPlan(
                            candidate.code(),
                            firstNonBlank(rs.getString("name"), candidate.displayName()),
                            candidate.shopLimit(),
                            candidate.employeeLimitPerShop(),
                            candidate.sellOrderLimit(),
                            candidate.pickupEnabled(),
                            !Boolean.FALSE.equals(rs.getObject("service_booking_enabled"))),
                    candidate.code());
            resolved = master.isEmpty() ? null : master.get(0);
        } catch (Exception e) {
            log.warn("Plan master lookup failed for {}: {}", candidate.code(), e.toString());
        }
        return new OwnerSubscription(sub.ownerUserId(), sub.plan(), sub.status(), sub.startsAt(), sub.endsAt(),
                sub.expired(), sub.purchasedShopCount(), sub.present(), resolved);
    }

    /** Convenience: the subscription that governs a given shop. */
    public OwnerSubscription subscriptionOfShop(UUID shopId) {
        return subscriptionOf(ownerOfShop(shopId));
    }

    // ── Usage ───────────────────────────────────────────────────────────────

    /**
     * Active employees in ONE shop — the number the "up to N employees per
     * shop" allowance is measured against.
     *
     * <p>Only {@code is_available = true} rows count, so deactivating someone
     * genuinely frees their seat and an employee who has left does not occupy
     * the allowance forever.
     *
     * <p>Scoped by shop_id, never account-wide: an owner on the trial with two
     * shops gets three employees in each, not three between them.
     */
    public long countActiveEmployees(UUID shopId) {
        if (shopId == null) return 0L;
        return count("SELECT COUNT(*) FROM technicians WHERE shop_id = ? AND is_available = true AND is_owner = false", shopId);
    }

    /**
     * Shops an owner is currently running — measured against the shop
     * allowance, which is per OWNER, not per shop.
     *
     * <p>{@code is_active} is respected for the same reason employees are: a
     * closed location should not hold a slot the owner is paying for.
     */
    public long countActiveShops(UUID ownerUserId) {
        if (ownerUserId == null) return 0L;
        return count(
                "SELECT COUNT(*) FROM shops WHERE owner_user_id = ? AND COALESCE(is_active, true) = true",
                ownerUserId);
    }

    /**
     * Sell orders this shop has won — the customer chose this shop's quotation,
     * which is the point the order becomes the shop's business.
     *
     * <p>Counted on {@code sell_orders.shop_id} rather than on quotations
     * submitted: a quotation the customer never accepted cost the shop nothing
     * and should not consume the allowance.
     */
    public long countSellOrders(UUID shopId) {
        if (shopId == null) return 0L;
        return count("SELECT COUNT(*) FROM sell_orders WHERE shop_id = ?", shopId);
    }

    /** Usage for any metered feature, counted over that feature's scope. */
    public long usageOf(SubscriptionFeature feature, UUID ownerUserId, UUID shopId) {
        return switch (feature) {
            case EMPLOYEES -> countActiveEmployees(shopId);
            case SHOPS -> countActiveShops(ownerUserId);
            case SELL_ORDERS -> countSellOrders(shopId);
            case BUY_PRODUCTS -> 0L;   // unlimited on every current plan
        };
    }

    private long count(String sql, Object arg) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, arg);
            return n != null ? n : 0L;
        } catch (Exception e) {
            // A failed count must not become a refusal: returning 0 keeps the
            // action allowed, matching every other fail-open path here.
            log.warn("Usage count failed ({}): {}", sql, e.toString());
            return 0L;
        }
    }

    // ── The check ───────────────────────────────────────────────────────────

    /**
     * Evaluate one feature against a usage count the caller has measured within
     * the feature's {@link SubscriptionFeature.LimitScope scope}.
     *
     * <p>Note {@code currentUsage >= limit} blocks, not {@code >}: usage is the
     * count <em>before</em> the new unit, so a shop already at 3 of 3 is full.
     * The {@code >=} also means an account that is somehow over its allowance
     * (a plan downgrade, or employees added before enforcement existed — the
     * 4-of-3 shop this system was written for) is held where it is rather than
     * being allowed to grow further.
     */
    public LimitCheck check(UUID ownerUserId, SubscriptionFeature feature, long currentUsage) {
        return evaluate(subscriptionOf(ownerUserId), feature, currentUsage);
    }

    /** Shop-scoped entry point: resolves the owner, then checks. */
    public LimitCheck checkForShop(UUID shopId, SubscriptionFeature feature, long currentUsage) {
        return evaluate(subscriptionOfShop(shopId), feature, currentUsage);
    }

    /** Measures usage itself, then checks. The form most call sites want. */
    public LimitCheck checkUsage(UUID ownerUserId, UUID shopId, SubscriptionFeature feature) {
        OwnerSubscription sub = ownerUserId != null
                ? subscriptionOf(ownerUserId)
                : subscriptionOfShop(shopId);
        UUID owner = ownerUserId != null ? ownerUserId : sub.ownerUserId();
        return evaluate(sub, feature, usageOf(feature, owner, shopId));
    }

    /** Evaluate against an already-loaded snapshot (avoids a second query when
     *  a caller checks several features at once). */
    public LimitCheck evaluate(OwnerSubscription sub, SubscriptionFeature feature, long currentUsage) {
        if (!enforcementEnabled) {
            return LimitCheck.unlimited(currentUsage, sub, feature, LimitCheck.REASON_ENFORCEMENT_DISABLED);
        }
        if (!sub.present() || !sub.resolved()) {
            if (sub.present()) {
                log.warn("Owner {} has a subscription row with an unrecognised plan ({}) — allowing through",
                        sub.ownerUserId(), sub.status());
            }
            return LimitCheck.unlimited(currentUsage, sub, feature, LimitCheck.REASON_NO_SUBSCRIPTION);
        }

        // Same values as plan.getCode()/getDisplayName() for the enum plans;
        // the stored v2 plan's code and master name otherwise.
        String planCode = sub.planCode();
        String planName = sub.planName();
        Integer limit = sub.limitFor(feature);

        // A lapsed plan blocks additions regardless of headroom: the allowance
        // belongs to a subscription that is no longer being paid for. Existing
        // records are left alone — this gate is only on adding more.
        if (sub.expired()) {
            return new LimitCheck(
                    false, currentUsage, limit, remainingOf(limit, currentUsage),
                    planCode, planName, feature.name(),
                    limit == null, true, sub.status(),
                    LimitCheck.REASON_SUBSCRIPTION_EXPIRED,
                    "Your " + planName + " has expired. "
                            + "Please upgrade your subscription to continue.");
        }

        if (limit == null) {
            return LimitCheck.unlimited(currentUsage, sub, feature, LimitCheck.REASON_OK);
        }

        boolean allowed = currentUsage < limit;
        return new LimitCheck(
                allowed, currentUsage, limit, remainingOf(limit, currentUsage),
                planCode, planName, feature.name(),
                false, false, sub.status(),
                allowed ? LimitCheck.REASON_OK : LimitCheck.REASON_LIMIT_REACHED,
                allowed ? null : limitMessage(planName, feature, limit));
    }

    /**
     * Check and throw. The one-liner enforcement sites use:
     * {@code limits.requireCapacity(shopId, EMPLOYEES, activeCount)}.
     *
     * @return the passing check, so callers can log or return the headroom
     * @throws SubscriptionLimitExceededException when the action is not allowed
     */
    public LimitCheck requireCapacity(UUID shopId, SubscriptionFeature feature, long currentUsage) {
        return require(checkForShop(shopId, feature, currentUsage));
    }

    /** Owner-scoped variant, for allowances counted across the account (SHOPS). */
    public LimitCheck requireOwnerCapacity(UUID ownerUserId, SubscriptionFeature feature, long currentUsage) {
        return require(check(ownerUserId, feature, currentUsage));
    }

    /** Measures usage itself, then requires capacity. */
    public LimitCheck requireUsageCapacity(UUID ownerUserId, UUID shopId, SubscriptionFeature feature) {
        return require(checkUsage(ownerUserId, shopId, feature));
    }

    private LimitCheck require(LimitCheck check) {
        if (!check.allowed()) throw new SubscriptionLimitExceededException(check);
        return check;
    }

    // ── Capabilities ────────────────────────────────────────────────────────

    /** Whether the shop's plan grants an on/off entitlement. */
    public boolean hasCapability(UUID shopId, SubscriptionCapability capability) {
        return hasCapability(subscriptionOfShop(shopId), capability);
    }

    public boolean hasCapability(OwnerSubscription sub, SubscriptionCapability capability) {
        // Fail open on the same three cases the metered path does, so a legacy
        // account or a disabled kill switch does not lose features either.
        if (!enforcementEnabled || !sub.present() || !sub.resolved()) return true;
        return sub.grants(capability);
    }

    /** Capability gate that throws, for use at a controller entry point. */
    public void requireCapability(UUID shopId, SubscriptionCapability capability) {
        OwnerSubscription sub = subscriptionOfShop(shopId);
        if (hasCapability(sub, capability)) return;

        boolean expired = sub.expired();
        throw new SubscriptionLimitExceededException(new LimitCheck(
                false, 0, null, null,
                sub.planCode(), sub.planName(), capability.name(),
                false, expired, sub.status(),
                expired ? LimitCheck.REASON_SUBSCRIPTION_EXPIRED : LimitCheck.REASON_FEATURE_UNAVAILABLE,
                expired
                        ? "Your " + sub.planName() + " has expired. Please upgrade your subscription to continue."
                        : capability.getLabel() + " is not included in your " + sub.planName()
                          + " plan. Please upgrade your subscription to use it."));
    }

    // ── Entitlements ────────────────────────────────────────────────────────

    /**
     * The whole entitlement picture for an account, with live usage for the
     * given shop. This is what the Subscription screen and every client-side
     * {@code canAddX()} read — assembled from the same {@link #evaluate} calls
     * the APIs enforce with, so the two cannot disagree.
     *
     * @param shopId the shop whose per-shop usage to report; null yields
     *               correct ceilings with zero usage for shop-scoped features
     */
    public Entitlements entitlements(UUID ownerUserId, UUID shopId) {
        OwnerSubscription sub = subscriptionOf(ownerUserId);

        Map<String, Entitlements.LimitUsage> limits = new LinkedHashMap<>();
        for (SubscriptionFeature feature : SubscriptionFeature.values()) {
            long used = usageOf(feature, ownerUserId, shopId);
            LimitCheck check = evaluate(sub, feature, used);
            limits.put(feature.name(), new Entitlements.LimitUsage(
                    check.limit(), used, check.remaining(), check.unlimited(),
                    feature.getScope().name(), check.allowed()));
        }

        Map<String, Boolean> features = Entitlements.emptyFeatures();
        for (SubscriptionCapability capability : SubscriptionCapability.values()) {
            features.put(capability.getKey(), hasCapability(sub, capability));
        }

        return new Entitlements(
                sub.planCode(), sub.planName(), sub.status(),
                sub.startsAt(), sub.endsAt(), sub.expired(),
                daysRemaining(sub.endsAt()), sub.present(), sub.purchasedShopCount(),
                enforcementEnabled, limits, features);
    }

    /** Entitlements for whichever owner runs this shop. */
    public Entitlements entitlementsForShop(UUID shopId) {
        return entitlements(ownerOfShop(shopId), shopId);
    }

    // ── Wording ─────────────────────────────────────────────────────────────

    /**
     * One place to phrase a refusal, so the sentence the API returns and the
     * sentence the app shows are the same sentence.
     */
    private String limitMessage(String planName, SubscriptionFeature feature, int limit) {
        return switch (feature) {
            case EMPLOYEES -> "You have reached the maximum of " + limit + " employees allowed for the "
                    + planName + " plan. "
                    + "Upgrade your subscription to add more employees.";
            case SHOPS -> "You have reached the maximum of " + limit + " shops allowed for the "
                    + planName + " plan. "
                    + "Upgrade your subscription to add more shops.";
            case SELL_ORDERS -> "You have reached the maximum of " + limit + " sell orders allowed for the "
                    + planName + " plan. "
                    + "Upgrade your subscription to take more sell orders.";
            default -> "Your " + planName + " plan allows up to " + limit + " "
                    + feature.getNoun() + (feature.isPerShop() ? " per shop" : "") + ". "
                    + "Upgrade your subscription to add more.";
        };
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private static Integer remainingOf(Integer limit, long usage) {
        if (limit == null) return null;
        return (int) Math.max(0, limit - usage);
    }

    private static long daysRemaining(Instant endsAt) {
        if (endsAt == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(Instant.now(), endsAt));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
