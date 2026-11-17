package com.repairshop.saas.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Places (New) proxy for the mobile apps. The apps' Maps key is
 * Android-restricted (Maps SDK only) and Google rejects it on the Places REST
 * API, so search goes through here with a separate server-restricted key
 * (env GOOGLE_PLACES_SERVER_API_KEY) that never reaches the phone.
 * Public (/auth/** is permitAll) because Partner sign-up uses it before login,
 * so it is rate-limited per client IP and only returns a small, fixed field set.
 */
@RestController
@RequestMapping("/auth/places")
public class PlacesController {

    private static final int MAX_PER_MINUTE = 60;
    private static final String DETAIL_FIELDS = "id,displayName,formattedAddress,location,addressComponents,nationalPhoneNumber";

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, long[]> hits = new ConcurrentHashMap<>(); // ip -> [windowStartMs, count]

    public PlacesController(@Value("${GOOGLE_PLACES_SERVER_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @GetMapping("/autocomplete")
    public Map<String, Object> autocomplete(@RequestParam("q") String q,
                                            @RequestParam(value = "session", required = false) String session,
                                            @RequestParam(value = "business", defaultValue = "false") boolean business,
                                            HttpServletRequest req) {
        guard(req);
        String input = q == null ? "" : q.trim();
        if (input.length() < 3 || input.length() > 120) return Map.of("suggestions", List.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input);
        body.put("includedRegionCodes", List.of("in"));
        body.put("languageCode", "en");
        if (business) body.put("includedPrimaryTypes", List.of("establishment"));
        if (validSession(session)) body.put("sessionToken", session);
        JsonNode r = call(HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/places:autocomplete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode s : r.path("suggestions")) {
            JsonNode p = s.path("placePrediction");
            if (p.isMissingNode() || !p.hasNonNull("placeId")) continue;
            out.add(Map.of(
                    "placeId", p.path("placeId").asText(),
                    "main", p.path("structuredFormat").path("mainText").path("text").asText(p.path("text").path("text").asText("")),
                    "secondary", p.path("structuredFormat").path("secondaryText").path("text").asText("")));
        }
        return Map.of("suggestions", out);
    }

    @GetMapping("/details/{placeId}")
    public Map<String, Object> details(@PathVariable String placeId,
                                       @RequestParam(value = "session", required = false) String session,
                                       HttpServletRequest req) {
        guard(req);
        if (placeId == null || !placeId.matches("[A-Za-z0-9_-]{10,300}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid place");
        String url = "https://places.googleapis.com/v1/places/" + placeId
                + (validSession(session) ? "?sessionToken=" + URLEncoder.encode(session, StandardCharsets.UTF_8) : "");
        JsonNode p = call(HttpRequest.newBuilder(URI.create(url)).header("X-Goog-FieldMask", DETAIL_FIELDS).GET());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placeId", p.path("id").asText(placeId));
        out.put("name", p.path("displayName").path("text").asText(""));
        out.put("address", p.path("formattedAddress").asText(""));
        out.put("latitude", p.path("location").has("latitude") ? p.path("location").path("latitude").asDouble() : null);
        out.put("longitude", p.path("location").has("longitude") ? p.path("location").path("longitude").asDouble() : null);
        out.put("phone", p.path("nationalPhoneNumber").asText("").replaceAll("\\D", ""));
        String pincode = "", city = "", district = "", state = "", area = "";
        for (JsonNode c : p.path("addressComponents")) {
            String t = c.path("types").toString(), v = c.path("longText").asText("");
            if (t.contains("\"postal_code\"")) pincode = v;
            else if (t.contains("\"locality\"")) city = v;
            else if (t.contains("\"administrative_area_level_3\"") || t.contains("\"administrative_area_level_2\"")) { if (district.isEmpty()) district = v; }
            else if (t.contains("\"administrative_area_level_1\"")) state = v;
            else if (t.contains("\"sublocality") && area.isEmpty()) area = v;
        }
        out.put("pincode", pincode); out.put("city", city); out.put("district", district.isEmpty() ? city : district);
        out.put("state", state); out.put("area", area);
        return out;
    }

    private static boolean validSession(String s) { return s != null && s.matches("[A-Za-z0-9_.-]{8,80}"); }

    private void guard(HttpServletRequest req) {
        if (apiKey.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google search is not configured");
        String fwd = req.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
        long now = System.currentTimeMillis();
        long[] w = hits.compute(ip, (k, v) -> (v == null || now - v[0] > 60_000) ? new long[]{now, 1} : new long[]{v[0], v[1] + 1});
        if (w[1] > MAX_PER_MINUTE) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many searches. Please wait a minute.");
        if (hits.size() > 10_000) hits.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
    }

    private JsonNode call(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.header("X-Goog-Api-Key", apiKey).timeout(Duration.ofSeconds(8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google search failed");
            return json.readTree(r.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google search failed");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google search failed");
        }
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
