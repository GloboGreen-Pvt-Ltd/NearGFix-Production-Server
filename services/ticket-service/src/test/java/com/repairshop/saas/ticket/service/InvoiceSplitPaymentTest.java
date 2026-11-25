package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.InvoiceRequest;
import com.repairshop.saas.ticket.entity.Invoice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvoiceSplitPaymentTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static InvoiceRequest req(String advance, String cash, String upi, Boolean confirmed) {
        InvoiceRequest r = new InvoiceRequest();
        r.setAdvancePaid(d(advance));
        if (cash != null) r.setCashAmount(d(cash));
        if (upi != null) r.setUpiAmount(d(upi));
        r.setCreditConfirmed(confirmed);
        return r;
    }

    private static Invoice bill(String finalPayable) {
        Invoice inv = new Invoice();
        inv.setFinalPayableAmount(d(finalPayable));
        return inv;
    }

    private static Invoice run(String finalPayable, String advance, String cash, String upi, Boolean confirmed) {
        Invoice inv = bill(finalPayable);
        InvoiceService.applyPayment(inv, req(advance, cash, upi, confirmed));
        return inv;
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), expected + " != " + actual);
    }

    @Test void fullCash() { Invoice i = run("3300", "0", "3300", "0", null); money("3300", i.getAmountPaid()); money("0", i.getCreditAmount()); money("3300", i.getCashAmount()); }
    @Test void fullUpi() { Invoice i = run("3300", "0", null, "3300", null); money("3300", i.getUpiAmount()); money("0", i.getCashAmount()); money("0", i.getCreditAmount()); }
    @Test void splitFull() { Invoice i = run("3300", "0", "500", "2800", null); money("3300", i.getAmountPaid()); money("0", i.getCreditAmount()); }

    /** GTS-224: bill 4300, cash 1000, UPI 2000 → paid 3000, credit 1300. */
    @Test void gts224SplitWithCredit() {
        Invoice i = run("4300", "0", "1000", "2000", true);
        money("1000", i.getCashAmount()); money("2000", i.getUpiAmount());
        money("3000", i.getAmountPaid()); money("1300", i.getCreditAmount());
        money("0", InvoiceService.legacyPaid(i));
    }

    @Test void unpaidCreditConfirmed() { Invoice i = run("4300", "0", "0", "0", true); money("0", i.getAmountPaid()); money("4300", i.getCreditAmount()); }
    @Test void advanceThenSplit() { Invoice i = run("4300", "1000", "1000", "300", true); money("3300", i.getNetPayableAmount()); money("2000", i.getCreditAmount()); }
    @Test void fullyPrepaid() { Invoice i = run("1000", "1000", "0", "0", null); money("0", i.getNetPayableAmount()); money("0", i.getAmountPaid()); money("0", i.getCreditAmount()); }

    @Test void creditWithoutConfirmationRejected() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> run("4300", "0", "1000", "2000", null));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> run("4300", "0", "0", "0", false));
    }

    @Test void negativeRejected() { assertThrows(ResponseStatusException.class, () -> run("1000", "0", "-1", "0", true)); }
    @Test void overpaymentRejected() { assertThrows(ResponseStatusException.class, () -> run("1000", "200", "500", "301", true)); }

    @Test void legacyAmountPaidOnlyRequestUnchanged() {
        Invoice inv = bill("1000");
        InvoiceRequest r = new InvoiceRequest();
        r.setAdvancePaid(d("0"));
        r.setAmountPaid(d("400"));
        InvoiceService.applyPayment(inv, r);
        money("400", inv.getAmountPaid()); money("600", inv.getCreditAmount());
        money("0", inv.getCashAmount()); money("0", inv.getUpiAmount());
        money("400", InvoiceService.legacyPaid(inv));
    }

    /** Old invoice: 2000 paid, method unknown. Editing adds UPI 1000; the 2000 is kept, not reset. */
    @Test void legacyPaymentPreservedOnEdit() {
        Invoice inv = bill("4300");
        inv.setAmountPaid(d("2000"));
        InvoiceService.applyPayment(inv, req("0", "0", "1000", true));
        money("3000", inv.getAmountPaid()); money("1300", inv.getCreditAmount());
        money("1000", inv.getUpiAmount()); money("0", inv.getCashAmount());
        money("2000", InvoiceService.legacyPaid(inv));
        // Regenerating again with the same split does not add the payment twice.
        InvoiceService.applyPayment(inv, req("0", "0", "1000", true));
        money("3000", inv.getAmountPaid()); money("1300", inv.getCreditAmount());
    }

    @Test void legacyPlusNewAboveNetRejected() {
        Invoice inv = bill("3000");
        inv.setAmountPaid(d("2000"));
        assertThrows(ResponseStatusException.class, () -> InvoiceService.applyPayment(inv, req("0", "1001", "0", true)));
    }

    /** Regenerating a split invoice replaces the split; it never stacks on the old one. */
    @Test void regenerateReplacesSplit() {
        Invoice inv = run("4300", "0", "1000", "2000", true);
        InvoiceService.applyPayment(inv, req("0", "4300", "0", null));
        money("4300", inv.getCashAmount()); money("0", inv.getUpiAmount());
        money("4300", inv.getAmountPaid()); money("0", inv.getCreditAmount());
    }
}
