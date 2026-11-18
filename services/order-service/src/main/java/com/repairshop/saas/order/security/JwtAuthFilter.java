package com.repairshop.saas.order.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${app.security.require-device-session:${REQUIRE_DEVICE_SESSION:false}}")
    private boolean requireDeviceSession;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtService.parseClaims(token);
                // Device sessions (migration 123): revoked / OTP-expired sessions are unauthenticated;
                // with app.security.require-device-session=true legacy tokens without "sid" are too.
                Object sid = claims.get("sid");
                if (sid == null ? requireDeviceSession : jdbc.queryForList(
                        "SELECT 1 FROM auth_device_sessions WHERE id = ?::uuid AND revoked_at IS NULL AND otp_expires_at > now()",
                        sid.toString()).isEmpty()) throw new IllegalStateException("SESSION_REVOKED");
                UUID userId = UUID.fromString(claims.getSubject());
                UUID shopId = parseUuid(claims.get("shopId", String.class));
                List<String> roles = rolesFromClaims(claims);
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .collect(Collectors.toList());
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userId.toString(), null, authorities);
                if (shopId != null) request.setAttribute("shopId", shopId.toString());
                request.setAttribute("userId", userId.toString());
                request.setAttribute("roles", roles);
                // OWNER | SHOP (shop-mobile login). Recorded with a push token so
                // a registration says which kind of session made it.
                String loginScope = claims.get("loginScope", String.class);
                if (StringUtils.hasText(loginScope)) request.setAttribute("loginScope", loginScope);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // Match ticket-service / shop-service behavior: silently ignore
                // unparseable tokens and let the request continue without auth
                // context. Controllers that need a caller will then throw 403
                // (Forbidden) via their own checks. The previous behavior of
                // returning 401 here was harmful: the employee-app client treats
                // 401 as "session dead" and bounces the user to Login, so a
                // single 401 from this service would log the user out app-wide
                // even when the rest of the app is healthy on the same token.
                // WARN (not DEBUG) so the root cause is visible without changing
                // log levels. Common reasons: signature mismatch (auth-service
                // and order-service were started with different JWT_SECRET env
                // values) or expired token (>24h since login).
                log.warn("JWT parse failed on {}: {}", request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer "))
            return bearer.substring(7);
        return null;
    }

    private UUID parseUuid(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try { return UUID.fromString(raw); } catch (Exception e) { return null; }
    }

    private List<String> rolesFromClaims(Claims claims) {
        List<?> raw = claims.get("roles", List.class);
        if (raw == null) return List.of();
        return raw.stream()
                .map(Object::toString)
                .map(this::normalizeRole)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String normalizeRole(String role) {
        if (role == null) return "";
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }
}
