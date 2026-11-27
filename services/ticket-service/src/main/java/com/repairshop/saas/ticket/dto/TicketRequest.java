package com.repairshop.saas.ticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create or update ticket")
public class TicketRequest {

    @NotNull
    @Schema(description = "Customer ID", required = true)
    private UUID customerId;

    @Schema(description = "Customer name (denormalized for booking list)")
    private String customerName;

    @Schema(description = "Customer phone (denormalized for booking list)")
    private String customerPhone;

    @Schema(description = "Device brand ID (master_brands)")
    private UUID brandId;

    @Schema(description = "Device model ID (master_models)")
    private UUID modelId;

    @Schema(description = "RAM option ID")
    private UUID ramOptionId;

    @Schema(description = "Storage option ID")
    private UUID storageOptionId;

    @Schema(description = "Device color")
    private String color;

    @Schema(description = "IMEI")
    private String imei;

    @Schema(description = "Serial number — non-mobile devices (laptop, tablet, smartwatch, audio); optional")
    @Size(max = 64)
    private String serialNumber;

    @Schema(description = "Accessories handed over with the device (charger, case, SIM tray …); optional")
    @Size(max = 500)
    private String accessories;

    @Schema(description = "Issue description")
    private String issueDescription;

    @Schema(description = "Hosted URL of the customer's voice-note recording of the issue " +
            "(Cloudinary). Optional — the booking flow accepts text, audio, or both.")
    private String issueAudioUrl;

    @Schema(description = "Estimated price")
    private BigDecimal estimatedPrice;

    @Schema(description = "Payment mode: ADVANCE or FULL. Anything else (including null/blank) " +
            "is stored as null = nothing collected.")
    private String paymentType;

    @Schema(description = "Amount collected. Ignored when paymentType is absent; rejected with 400 " +
            "when it exceeds the applicable total (final price when set, else estimated price).")
    private BigDecimal paymentAmount;

    // paymentStatus, balanceAmount and paymentPaidAt are deliberately absent:
    // all three are derived server-side from the pair above plus the ticket's
    // price. A client that could set them could report money as PAID without
    // an amount, or back-date when it was taken.

    @Schema(description = "Device display name override")
    private String deviceDisplayName;

    @Schema(description = "Device image URL")
    private String deviceImageUrl;

    @Schema(description = "Repair services summary text")
    private String repairServicesSummary;

    @Schema(description = "Price items JSON (array of {label, amount})")
    private String priceItemsJson;

    @Schema(description = "Missing / damaged parts JSON")
    private String missingPartsJson;

    @Schema(description = "Device photos metadata JSON")
    private String devicePhotosJson;

    @Schema(description = "Device security type (NONE, PATTERN, PIN, PASSWORD)")
    private String deviceSecurityType;

    @Schema(description = "Device security value (pattern, PIN, password)")
    private String deviceSecurityValue;

    @Schema(description = "Customer repair approval flag")
    private Boolean customerApproval;

    @Schema(description = "Update only: which action saved this edit. true = Re-Estimate (a service / price "
            + "change re-quotes the booking: status QUOTED, \"Service Re-estimated\" step). false = Edit Booking / "
            + "Price Revised / Re-Service: the ticket status and approval are left exactly as they are. "
            + "Omitted (older apps) = a service / price change is treated as a re-estimate.")
    private Boolean reEstimate;

    @Schema(description = "Estimated ready at (start of repair window)")
    private Instant estimatedReadyAt;

    @Schema(description = "Estimated delivery at (when device will be ready)")
    private Instant estimatedDeliveryAt;

    // Create-only. Neither is read by update — a device's ticket id never changes.

    @Schema(description = "Create only: first device of a multi-device booking. A new booking number " +
            "is drawn and this device gets suffix A (GTS-001-A). Ignored when bookingNumber is set.")
    private Boolean multiDevice;

    @Schema(description = "Create only: add this device to an existing booking (e.g. GTS-001). It gets " +
            "the next free suffix (GTS-001-B …) without consuming a new booking number.")
    private String bookingNumber;

    @Schema(description = "Create only: the customer agreed to receive the booking confirmation on WhatsApp. " +
            "Absent / false = no WhatsApp message (older app builds).")
    private Boolean whatsappOptIn;

    // ── Re-Service (create only) ──────────────────────────────────────────────
    @Schema(description = "Create only: id of the DELIVERED service this Re-Service follows. A new linked " +
            "ticket (GTS-231-R01 …) is created; the delivered ticket is never changed.")
    private UUID reServiceOf;

    @Schema(description = "Re-Service type: WARRANTY | PAID | FREE_REWORK")
    private String serviceType;

    @Schema(description = "Return reason: SAME_ISSUE | NEW_ISSUE | NOT_RESOLVED | REPEATED | OTHER")
    private String returnReason;

    @Schema(description = "JSON: complaint, condition, inspectionNotes, remarks")
    private String reServiceNotes;

    @Schema(description = "Client idempotency key; a retry with the same key returns the same Re-Service")
    private String reServiceRequestId;
}
