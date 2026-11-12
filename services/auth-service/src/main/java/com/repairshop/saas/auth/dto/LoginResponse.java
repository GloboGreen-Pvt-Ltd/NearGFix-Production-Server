package com.repairshop.saas.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Login response with JWT and user info")
public class LoginResponse {

    @Schema(description = "JWT access token")
    private String accessToken;

    @Schema(description = "Opaque refresh token for POST /auth/refresh (rotated on every use)")
    private String refreshToken;

    @Schema(description = "Refresh token expiry in seconds")
    private Long refreshExpiresIn;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Token expiry in seconds")
    private Long expiresIn;

    @Schema(description = "User ID")
    private String userId;

    @Schema(description = "Shop ID (tenant)")
    private String shopId;

    @Schema(description = "User email")
    private String email;

    @Schema(description = "User display name")
    private String name;

    @Schema(description = "User roles")
    private List<String> roles;

    @Schema(description = "Friendly role label for employee-app UI routing (Technician / Staff / Pickup Person / Shop Owner)")
    private String roleLabel;

    @Schema(description = "Display name of the active shop (for the JWT shopId)")
    private String shopName;

    @Schema(description = "All shops the user can access (SHOP_OWNER only). One of these is the active shop matching shopId.")
    private List<ShopAccess> shops;

    @Schema(description = "Login scope: OWNER (multi-shop, can switch) or SHOP (single-shop, locked to shopId). Drives client switcher visibility.",
            example = "OWNER")
    private String loginScope;

    @Schema(description = "Authoritative login type used by clients to route the user. " +
            "SUPER_ADMIN → admin dashboard; SHOP_OWNER → owner home with shop switcher; " +
            "SHOP_LOGIN → single-shop home (no switcher); EMPLOYEE → employee home.",
            example = "SHOP_OWNER",
            allowableValues = {"SUPER_ADMIN", "SHOP_OWNER", "SHOP_LOGIN", "EMPLOYEE"})
    private String loginType;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ShopAccess {
        private String id;
        private String name;
        private String slug;
        private Boolean isActive;
    }
}
