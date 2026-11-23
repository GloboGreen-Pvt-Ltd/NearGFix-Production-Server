package com.repairshop.saas.order.exception;

import com.repairshop.saas.common.subscription.SubscriptionLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden", "message", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "conflict", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "bad_request", "message", e.getMessage()));
    }

    /**
     * Plan allowance exhausted, or the subscription behind it has lapsed. 409
     * with the whole limit calculation inline, matching the handlers in
     * ticket-service and auth-service so a client can treat a subscription
     * refusal identically wherever it comes from.
     */
    @ExceptionHandler(SubscriptionLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> subscriptionLimit(
            SubscriptionLimitExceededException e, HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.putAll(e.toBody());
        body.put("path", req.getRequestURI());
        log.info("Subscription limit blocked {} {} — {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Unmapped route — typically the JVM is running stale code that doesn't
     * yet have a recently-added @RequestMapping. Surface this clearly so the
     * client knows it's a deploy / restart issue, not a request-shape issue.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noResource(NoResourceFoundException e, HttpServletRequest req) {
        String path = req.getRequestURI();
        log.warn("404 no resource for {} {} — controller route not registered (rebuild + restart this JVM if you just added the endpoint)",
                req.getMethod(), path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "not_found",
                "message", "No handler for " + req.getMethod() + " " + path
                        + " — verify the URL or restart this service if the endpoint was just added"));
    }

    /**
     * Keeps the status of a ResponseStatusException (e.g. TpinGuard: 403 TPIN_REQUIRED /
     * "TPIN authorization invalid, expired or already used", 401 SESSION_REQUIRED) instead
     * of the catch-all turning it into a 500. Same body shape as the other handlers.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(org.springframework.web.server.ResponseStatusException e, HttpServletRequest req) {
        log.info("{} {} -> {} {}", req.getMethod(), req.getRequestURI(), e.getStatusCode().value(), e.getReason());
        String reason = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "error", e.getStatusCode().is4xxClientError() ? "request_rejected" : "error",
                "message", reason));
    }

    /**
     * Catch-all so an unexpected throw doesn't produce an opaque 500 with
     * no body. The stack trace is logged at ERROR so the operator can
     * actually diagnose the failure; the response carries the exception
     * class name + message so the mobile client can show it to the user.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> generic(Exception e, HttpServletRequest req) {
        log.error("500 unhandled exception for {} {}: {}",
                req.getMethod(), req.getRequestURI(), e.getMessage(), e);
        String detail = e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "internal_error",
                "message", detail));
    }
}
