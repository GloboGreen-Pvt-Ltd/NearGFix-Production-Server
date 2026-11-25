package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.InvoiceRequest;
import com.repairshop.saas.ticket.dto.InvoiceResponse;
import com.repairshop.saas.ticket.entity.Invoice;
import com.repairshop.saas.ticket.entity.ShopLedgerEntry;
import com.repairshop.saas.ticket.entity.ShopLedgerParty;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.repository.InvoiceRepository;
import com.repairshop.saas.ticket.repository.ShopLedgerEntryRepository;
import com.repairshop.saas.ticket.repository.ShopLedgerPartyRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Upsert + read of an invoice tied to a ticket. One row per ticket
 * (uq_invoices_ticket_id) — re-generating overwrites the same row so the
 * owner can correct mistakes without leaving phantom invoice numbers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    /**
     * The counter's calendar, not the JVM's. Production runs on a UTC box, so an
     * evening bill would otherwise file its credit under the previous day in the
     * Cash Book — the same trap the attendance clock hit.
     */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String CUSTOMER = "CUSTOMER";

    /** The party owes the shop — see migration 82's sign convention. */
    private static final String GIVEN = "GIVEN";

    private static final int PHONE_DIGITS = 10;
    private static final int MAX_NOTE = 500;
    private static final int MAX_NAME = 120;

    private final InvoiceRepository invoiceRepository;
    private final TicketRepository ticketRepository;
    private final ShopLedgerPartyRepository ledgerPartyRepository;
    private final ShopLedgerEntryRepository ledgerEntryRepository;
    private final TicketService ticketService;
    private final ReEstimateWorkflowService reEstimateWorkflow;

    @Transactional
    public InvoiceResponse upsert(UUID ticketId, InvoiceRequest req, UUID generatedBy) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        // No bill while a re-estimate is open — the price isn't settled yet (409 REESTIMATE_PENDING).
        reEstimateWorkflow.assertNoOpenRevision(ticket);

        Invoice inv = invoiceRepository.findByTicketId(ticketId).orElseGet(Invoice::new);
        inv.setTicketId(ticketId);
        inv.setShopId(ticket.getShopId());

        // Auto-fill invoice_no if client didn't send one. Use the ticket's
        // tracking ID as a deterministic seed so repeated calls don't churn
        // through new numbers, then suffix with the row's first 8 chars if
        // the tracking ID is already in use elsewhere.
        String invoiceNo = req.getInvoiceNo();
        if (invoiceNo == null || invoiceNo.isBlank()) {
            invoiceNo = ticket.getTrackingId() != null ? ticket.getTrackingId() : ticketId.toString().substring(0, 10);
        }
        inv.setInvoiceNo(invoiceNo);

        inv.setTicketDate(req.getTicketDate() != null ? req.getTicketDate() : ticket.getCreatedAt());
        inv.setDeliveryDate(req.getDeliveryDate());
        inv.setGstNo(req.getGstNo());

        inv.setServiceCharges(nz(req.getServiceCharges()));
        inv.setTotalRepairAmount(nz(req.getTotalRepairAmount()));
        inv.setSpareUtilityCharge(nz(req.getSpareUtilityCharge()));
        inv.setDiscount(nz(req.getDiscount()));
        inv.setTaxMode(req.getTaxMode() != null ? req.getTaxMode() : "WITHOUT");
        inv.setGstPercent(nz(req.getGstPercent()));

        inv.setAmount2Plus3(nz(req.getAmount2Plus3()));
        inv.setBaseAmount(nz(req.getBaseAmount()));
        inv.setTotalGst(nz(req.getTotalGst()));
        inv.setFinalPayableAmount(nz(req.getFinalPayableAmount()));
        inv.setAmountInWords(req.getAmountInWords());

        inv.setSpareLinesJson(req.getSpareLinesJson());
        inv.setServiceLinesJson(req.getServiceLinesJson());

        applyPayment(inv, req);

        if (inv.getGeneratedAt() == null) inv.setGeneratedAt(Instant.now());
        if (generatedBy != null) inv.setGeneratedBy(generatedBy);

        // Before the save, so the entry id it resolves is persisted with the row.
        syncCreditToCashBook(inv, ticket);

        Invoice saved = invoiceRepository.save(inv);

        // "Invoice Generated" is written HERE, next to the row it describes,
        // rather than being left to the client's follow-up call. An invoice that
        // exists with no timeline step behind it is exactly the state the
        // Service History screen cannot represent — the rail would show the
        // booking delivered with the invoice row still grey. The emit is
        // idempotent (keyed by status), so the app's own post is a no-op refresh.
        //
        // Swallowed on failure: the bill is saved and correct, and losing it over
        // a timeline row would be the worse trade.
        try {
            ticketService.emitProgressStepEvent(ticket.getShopId(), ticket.getId(),
                    "INVOICE_GENERATED", invoiceGeneratedNote(saved), "OWNER");
        } catch (Exception e) {
            log.error("INVOICE_GENERATED emit failed for ticket {}", ticket.getId(), e);
        }

        return InvoiceResponse.from(saved);
    }

    /** Shared with TicketService's self-heal so both write the same sentence. */
    static String invoiceGeneratedNote(Invoice inv) {
        return "Invoice #" + inv.getInvoiceNo() + " generated";
    }

    /**
     * Delete a ticket's invoice while it is still the booking's latest step.
     * The "Invoice Generated" step is retracted first — that is where the
     * refusal lives (409 once Out for Delivery / Delivered is recorded), and the
     * shared transaction rolls everything back if it throws. The Cash Book
     * credit the invoice posted goes with it, so the customer no longer owes it.
     */
    @Transactional
    public void delete(UUID ticketId, UUID callerShopId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .filter(t -> callerShopId != null && callerShopId.equals(t.getShopId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        Invoice inv = invoiceRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));

        ticketService.retractInvoiceStep(ticket.getShopId(), ticketId);

        if (inv.getCreditLedgerEntryId() != null && ticket.getShopId() != null) {
            ledgerEntryRepository.findByIdAndShopId(inv.getCreditLedgerEntryId(), ticket.getShopId())
                    .ifPresent(ledgerEntryRepository::delete);
        }
        invoiceRepository.delete(inv);
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceResponse> findByTicket(UUID ticketId) {
        return invoiceRepository.findByTicketId(ticketId).map(InvoiceResponse::from);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getById(UUID id) {
        return invoiceRepository.findById(id)
                .map(InvoiceResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    // ---- Payment & credit -------------------------------------------------------

    /**
     * The payment chain, re-derived rather than trusted.
     *
     * <pre>
     *   net    = max(0, finalPayable − advance)
     *   credit = max(0, net − paid)
     * </pre>
     *
     * The client sends its own net and credit and they are deliberately ignored:
     * credit becomes a debt on a customer's Cash Book account, and a number that
     * only the phone computed could put a figure there the invoice above it never
     * justified. The clamps matter as much as the arithmetic — an advance larger
     * than the bill (a re-estimate that came down) must read as "nothing owed",
     * not as a negative debt that would flip to the shop owing the customer.
     */
    static void applyPayment(Invoice inv, InvoiceRequest req) {
        BigDecimal finalPayable = nz(inv.getFinalPayableAmount());
        BigDecimal advance = clampToRange(nz(req.getAdvancePaid()), finalPayable);
        BigDecimal net = finalPayable.subtract(advance).max(BigDecimal.ZERO);
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal upi = BigDecimal.ZERO;
        BigDecimal paid;
        if (req.getCashAmount() != null || req.getUpiAmount() != null) {
            // Split payment: rejected, not clamped — a typo must not become a
            // silently different receipt.
            cash = nz(req.getCashAmount()).setScale(2, java.math.RoundingMode.HALF_UP);
            upi = nz(req.getUpiAmount()).setScale(2, java.math.RoundingMode.HALF_UP);
            if (cash.signum() < 0 || upi.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amounts cannot be negative");
            }
            paid = cash.add(upi);
            if (paid.compareTo(net.setScale(2, java.math.RoundingMode.HALF_UP)) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cash + UPI cannot be more than the net payable amount");
            }
        } else {
            paid = clampToRange(nz(req.getAmountPaid()), net);
        }
        BigDecimal credit = net.subtract(paid).max(BigDecimal.ZERO);

        inv.setCashAmount(cash);
        inv.setUpiAmount(upi);
        inv.setAdvancePaid(advance);
        inv.setNetPayableAmount(net);
        inv.setAmountPaid(paid);
        inv.setCreditAmount(credit);
        inv.setPaymentNote(trim(req.getPaymentNote(), MAX_NOTE));
        inv.setPaymentDate(req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now(IST));
    }

    /**
     * Mirrors {@link Invoice#getCreditAmount()} onto the customer's Cash Book
     * account as a GIVEN entry, so the outstanding balance the owner chases and
     * the balance on the bill are the same number by construction.
     *
     * <p>Three cases, and the middle one is the reason the entry id is stored on
     * the invoice at all:
     * <ul>
     *   <li><b>No credit</b> — any entry a previous generation wrote is deleted.
     *       A bill that was settled on the second attempt must not leave a debt
     *       behind on the customer's account.</li>
     *   <li><b>Credit, entry already exists</b> — that row is UPDATED. Posting a
     *       fresh one on every re-generation would silently double the debt.</li>
     *   <li><b>Credit, no entry</b> — the account is opened (upserted on the
     *       shop/type/phone key the rest of the ledger uses) and the row written.</li>
     * </ul>
     *
     * <p>Only the credit is posted, never the full invoice with the payments
     * against it. A double-entry posting would be the more orthodox books, but it
     * only balances if the booking advance was itself written to the ledger — and
     * nothing guarantees that, since {@code tickets.payment_amount} is captured by
     * the booking screen whether or not the owner keeps a Cash Book. Posting the
     * outstanding figure alone cannot disagree with the invoice that produced it.
     *
     * <p>A ticket with no usable phone number is left unposted rather than failing
     * the whole invoice: the bill is still correct and still prints, and the
     * response carries a null entry id so the app can say the credit is not being
     * tracked. Losing the invoice over a missing phone number would be worse.
     */
    private void syncCreditToCashBook(Invoice inv, Ticket ticket) {
        UUID shopId = ticket.getShopId();
        UUID existingEntryId = inv.getCreditLedgerEntryId();
        boolean hasCredit = inv.getCreditAmount().signum() > 0;

        if (shopId == null || !hasCredit) {
            if (existingEntryId != null) {
                ledgerEntryRepository.findByIdAndShopId(existingEntryId, shopId).ifPresent(ledgerEntryRepository::delete);
            }
            inv.setCreditLedgerEntryId(null);
            inv.setCreditPartyId(null);
            return;
        }

        String phone = normalizePhone(ticket.getCustomerPhone());
        if (phone == null) {
            inv.setCreditLedgerEntryId(null);
            inv.setCreditPartyId(null);
            return;
        }

        ShopLedgerParty party = ledgerPartyRepository
                .findByShopIdAndPartyTypeAndPhone(shopId, CUSTOMER, phone)
                .orElseGet(() -> {
                    ShopLedgerParty p = new ShopLedgerParty();
                    p.setShopId(shopId);
                    p.setPartyType(CUSTOMER);
                    p.setPhone(phone);
                    // Falls back to the number, exactly as ShopLedgerPartyService
                    // does, so a nameless walk-in still renders in the account list.
                    p.setName(trim(defaultIfBlank(ticket.getCustomerName(), phone), MAX_NAME));
                    return ledgerPartyRepository.save(p);
                });

        ShopLedgerEntry e = existingEntryId != null
                ? ledgerEntryRepository.findByIdAndShopId(existingEntryId, shopId).orElseGet(ShopLedgerEntry::new)
                : new ShopLedgerEntry();

        e.setShopId(shopId);
        e.setPartyId(party.getId());
        e.setDirection(GIVEN);
        e.setAmount(inv.getCreditAmount());
        e.setEntryDate(inv.getPaymentDate() != null ? inv.getPaymentDate() : LocalDate.now(IST));
        e.setNote(creditNote(inv));
        e.setTicketId(ticket.getId());
        e.setTicketTrackingId(ticket.getTrackingId());
        e.setTicketLabel(ticket.getDeviceDisplayName());

        ShopLedgerEntry saved = ledgerEntryRepository.save(e);
        inv.setCreditPartyId(party.getId());
        inv.setCreditLedgerEntryId(saved.getId());
    }

    /**
     * What the row reads as in the statement. The bill number leads because that
     * is what the customer will be holding when they come back to argue about it;
     * the owner's own note follows so it isn't buried.
     */
    private static String creditNote(Invoice inv) {
        String base = "Credit · Invoice #" + inv.getInvoiceNo();
        String own = trim(inv.getPaymentNote(), MAX_NOTE);
        String full = (own == null || own.isBlank()) ? base : base + " · " + own;
        return trim(full, MAX_NOTE);
    }

    // ---- Helpers ---------------------------------------------------------------

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Never below zero, never above the figure it is being taken out of. */
    private static BigDecimal clampToRange(BigDecimal v, BigDecimal max) {
        return v.max(BigDecimal.ZERO).min(max.max(BigDecimal.ZERO));
    }

    /**
     * Digits only, in the 10-digit national form the ledger stores — kept
     * deliberately identical to ShopLedgerPartyService.requirePhone so a credit
     * lands on the SAME account the owner would have created by hand. Returns
     * null instead of throwing: a missing number must not fail the invoice.
     */
    private static String normalizePhone(String raw) {
        String digits = raw != null ? raw.replaceAll("\\D", "") : "";
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        else if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        return digits.length() == PHONE_DIGITS ? digits : null;
    }

    private static String defaultIfBlank(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String trim(String v, int max) {
        if (v == null) return null;
        String s = v.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
