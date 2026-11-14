package com.repairshop.saas.auth.controller;

import com.repairshop.saas.auth.dto.CreateShopOwnerRequest;
import com.repairshop.saas.auth.dto.CreateShopRequest;
import com.repairshop.saas.auth.dto.CustomerAuthResponse;
import com.repairshop.saas.auth.dto.CustomerLoginRequest;
import com.repairshop.saas.auth.dto.CustomerRegisterRequest;
import com.repairshop.saas.auth.dto.LoginRequest;
import com.repairshop.saas.auth.dto.LoginResponse;
import com.repairshop.saas.auth.dto.PartnerSignupRequest;
import com.repairshop.saas.auth.dto.ShopLoginRequest;
import com.repairshop.saas.auth.dto.RegisterRequest;
import com.repairshop.saas.auth.dto.RegisterResponse;
import com.repairshop.saas.auth.dto.RegisterTechnicianRequest;
import com.repairshop.saas.auth.dto.PickupShopView;
import com.repairshop.saas.auth.dto.ShopOwnerResponse;
import com.repairshop.saas.auth.dto.ShopOwnerView;
import com.repairshop.saas.auth.dto.ShopResponse;
import com.repairshop.saas.auth.dto.UpdateShopOwnerRequest;
import com.repairshop.saas.auth.dto.TechnicianResponse;
import com.repairshop.saas.auth.dto.UserResponse;
import com.repairshop.saas.auth.entity.KycDocument;
import com.repairshop.saas.auth.entity.Roles;
import com.repairshop.saas.auth.entity.User;
import com.repairshop.saas.auth.exception.ForbiddenException;
import com.repairshop.saas.auth.exception.UnauthorizedException;
import com.repairshop.saas.auth.security.JwtService;
import com.repairshop.saas.auth.service.AuthService;
import com.repairshop.saas.auth.service.CustomerAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Login, registration, shops, technicians")
public class AuthController {

