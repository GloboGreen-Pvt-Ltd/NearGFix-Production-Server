package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.InvoiceRequest;
import com.repairshop.saas.ticket.entity.Invoice;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvoiceSplitPaymentTest {

    private static Invoice run(String finalPayable, String advance, String cash, String upi, String legacyPaid) {
        Invoice inv = new Invoice();
        inv.setFinalPayableAmount(new BigDecimal(finalPayable));
        InvoiceRequest req = new InvoiceRequest();
        req.setAdvancePaid(new BigDecimal(advance));
        if (cash != null) req.setCashAmount(new BigDecimal(cash));
        if (upi != null) req.setUpiAmount(new BigDecimal(upi));
        if (legacyPaid != null) req.setAmountPaid(new BigDecimal(legacyPaid));
        InvoiceService.applyPayment(inv, req);
        return inv;
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " != " + actual);
    }

    @Test void fullCash() { Invoice i = run("3300", "0", "3300", "0", null); money("3300", i.getAmountPaid()); money("0", i.getCreditAmount()); money("3300", i.getCashAmount()); }
    @Test void fullUpi() { Invoice i = run("3300", "0", null, "3300", null); money("3300", i.getUpiAmount()); money("0", i.getCashAmount()); money("0", i.getCreditAmount()); }
    @Test void splitFull() { Invoice i = run("3300", "0", "500", "2800", null); money("3300", i.getAmountPaid()); money("0", i.getCreditAmount()); }
    @Test void splitWithCredit() { Invoice i = run("3300", "0", "500", "2200", null); money("2700", i.getAmountPaid()); money("600", i.getCreditAmount()); }
    @Test void unpaidCredit() { Invoice i = run("4300", "0", "0", "0", null); money("0", i.getAmountPaid()); money("4300", i.getCreditAmount()); }
    @Test void advanceThenSplit() { Invoice i = run("4300", "1000", "1000", "300", null); money("3300", i.getNetPayableAmount()); money("2000", i.getCreditAmount()); }
    @Test void fullyPrepaid() { Invoice i = run("1000", "1000", "0", "0", null); money("0", i.getNetPayableAmount()); money("0", i.getAmountPaid()); money("0", i.getCreditAmount()); }
    @Test void negativeRejected() { assertThrows(ResponseStatusException.class, () -> run("1000", "0", "-1", "0", null)); }
    @Test void overpaymentRejected() { assertThrows(ResponseStatusException.class, () -> run("1000", "200", "500", "301", null)); }
    @Test void legacyAmountPaidStillClamps() { Invoice i = run("1000", "0", null, null, "1500"); money("1000", i.getAmountPaid()); money("0", i.getCashAmount()); money("0", i.getUpiAmount()); }
}
