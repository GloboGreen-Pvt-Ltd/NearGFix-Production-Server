package com.repairshop.saas.order.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Requires a one-time TPIN authorization on protected business routes.
 * Route spec: "METHOD /ant/path/** = ACTION_TYPE" (app.security.tpin-routes).
 * The client authorizes with contextHash = sha256hex(METHOD + " " + requestURI).
 */
@Configuration
public class TpinInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final TpinGuard guard;
    private final List<String> routes;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public TpinInterceptor(TpinGuard guard, @Value("${app.security.tpin-routes:}") List<String> routes) {
        this.guard = guard;
        this.routes = routes;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        for (String r : routes) {
            String[] kv = r.split("=", 2);
            String[] mp = kv[0].trim().split("\\s+", 2);
            if (kv.length == 2 && mp.length == 2 && mp[0].equalsIgnoreCase(req.getMethod()) && matcher.match(mp[1], path)) {
                guard.consume(req, kv[1].trim(), null, sha256(req.getMethod().toUpperCase() + " " + req.getRequestURI()));
                break;
            }
        }
        return true;
    }

    public static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
