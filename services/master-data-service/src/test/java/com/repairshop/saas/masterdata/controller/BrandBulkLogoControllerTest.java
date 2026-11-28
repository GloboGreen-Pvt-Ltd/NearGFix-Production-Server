package com.repairshop.saas.masterdata.controller;

import com.repairshop.saas.common.media.MediaExceptionHandler;
import com.repairshop.saas.common.media.MediaStorageException;
import com.repairshop.saas.common.media.MediaValidationException;
import com.repairshop.saas.masterdata.config.SecurityConfig;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewResponse;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.UploadResult;
import com.repairshop.saas.masterdata.security.JwtAuthFilter;
import com.repairshop.saas.masterdata.security.JwtService;
import com.repairshop.saas.masterdata.service.BrandBulkLogoService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing, admin-only access and error mapping of the bulk logo endpoints (real security chain). */
@WebMvcTest
@ContextConfiguration(classes = {BrandBulkLogoController.class, SecurityConfig.class, JwtAuthFilter.class,
        JwtService.class, MediaExceptionHandler.class})
@TestPropertySource(properties = "app.jwt.secret=" + BrandBulkLogoControllerTest.SECRET)
class BrandBulkLogoControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired MockMvc mvc;
    @MockitoBean BrandBulkLogoService service;
    // JwtAuthFilter's device-session lookup; these test tokens carry no session id, so it is never queried.
    @MockitoBean org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final UUID admin = UUID.randomUUID();
    private final UUID brand = UUID.randomUUID();

    private static String token(UUID user, String role) {
        return "Bearer " + Jwts.builder().subject(user.toString()).claim("roles", List.of(role))
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("image", "samsung.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void previewIsForAdminsOnly() throws Exception {
        when(service.preview(any())).thenReturn(new PreviewResponse(List.of(), 200, 0, 0, 0, 0));
        String body = "{\"fileNames\":[\"Samsung.png\"]}";

        mvc.perform(post("/master/brands/bulk-logo/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/master/brands/bulk-logo/preview").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token(UUID.randomUUID(), "SHOP_OWNER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/master/brands/bulk-logo/preview").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxFiles").value(200));
        verify(service).preview(List.of("Samsung.png"));
    }

    @Test
    void uploadBindsTheFormAndTheAdminAsActor() throws Exception {
        when(service.upload(eq(brand), any(), eq("REPLACE"), eq("b-1"), eq("Samsung.webp"), eq(admin)))
                .thenReturn(new UploadResult(brand, "Samsung", "Samsung.webp", "UPLOADED",
                        "https://cdn.neargfix.com/master/brands/samsung-1.png", null, false, null));

        mvc.perform(multipart("/master/brands/bulk-logo/{id}", brand).file(image())
                        .param("mode", "REPLACE").param("batchId", "b-1").param("fileName", "Samsung.webp")
                        .header("Authorization", token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.brandName").value("Samsung"));
    }

    @Test
    void uploadIsRefusedForNonAdmins() throws Exception {
        mvc.perform(multipart("/master/brands/bulk-logo/{id}", brand).file(image())
                        .header("Authorization", token(UUID.randomUUID(), "EMPLOYEE")))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/master/brands/bulk-logo/{id}", brand).file(image()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void failuresMapToClearStatusCodes() throws Exception {
        when(service.upload(any(), any(), any(), any(), any(), any()))
                .thenThrow(new MediaValidationException("A logo must be exactly 512 × 512 pixels; this file is 10 × 10."))
                .thenThrow(new MediaStorageException("S3 unavailable"));

        mvc.perform(multipart("/master/brands/bulk-logo/{id}", brand).file(image())
                        .header("Authorization", token(admin, "SUPER_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A logo must be exactly 512 × 512 pixels; this file is 10 × 10."));
        mvc.perform(multipart("/master/brands/bulk-logo/{id}", brand).file(image())
                        .header("Authorization", token(admin, "SUPER_ADMIN")))
                .andExpect(status().isBadGateway());
    }
}
