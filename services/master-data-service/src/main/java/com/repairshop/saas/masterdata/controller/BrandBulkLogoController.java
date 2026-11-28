package com.repairshop.saas.masterdata.controller;

import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewRequest;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewResponse;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.UploadResult;
import com.repairshop.saas.masterdata.service.BrandBulkLogoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Bulk brand logo upload — admin portal, Master Data → Brands. Platform admins
 * only (SecurityConfig: SUPER_ADMIN / ADMIN).
 *
 * <pre>
 * POST /master/brands/bulk-logo/preview          application/json
 *   { "fileNames": ["Samsung.png", "one_plus.webp", …] }
 *   → { items: [{ fileName, normalizedName, status, matches: [{ id, name, imageUrl, hasLogo }] }],
 *       maxFiles, matched, ambiguous, unmatched, duplicates }
 *   status: MATCHED | AMBIGUOUS | UNMATCHED | DUPLICATE
 *
 * POST /master/brands/bulk-logo/{brandId}        multipart/form-data
 *   image     file    standardised PNG, exactly 512 × 512
 *   mode      text    MISSING_ONLY (default) | REPLACE
 *   batchId   text    optional UUID grouping one batch in the audit log
 *   fileName  text    optional original file name (e.g. "Samsung.webp") for the audit log
 *   → 200 { brandId, brandName, fileName, status: UPLOADED | SKIPPED, imageUrl,
 *           previousImageUrl, previousImageRemoved, message }
 *   400 invalid file / brand / mode · 413 too large · 502 storage failure — the
 *   brand keeps its current logo in every failure case.
 * </pre>
 */
@RestController
@RequestMapping("/master/brands/bulk-logo")
public class BrandBulkLogoController {

    private final BrandBulkLogoService service;

    public BrandBulkLogoController(BrandBulkLogoService service) {
        this.service = service;
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PreviewResponse preview(@RequestBody PreviewRequest body) {
        return service.preview(body == null ? null : body.fileNames());
    }

    @PostMapping(value = "/{brandId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@PathVariable UUID brandId,
                               @RequestParam("image") MultipartFile image,
                               @RequestParam(value = "mode", required = false) String mode,
                               @RequestParam(value = "batchId", required = false) String batchId,
                               @RequestParam(value = "fileName", required = false) String fileName,
                               HttpServletRequest req) {
        return service.upload(brandId, image, mode, batchId, fileName, actor(req));
    }

    private static UUID actor(HttpServletRequest req) {
        Object v = req.getAttribute("userId");
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
