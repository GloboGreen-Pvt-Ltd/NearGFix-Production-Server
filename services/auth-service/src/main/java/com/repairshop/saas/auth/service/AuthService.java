package com.repairshop.saas.auth.service;

import com.repairshop.saas.auth.dto.CreateShopOwnerRequest;
import com.repairshop.saas.auth.dto.LoginRequest;
import com.repairshop.saas.auth.dto.LoginResponse;
import com.repairshop.saas.auth.dto.ShopLoginRequest;
import com.repairshop.saas.auth.dto.RegisterRequest;
import com.repairshop.saas.auth.dto.RegisterResponse;
import com.repairshop.saas.auth.dto.CreateShopRequest;
import com.repairshop.saas.auth.dto.RegisterTechnicianRequest;
import com.repairshop.saas.auth.dto.ShopOwnerResponse;
import com.repairshop.saas.auth.dto.PickupShopView;
import com.repairshop.saas.auth.dto.ShopOwnerView;
import com.repairshop.saas.auth.dto.ShopResponse;
import com.repairshop.saas.auth.dto.UpdateShopOwnerRequest;
import com.repairshop.saas.auth.dto.TechnicianResponse;
import com.repairshop.saas.auth.dto.UserResponse;
import com.repairshop.saas.common.subscription.SubscriptionFeature;
import com.repairshop.saas.auth.entity.KycDocument;
import com.repairshop.saas.auth.entity.Roles;
import com.repairshop.saas.auth.entity.Shop;
import com.repairshop.saas.auth.entity.Subscription;
import com.repairshop.saas.auth.entity.User;
import com.repairshop.saas.auth.exception.BadRequestException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.repository.ShopRepository;
import com.repairshop.saas.auth.repository.SubscriptionRepository;
import com.repairshop.saas.auth.repository.UserRepository;
import com.repairshop.saas.auth.security.JwtService;
import com.repairshop.saas.auth.security.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    /**
     * Shown verbatim to a user whose account an admin has deactivated. Kept as
     * one constant so every login path (email/mobile, shop-mobile, customer)
     * returns the same wording — clients surface this string directly.
     */
    public static final String INACTIVE_ACCOUNT_MESSAGE =
            "Your account is inactive. Please contact the administrator.";

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final com.repairshop.saas.common.subscription.SubscriptionLimitService subscriptionLimits;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final OtpStore otpStore;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;
    // ticket-service owns the `technicians` table but shares this Postgres DB.
    // We JDBC-link a technician's user_id when provisioning its login so the
    // employee app's /technicians/me (keyed by user_id) resolves.
    private final JdbcTemplate jdbc;

    /**
     * Unified login. The identifier in {@code request.email} may be either an
     * email or a mobile number. Resolution order:
     *
     *   1. users table (SUPER_ADMIN / SHOP_OWNER / EMPLOYEE roles)
     *      — looked up by email first, then phone with the usual +91 / 0-prefix
     *      tolerance. Authenticated against users.password_hash / users.otp_code.
     *   2. shops table (SHOP_LOGIN) — only attempted when the users-table lookup
     *      misses. Authenticated against shops.mobile_password_hash /
     *      shops.mobile_otp_code. Issues a single-shop scoped JWT.
     *
     * Default OTP for both flows is 123456 (see migration 54 and User.otp_code default).
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        boolean usingOtp = request.getOtp() != null && !request.getOtp().isBlank();
        boolean usingPwd = request.getPassword() != null && !request.getPassword().isBlank();
        if (!usingOtp && !usingPwd)
            throw new BadRequestException("Either password or otp is required");

        java.util.Optional<User> userOpt;
        if (request.getShopSlug() != null && !request.getShopSlug().isBlank()) {
            Shop shop = shopRepository.findBySlug(request.getShopSlug())
                    .orElseThrow(() -> new UnauthorizedException("Invalid shop or credentials"));
            userOpt = userRepository.findByShop_IdAndEmail(shop.getId(), request.getEmail());
        } else {
            userOpt = findUserByEmailOrPhone(request.getEmail());
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (!Boolean.TRUE.equals(user.getIsActive()))
                throw new UnauthorizedException(INACTIVE_ACCOUNT_MESSAGE);

            if (usingOtp) {
                // Accept either the account's static OTP (e.g. 123456 default) or a
                // freshly-issued OtpStore code (email "sign in with OTP"). Static is
                // checked first so a valid one doesn't consume the single-use code.
                String entered = request.getOtp().trim();
                boolean ok = (user.getOtpCode() != null && user.getOtpCode().equals(entered))
                        || otpStore.verify(request.getEmail().trim(), entered);
                if (!ok)
                    throw new UnauthorizedException("Invalid OTP");
            } else {
                if (user.getPasswordHash() == null
                        || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash()))
                    throw new UnauthorizedException("Invalid credentials");
            }
            return buildLoginResponse(user, null);
        }

        // Fall through to shop-mobile credentials. Skipped when a shopSlug was
        // supplied — that path is explicitly a tenant-scoped users-table lookup.
        if (request.getShopSlug() == null || request.getShopSlug().isBlank()) {
            java.util.Optional<Shop> shopOpt = findShopByMobile(request.getEmail());
            if (shopOpt.isPresent()) {
                ShopLoginRequest shopReq = ShopLoginRequest.builder()
                        .mobile(request.getEmail())
                        .password(request.getPassword())
                        .otp(request.getOtp())
                        .build();
                return shopLogin(shopReq);
            }
        }

        throw new UnauthorizedException("Invalid credentials");
    }

    /**
     * Login by SHOP MOBILE NUMBER (single-shop session). The shop's mobile
     * authenticates against either shops.mobile_password_hash or
     * shops.mobile_otp_code. The issued JWT is locked to this shopId via the
     * loginScope=SHOP claim — switch-shop will reject it. The token's subject
     * is the shop's owner user, so downstream services still see a userId.
     */
    @Transactional
    public LoginResponse shopLogin(ShopLoginRequest request) {
        boolean usingOtp = request.getOtp() != null && !request.getOtp().isBlank();
        boolean usingPwd = request.getPassword() != null && !request.getPassword().isBlank();
        if (!usingOtp && !usingPwd)
            throw new BadRequestException("Either password or otp is required");

        Shop shop = findShopByMobile(request.getMobile())
                .orElseThrow(() -> new UnauthorizedException("Invalid shop credentials"));
        if (!Boolean.TRUE.equals(shop.getIsActive()))
            throw new UnauthorizedException("Shop is disabled");
        if (shop.getOwnerUserId() == null)
            throw new UnauthorizedException("Shop is not linked to an owner — use email login");

        if (usingOtp) {
            if (shop.getMobileOtpCode() == null || !shop.getMobileOtpCode().equals(request.getOtp().trim()))
                throw new UnauthorizedException("Invalid OTP");
        } else {
            if (shop.getMobilePasswordHash() == null
                    || !passwordEncoder.matches(request.getPassword(), shop.getMobilePasswordHash()))
                throw new UnauthorizedException("Invalid shop credentials");
        }

        User owner = userRepository.findById(shop.getOwnerUserId())
                .orElseThrow(() -> new UnauthorizedException("Shop owner not found"));
        if (!Boolean.TRUE.equals(owner.getIsActive()))
            throw new UnauthorizedException(INACTIVE_ACCOUNT_MESSAGE);

        return buildShopScopedLoginResponse(owner, shop);
    }

    /**
     * Switch the active shop for a SHOP_OWNER. Re-issues the JWT with the
     * given shopId in the claim so downstream services scope queries to it.
     * Rejects shopIds the owner doesn't actually own.
     */
    @Transactional
    public LoginResponse switchShop(UUID userId, UUID shopId) {
        return switchShop(userId, shopId, null);
    }

    /** As above; the pre-switch refresh session (if sent) is revoked — the new one replaces it. */
    @Transactional
    public LoginResponse switchShop(UUID userId, UUID shopId, String previousRefreshToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        if (!"SHOP_OWNER".equals(user.getRole()))
            throw new BadRequestException("Only shop owners can switch shops");
        Shop target = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found"));
        if (target.getOwnerUserId() == null || !target.getOwnerUserId().equals(userId))
            throw new UnauthorizedException("Shop not owned by this user");
        refreshTokenService.revoke(previousRefreshToken);
        return buildLoginResponse(user, target.getId());
    }

    /**
     * Exchange a refresh token for a new access + refresh token pair (rotation).
     * Re-checks that the account (and, for shop-mobile sessions, the shop) is
     * still active, so a deactivated user cannot keep refreshing.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse refresh(String refreshToken) {
        RefreshTokenService.Grant g = refreshTokenService.consume(refreshToken);
        User user = userRepository.findById(g.userId())
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        if (!Boolean.TRUE.equals(user.getIsActive()))
            throw new UnauthorizedException(INACTIVE_ACCOUNT_MESSAGE);
        if ("SHOP".equals(g.loginScope())) {
            Shop shop = g.shopId() == null ? null : shopRepository.findById(g.shopId()).orElse(null);
            if (shop == null || !Boolean.TRUE.equals(shop.getIsActive())
                    || shop.getOwnerUserId() == null || !shop.getOwnerUserId().equals(user.getId()))
                throw new UnauthorizedException("Shop is disabled");
            return buildShopScopedLoginResponse(user, shop, g.familyId());
        }
        return buildLoginResponse(user, g.shopId(), g.familyId());
    }

    /** Logout: revoke the refresh token's session family. */
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /**
     * Build a single-shop LoginResponse for shop-mobile logins. Marks
     * loginScope=SHOP and returns only the authenticated shop in the
     * shops array so the client knows there's nothing to switch to.
     */
    private LoginResponse buildShopScopedLoginResponse(User owner, Shop shop) {
        return buildShopScopedLoginResponse(owner, shop, null);
    }

    private LoginResponse buildShopScopedLoginResponse(User owner, Shop shop, UUID refreshFamily) {
        String token = jwtService.generateToken(
                owner.getId(),
                shop.getId(),
                owner.getEmail(),
                List.of(owner.getRole()),
                "SHOP"
        );
        List<LoginResponse.ShopAccess> shopList = List.of(LoginResponse.ShopAccess.builder()
                .id(shop.getId().toString())
                .name(shop.getName())
                .slug(shop.getSlug())
                .isActive(true)
                .build());
        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpiryMs() / 1000)
                .refreshToken(refreshTokenService.issue(owner.getId(), shop.getId(), "SHOP", refreshFamily))
                .refreshExpiresIn(refreshTokenService.getTtlMs() / 1000)
                .userId(owner.getId().toString())
                .shopId(shop.getId().toString())
                .shopName(shop.getName())
                .email(owner.getEmail())
                .name(owner.getName())
                .roles(List.of(owner.getRole()))
                .roleLabel(friendlyEmployeeRoleLabel(owner.getRole()))
                .shops(shopList)
                .loginScope("SHOP")
                .loginType("SHOP_LOGIN")
                .build();
    }

    /**
     * Resolve the active shopId for this user and assemble LoginResponse.
     * If preferredShopId is supplied and the user owns it, use it; otherwise
     * fall back to the first owned shop (or user.shop / null per role).
     */
    private LoginResponse buildLoginResponse(User user, UUID preferredShopId) {
        return buildLoginResponse(user, preferredShopId, null);
    }

    private LoginResponse buildLoginResponse(User user, UUID preferredShopId, UUID refreshFamily) {
        // Determine the active shop for JWT shopId claim.
        List<Shop> ownedShops = "SHOP_OWNER".equals(user.getRole())
                ? shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(user.getId())
                : java.util.Collections.emptyList();

        Shop activeShop = null;
        if (preferredShopId != null) {
            activeShop = ownedShops.stream().filter(s -> s.getId().equals(preferredShopId)).findFirst().orElse(null);
        }
        if (activeShop == null && user.getShop() != null) {
            activeShop = user.getShop();
        }
        if (activeShop == null && !ownedShops.isEmpty()) {
            activeShop = ownedShops.get(0);
        }

        UUID shopId = activeShop != null ? activeShop.getId() : null;
        String shopName = activeShop != null ? activeShop.getName() : null;
        String token = jwtService.generateToken(
                user.getId(),
                shopId,
                user.getEmail(),
                List.of(user.getRole()),
                "OWNER"
        );

        final UUID activeShopId = activeShop != null ? activeShop.getId() : null;
        List<LoginResponse.ShopAccess> shopList = ownedShops.stream()
                .map(s -> LoginResponse.ShopAccess.builder()
                        .id(s.getId().toString())
                        .name(s.getName())
                        .slug(s.getSlug())
                        .isActive(activeShopId != null && s.getId().equals(activeShopId))
                        .build())
                .toList();

        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpiryMs() / 1000)
                .refreshToken(refreshTokenService.issue(user.getId(), shopId, "OWNER", refreshFamily))
                .refreshExpiresIn(refreshTokenService.getTtlMs() / 1000)
                .userId(user.getId().toString())
                .shopId(shopId != null ? shopId.toString() : null)
                .shopName(shopName)
                .email(user.getEmail())
                .name(user.getName())
                .roles(List.of(user.getRole()))
                .roleLabel(friendlyEmployeeRoleLabel(user.getRole()))
                .shops(shopList)
                .loginScope("OWNER")
                .loginType(loginTypeForRole(user.getRole()))
                .build();
    }

    /**
     * Map a stored users.role to the wire-level loginType the clients route on.
     * SHOP_OWNER, SUPER_ADMIN and MARKET_PERSON are 1:1; every other employee
     * role (TECHNICIAN, STAFF, PICKUP_PERSON) collapses to EMPLOYEE so the
     * mobile app can route them through the technician/employee UI uniformly.
     *
     * MARKET_PERSON gets its own loginType rather than folding into SUPER_ADMIN:
     * both sign in to the admin web, but only SUPER_ADMIN may change account
     * status, so the client has to be able to tell them apart.
     */
    private static String loginTypeForRole(String role) {
        if (role == null) return "EMPLOYEE";
        String r = role.trim().toUpperCase();
        if (Roles.isAdmin(r))         return "SUPER_ADMIN";
        if (Roles.isMarketPerson(r))  return "MARKET_PERSON";
        if (Roles.isShopOwner(r))     return "SHOP_OWNER";
        return "EMPLOYEE";
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (shopRepository.existsBySlug(request.getShopSlug()))
            throw new BadRequestException("Shop slug already exists: " + request.getShopSlug());

        Shop shop = Shop.builder()
                .id(UUID.randomUUID())
                .name(request.getShopName())
                .slug(request.getShopSlug())
                .email(request.getEmail())
                .isActive(true)
                .build();
        shop = shopRepository.save(shop);

        if (userRepository.existsByShop_IdAndEmail(shop.getId(), request.getEmail()))
            throw new BadRequestException("Email already registered for this shop");

        User user = User.builder()
                .shop(shop)
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .name(request.getName() != null ? request.getName() : request.getEmail())
                .role("SHOP_OWNER")
                .isActive(true)
                .build();
        user = userRepository.save(user);

        // Auto-start a 15-day FREE_TRIAL. Best-effort: never break registration.
        createFreeTrial(user.getId(), shop.getId());

        return RegisterResponse.builder()
                .userId(user.getId().toString())
                .shopId(shop.getId().toString())
                .shopSlug(shop.getSlug())
                .email(user.getEmail())
                .message("Registration successful")
                .build();
    }

    /**
     * Create the 15-day FREE_TRIAL subscription row for a newly registered
     * owner. Wrapped so a failure here (e.g. migration 66 not yet applied) is
     * logged and swallowed rather than rolling back the registration itself.
     * Idempotent: skips if the owner already has a subscription.
     */
    private void createFreeTrial(UUID ownerUserId, UUID shopId) {
        try {
            if (ownerUserId == null) return;
            if (subscriptionRepository.findByOwnerUserId(ownerUserId).isPresent()) return;
            Instant now = Instant.now();
            Instant end = now.plus(15, ChronoUnit.DAYS);
            Subscription sub = Subscription.builder()
                    .ownerUserId(ownerUserId)
                    .shopId(shopId)
                    .planCode("FREE_TRIAL")
                    .status("FREE_TRIAL")
                    .subscriptionType("FREE_TRIAL")
                    .trialStartDate(now)
                    .trialEndDate(end)
                    .activeDate(now)
                    .inactiveDate(end)
                    .shopLimit(2)
                    .employeeLimit(3)
                    .sellLimit(5)
                    .pickupServiceEnabled(true)
                    .buyProductUnlimited(true)
                    .sellProductUnlimited(false)
                    .shopCount(1)
                    .priceAmount(BigDecimal.ZERO)
                    .startedAt(now)
                    .currentPeriodEnd(end)
                    .build();
            subscriptionRepository.save(sub);
        } catch (Exception e) {
            log.warn("createFreeTrial failed for owner={} shop={}: {}", ownerUserId, shopId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<ShopResponse> listShops() {
        return shopRepository.findAll().stream()
                .map(s -> ShopResponse.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .slug(s.getSlug())
                        .isActive(s.getIsActive())
                        .status(Boolean.TRUE.equals(s.getIsActive()) ? "ACTIVE" : "SUSPENDED")
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsersByShop(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found: " + shopId));
        String shopName = shop.getName();
        return userRepository.findByShop_IdOrderByEmailAsc(shopId).stream()
                .map(u -> UserResponse.builder()
                        .id(u.getId())
                        .email(u.getEmail())
                        .name(u.getName())
                        .role(u.getRole())
                        .isActive(u.getIsActive())
                        .shopId(shopId)
                        .shopName(shopName)
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TechnicianResponse> listTechnicians(UUID shopId) {
        return userRepository.findByShop_IdAndRole(shopId, "TECHNICIAN").stream()
                .map(u -> TechnicianResponse.builder()
                        .id(u.getId())
                        .name(u.getName())
                        .email(u.getEmail())
                        .roleLabel("TECHNICIAN")
                        .build())
                .toList();
    }

    @Transactional
    public RegisterResponse registerTechnician(UUID shopId, RegisterTechnicianRequest request) {
        // Adding staff is a two-call flow: the shop app provisions the login
        // here, then creates the technician row in ticket-service. Both ends
        // check the seat allowance — if only the second one did, hitting the
        // limit would leave a login behind with no employee attached to it.
        subscriptionLimits.requireCapacity(shopId, SubscriptionFeature.EMPLOYEES,
                subscriptionLimits.countActiveEmployees(shopId));

        Shop shop = shopRepository.findById(shopId).orElseGet(() -> {
            // Shop may exist in ticket-service but not in auth (e.g. after auth DB reset). Create stub so technician can be registered.
            String slug = "shop-" + shopId.toString().replace("-", "");
            if (shopRepository.existsBySlug(slug))
                slug = "shop-" + shopId.toString();
            return shopRepository.save(Shop.builder()
                    .id(shopId)
                    .name("Shop")
                    .slug(slug)
                    .isActive(true)
                    .build());
        });
        String phone = trimToNull(request.getPhone());
        String email = trimToNull(request.getEmail());

        // A login needs an identifier. Staff are usually keyed by mobile, so when
        // no email is supplied we synthesise a stable placeholder from the phone
        // (users.email is NOT NULL + unique per shop). Login still resolves by the
        // real phone via findUserByEmailOrPhone, so the placeholder is never typed.
        if (email == null) {
            if (phone == null)
                throw new BadRequestException("Provide an email or a mobile number to create the login");
            email = phone.replaceAll("[^0-9]", "") + "@staff.local";
        }

        // Idempotent: if a login already exists for this shop+identity, return it
        // instead of 400. Lets a re-add — or an add whose technician step didn't
        // finish the first time — succeed and hand the caller the existing userId.
        java.util.Optional<User> existing = userRepository.findByShop_IdAndEmail(shop.getId(), email);
        if (existing.isPresent()) {
            User u = existing.get();
            return RegisterResponse.builder()
                    .userId(u.getId().toString())
                    .shopId(shop.getId().toString())
                    .shopSlug(shop.getSlug())
                    .email(u.getEmail())
                    .message("Employee login already existed")
                    .build();
        }

        // Password is optional (OTP-only login). OTP defaults to 123456 — the same
        // staff default used for shop-mobile login (see shops.mobile_otp_code and
        // createShopOwner) — so a mobile-only employee can sign in immediately.
        String rawPassword = trimToNull(request.getPassword());
        String otp = trimToNull(request.getOtp());
        if (otp == null) otp = "123456";

        User user = User.builder()
                .shop(shop)
                .email(email)
                .phone(phone)
                .passwordHash(rawPassword != null ? passwordEncoder.encode(rawPassword) : null)
                .otpCode(otp)
                .name(request.getName() != null ? request.getName() : email)
                .role(canonicalEmployeeRole(request.getRoleLabel()))
                .isActive(true)
                .build();
        user = userRepository.save(user);

        return RegisterResponse.builder()
                .userId(user.getId().toString())
                .shopId(shop.getId().toString())
                .shopSlug(shop.getSlug())
                .email(user.getEmail())
                .message("Employee login created")
                .build();
    }

    /**
     * Best-effort: link an existing (not-yet-linked) technician row for this
     * shop+phone to the given login's user_id so the employee app's
     * /technicians/me (which resolves the technician by user_id) works.
     *
     * Called from the controller AFTER registerTechnician commits — so it runs
     * in its OWN transaction on an already-persisted user (no FK ordering issue),
     * and a link failure can never roll back the created login. In the normal
     * owner-app add flow the technician row doesn't exist yet, so this updates 0
     * rows (harmless) and the app links it itself with the returned userId.
     */
    @Transactional
    public void linkTechnicianByPhone(UUID shopId, String userId, String phone) {
        if (shopId == null || userId == null || phone == null || phone.trim().isEmpty()) return;
        try {
            int n = jdbc.update(
                    "UPDATE technicians SET user_id = CAST(? AS uuid) "
                            + "WHERE shop_id = CAST(? AS uuid) AND phone = ? AND user_id IS NULL",
                    userId, shopId.toString(), phone.trim());
            log.info("linkTechnicianByPhone: linked {} technician row(s) shop={} phone={}", n, shopId, phone);
        } catch (Exception e) {
            log.warn("linkTechnicianByPhone failed shop={} phone={}: {}", shopId, phone, e.getMessage());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Transactional
    public ShopResponse createShop(CreateShopRequest request) {
        String slug = request.getSlug().trim().toLowerCase().replaceAll("\\s+", "-");
        if (shopRepository.existsBySlug(slug))
            throw new BadRequestException("Shop slug already exists: " + slug);
        Shop shop = Shop.builder()
                .id(UUID.randomUUID())
                .name(request.getName().trim())
                .slug(slug)
                .address(request.getAddress() != null ? request.getAddress().trim() : null)
                .isActive(true)
                .build();
        shop = shopRepository.save(shop);
        return ShopResponse.builder()
                .id(shop.getId())
                .name(shop.getName())
                .slug(shop.getSlug())
                .isActive(true)
                .status("ACTIVE")
                .build();
    }

    /**
     * Create a SHOP_OWNER plus its shops.
     *
     * The account is created INACTIVE regardless of who makes it — only an
     * admin can activate it (see {@link #setShopOwnerActive}). Creator identity
     * is taken from the authenticated caller, never from the request body, so
     * a client cannot forge provenance.
     *
     * When a MARKET_PERSON creates the owner they also become its initial
     * active person; an admin can reassign that later without disturbing the
     * creator fields. An admin-created owner starts with no active person.
     *
     * @param creator the authenticated staff account performing the creation.
     */
    @Transactional
    public ShopOwnerResponse createShopOwner(CreateShopOwnerRequest req, User creator) {
        String email = req.getEmail().trim();
        // Shop owners are platform-level users; their owned shops are linked via
        // shops.owner_user_id, not via users.shop_id. No parent shop needed.
        if (userRepository.findByEmail(email).isPresent())
            throw new BadRequestException("A user with this email already exists");

        User owner = User.builder()
                .shop(null)
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .otpCode(req.getOtpCode() != null && !req.getOtpCode().isBlank() ? req.getOtpCode() : "123456")
                .name(req.getName() != null ? req.getName().trim() : email)
                .phone(req.getPhone())
                .secondaryMobile(req.getSecondaryMobile())
                .avatarUrl(req.getAvatarUrl())
                .idProofUrl(req.getIdProofUrl())
                .kycDocument(newKyc(req.getAadharFrontUrl(), req.getAadharBackUrl(), req.getPanUrl()))
                .personalAddress(req.getPersonalAddress())
                .addrState(req.getAddrState())
                .addrDistrict(req.getAddrDistrict())
                .addrTaluk(req.getAddrTaluk())
                .addrArea(req.getAddrArea())
                .addrStreet(req.getAddrStreet())
                .addrPincode(req.getAddrPincode())
                .role(Roles.SHOP_OWNER)
                // Created inactive by design: activation is an ADMIN-only act,
                // so neither an admin nor a market person can create an account
                // that is usable before an admin signs off on it.
                .isActive(false)
                .createdBy(creator == null ? null : Roles.canonical(creator.getRole()))
                .createdPersonId(creator == null ? null : creator.getId())
                .createdPersonName(creator == null ? null : displayName(creator))
                .emailVerified(false)
                .build();
        // A market person who creates an owner is that owner's active person
        // from the start. An admin-created owner waits for an explicit
        // assignment, so activeRole stays null rather than reading "ADMIN".
        if (creator != null && Roles.isMarketPerson(creator.getRole())) {
            owner.setActiveRole(Roles.MARKET_PERSON);
            owner.setActivePersonId(creator.getId());
            owner.setActivePersonName(displayName(creator));
        }
        owner = userRepository.save(owner);

        List<ShopOwnerResponse.ShopSummary> summaries = new java.util.ArrayList<>();
        UUID primaryShopId = null;
        for (CreateShopOwnerRequest.ShopLocationDto loc : req.getLocations()) {
            String slug = loc.getSlug();
            if (slug == null || slug.isBlank())
                slug = slugify(loc.getName()) + "-" + UUID.randomUUID().toString().substring(0, 6);
            if (shopRepository.existsBySlug(slug))
                slug = slug + "-" + UUID.randomUUID().toString().substring(0, 4);

            Shop shop = Shop.builder()
                    .id(UUID.randomUUID())
                    .name(loc.getName().trim())
                    .slug(slug)
                    .mobile(loc.getMobile())
                    .address(loc.getAddress())
                    .district(loc.getDistrict())
                    .state(loc.getState())
                    .pincode(loc.getPincode())
                    .taluk(loc.getTaluk())
                    .area(loc.getArea())
                    .street(loc.getStreet())
                    .gstNumber(loc.getGstNumber())
                    .latitude(loc.getLatitude())
                    .longitude(loc.getLongitude())
                    .frontImageUrl(loc.getFrontImageUrl())
                    .bannerImageUrl(loc.getBannerImageUrl())
                    .pickupFromTime(loc.getPickupFromTime())
                    .pickupToTime(loc.getPickupToTime())
                    .pickupDistanceKm(loc.getPickupDistanceKm())
                    .pickupEnabled(loc.getPickupEnabled() != null ? loc.getPickupEnabled() : false)
                    .gstCertificateUrl(loc.getGstCertificateUrl())
                    .udyamCertificateUrl(loc.getUdyamCertificateUrl())
                    .workingDays(loc.getWorkingDays())
                    .openingTime(loc.getOpeningTime())
                    .closingTime(loc.getClosingTime())
                    .ownerUserId(owner.getId())
                    .mobileOtpCode("123456")
                    .isActive(true)
                    .build();
            shop = shopRepository.save(shop);
            if (primaryShopId == null) primaryShopId = shop.getId();
            summaries.add(ShopOwnerResponse.ShopSummary.builder()
                    .id(shop.getId()).name(shop.getName()).slug(shop.getSlug()).build());
        }

        // Auto-start a 15-day FREE_TRIAL for the owner (first shop = primary),
        // if none exists yet. Best-effort — never break owner creation.
        createFreeTrial(owner.getId(), primaryShopId);

        return ShopOwnerResponse.builder()
                .ownerId(owner.getId())
                .email(owner.getEmail())
                .name(owner.getName())
                .shops(summaries)
                .build();
    }

    /** 6-field progress score: identity, mobile, address, GST number, front image, certificate (any). */
    private static int locationProgressPercent(Shop s) {
        if (s == null) return 0;
        int total = 6;
        int done = 0;
        if (notBlank(s.getName()))            done++;
        if (notBlank(s.getMobile()))          done++;
        if (notBlank(s.getAddress()) || notBlank(s.getStreet()) || notBlank(s.getArea())) done++;
        if (notBlank(s.getGstNumber()))       done++;
        if (notBlank(s.getFrontImageUrl()))   done++;
        if (notBlank(s.getGstCertificateUrl()) || notBlank(s.getUdyamCertificateUrl())) done++;
        return (int) Math.round(done * 100.0 / total);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * Shop/owner login identifier resolution: try email first, then phone with
     * common formatting variants (raw, digits-only last-10, with/without +91
     * or leading 0). Lets owners log in with either their email or any
     * reasonable form of their phone number.
     */
    /**
     * Shop-mobile lookup for the shop-login flow. Tries the raw value first,
     * then the last-10 digits with the same +91 / 0-prefix variants as the
     * owner phone resolver so owners can register the number in any common
     * shape. Returns the first matching ACTIVE shop (mobile is not unique
     * at the DB level — see migration 54).
     */
    private java.util.Optional<Shop> findShopByMobile(String identifier) {
        if (identifier == null) return java.util.Optional.empty();
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) return java.util.Optional.empty();
        final java.util.List<Shop> firstHits = shopRepository.findByMobile(trimmed);
        if (!firstHits.isEmpty()) return firstHits.stream().filter(s -> Boolean.TRUE.equals(s.getIsActive())).findFirst().or(() -> java.util.Optional.of(firstHits.get(0)));
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() >= 10) {
            String last10 = digits.substring(digits.length() - 10);
            for (String variant : java.util.List.of(last10, "0" + last10, "+91 " + last10, "+91" + last10, "91" + last10)) {
                final java.util.List<Shop> variantHits = shopRepository.findByMobile(variant);
                if (!variantHits.isEmpty()) return variantHits.stream().filter(s -> Boolean.TRUE.equals(s.getIsActive())).findFirst().or(() -> java.util.Optional.of(variantHits.get(0)));
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Issue (or surface, in dev) an OTP for shop-mobile login. The current
     * implementation simply returns the existing shops.mobile_otp_code so
     * developers can complete the flow without an SMS gateway. A real OTP
     * service would rotate the code here and trigger an SMS send.
     */
    @Transactional
    public String issueShopMobileOtp(String mobile) {
        Shop shop = findShopByMobile(mobile)
                .orElseThrow(() -> new BadRequestException("No shop registered for that mobile number"));
        if (shop.getMobileOtpCode() == null || shop.getMobileOtpCode().isBlank()) {
            shop.setMobileOtpCode("123456");
            shopRepository.save(shop);
        }
        return shop.getMobileOtpCode();
    }

    /**
     * Set/replace the bcrypt password for a shop's mobile login. Owner-only
     * action enforced at the controller via ownerId path param. Pass an empty
     * string to clear (disables password login; OTP login still works).
     */
    @Transactional
    public ShopOwnerView setShopMobilePassword(UUID ownerId, UUID shopId, String newPassword) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found: " + shopId));
        if (shop.getOwnerUserId() == null || !shop.getOwnerUserId().equals(ownerId))
            throw new BadRequestException("Shop does not belong to this owner");
        if (newPassword == null || newPassword.isBlank()) {
            shop.setMobilePasswordHash(null);
        } else {
            shop.setMobilePasswordHash(passwordEncoder.encode(newPassword));
            refreshTokenService.revokeShopScope(shop.getId());
        }
        shopRepository.save(shop);
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new BadRequestException("Owner not found"));
        return toOwnerView(owner, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(owner.getId()));
    }

    private java.util.Optional<User> findUserByEmailOrPhone(String identifier) {
        if (identifier == null) return java.util.Optional.empty();
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) return java.util.Optional.empty();
        java.util.Optional<User> u = userRepository.findByEmail(trimmed);
        if (u.isPresent()) return u;
        u = userRepository.findByPhone(trimmed);
        if (u.isPresent()) return u;
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() >= 10) {
            String last10 = digits.substring(digits.length() - 10);
            for (String variant : java.util.List.of(last10, "0" + last10, "+91 " + last10, "+91" + last10, "91" + last10)) {
                u = userRepository.findByPhone(variant);
                if (u.isPresent()) return u;
            }
        }
        return java.util.Optional.empty();
    }

    private static String slugify(String s) {
        if (s == null) return "shop";
        return s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static final double EARTH_KM = 6371.0;

    /**
     * Pickup-enabled shops within {@code radiusKm} of the caller (lat,lng).
     * Filters shops.pickup_enabled = TRUE and computes Haversine distance in
     * Java so this works on both Postgres and H2. Sorted by distance asc.
     */
    @Transactional(readOnly = true)
    public List<PickupShopView> findPickupShopsNearby(java.math.BigDecimal callerLat, java.math.BigDecimal callerLng, double radiusKm) {
        if (callerLat == null || callerLng == null) return java.util.Collections.emptyList();
        final double oLat = callerLat.doubleValue();
        final double oLng = callerLng.doubleValue();
        return shopRepository.findByPickupEnabledTrueAndLatitudeNotNullAndLongitudeNotNull().stream()
                .map(s -> {
                    double d = haversineKm(oLat, oLng, s.getLatitude().doubleValue(), s.getLongitude().doubleValue());
                    return toPickupShopView(s, d);
                })
                .filter(v -> v.getDistanceKm() <= radiusKm)
                .sorted(java.util.Comparator.comparing(PickupShopView::getDistanceKm))
                .toList();
    }

    /**
     * Public shop detail used by the customer "Pickup Service Shop" detail screen.
     * Returns the same shape as the pickup-nearby feed so the client can reuse
     * its view model. If the caller supplies (lat,lng), distanceKm is computed.
     */
    @Transactional(readOnly = true)
    public PickupShopView findPublicShop(UUID shopId, java.math.BigDecimal callerLat, java.math.BigDecimal callerLng) {
        Shop s = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found: " + shopId));
        double d = 0.0;
        if (callerLat != null && callerLng != null && s.getLatitude() != null && s.getLongitude() != null) {
            d = haversineKm(callerLat.doubleValue(), callerLng.doubleValue(),
                    s.getLatitude().doubleValue(), s.getLongitude().doubleValue());
        }
        return toPickupShopView(s, d);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static PickupShopView toPickupShopView(Shop s, double distanceKm) {
        String addressLine = java.util.stream.Stream.of(
                s.getStreet(), s.getArea(), s.getTaluk(), s.getDistrict(), s.getState(), s.getPincode()
        ).filter(p -> p != null && !p.isBlank()).reduce((a, b) -> a + ", " + b).orElse(s.getAddress());
        return PickupShopView.builder()
                .id(s.getId())
                .name(s.getName())
                .slug(s.getSlug())
                .mobile(s.getMobile())
                .address(addressLine)
                .city(s.getArea() != null ? s.getArea() : s.getTaluk())
                .district(s.getDistrict())
                .state(s.getState())
                .pincode(s.getPincode())
                .gstNumber(s.getGstNumber())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .frontImageUrl(s.getFrontImageUrl())
                .bannerImageUrl(s.getBannerImageUrl())
                .pickupFromTime(s.getPickupFromTime())
                .pickupToTime(s.getPickupToTime())
                .pickupDistanceKm(s.getPickupDistanceKm())
                .pickupEnabled(Boolean.TRUE.equals(s.getPickupEnabled()))
                .distanceKm(Math.round(distanceKm * 10) / 10.0)
                .build();
    }

    @Transactional
    public List<ShopOwnerView> listShopOwners() {
        return userRepository.findByRoleOrderByCreatedAtDesc(Roles.SHOP_OWNER).stream()
                .map(u -> toOwnerView(u, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId())))
                .toList();
    }

    @Transactional
    public ShopOwnerView getShopOwner(UUID id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + id));
        if (!"SHOP_OWNER".equals(u.getRole()))
            throw new BadRequestException("User is not a SHOP_OWNER");
        return toOwnerView(u, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId()));
    }

    @Transactional
    public ShopOwnerView updateShopOwner(UUID id, UpdateShopOwnerRequest req) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + id));
        if (!"SHOP_OWNER".equals(u.getRole()))
            throw new BadRequestException("User is not a SHOP_OWNER");

        if (notBlank(req.getName()))           u.setName(req.getName().trim());
        if (notBlank(req.getEmail()))          u.setEmail(req.getEmail().trim());
        if (notBlank(req.getPassword())) {
            u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
            refreshTokenService.revokeAllForUser(u.getId());
        }
        if (req.getPhone() != null)            u.setPhone(req.getPhone());
        if (req.getSecondaryMobile() != null)  u.setSecondaryMobile(req.getSecondaryMobile());
        // Media URLs use notBlank, not a bare null check: a client that omits the
        // field OR sends "" both mean "no new file" and must never wipe the
        // stored image. Intentional removal is a separate action, not this one.
        if (notBlank(req.getAvatarUrl()))      u.setAvatarUrl(req.getAvatarUrl());
        if (notBlank(req.getIdProofUrl()))     u.setIdProofUrl(req.getIdProofUrl());
        if (req.getAadharFrontUrl() != null || req.getAadharBackUrl() != null || req.getPanUrl() != null) {
            u.setKycDocument(mergeKyc(u.getKycDocument(),
                    req.getAadharFrontUrl(), req.getAadharBackUrl(), req.getPanUrl()));
        }
        if (req.getPersonalAddress() != null)  u.setPersonalAddress(req.getPersonalAddress());
        if (req.getAddrState() != null)        u.setAddrState(req.getAddrState());
        if (req.getAddrDistrict() != null)     u.setAddrDistrict(req.getAddrDistrict());
        if (req.getAddrTaluk() != null)        u.setAddrTaluk(req.getAddrTaluk());
        if (req.getAddrArea() != null)         u.setAddrArea(req.getAddrArea());
        if (req.getAddrStreet() != null)       u.setAddrStreet(req.getAddrStreet());
        if (req.getAddrPincode() != null)      u.setAddrPincode(req.getAddrPincode());
        if (notBlank(req.getOtpCode()))        u.setOtpCode(req.getOtpCode().trim());

        u = userRepository.save(u);
        return toOwnerView(u, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId()));
    }

    // ---- Owner KYC (users.kyc_document jsonb) ---------------------------------

    private static final String KYC_PENDING  = "PENDING_REVIEW";
    private static final String KYC_APPROVED = "APPROVED";
    private static final String KYC_REJECTED = "REJECTED";

    /** Build a fresh KycDocument from upload URLs; returns null when none supplied. */
    private static KycDocument newKyc(String aadharFrontUrl, String aadharBackUrl, String panUrl) {
        return mergeKyc(null, aadharFrontUrl, aadharBackUrl, panUrl);
    }

    /**
     * Apply the supplied (non-null) URLs onto the existing KYC blob and mark the
     * submission PENDING_REVIEW (a resubmit clears any prior rejection). Returns
     * null only when there is no existing document and nothing was supplied.
     */
    private static KycDocument mergeKyc(KycDocument existing, String aadharFrontUrl, String aadharBackUrl, String panUrl) {
        boolean anySupplied = aadharFrontUrl != null || aadharBackUrl != null || panUrl != null;
        if (existing == null && !anySupplied) return null;
        KycDocument kyc = existing != null ? existing : KycDocument.builder().build();
        if (aadharFrontUrl != null) kyc.setAadharFrontUrl(blankToNull(aadharFrontUrl));
        if (aadharBackUrl != null)  kyc.setAadharBackUrl(blankToNull(aadharBackUrl));
        if (panUrl != null)         kyc.setPanUrl(blankToNull(panUrl));
        if (anySupplied) {
            kyc.setStatus(KYC_PENDING);
            kyc.setRejectReason(null);
            kyc.setReviewedAt(null);
            kyc.setSubmittedAt(java.time.Instant.now());
        }
        return kyc;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Current owner's KYC (never null — returns an empty blob when nothing submitted). */
    @Transactional(readOnly = true)
    public KycDocument getOwnerKyc(UUID userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found: " + userId));
        return u.getKycDocument() != null ? u.getKycDocument() : KycDocument.builder().build();
    }

    /** Owner-scoped save/resubmit of KYC documents (from the mobile app). */
    @Transactional
    public KycDocument saveOwnerKyc(UUID userId, String aadharFrontUrl, String aadharBackUrl, String panUrl) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found: " + userId));
        KycDocument kyc = mergeKyc(u.getKycDocument(), aadharFrontUrl, aadharBackUrl, panUrl);
        u.setKycDocument(kyc);
        u = userRepository.save(u);
        return u.getKycDocument() != null ? u.getKycDocument() : KycDocument.builder().build();
    }

    /** Admin review action — approve / reject / re-open the owner's KYC submission. */
    @Transactional
    public ShopOwnerView reviewOwnerKyc(UUID ownerId, String status, String rejectReason) {
        User u = userRepository.findById(ownerId)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + ownerId));
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!s.equals(KYC_APPROVED) && !s.equals(KYC_REJECTED) && !s.equals(KYC_PENDING))
            throw new BadRequestException("status must be APPROVED, REJECTED or PENDING_REVIEW");
        KycDocument kyc = u.getKycDocument();
        if (kyc == null || !kyc.hasAnyDocument())
            throw new BadRequestException("No KYC documents submitted for this owner");
        kyc.setStatus(s);
        kyc.setRejectReason(s.equals(KYC_REJECTED) ? rejectReason : null);
        kyc.setReviewedAt(java.time.Instant.now());
        u.setKycDocument(kyc);
        u = userRepository.save(u);
        return toOwnerView(u, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId()));
    }

    @Transactional
    public void deleteShopOwner(UUID id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + id));
        if (!"SHOP_OWNER".equals(u.getRole()))
            throw new BadRequestException("User is not a SHOP_OWNER");
        // Detach shops first (owner_user_id has ON DELETE SET NULL but be explicit).
        List<Shop> owned = shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(id);
        for (Shop s : owned) {
            s.setOwnerUserId(null);
            shopRepository.save(s);
        }
        userRepository.delete(u);
    }

    // ---- Email verification ----------------------------------------------------

    /** Issues a one-time code for this email; returns the code (dev) — production would email it. */
    @Transactional(readOnly = true)
    public String sendEmailVerifyOtp(String email) {
        if (email == null || email.isBlank())
            throw new BadRequestException("Email is required");
        // Confirm the email maps to a real user; do NOT leak existence on failure.
        userRepository.findByEmail(email.trim())
                .orElseThrow(() -> new BadRequestException("No account for this email"));
        return otpStore.issue(email.trim());
    }

    /** Confirms the OTP for the email; flips email_verified to true. */
    @Transactional
    public ShopOwnerView confirmEmailVerifyOtp(String email, String otp) {
        if (email == null || email.isBlank() || otp == null || otp.isBlank())
            throw new BadRequestException("Email and otp are required");
        User u = userRepository.findByEmail(email.trim())
                .orElseThrow(() -> new BadRequestException("No account for this email"));
        if (!otpStore.verify(email.trim(), otp))
            throw new UnauthorizedException("Invalid or expired OTP");
        u.setEmailVerified(true);
        u = userRepository.save(u);
        return toOwnerView(u, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId()));
    }

    // ---- Password reset (forgot password) / passwordless sign-in ---------------

    /**
     * Issue an OTP for a password reset (or email "sign in with OTP"). For an
     * EMAIL identifier we generate a 6-digit OtpStore code and email it via
     * Resend; for a MOBILE identifier the OTP is the default 123456 (no SMS
     * gateway). The account must exist. In dev the code is surfaced as devOtp.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> sendPasswordResetOtp(String identifier) {
        if (identifier == null || identifier.isBlank())
            throw new BadRequestException("Email or mobile number is required");
        String id = identifier.trim();
        boolean isEmail = id.contains("@");
        User user = (isEmail ? userRepository.findByEmail(id) : findUserByEmailOrPhone(id))
                .orElseThrow(() -> new BadRequestException("No account found for that email or mobile number."));

        java.util.Map<String, Object> res = new java.util.HashMap<>();
        if (isEmail) {
            String code = otpStore.issue(id);
            boolean sent = emailService.sendOtpEmail(id, code, "reset your GGFIX password");
            res.put("channel", "EMAIL");
            res.put("sent", sent);
            res.put("target", maskEmail(id));
            res.put("ttlMinutes", 10);
            res.put("devOtp", code); // dev convenience; production clients ignore this
        } else {
            res.put("channel", "MOBILE");
            res.put("sent", true);
            res.put("target", maskMobile(id));
            res.put("defaultOtp", "123456");
        }
        res.put("email", isEmail ? id : user.getEmail());
        // The account type the login would produce (same mapping as the login
        // response), so an app can turn a number away before the OTP step —
        // the Partner app accepts only SHOP_OWNER (and shop logins, which use
        // /auth/shop-login/request-otp), never an EMPLOYEE.
        res.put("loginType", loginTypeForRole(user.getRole()));
        return res;
    }

    /**
     * Verify the reset OTP and set a new bcrypt password, then return a fresh
     * login session (auto sign-in). Email OTPs are verified via OtpStore;
     * mobile uses the default 123456.
     */
    @Transactional
    public LoginResponse resetPasswordWithOtp(String identifier, String otp, String newPassword) {
        if (identifier == null || identifier.isBlank())
            throw new BadRequestException("Email or mobile number is required");
        if (otp == null || otp.isBlank())
            throw new BadRequestException("OTP is required");
        if (newPassword == null || newPassword.trim().length() < 8)
            throw new BadRequestException("Password must be at least 8 characters.");
        String id = identifier.trim();
        boolean isEmail = id.contains("@");
        User user = (isEmail ? userRepository.findByEmail(id) : findUserByEmailOrPhone(id))
                .orElseThrow(() -> new BadRequestException("No account found."));
        boolean ok = isEmail ? otpStore.verify(id, otp.trim()) : "123456".equals(otp.trim());
        if (!ok)
            throw new UnauthorizedException("Invalid or expired OTP.");
        user.setPasswordHash(passwordEncoder.encode(newPassword.trim()));
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(user.getId());   // end every other session
        return buildLoginResponse(user, null);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at - 1);
    }

    private static String maskMobile(String mobile) {
        String d = mobile.replaceAll("\\D", "");
        if (d.length() < 2) return "***";
        return "***-***-" + d.substring(d.length() - 2);
    }

    // ---- Per-location CRUD -----------------------------------------------------

    @Transactional
    public ShopOwnerView addLocation(UUID ownerId, CreateShopOwnerRequest.ShopLocationDto loc) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + ownerId));
        if (!"SHOP_OWNER".equals(owner.getRole()))
            throw new BadRequestException("User is not a SHOP_OWNER");
        if (loc.getName() == null || loc.getName().isBlank())
            throw new BadRequestException("Shop name is required");

        // Shop allowance is counted per OWNER, not per shop — a trial covers
        // two shops for the account, not two per shop. requireOwnerCapacity
        // takes the owner id directly rather than resolving one from a shopId,
        // which is what the employee checks do.
        subscriptionLimits.requireOwnerCapacity(ownerId, SubscriptionFeature.SHOPS,
                subscriptionLimits.countActiveShops(ownerId));

        String slug = loc.getSlug();
        if (slug == null || slug.isBlank())
            slug = slugify(loc.getName()) + "-" + UUID.randomUUID().toString().substring(0, 6);
        if (shopRepository.existsBySlug(slug))
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 4);

        Shop shop = Shop.builder()
                .id(UUID.randomUUID())
                .name(loc.getName().trim())
                .slug(slug)
                .mobile(loc.getMobile())
                .address(loc.getAddress())
                .district(loc.getDistrict())
                .state(loc.getState())
                .pincode(loc.getPincode())
                .taluk(loc.getTaluk())
                .area(loc.getArea())
                .street(loc.getStreet())
                .gstNumber(loc.getGstNumber())
                .latitude(loc.getLatitude())
                .longitude(loc.getLongitude())
                .frontImageUrl(loc.getFrontImageUrl())
                .bannerImageUrl(loc.getBannerImageUrl())
                .gstCertificateUrl(loc.getGstCertificateUrl())
                .udyamCertificateUrl(loc.getUdyamCertificateUrl())
                .workingDays(loc.getWorkingDays())
                .openingTime(loc.getOpeningTime())
                .closingTime(loc.getClosingTime())
                // Pickup settings are carried on the same DTO as createShopOwner
                // uses; without these a location added after owner creation
                // silently lost its pickup window and came up pickup-disabled.
                // Absent pickupEnabled means off — a new shop must not appear in
                // the customer pickup feed before its owner opts in.
                .pickupFromTime(loc.getPickupFromTime())
                .pickupToTime(loc.getPickupToTime())
                .pickupDistanceKm(loc.getPickupDistanceKm())
                .pickupEnabled(Boolean.TRUE.equals(loc.getPickupEnabled()))
                .ownerUserId(owner.getId())
                .mobileOtpCode("123456")
                .isActive(true)
                .build();
        shopRepository.save(shop);
        return toOwnerView(owner, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(owner.getId()));
    }

    @Transactional
    public ShopOwnerView updateLocation(UUID ownerId, UUID shopId, CreateShopOwnerRequest.ShopLocationDto loc) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found: " + shopId));
        if (shop.getOwnerUserId() == null || !shop.getOwnerUserId().equals(ownerId))
            throw new BadRequestException("Shop does not belong to this owner");

        // PATCH semantics: only update a field when the request actually sent it.
        // null = "field not in this request, leave existing value alone".
        // Empty string = explicit clear (handled by the same null-check skip
        // since the mobile/admin clients send the prior value when editing).
        if (loc.getName() != null && !loc.getName().isBlank()) shop.setName(loc.getName().trim());
        if (loc.getMobile() != null)               shop.setMobile(loc.getMobile());
        if (loc.getAddress() != null)              shop.setAddress(loc.getAddress());
        if (loc.getDistrict() != null)             shop.setDistrict(loc.getDistrict());
        if (loc.getState() != null)                shop.setState(loc.getState());
        if (loc.getPincode() != null)              shop.setPincode(loc.getPincode());
        if (loc.getTaluk() != null)                shop.setTaluk(loc.getTaluk());
        if (loc.getArea() != null)                 shop.setArea(loc.getArea());
        if (loc.getStreet() != null)               shop.setStreet(loc.getStreet());
        if (loc.getGstNumber() != null)            shop.setGstNumber(loc.getGstNumber());
        if (loc.getLatitude() != null)             shop.setLatitude(loc.getLatitude());
        if (loc.getLongitude() != null)            shop.setLongitude(loc.getLongitude());
        // Media URLs: notBlank, not a bare null check — see updateShopOwner above.
        // A location edit that doesn't touch documents must never blank them out.
        if (notBlank(loc.getFrontImageUrl()))       shop.setFrontImageUrl(loc.getFrontImageUrl());
        if (notBlank(loc.getBannerImageUrl()))      shop.setBannerImageUrl(loc.getBannerImageUrl());
        if (loc.getPickupFromTime() != null)       shop.setPickupFromTime(loc.getPickupFromTime());
        if (loc.getPickupToTime() != null)         shop.setPickupToTime(loc.getPickupToTime());
        if (loc.getPickupDistanceKm() != null)     shop.setPickupDistanceKm(loc.getPickupDistanceKm());
        if (loc.getPickupEnabled() != null)        shop.setPickupEnabled(loc.getPickupEnabled());
        if (loc.getWorkingDays() != null)          shop.setWorkingDays(loc.getWorkingDays());
        if (loc.getOpeningTime() != null)          shop.setOpeningTime(loc.getOpeningTime());
        if (loc.getClosingTime() != null)          shop.setClosingTime(loc.getClosingTime());
        if (notBlank(loc.getGstCertificateUrl()))   shop.setGstCertificateUrl(loc.getGstCertificateUrl());
        if (notBlank(loc.getUdyamCertificateUrl())) shop.setUdyamCertificateUrl(loc.getUdyamCertificateUrl());
        if (loc.getServiceCategoriesJson() != null) shop.setServiceCategoriesJson(loc.getServiceCategoriesJson());
        shopRepository.save(shop);

        User owner = userRepository.findById(ownerId).orElseThrow(() -> new BadRequestException("Owner not found"));
        return toOwnerView(owner, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(owner.getId()));
    }

    @Transactional
    public void deleteLocation(UUID ownerId, UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found: " + shopId));
        if (shop.getOwnerUserId() == null || !shop.getOwnerUserId().equals(ownerId))
            throw new BadRequestException("Shop does not belong to this owner");
        shopRepository.delete(shop);
    }

    /**
     * Activate / deactivate a shop owner. ADMIN-only — the caller's role is
     * verified in {@link com.repairshop.saas.auth.controller.AuthController}
     * before this runs.
     *
     * isActive is the ONLY field written. createdBy, createdAt and role are
     * deliberately untouched: the endpoint takes no values for them, so they
     * cannot be edited through the account-status API even if a client sends
     * them in the body.
     */
    @Transactional
    public ShopOwnerView setShopOwnerActive(UUID id, boolean active) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + id));
        // Shop owners and market persons are the accounts this screen governs.
        // Admins are deliberately out of reach: allowing one admin to deactivate
        // another (or themselves) is how a platform ends up with no way back in.
        if (Roles.isAdmin(u.getRole()))
            throw new BadRequestException("Administrator accounts cannot be deactivated here");
        if (!Roles.isShopOwner(u.getRole()) && !Roles.isMarketPerson(u.getRole()))
            throw new BadRequestException("User is not a shop owner or market person");
        u.setIsActive(active);
        if (!active) refreshTokenService.revokeAllForUser(u.getId());
        u = userRepository.save(u);
        return toOwnerView(u, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId()));
    }

    /**
     * Look up the caller behind a JWT so controllers can enforce role rules.
     * Throws 401 when the id doesn't resolve — a token for a deleted user is
     * not a valid session.
     */
    @Transactional(readOnly = true)
    public User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
    }

    /** Name to record for a person, falling back to email when unnamed. */
    private static String displayName(User u) {
        if (u == null) return null;
        return notBlank(u.getName()) ? u.getName().trim() : u.getEmail();
    }

    /**
     * Create a MARKET_PERSON login. ADMIN-only — market persons cannot create
     * more of themselves. Created active: the inactive-by-default rule exists
     * so an admin vets shop owners, and an admin making this account IS that
     * sign-off.
     */
    @Transactional
    public ShopOwnerView createMarketPerson(String name, String email, String phone,
                                            String password, User creator) {
        if (!notBlank(email)) throw new BadRequestException("Email is required");
        if (!notBlank(name))  throw new BadRequestException("Name is required");
        String cleanEmail = email.trim();
        if (userRepository.findByEmail(cleanEmail).isPresent())
            throw new BadRequestException("A user with this email already exists");

        User mp = User.builder()
                .id(UUID.randomUUID())
                .name(name.trim())
                .email(cleanEmail)
                .phone(notBlank(phone) ? phone.trim() : null)
                .passwordHash(notBlank(password) ? passwordEncoder.encode(password) : null)
                .otpCode("123456")
                .role(Roles.MARKET_PERSON)
                .isActive(true)
                .createdBy(creator == null ? null : Roles.canonical(creator.getRole()))
                .createdPersonId(creator == null ? null : creator.getId())
                .createdPersonName(creator == null ? null : displayName(creator))
                .emailVerified(false)
                .build();
        mp = userRepository.save(mp);
        return toOwnerView(mp, List.of());
    }

    /**
     * Assign (or clear) the market person currently responsible for a shop
     * owner. ADMIN-only.
     *
     * The name and role are read off the target user row rather than taken from
     * the request, so a client cannot label an arbitrary id with a name of its
     * choosing. Passing a null id clears the assignment. Creator fields are
     * never touched — reassignment does not rewrite history.
     */
    @Transactional
    public ShopOwnerView assignActivePerson(UUID ownerId, UUID marketPersonId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new BadRequestException("Owner not found: " + ownerId));
        if (!Roles.isShopOwner(owner.getRole()))
            throw new BadRequestException("User is not a SHOP_OWNER");

        if (marketPersonId == null) {
            owner.setActiveRole(null);
            owner.setActivePersonId(null);
            owner.setActivePersonName(null);
        } else {
            User mp = userRepository.findById(marketPersonId)
                    .orElseThrow(() -> new BadRequestException("Market person not found: " + marketPersonId));
            if (!Roles.isMarketPerson(mp.getRole()))
                throw new BadRequestException("Assigned user is not a MARKET_PERSON");
            owner.setActiveRole(Roles.MARKET_PERSON);
            owner.setActivePersonId(mp.getId());
            owner.setActivePersonName(displayName(mp));
        }
        owner = userRepository.save(owner);
        return toOwnerView(owner, shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(owner.getId()));
    }

    /** Every market person, for the admin's assignment picker. */
    @Transactional(readOnly = true)
    public List<ShopOwnerView> listMarketPersons() {
        return userRepository.findByRoleOrderByCreatedAtDesc(Roles.MARKET_PERSON).stream()
                .map(u -> toOwnerView(u, List.of()))
                .toList();
    }

    /**
     * Every account the User Management screen manages — shop owners and market
     * persons, newest first. Admins are excluded: they are not administered
     * through this screen and showing a deactivate control against them invites
     * an admin locking themselves out.
     */
    @Transactional(readOnly = true)
    public List<ShopOwnerView> listManagedUsers() {
        return userRepository.findAll().stream()
                .filter(u -> Roles.isShopOwner(u.getRole()) || Roles.isMarketPerson(u.getRole()))
                .sorted((a, b) -> {
                    Instant x = a.getCreatedAt(), y = b.getCreatedAt();
                    if (x == null && y == null) return 0;
                    if (x == null) return 1;
                    if (y == null) return -1;
                    return y.compareTo(x);
                })
                .map(u -> toOwnerView(u, Roles.isShopOwner(u.getRole())
                        ? shopRepository.findByOwnerUserIdOrderByCreatedAtAsc(u.getId())
                        : List.of()))
                .toList();
    }

    private ShopOwnerView toOwnerView(User u, List<Shop> shops) {
        // Profile completeness: 5 sections — Basic info (name+email+phone), Avatar,
        // ID Proof, Personal Address, At-least-one-shop.
        int sections = 0;
        if (u.getName() != null && !u.getName().isBlank()
                && u.getEmail() != null && !u.getEmail().isBlank()
                && u.getPhone() != null && !u.getPhone().isBlank()) sections++;
        if (u.getAvatarUrl() != null && !u.getAvatarUrl().isBlank()) sections++;
        if (u.getIdProofUrl() != null && !u.getIdProofUrl().isBlank()) sections++;
        if (u.getPersonalAddress() != null && !u.getPersonalAddress().isBlank()) sections++;
        if (shops != null && !shops.isEmpty()) sections++;
        int total = 5;
        int percent = (int) Math.round((sections * 100.0) / total);

        boolean emailVerified = Boolean.TRUE.equals(u.getEmailVerified());

        // Subscription window for the admin owner views (null if no row).
        Instant subActiveDate = null;
        Instant subInactiveDate = null;
        Subscription sub = subscriptionRepository.findByOwnerUserId(u.getId()).orElse(null);
        if (sub == null && "SHOP_OWNER".equals(u.getRole()) && shops != null && !shops.isEmpty()) {
            // Auto-heal: owners created before the subscription feature have no row.
            // Give them a fresh 15-day free trial on first admin read so active/
            // inactive dates populate (guarded + swallows errors — needs a writable tx).
            createFreeTrial(u.getId(), shops.get(0).getId());
            sub = subscriptionRepository.findByOwnerUserId(u.getId()).orElse(null);
        }
        if (sub != null) {
            subActiveDate = sub.getActiveDate();
            subInactiveDate = sub.getInactiveDate();
        }

        List<ShopOwnerView.ShopLocationView> locs = shops.stream().map(s -> ShopOwnerView.ShopLocationView.builder()
                .id(s.getId())
                .name(s.getName())
                .slug(s.getSlug())
                .mobile(s.getMobile())
                .address(s.getAddress())
                .district(s.getDistrict())
                .state(s.getState())
                .pincode(s.getPincode())
                .taluk(s.getTaluk())
                .area(s.getArea())
                .street(s.getStreet())
                .gstNumber(s.getGstNumber())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .frontImageUrl(s.getFrontImageUrl())
                .bannerImageUrl(s.getBannerImageUrl())
                .pickupFromTime(s.getPickupFromTime())
                .pickupToTime(s.getPickupToTime())
                .pickupDistanceKm(s.getPickupDistanceKm())
                .pickupEnabled(s.getPickupEnabled())
                .workingDays(s.getWorkingDays())
                .openingTime(s.getOpeningTime())
                .closingTime(s.getClosingTime())
                .gstCertificateUrl(s.getGstCertificateUrl())
                .udyamCertificateUrl(s.getUdyamCertificateUrl())
                .isActive(s.getIsActive())
                .serviceCategoriesJson(s.getServiceCategoriesJson())
                .progressPercent(locationProgressPercent(s))
                .createdAt(s.getCreatedAt())
                .build()).toList();

        return ShopOwnerView.builder()
                .id(u.getId())
                .name(u.getName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .secondaryMobile(u.getSecondaryMobile())
                .avatarUrl(u.getAvatarUrl())
                .idProofUrl(u.getIdProofUrl())
                .kycDocument(u.getKycDocument())
                .personalAddress(u.getPersonalAddress())
                .addrState(u.getAddrState())
                .addrDistrict(u.getAddrDistrict())
                .addrTaluk(u.getAddrTaluk())
                .addrArea(u.getAddrArea())
                .addrStreet(u.getAddrStreet())
                .addrPincode(u.getAddrPincode())
                .role(u.getRole())
                .isActive(u.getIsActive())
                .createdBy(u.getCreatedBy())
                .createdPersonId(u.getCreatedPersonId())
                .createdPersonName(u.getCreatedPersonName())
                .activeRole(u.getActiveRole())
                .activePersonId(u.getActivePersonId())
                .activePersonName(u.getActivePersonName())
                .emailVerified(emailVerified)
                .profileCompletePercent(percent)
                .sectionsComplete(sections)
                .sectionsTotal(total)
                .createdAt(u.getCreatedAt())
                .activeDate(subActiveDate)
                .inactiveDate(subInactiveDate)
                .locations(locs)
                .build();
    }

    @Transactional
    public ShopResponse updateShopStatus(UUID shopId, String status) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new BadRequestException("Shop not found: " + shopId));
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        shop.setIsActive(active);
        shop = shopRepository.save(shop);
        return ShopResponse.builder()
                .id(shop.getId())
                .name(shop.getName())
                .slug(shop.getSlug())
                .isActive(shop.getIsActive())
                .status(Boolean.TRUE.equals(shop.getIsActive()) ? "ACTIVE" : "SUSPENDED")
                .build();
    }

    // Map the owner-UI friendly label (Technician / Staff / Pickup Person) to the
    // canonical role stored in users.role. Defaults to TECHNICIAN when omitted so
    // older clients still work.
    static String canonicalEmployeeRole(String roleLabel) {
        if (roleLabel == null) return "TECHNICIAN";
        String normalized = roleLabel.trim().toUpperCase().replace(' ', '_');
        if (normalized.isEmpty()) return "TECHNICIAN";
        switch (normalized) {
            case "PICKUP_PERSON":
            case "PICKUPPERSON":
                return "PICKUP_PERSON";
            case "STAFF":
                return "STAFF";
            case "TECHNICIAN":
            default:
                return "TECHNICIAN";
        }
    }

    // Inverse of canonicalEmployeeRole: convert the stored role back to the
    // owner-UI friendly label. Tolerates the legacy "PICKUP PERSON" form.
    static String friendlyEmployeeRoleLabel(String storedRole) {
        if (storedRole == null) return null;
        String normalized = storedRole.trim().toUpperCase().replace(' ', '_');
        switch (normalized) {
            case "PICKUP_PERSON":
                return "Pickup Person";
            case "STAFF":
                return "Staff";
            case "TECHNICIAN":
                return "Technician";
            case "SHOP_OWNER":
                return "Shop Owner";
            default:
                return storedRole;
        }
    }
}
