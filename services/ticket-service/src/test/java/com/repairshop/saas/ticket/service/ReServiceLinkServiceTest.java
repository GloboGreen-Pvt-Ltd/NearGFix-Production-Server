package com.repairshop.saas.ticket.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReServiceLinkServiceTest {

    @Test
    void longestWarrantyAcrossServiceLines() {
        String items = "[{\"name\":\"Display\",\"price\":900,\"warranty\":\"6 Months\"},"
                + "{\"name\":\"Battery\",\"price\":450,\"warranty\":\"3 Months\"}]";
        assertEquals(6, ReServiceLinkService.maxWarrantyMonths(items));
    }

    @Test
    void cancelledLinesGiveNoWarranty() {
        String items = "[{\"name\":\"Display\",\"warranty\":\"12 Months\",\"cancelled\":true},"
                + "{\"name\":\"Battery\",\"warranty\":\"1 Month\"}]";
        assertEquals(1, ReServiceLinkService.maxWarrantyMonths(items));
    }

    @Test
    void missingOrUnreadableMeansNoWarranty() {
        assertEquals(0, ReServiceLinkService.maxWarrantyMonths(null));
        assertEquals(0, ReServiceLinkService.maxWarrantyMonths(""));
        assertEquals(0, ReServiceLinkService.maxWarrantyMonths("not json"));
        assertEquals(0, ReServiceLinkService.maxWarrantyMonths("[{\"name\":\"Software\",\"warranty\":null}]"));
    }
}
