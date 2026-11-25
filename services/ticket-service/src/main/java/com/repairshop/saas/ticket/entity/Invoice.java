package com.repairshop.saas.ticket.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Snapshot of the Invoice Generator form for a ticket. One row per ticket
 * (unique index on ticket_id) — re-generating updates the same row so the
 * owner can correct mistakes without leaving stale invoice numbers behind.
 * See migration 58_invoices.sql for column comments.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "shop_id")
    private UUID shopId;

    @Column(name = "invoice_no", nullable = false, length = 80)
    private String invoiceNo;

    @Column(name = "ticket_date")
    private Instant ticketDate;

    @Column(name = "delivery_date")
    private Instant deliveryDate;

    @Column(name = "gst_no", length = 50)
    private String gstNo;

    // ── Inputs from the Invoice Generator form ──
    @Column(name = "service_charges", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal serviceCharges = BigDecimal.ZERO;

    @Column(name = "total_repair_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalRepairAmount = BigDecimal.ZERO;

    @Column(name = "spare_utility_charge", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal spareUtilityCharge = BigDecimal.ZERO;

    @Column(name = "discount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    /** WITHOUT | INCLUSIVE | EXCLUSIVE */
    @Column(name = "tax_mode", nullable = false, length = 16)
    @Builder.Default
    private String taxMode = "WITHOUT";

    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal gstPercent = BigDecimal.ZERO;

    // ── Computed totals (client-sent, server-persisted) ──
    @Column(name = "amount_2_plus_3", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal amount2Plus3 = BigDecimal.ZERO;

    @Column(name = "base_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal baseAmount = BigDecimal.ZERO;

    @Column(name = "total_gst", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalGst = BigDecimal.ZERO;

    @Column(name = "final_payable_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal finalPayableAmount = BigDecimal.ZERO;

    @Column(name = "amount_in_words", columnDefinition = "TEXT")
    private String amountInWords;

    // ── Payment & credit (migration 89) ──
    // final − advance = net; net − paid = credit. Stored rather than derived
    // because an invoice is a document: it has to reprint the same figures next
    // year even if the ticket's advance or the tax rules have moved since.

    /** Collected before this bill — normally the booking deposit on the ticket. */
    @Column(name = "advance_paid", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal advancePaid = BigDecimal.ZERO;

    @Column(name = "net_payable_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal netPayableAmount = BigDecimal.ZERO;

    /** Handed over at the counter now. Equals the net on a full payment. */
    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /** Split of amountPaid by method (migration 120). 0 / 0 = method not recorded. */
    @Column(name = "cash_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal cashAmount = BigDecimal.ZERO;

    /** UPI the shop confirmed it received; not a payment-gateway result. */
    @Column(name = "upi_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal upiAmount = BigDecimal.ZERO;

    /** What the customer still owes — mirrored into the Cash Book, see below. */
    @Column(name = "credit_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "payment_note", length = 500)
    private String paymentNote;

    /** Counter day, not an instant — same rule as ShopLedgerEntry.entryDate. */
    @Column(name = "payment_date")
    private LocalDate paymentDate;

    /** The Cash Book customer account the credit was posted to (migration 81). */
    @Column(name = "credit_party_id")
    private UUID creditPartyId;

    /**
     * The shop_ledger_entries row mirroring {@link #creditAmount}.
     *
     * Held so re-generating this invoice UPDATES that row. Without it every
     * re-generation would post a second GIVEN entry and silently double the
     * customer's debt — and a credit later cleared to zero could never find the
     * row it needs to delete.
     */
    @Column(name = "credit_ledger_entry_id")
    private UUID creditLedgerEntryId;

    // ── Line item snapshots ──
    @Column(name = "spare_lines_json", columnDefinition = "TEXT")
    private String spareLinesJson;

    @Column(name = "service_lines_json", columnDefinition = "TEXT")
    private String serviceLinesJson;

    // ── Audit ──
    @Column(name = "generated_by")
    private UUID generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (generatedAt == null) generatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
