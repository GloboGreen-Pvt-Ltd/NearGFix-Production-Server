package com.repairshop.saas.masterdata.dto;

import java.util.List;
import java.util.UUID;

/** Request / response shapes of the bulk brand logo upload (BrandBulkLogoController). */
public final class BrandBulkLogoDtos {

    private BrandBulkLogoDtos() {}

    /** POST /master/brands/bulk-logo/preview */
    public record PreviewRequest(List<String> fileNames) {}

    public record BrandMatch(UUID id, String name, String imageUrl, boolean hasLogo) {}

    /**
     * One file. status: MATCHED (exactly one brand — preselect it), AMBIGUOUS
     * (several brands normalise to this name — the admin picks), UNMATCHED, or
     * DUPLICATE (an earlier file in the batch has the same normalised name).
     */
    public record PreviewItem(String fileName, String normalizedName, String status, List<BrandMatch> matches) {}

    public record PreviewResponse(List<PreviewItem> items, int maxFiles,
                                  int matched, int ambiguous, int unmatched, int duplicates) {}

    /** POST /master/brands/bulk-logo/{brandId}. status: UPLOADED or SKIPPED (failures are HTTP errors). */
    public record UploadResult(UUID brandId, String brandName, String fileName, String status,
                               String imageUrl, String previousImageUrl, boolean previousImageRemoved,
                               String message) {}
}
