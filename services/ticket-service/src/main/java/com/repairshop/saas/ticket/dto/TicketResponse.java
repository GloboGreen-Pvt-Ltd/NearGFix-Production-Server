package com.repairshop.saas.ticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Ticket response")
public class TicketResponse {

    @Schema(description = "Ticket ID")
    private UUID id;

    @Schema(description = "Shop ID")
    private UUID shopId;

    @Schema(description = "Customer ID")
    private UUID customerId;

    @Schema(description = "Customer name (denormalized)")
    private String customerName;

    @Schema(description = "Customer phone (denormalized)")
    private String customerPhone;

    @Schema(description = "Customer pickup/postal address (denormalized snapshot)")
    private String customerAddress;

    @Schema(description = "Assigned technician ID")
    private UUID assignedTechnicianId;

    @Schema(description = "Timestamp of the assigned technician's explicit accept action; NULL while awaiting acceptance")
    private java.time.Instant technicianAcceptedAt;

    @Schema(description = "Tracking ID")
    private String trackingId;

    @Schema(description = "Parent booking number shared by every device of the booking (GTS-001); " +
            "falls back to trackingId on older tickets")
    private String bookingNumber;

    @Schema(description = "Device suffix within a multi-device booking (A, B, C …); null for a single device")
    private String deviceSuffix;

    @Schema(description = "Brand ID")
    private UUID brandId;

    @Schema(description = "Model ID")
    private UUID modelId;

    @Schema(description = "RAM option ID")
    private UUID ramOptionId;

    @Schema(description = "Storage option ID")
    private UUID storageOptionId;

    @Schema(description = "Color")
    private String color;

    @Schema(description = "Device IMEI. Falls back to the linked repair_booking's imei when the ticket's own column is blank — the pickup person captures it on the booking row.")
    private String imei;

    @Schema(description = "Serial number — non-mobile devices; null when not captured")
    private String serialNumber;

    @Schema(description = "Accessories handed over with the device (charger, case, SIM tray …); optional")
    private String accessories;

    @Schema(description = "Status")
    private String status;

    @Schema(description = "Estimated price")
    private BigDecimal estimatedPrice;

    @Schema(description = "Final price")
    private BigDecimal finalPrice;

    @Schema(description = "Payment mode: ADVANCE, FULL, or null when nothing was collected")
    private String paymentType;

    @Schema(description = "Amount collected; null when nothing was collected")
    private BigDecimal paymentAmount;

    @Schema(description = "Still owed: applicable total minus amount collected, never negative")
    private BigDecimal balanceAmount;

    @Schema(description = "PAID once an amount is recorded, PENDING while nothing has been collected")
    private String paymentStatus;

    @Schema(description = "When the payment was recorded (server-stamped); null when nothing was collected")
    private Instant paymentPaidAt;

    // Re-Service chain (all null on an original service).
    private UUID parentTicketId;
    private UUID rootTicketId;
    private Integer serviceCycleNo;
    private String serviceType;
    private String returnReason;
    private String reServiceNotes;

    @Schema(description = "Issue description")
    private String issueDescription;

    @Schema(description = "Voice-note recording of the issue (hosted URL). Null when none.")
    private String issueAudioUrl;

    @Schema(description = "Created at")
    private Instant createdAt;

    @Schema(description = "Updated at")
    private Instant updatedAt;

    @Schema(description = "Device display name")
    private String deviceDisplayName;

    @Schema(description = "Device image URL")
    private String deviceImageUrl;

    @Schema(description = "Repair services summary")
    private String repairServicesSummary;

    @Schema(description = "Price items JSON")
    private String priceItemsJson;

    @Schema(description = "Missing parts JSON")
    private String missingPartsJson;

    @Schema(description = "Device photos JSON")
    private String devicePhotosJson;

    @Schema(description = "Technician-uploaded post-acceptance photos JSON (array of URLs)")
    private String technicianPhotosJson;

    @Schema(description = "Device security type")
    private String deviceSecurityType;

    @Schema(description = "Device security value")
    private String deviceSecurityValue;

    @Schema(description = "Customer repair approval flag")
    private Boolean customerApproval;

    @Schema(description = "Estimated ready at")
    private Instant estimatedReadyAt;

    @Schema(description = "Estimated delivery at")
    private Instant estimatedDeliveryAt;

    @Schema(description = "The booking's first estimated delivery, kept once a re-estimate revises estimatedDeliveryAt; null when never re-estimated")
    private Instant originalEstimatedDeliveryAt;

    @Schema(description = "Booked By: name of the employee/owner whose PIN was verified at submit; null for older bookings")
    private String bookedByName;

    @Schema(description = "Booked By role: EMPLOYEE or OWNER")
    private String bookedByRole;

    @Schema(description = "When the PIN-verified booking was submitted")
    private Instant bookedAt;

    @Schema(description = "Assigned technician name (denormalized for customer-side render)")
    private String assignedTechnicianName;

    @Schema(description = "Short uppercase technician code (first 8 chars of technician id)")
    private String assignedTechnicianCode;

    // ---- Re-estimate workflow (GET /tickets/{id} only; null on lists) ----

    @Schema(description = "Open re-estimate (REQUESTED / AWAITING_APPROVAL); null when none")
    private ReEstimateRevisionDtos.Revision activeReEstimate;

    @Schema(description = "Most recent re-estimate of any status; null when never re-estimated")
    private ReEstimateRevisionDtos.Revision latestReEstimate;

    // ---- Latest customer-visible "Issue Verified & Updated" note ----
    // Populated from repair_notes by TicketService.toResponse, taking the
    // most recent row with is_internal=false. Customer + owner detail
    // screens render these on the "Technician Issue Verified & Updated"
    // card so the verification is visible without a second API call.

    // ---- Invoice (invoices table, one row per ticket) ----
    // Carried on the ticket so a list row can answer "has this booking been
    // invoiced?" without a per-row GET /tickets/{id}/invoice. The bookings
    // list uses it for the card's Invoice action and the Invoice counter;
    // both therefore count real invoice rows, not a proxy status.

    @Schema(description = "Id of the invoice raised against this ticket; null when none has been generated")
    private UUID invoiceId;

    @Schema(description = "Invoice number of the invoice raised against this ticket; null when none has been generated")
    private String invoiceNo;

    @Schema(description = "When the invoice was generated; null when none has been generated")
    private Instant invoiceGeneratedAt;

    @Schema(description = "Invoice final payable amount (incl. GST); null when none has been generated")
    private BigDecimal invoiceAmount;

    @Schema(description = "Latest customer-visible compliance note text from repair_notes; null when none has been submitted")
    private String complianceNote;

    @Schema(description = "Hosted URL of the voice note attached to the latest compliance note; null when none")
    private String complianceAudioUrl;

    @Schema(description = "Image URLs attached to the latest compliance note; empty list when none")
    private List<String> complianceImageUrls;

    @Schema(description = "Timestamp at which the latest compliance note was created")
    private Instant complianceVerifiedAt;
}
