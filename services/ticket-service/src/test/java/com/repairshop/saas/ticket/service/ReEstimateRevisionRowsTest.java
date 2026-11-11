package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.entity.PlatformRepairBookingEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReEstimateRevisionRowsTest {

    private static PlatformRepairBookingEvent ev(String status, long sec) {
        return PlatformRepairBookingEvent.builder().status(status).createdAt(Instant.ofEpochSecond(sec)).build();
    }

    @Test
    void secondRevisionInsertsNewRows() {
        var est1 = ev("RE_ESTIMATED_CONFIRMED", 1);
        var ok1 = ev("RE_ESTIMATE_APPROVED", 2);
        var cycle = List.of(est1, ok1, ev("REPAIR_WORK_RESUMED", 3));
        // Revision 2 request / re-estimate: Revision 1's rows are closed.
        assertFalse(ReEstimateWorkflowService.sameRevisionRow(cycle, "TECHNICIAN_REESTIMATE_REQUESTED").isPresent());
        assertFalse(ReEstimateWorkflowService.sameRevisionRow(cycle, "RE_ESTIMATED_CONFIRMED").isPresent());
        // Revision 2 priced → its verdict / resume are new rows too.
        var withEst2 = List.of(est1, ok1, ev("REPAIR_WORK_RESUMED", 3), ev("RE_ESTIMATED_CONFIRMED", 5));
        assertFalse(ReEstimateWorkflowService.sameRevisionRow(withEst2, "RE_ESTIMATE_APPROVED").isPresent());
        assertFalse(ReEstimateWorkflowService.sameRevisionRow(withEst2, "REPAIR_WORK_RESUMED").isPresent());
    }

    @Test
    void reEditBeforeVerdictRefreshesSameRow() {
        var est1 = ev("RE_ESTIMATED_CONFIRMED", 1);
        assertSame(est1, ReEstimateWorkflowService.sameRevisionRow(List.of(est1), "RE_ESTIMATED_CONFIRMED").orElse(null));
        var booked = ev("BOOKING_EDITED", 1);
        assertSame(booked, ReEstimateWorkflowService.sameRevisionRow(List.of(booked, ev("RE_ESTIMATE_APPROVED", 2)), "BOOKING_EDITED").orElse(null));
    }
}
