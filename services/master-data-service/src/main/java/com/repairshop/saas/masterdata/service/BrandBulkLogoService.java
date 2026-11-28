package com.repairshop.saas.masterdata.service;

import com.repairshop.saas.common.media.MediaFileType;
import com.repairshop.saas.common.media.MediaUploadValidator;
import com.repairshop.saas.common.media.MediaValidationException;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.BrandMatch;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewItem;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewResponse;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.UploadResult;
import com.repairshop.saas.masterdata.dto.TaxonomyImageResponse;
import com.repairshop.saas.masterdata.entity.MasterBrand;
import com.repairshop.saas.masterdata.repository.MasterBrandRepository;
import com.repairshop.saas.masterdata.service.TaxonomyMediaService.LogoAlreadyPresentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bulk brand logo upload for the admin portal (Master Data → Brands).
 *
 * <h2>Split of work</h2>
 * The browser unpacks ZIPs, standardises every image to a 512 × 512 PNG on white
 * (logo fitted inside 384 × 384, centred) and uploads the files one by one with a
 * small concurrency limit. This service:
 * <ul>
 *   <li>matches file names to existing brands ({@link #preview}) — the single
 *       source of truth for the matching rule ({@link BrandLogoNames});</li>
 *   <li>re-validates each file strictly ({@link #standardizedPng}) — real PNG
 *       content, exactly 512 × 512 (read from the header before any pixel is
 *       decoded), re-encoded flat on white so nothing but pixels is stored;</li>
 *   <li>stores it through the same path as the single upload
 *       ({@link TaxonomyMediaService#storeBrandImage}): unique (versioned) S3 key,
 *       row updated only after the object exists, existing logo kept on failure;</li>
 *   <li>records every outcome in master_data_audit_log.</li>
 * </ul>
 * Brands are only ever updated by id: nothing is created, renamed or re-mapped,
 * and only image_url (plus clearing the legacy inline image) changes.
 */
@Service
public class BrandBulkLogoService {

    public static final int LOGO_SIZE = 512;
    public static final String MODE_MISSING_ONLY = "MISSING_ONLY";
    public static final String MODE_REPLACE = "REPLACE";

    private final MasterBrandRepository brandRepo;
    private final TaxonomyMediaService media;
    private final MediaUploadValidator validator;
    private final MasterAuditLog audit;
    private final int maxFiles;

    public BrandBulkLogoService(MasterBrandRepository brandRepo,
                                TaxonomyMediaService media,
                                MediaUploadValidator validator,
                                MasterAuditLog audit,
                                @Value("${master.bulk-logo.max-files:200}") int maxFiles) {
        this.brandRepo = brandRepo;
        this.media = media;
        this.validator = validator;
        this.audit = audit;
        this.maxFiles = Math.max(1, maxFiles);
    }

    // ── Preview ─────────────────────────────────────────────────────────────

    public PreviewResponse preview(List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            throw new MediaValidationException("Add at least one image to preview.");
        }
        if (fileNames.size() > maxFiles) {
            throw new MediaValidationException(
                    "A batch can hold at most %d images; this one has %d.".formatted(maxFiles, fileNames.size()));
        }
        Map<String, List<MasterBrand>> byKey = brandRepo.findAll().stream()
                .filter(b -> !BrandLogoNames.normalize(b.getName()).isEmpty())
                .collect(Collectors.groupingBy(b -> BrandLogoNames.normalize(b.getName())));

        Map<String, Integer> seen = new HashMap<>();
        List<PreviewItem> items = new ArrayList<>(fileNames.size());
        int matched = 0, ambiguous = 0, unmatched = 0, duplicates = 0;
        for (String raw : fileNames) {
            String fileName = BrandLogoNames.baseName(raw);
            String key = BrandLogoNames.fileKey(fileName);
            List<MasterBrand> hits = key.isEmpty() ? List.of() : byKey.getOrDefault(key, List.of());
            String status;
            if (!key.isEmpty() && seen.merge(key, 1, Integer::sum) > 1) {
                status = "DUPLICATE";
                duplicates++;
            } else if (hits.isEmpty()) {
                status = "UNMATCHED";
                unmatched++;
            } else if (hits.size() > 1) {
                status = "AMBIGUOUS";
                ambiguous++;
            } else {
                status = "MATCHED";
                matched++;
            }
            items.add(new PreviewItem(fileName, key, status, hits.stream()
                    .map(b -> new BrandMatch(b.getId(), b.getName(), b.getImageUrl(), TaxonomyMediaService.hasLogo(b)))
                    .toList()));
        }
        return new PreviewResponse(items, maxFiles, matched, ambiguous, unmatched, duplicates);
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    /**
     * Store one standardised logo on one brand.
     *
     * @param mode MISSING_ONLY (default) skips a brand that already has a logo —
     *             reported as SKIPPED, nothing changed; REPLACE overwrites it.
     * @throws MediaValidationException invalid file / brand / mode (HTTP 400)
     * @throws com.repairshop.saas.common.media.MediaStorageException S3 failure (HTTP 502);
     *         the brand keeps its current logo
     */
    public UploadResult upload(UUID brandId, MultipartFile file, String mode, String batchId,
                               String fileName, UUID actor) {
        boolean onlyIfMissing = onlyIfMissing(mode);
        MasterBrand brand = brandRepo.findById(brandId)
                .orElseThrow(() -> new MediaValidationException("No brand with id " + brandId + "."));
        String name = BrandLogoNames.baseName(fileName != null && !fileName.isBlank()
                ? fileName : file == null ? "" : file.getOriginalFilename());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("batchId", batch(batchId));
        details.put("fileName", name);
        details.put("mode", onlyIfMissing ? MODE_MISSING_ONLY : MODE_REPLACE);
        details.put("brandName", brand.getName());
        try {
            TaxonomyImageResponse r = media.storeBrandImage(brand, standardizedPng(file), onlyIfMissing);
            details.put("previousImageUrl", r.previousImageUrl());
            details.put("imageUrl", r.imageUrl());
            details.put("previousImageRemoved", r.previousImageRemoved());
            audit.record(actor, "BRAND_LOGO_UPLOADED", "BRAND", brandId, details);
            return new UploadResult(brandId, brand.getName(), name, "UPLOADED", r.imageUrl(),
                    r.previousImageUrl(), r.previousImageRemoved(), null);
        } catch (LogoAlreadyPresentException e) {
            details.put("imageUrl", brand.getImageUrl());
            audit.record(actor, "BRAND_LOGO_SKIPPED", "BRAND", brandId, details);
            return new UploadResult(brandId, brand.getName(), name, "SKIPPED", brand.getImageUrl(),
                    null, false, e.getMessage());
        } catch (RuntimeException e) {
            details.put("error", e.getMessage());
            audit.record(actor, "BRAND_LOGO_FAILED", "BRAND", brandId, details);
            throw e;
        }
    }

    static boolean onlyIfMissing(String mode) {
        if (mode == null || mode.isBlank() || MODE_MISSING_ONLY.equalsIgnoreCase(mode.trim())) return true;
        if (MODE_REPLACE.equalsIgnoreCase(mode.trim())) return false;
        throw new MediaValidationException("Unknown mode \"" + mode + "\" — use MISSING_ONLY or REPLACE.");
    }

    private static String batch(String batchId) {
        if (batchId == null || batchId.isBlank()) return null;
        try {
            return UUID.fromString(batchId.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The upload as a clean 512 × 512 PNG, or a 400.
     *
     * Size, magic bytes and the declared MIME type are checked by the shared
     * validator. The dimensions are then read from the PNG header BEFORE decoding,
     * so a small file declaring a huge canvas (a decompression bomb) is refused
     * without allocating it. The decoded pixels are flattened onto white and
     * re-encoded: transparency cannot leave a non-white background, and metadata
     * or bytes appended after the image are never stored.
     */
    MediaUploadValidator.ValidatedUpload standardizedPng(MultipartFile file) {
        MediaUploadValidator.ValidatedUpload v = validator.validateImage(file);
        if (v.type() != MediaFileType.PNG) {
            throw new MediaValidationException("Logos are uploaded as standardised PNG files (512 × 512); got "
                    + v.contentType() + ".");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(v.bytes()))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw unreadable();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w != LOGO_SIZE || h != LOGO_SIZE) {
                    throw new MediaValidationException("A logo must be exactly %d × %d pixels; this file is %d × %d."
                            .formatted(LOGO_SIZE, LOGO_SIZE, w, h));
                }
                BufferedImage src = reader.read(0);
                BufferedImage flat = new BufferedImage(LOGO_SIZE, LOGO_SIZE, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = flat.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, LOGO_SIZE, LOGO_SIZE);
                    g.drawImage(src, 0, 0, null);
                } finally {
                    g.dispose();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                if (!ImageIO.write(flat, "png", out)) throw unreadable();
                return new MediaUploadValidator.ValidatedUpload(MediaFileType.PNG, out.toByteArray(), v.originalName());
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof MediaValidationException mve) throw mve;
            throw unreadable();
        }
    }

    private static MediaValidationException unreadable() {
        return new MediaValidationException("The file is not a readable PNG image.");
    }
}