    private final AuthService authService;
    private final CustomerAuthService customerAuthService;
    private final JwtService jwtService;

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Login", description = "Returns JWT and user info")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Refresh session", description = "Exchanges a refresh token for a new access token and a rotated refresh token")
    public LoginResponse refresh(@RequestBody(required = false) Map<String, String> body) {
        return authService.refresh(body == null ? null : body.get("refreshToken"));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Logout", description = "Revokes the refresh token's session")
    public void logout(@RequestBody(required = false) Map<String, String> body) {
        authService.logout(body == null ? null : body.get("refreshToken"));
    }

    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Current shop-owner profile",
            description = "Returns the authenticated user's ShopOwnerView (profile + owned shops). Used by mobile screens to hydrate forms with live data.")
    public ShopOwnerView me(HttpServletRequest httpRequest) {
        String header = httpRequest.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer "))
            throw new UnauthorizedException("Missing or invalid Authorization header");
        UUID userId = jwtService.getUserId(header.substring("Bearer ".length()).trim());
        return authService.getShopOwner(userId);
    }

    @GetMapping("/me/kyc-documents")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Current owner KYC documents",
            description = "Returns the authenticated owner's KYC blob (Aadhar front/back + PAN + review status). Empty object when nothing submitted.")
    public KycDocument myKycDocuments(HttpServletRequest httpRequest) {
        return authService.getOwnerKyc(requireUserId(httpRequest));
    }

    @PostMapping("/me/kyc-documents")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Save current owner KYC documents",
            description = "Owner-scoped save/resubmit. Body: { aadharFrontUrl, aadharBackUrl, panUrl }. Sets status back to PENDING_REVIEW.")
    public KycDocument saveMyKycDocuments(HttpServletRequest httpRequest, @RequestBody Map<String, String> body) {
        UUID userId = requireUserId(httpRequest);
        return authService.saveOwnerKyc(userId,
                body == null ? null : body.get("aadharFrontUrl"),
                body == null ? null : body.get("aadharBackUrl"),
                body == null ? null : body.get("panUrl"));
    }

    @PostMapping("/switch-shop")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Switch active shop",
            description = "Re-issue JWT with the given shopId. Only SHOP_OWNER users may call this; the shopId must be one they own. Refused for SHOP-scoped tokens (shop-mobile login).")
    public LoginResponse switchShop(HttpServletRequest httpRequest, @RequestBody Map<String, String> body) {
        String header = httpRequest.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer "))
            throw new UnauthorizedException("Missing or invalid Authorization header");
        String token = header.substring("Bearer ".length()).trim();
        if ("SHOP".equalsIgnoreCase(jwtService.getLoginScope(token)))
            throw new UnauthorizedException("This session is locked to a single shop");
        UUID userId = jwtService.getUserId(token);
        String shopIdStr = body == null ? null : body.get("shopId");
        if (shopIdStr == null || shopIdStr.isBlank())
            throw new UnauthorizedException("shopId is required");
        UUID shopId;
        try { shopId = UUID.fromString(shopIdStr); }
        catch (IllegalArgumentException e) { throw new UnauthorizedException("Invalid shopId"); }
        return authService.switchShop(userId, shopId, body.get("refreshToken"));
    }

    @PostMapping("/shop-login")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Login by shop mobile number",
            description = "Authenticates against shops.mobile + (mobile_password_hash | mobile_otp_code). Returns a JWT locked to that one shop (loginScope=SHOP). The shop switcher is disabled for these sessions.")
    public LoginResponse shopLogin(@Valid @RequestBody ShopLoginRequest request) {
        return authService.shopLogin(request);
    }

    @PostMapping("/shop-login/request-otp")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Request OTP for shop-mobile login",
            description = "Returns the current shops.mobile_otp_code in dev mode (production would SMS it and respond { sent: true }). Default OTP is 123456 to match the existing dev pattern.")
    public Map<String, Object> requestShopLoginOtp(@RequestBody Map<String, String> body) {
        String mobile = body == null ? null : body.get("mobile");
        if (mobile == null || mobile.isBlank())
            throw new UnauthorizedException("mobile is required");
        String code = authService.issueShopMobileOtp(mobile);
        return Map.of("sent", true, "devOtp", code, "ttlMinutes", 10);
    }

    @PatchMapping("/shop-owners/{ownerId}/locations/{shopId}/mobile-password")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Set the shop's mobile-login password",
            description = "Owner-only. Sets/replaces the bcrypt password used by POST /auth/shop-login. Pass an empty string to clear (OTP login remains available).")
    public ShopOwnerView setShopMobilePassword(@PathVariable UUID ownerId,
                                               @PathVariable UUID shopId,
                                               @RequestBody Map<String, String> body) {
        String pwd = body == null ? null : body.get("password");
        return authService.setShopMobilePassword(ownerId, shopId, pwd);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register", description = "Register new shop and owner")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/shops")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "List shops", description = "List all shops (for admin / assigning technicians)")
    public List<ShopResponse> listShops() {
        return authService.listShops();
    }

    @GetMapping("/shops/pickup-nearby")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Pickup shops nearby",
            description = "Public endpoint. Returns shops where pickup_enabled=true within radiusKm of (lat,lng), sorted by distance asc.")
    public List<PickupShopView> pickupShopsNearby(
            @org.springframework.web.bind.annotation.RequestParam("lat") java.math.BigDecimal lat,
            @org.springframework.web.bind.annotation.RequestParam("lng") java.math.BigDecimal lng,
            @org.springframework.web.bind.annotation.RequestParam(value = "radiusKm", required = false, defaultValue = "20") double radiusKm
    ) {
        return authService.findPickupShopsNearby(lat, lng, radiusKm);
    }

    @GetMapping("/shops/{shopId}/public")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Public shop detail",
            description = "Public endpoint. Returns the same view used by the pickup-nearby feed, including address, phone, lat/lng, and pickup window. Optionally accepts caller lat/lng to also compute distanceKm.")
    public PickupShopView publicShopDetail(
            @org.springframework.web.bind.annotation.PathVariable("shopId") java.util.UUID shopId,
            @org.springframework.web.bind.annotation.RequestParam(value = "lat", required = false) java.math.BigDecimal lat,
            @org.springframework.web.bind.annotation.RequestParam(value = "lng", required = false) java.math.BigDecimal lng
    ) {
        return authService.findPublicShop(shopId, lat, lng);
    }

    @PostMapping("/shops")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create shop", description = "Create a new shop (admin)")
    public ShopResponse createShop(@Valid @RequestBody CreateShopRequest request) {
        return authService.createShop(request);
    }

    @PostMapping("/shop-owner")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create shop owner with locations",
            description = "ADMIN or MARKET_PERSON only. Creates a SHOP_OWNER user plus one or more shops "
                    + "linked via owner_user_id, atomically. Stamps createdBy with the calling staff account "
                    + "and createdAt with the current time; isActive defaults to true.")
    public ShopOwnerResponse createShopOwner(HttpServletRequest httpRequest,
                                             @Valid @RequestBody CreateShopOwnerRequest request) {
        User creator = requireRole(httpRequest, Roles::isStaff,
                "Only an administrator or market person can create shop owners");
        return authService.createShopOwner(request, creator);
    }

    @PostMapping("/market-persons")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create market person",
            description = "ADMIN only. Body: { name, email, phone, password }. Creator provenance is taken "
                    + "from the caller's token, never the body.")
    public ShopOwnerView createMarketPerson(HttpServletRequest httpRequest,
                                            @RequestBody Map<String, String> body) {
        User creator = requireRole(httpRequest, Roles::isAdmin,
                "Only an administrator can create market persons");
        return authService.createMarketPerson(
                body == null ? null : body.get("name"),
                body == null ? null : body.get("email"),
                body == null ? null : body.get("phone"),
                body == null ? null : body.get("password"),
                creator);
    }

    @GetMapping("/market-persons")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "List market persons", description = "ADMIN only. Feeds the assignment picker.")
    public List<ShopOwnerView> listMarketPersons(HttpServletRequest httpRequest) {
        requireRole(httpRequest, Roles::isAdmin, "Only an administrator can list market persons");
        return authService.listMarketPersons();
    }

    @GetMapping("/managed-users")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "List managed users",
            description = "ADMIN or MARKET_PERSON. Shop owners and market persons with creator and "
                    + "active-person provenance, newest first. Administrator accounts are excluded.")
    public List<ShopOwnerView> listManagedUsers(HttpServletRequest httpRequest) {
        requireRole(httpRequest, Roles::isStaff, "Only staff can view user management");
        return authService.listManagedUsers();
    }

    @PatchMapping("/shop-owners/{id}/active-person")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Assign the market person responsible for a shop owner",
            description = "ADMIN only. Body: { marketPersonId } — null clears the assignment. The name and "
                    + "role are read from the market person's own row, not the request. Creator fields "
                    + "(createdBy, createdPersonId, createdPersonName, createdAt) are never modified.")
    public ShopOwnerView assignActivePerson(HttpServletRequest httpRequest,
                                            @PathVariable UUID id,
                                            @RequestBody Map<String, String> body) {
        requireRole(httpRequest, Roles::isAdmin, "Only an administrator can assign a market person");
        String raw = body == null ? null : body.get("marketPersonId");
        UUID marketPersonId = (raw == null || raw.isBlank()) ? null : UUID.fromString(raw.trim());
        return authService.assignActivePerson(id, marketPersonId);
    }

    @GetMapping("/shop-owners")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "List shop owners", description = "Returns SHOP_OWNER users with linked shops and profile-completeness")
    public List<ShopOwnerView> listShopOwners() {
        return authService.listShopOwners();
    }

    @GetMapping("/shop-owners/{id}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Get shop owner detail", description = "Owner profile + all locations")
    public ShopOwnerView getShopOwner(@PathVariable UUID id) {
        return authService.getShopOwner(id);
    }

    @PatchMapping("/shop-owners/{id}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Update shop owner profile", description = "Partial update — only non-null fields are applied")
    public ShopOwnerView updateShopOwner(@PathVariable UUID id, @RequestBody UpdateShopOwnerRequest body) {
        return authService.updateShopOwner(id, body);
    }

    @DeleteMapping("/shop-owners/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete shop owner", description = "Removes the owner user; linked shops are detached (owner_user_id set to NULL)")
    public void deleteShopOwner(@PathVariable UUID id) {
        authService.deleteShopOwner(id);
    }

    @PatchMapping("/shop-owners/{id}/status")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Activate/deactivate shop owner",
            description = "ADMIN only. Body: { active: true|false } or { status: ACTIVE|INACTIVE }. "
                    + "MARKET_PERSON and SHOP_OWNER callers get 403. Only isActive is written — "
                    + "createdBy, createdAt and role are not editable through this endpoint.")
    public ShopOwnerView setShopOwnerActive(HttpServletRequest httpRequest,
                                            @PathVariable UUID id,
                                            @RequestBody Map<String, Object> body) {
        requireRole(httpRequest, Roles::isAdmin, "Only an administrator can change account status");
        boolean active = Boolean.TRUE.equals(body.get("active"))
                || "ACTIVE".equalsIgnoreCase(String.valueOf(body.get("status")));
        return authService.setShopOwnerActive(id, active);
    }

    @PatchMapping("/shop-owners/{id}/kyc-status")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Review owner KYC",
            description = "Admin action. Body: { status: APPROVED|REJECTED|PENDING_REVIEW, rejectReason }. Updates users.kyc_document and returns the refreshed owner view.")
    public ShopOwnerView reviewOwnerKyc(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String status = body == null ? null : body.get("status");
        String rejectReason = body == null ? null : body.get("rejectReason");
        return authService.reviewOwnerKyc(id, status, rejectReason);
    }

    // ---- Email verification (OTP is NOT stored in DB — in-memory only) -------

    @PostMapping("/email-verify/send")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Send email-verify OTP",
            description = "Issues a 6-digit OTP for the email; in dev the code is returned in the response. Production should email the code and return only { sent: true }.")
    public Map<String, Object> sendEmailVerifyOtp(@RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String code = authService.sendEmailVerifyOtp(email);
        // Dev convenience: surface the code so the admin can complete the flow without an email server.
        return Map.of("sent", true, "devOtp", code, "ttlMinutes", 10);
    }

    @PostMapping("/email-verify/confirm")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Confirm email-verify OTP",
            description = "Validates the OTP; flips users.email_verified to true on success.")
    public ShopOwnerView confirmEmailVerifyOtp(@RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String otp = body == null ? null : body.get("otp");
        return authService.confirmEmailVerifyOtp(email, otp);
    }

    // ---- Password reset (forgot password) / passwordless sign-in --------------

    @PostMapping("/otp/send")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Send a login/reset OTP",
            description = "Email identifiers get a generated 6-digit code emailed via Resend; mobile identifiers use the default 123456. Used by 'sign in with OTP' and 'forgot password'.")
    public Map<String, Object> sendOtp(@RequestBody Map<String, String> body) {
        String identifier = body == null ? null : (body.get("email") != null ? body.get("email") : body.get("identifier"));
        return authService.sendPasswordResetOtp(identifier);
    }

    @PostMapping("/forgot-password/reset")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Reset password with OTP",
            description = "Verifies the OTP (email via OtpStore, mobile via default 123456), sets a new bcrypt password, and returns a fresh login session.")
    public LoginResponse resetPassword(@RequestBody Map<String, String> body) {
        String identifier = body == null ? null : (body.get("email") != null ? body.get("email") : body.get("identifier"));
        String otp = body == null ? null : body.get("otp");
        String password = body == null ? null : body.get("password");
        return authService.resetPasswordWithOtp(identifier, otp, password);
    }

    // ---- Per-location CRUD ----------------------------------------------------

    @PostMapping("/shop-owners/{ownerId}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a business location to a shop owner")
    public ShopOwnerView addLocation(@PathVariable UUID ownerId,
                                     @Valid @RequestBody CreateShopOwnerRequest.ShopLocationDto loc) {
        return authService.addLocation(ownerId, loc);
    }

    @PatchMapping("/shop-owners/{ownerId}/locations/{shopId}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Update a business location")
    public ShopOwnerView updateLocation(@PathVariable UUID ownerId,
                                        @PathVariable UUID shopId,
                                        @RequestBody CreateShopOwnerRequest.ShopLocationDto loc) {
        return authService.updateLocation(ownerId, shopId, loc);
    }

    @DeleteMapping("/shop-owners/{ownerId}/locations/{shopId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a business location")
    public void deleteLocation(@PathVariable UUID ownerId, @PathVariable UUID shopId) {
        authService.deleteLocation(ownerId, shopId);
    }

    @PatchMapping("/shops/{shopId}/status")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Update shop status", description = "Activate or suspend shop")
    public ShopResponse updateShopStatus(@PathVariable UUID shopId, @RequestBody Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        if (status == null || status.isBlank())
            throw new IllegalArgumentException("status is required (ACTIVE or SUSPENDED)");
        return authService.updateShopStatus(shopId, status);
    }

    @GetMapping("/shops/{shopId}/users")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "List users", description = "List all users for a shop (user management)")
    public List<UserResponse> listUsersByShop(@PathVariable UUID shopId) {
        return authService.listUsersByShop(shopId);
    }

    @GetMapping("/shops/{shopId}/technicians")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "List technicians", description = "List technicians for a shop (for assignment)")
    public List<TechnicianResponse> listTechnicians(@PathVariable UUID shopId) {
        return authService.listTechnicians(shopId);
    }

    @PostMapping("/shops/{shopId}/technicians")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add technician", description = "Add a technician user to a shop")
    public RegisterResponse addTechnician(@PathVariable UUID shopId,
                                         @Valid @RequestBody RegisterTechnicianRequest request) {
        RegisterResponse resp = authService.registerTechnician(shopId, request);
        // Separate transaction: link an existing technician row (by shop+phone)
        // to the new login so /technicians/me resolves. Best-effort — never
        // rolls back the created login.
        authService.linkTechnicianByPhone(shopId, resp.getUserId(), request.getPhone());
        return resp;
    }

    // =========================================================================
    // Platform customer authentication (mobile app)
    // =========================================================================

    @PostMapping("/customer-register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Customer register",
            description = "Register a new platform customer (mobile app). Returns JWT.")
    public CustomerAuthResponse customerRegister(@Valid @RequestBody CustomerRegisterRequest request) {
        return customerAuthService.register(request);
    }

    @PostMapping("/customer-login")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Customer login",
            description = "Login platform customer by mobile or email. Returns JWT.")
    public CustomerAuthResponse customerLogin(@Valid @RequestBody CustomerLoginRequest request) {
        return customerAuthService.login(request);
    }

    @PostMapping("/partner/signup/otp/send")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Partner App Create Account — send OTP",
            description = "Public. 409 when the owner mobile is already registered (user or shop login).")
    public Map<String, Object> partnerSignupOtp(@RequestBody Map<String, String> body) {
        return authService.sendPartnerSignupOtp(body == null ? null : body.get("mobile"));
    }

    @PostMapping("/partner/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Partner App Create Account — verify OTP and register",
            description = "Public. Verifies the OTP, creates the shop owner + first shop (active, KYC pending) and returns a login session.")
    public LoginResponse partnerSignup(@Valid @RequestBody PartnerSignupRequest request) {
        return authService.partnerSignup(request);
    }

    @PostMapping("/customer/signup/otp/send")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Send a customer SIGN-UP OTP",
            description = "Issues an OTP for a mobile that has no account yet (Create Account, step 1). "
                    + "409 when the number is already registered — the app shows that as "
                    + "\"This mobile number is already registered.\" and points the user at sign-in. "
                    + "Distinct from /auth/customer/otp/send, which requires an EXISTING account.")
    public Map<String, Object> customerSendSignupOtp(@RequestBody Map<String, String> body) {
        String mobile = body == null ? null : (body.get("mobile") != null ? body.get("mobile") : body.get("identifier"));
        return customerAuthService.sendSignupOtp(mobile);
    }

    @PostMapping("/customer/signup/otp/verify")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Verify a customer SIGN-UP OTP",
            description = "Checks the code without consuming it (Create Account, step 2) so the client can "
                    + "collect the name next; /auth/customer-register re-verifies and consumes it.")
    public Map<String, Object> customerVerifySignupOtp(@RequestBody Map<String, String> body) {
        String mobile = body == null ? null : (body.get("mobile") != null ? body.get("mobile") : body.get("identifier"));
        String otp = body == null ? null : body.get("otp");
        return customerAuthService.verifySignupOtp(mobile, otp);
    }

    @PostMapping("/customer/otp/send")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Send a customer login/reset OTP",
            description = "Email identifiers get a generated 6-digit code emailed via Resend; mobile identifiers use the default 123456. The code is stored on customer_users.otp_code for 'sign in with OTP' and 'forgot password'.")
    public Map<String, Object> customerSendOtp(@RequestBody Map<String, String> body) {
        String identifier = body == null ? null : (body.get("email") != null ? body.get("email") : (body.get("mobile") != null ? body.get("mobile") : body.get("identifier")));
        return customerAuthService.sendOtp(identifier);
    }

    @PostMapping("/customer/forgot-password/reset")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Reset customer password with OTP",
            description = "Verifies the OTP against customer_users.otp_code, sets a new bcrypt password, and returns a fresh customer session.")
    public CustomerAuthResponse customerResetPassword(@RequestBody Map<String, String> body) {
        String identifier = body == null ? null : (body.get("email") != null ? body.get("email") : (body.get("mobile") != null ? body.get("mobile") : body.get("identifier")));
        String otp = body == null ? null : body.get("otp");
        String password = body == null ? null : body.get("password");
        return customerAuthService.resetPasswordWithOtp(identifier, otp, password);
    }

    @GetMapping("/customer-me")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Current customer profile",
            description = "Returns the customer profile for the JWT-bearer. No new token issued.")
    public CustomerAuthResponse customerMe(HttpServletRequest httpRequest) {
        UUID customerUserId = extractCustomerUserId(httpRequest);
        return customerAuthService.me(customerUserId);
    }

    /**
     * Resolve the caller and assert they hold one of {@code allowed}.
     *
     * This is the real enforcement point for account management: Spring Security
     * leaves /auth/** permitAll (SecurityConfig), so without this check any
     * caller with any token — or none — could flip another account's status.
     * Frontend button visibility is a convenience, never the control.
     *
     * 401 when we can't identify the caller, 403 when we can but they're the
     * wrong role.
     */
    private User requireRole(HttpServletRequest httpRequest, java.util.function.Predicate<String> allowed,
                             String requirement) {
        User caller = authService.requireUser(requireUserId(httpRequest));
        if (!allowed.test(caller.getRole()))
            throw new ForbiddenException(requirement);
        return caller;
    }

    /** Resolve the authenticated user id from the Bearer token (owner-scoped endpoints). */
    private UUID requireUserId(HttpServletRequest httpRequest) {
        String header = httpRequest.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer "))
            throw new UnauthorizedException("Missing or invalid Authorization header");
        String token = header.substring("Bearer ".length()).trim();
        try {
            return jwtService.getUserId(token);
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    private UUID extractCustomerUserId(HttpServletRequest httpRequest) {
        // Prefer userId previously set by an auth filter (request attribute).
        Object attr = httpRequest.getAttribute("userId");
        if (attr instanceof UUID uuid) return uuid;
        if (attr instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) { /* fall through */ }
        }
        // Fallback: parse Authorization: Bearer <jwt>
        String header = httpRequest.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer "))
            throw new UnauthorizedException("Missing or invalid Authorization header");
        String token = header.substring("Bearer ".length()).trim();
        try {
            return jwtService.getUserId(token);
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }
}
