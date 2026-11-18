package com.repairshop.saas.marketplace.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
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
                // Device sessions (migration 123): revoked / OTP-expired sessions are unauthenticated;
                // with app.security.require-device-session=true legacy tokens without "sid" are too.
                Object sid = jwtService.parseClaims(token).get("sid");
                if (sid == null ? requireDeviceSession : jdbc.queryForList(
                        "SELECT 1 FROM auth_device_sessions WHERE id = ?::uuid AND revoked_at IS NULL AND otp_expires_at > now()",
                        sid.toString()).isEmpty()) throw new IllegalStateException("SESSION_REVOKED");
                UUID userId = jwtService.getUserId(token);
                UUID shopId = jwtService.getShopId(token); // may be null for customer tokens
                List<String> roles = jwtService.getRoles(token);
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .collect(Collectors.toList());
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userId.toString(), null, authorities);
                if (shopId != null) request.setAttribute("shopId", shopId.toString());
                request.setAttribute("userId", userId.toString());
                String loginScope = jwtService.getLoginScope(token);
                if (loginScope != null) request.setAttribute("loginScope", loginScope);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // Invalid token – continue without auth
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
}
