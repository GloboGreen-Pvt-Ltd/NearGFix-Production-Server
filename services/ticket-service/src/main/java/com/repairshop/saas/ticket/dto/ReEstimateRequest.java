package com.repairshop.saas.ticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * POST /tickets/{id}/re-estimate — ADDITIONAL issues found during repair.
 * The original line items and their prices are never sent or changed; these
 * items are appended to them.
 */
@Data
@Schema(description = "Re-estimate: additional repair issues + charges for an existing ticket")
public class ReEstimateRequest {

    @Schema(description = "Additional issues (at least one new one)")
    private List<Item> items;

    @Schema(description = "Why the extra work is needed — stored as its own re-estimate note")
    private String reason;

    @Schema(description = "Supporting photo / video URLs (JSON array) for this re-estimate")
    private String imagesJson;

    @Schema(description = "Proposed new estimated delivery; omit to keep the current one")
    private Instant estimatedDeliveryAt;

    @Schema(description = "true = the customer approved the revised estimate at the counter; otherwise it waits for approval")
    private Boolean customerApproved;

    @Schema(description = "Technician re-estimate request this answers (REQUESTED revision id); omit when re-estimating on the shop's own")
    private java.util.UUID requestId;

    @Schema(description = "Identities (id / code / label) of ORIGINAL line items the revision drops; restored if the customer rejects")
    private List<String> removedItemIds;

    @Data
    public static class Item {
        @Schema(description = "Catalogue service id (null for a custom issue)")
        private String id;
        private String code;
        private String label;
        @Schema(description = "Additional price — required, 0 or more")
        private BigDecimal amount;
        @Schema(description = "Warranty text, e.g. \"3 Months\"")
        private String warranty;
    }
}
