package com.repairshop.saas.ticket.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Payload from the Invoice Generator screen. Client computes the totals
 * (amount2Plus3, baseAmount, totalGst, finalPayableAmount) using the same
 * rounding the report screen displays and sends them straight through —
 * the server only validates non-null and persists, so the visual numbers
 * the owner saw at "Invoice Generated" are byte-stable for the report.
 */
@Data
public class InvoiceRequest {
    private String invoiceNo;
    private Instant ticketDate;
    private Instant deliveryDate;
    private String gstNo;

    // Inputs
    private BigDecimal serviceCharges;
    private BigDecimal totalRepairAmount;
    private BigDecimal spareUtilityCharge;
    private BigDecimal discount;
    private String taxMode;         // WITHOUT | INCLUSIVE | EXCLUSIVE
    private BigDecimal gstPercent;

    // Computed totals
    private BigDecimal amount2Plus3;
    private BigDecimal baseAmount;
    private BigDecimal totalGst;
    private BigDecimal finalPayableAmount;
    private String amountInWords;

    // Snapshot line arrays as JSON strings (client serializes).
    private String spareLinesJson;
    private String serviceLinesJson;

    /*
     * Payment & credit (migration 89).
     *
     * Unlike the totals above, net and credit are NOT taken on trust: the server
     * re-derives both from finalPayableAmount / advancePaid / amountPaid before
     * it writes. These two carry a debt onto a customer's Cash Book account, and
     * a figure that only the client computed could put a number there that the
     * invoice above it never justified.
     */
    private BigDecimal advancePaid;
    private BigDecimal netPayableAmount;
    private BigDecimal amountPaid;
    /**
     * Split payment (migration 128). When either is sent, amountPaid is
     * cashAmount + upiAmount; each must be >= 0 and their sum must not exceed
     * the net payable (400 otherwise). Older apps send only amountPaid.
     */
    private BigDecimal cashAmount;
    private BigDecimal upiAmount;
    /**
     * Required (true) with cashAmount / upiAmount when a balance remains;
     * otherwise 409 CREDIT_CONFIRMATION_REQUIRED. Ignored by the legacy
     * amountPaid-only request, which still records credit without it.
     */
    private Boolean creditConfirmed;
    private BigDecimal creditAmount;
    private String paymentNote;
    private LocalDate paymentDate;
}
