package com.repairshop.saas.auth.entity;

/**
 * Canonical users.role values and the role predicates the account-management
 * endpoints gate on.
 *
 * Naming note: the platform-administrator role has always been stored as
 * SUPER_ADMIN (see AdminSeeder and the seeded accounts), so "ADMIN" in the
 * account-management spec maps onto SUPER_ADMIN rather than renaming it —
 * a rename would invalidate every seeded admin row, the loginType clients
 * route on, and the admin web's login gate. {@link #isAdmin} accepts the
 * literal "ADMIN" too so a hand-inserted row spelled that way still works.
 */
public final class Roles {

    public static final String SUPER_ADMIN   = "SUPER_ADMIN";
    public static final String ADMIN         = "ADMIN";
    public static final String MARKET_PERSON = "MARKET_PERSON";
    public static final String SHOP_OWNER    = "SHOP_OWNER";
    public static final String TECHNICIAN    = "TECHNICIAN";

    private Roles() {}

    private static String norm(String role) {
        return role == null ? "" : role.trim().toUpperCase();
    }

    /** Platform administrator — the only role allowed to change account status. */
    public static boolean isAdmin(String role) {
        String r = norm(role);
        return SUPER_ADMIN.equals(r) || ADMIN.equals(r);
    }

    public static boolean isMarketPerson(String role) {
        return MARKET_PERSON.equals(norm(role));
    }

    public static boolean isShopOwner(String role) {
        return SHOP_OWNER.equals(norm(role));
    }

    /** Shop employee roles (Staff App). They sign in ONLY with mobile + owner-set MPIN. */
    public static boolean isEmployee(String role) {
        String r = norm(role);
        return TECHNICIAN.equals(r) || "STAFF".equals(r) || "PICKUP_PERSON".equals(r);
    }

    /** Back-office staff— may create shop owners. Only admins may (de)activate them. */
    public static boolean isStaff(String role) {
        return isAdmin(role) || isMarketPerson(role);
    }

    /**
     * The role name to RECORD for a creator or active person.
     *
     * Collapses the stored SUPER_ADMIN onto the spec's "ADMIN" so provenance
     * reads the way the account-management screen describes it, while the
     * users.role column keeps its historical value untouched.
     */
    public static String canonical(String role) {
        if (isAdmin(role)) return ADMIN;
        String r = norm(role);
        return r.isEmpty() ? null : r;
    }
}
