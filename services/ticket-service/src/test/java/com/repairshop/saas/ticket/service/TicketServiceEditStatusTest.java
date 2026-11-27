package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.dto.TicketRequest;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression: Edit Booking must not change the ticket status. Only the
 * Re-Estimate action (TicketRequest.reEstimate = true) re-quotes to QUOTED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketServiceEditStatusTest {

    private static final String OLD_ITEMS = "[{\"id\":\"s1\",\"label\":\"Display\",\"amount\":1500}]";
    private static final String NEW_ITEMS = "[{\"id\":\"s1\",\"label\":\"Display\",\"amount\":1500},"
            + "{\"id\":\"s2\",\"label\":\"Battery\",\"amount\":900}]";

    @Mock TicketRepository ticketRepository;
    @Mock com.repairshop.saas.ticket.repository.TechnicianRepository technicianRepository;
    @Mock com.repairshop.saas.ticket.repository.PlatformRepairBookingRepository platformRepairBookingRepository;
    @Mock com.repairshop.saas.ticket.repository.PlatformRepairBookingEventRepository platformRepairBookingEventRepository;
    @Mock com.repairshop.saas.ticket.repository.PlatformCustomerAddressRepository platformCustomerAddressRepository;
    @Mock com.repairshop.saas.ticket.repository.PlatformCustomerUserRepository platformCustomerUserRepository;
    @Mock com.repairshop.saas.ticket.repository.RepairNoteRepository repairNoteRepository;
    @Mock com.repairshop.saas.ticket.repository.TicketSolutionPackRepository ticketSolutionPackRepository;
    @Mock com.repairshop.saas.ticket.repository.MasterTechnicianWorkStatusViewRepository masterWorkStatusRepository;
    @Mock com.repairshop.saas.ticket.repository.InvoiceRepository invoiceRepository;
    @Mock CustomerOrderMirrorService customerOrderMirrorService;
    @Mock TicketNumberService ticketNumberService;
    @Mock ReEstimateWorkflowService reEstimateWorkflow;

    @InjectMocks TicketService service;

    private final UUID shopId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setShopId(shopId);
        ticket.setPriceItemsJson(OLD_ITEMS);
        ticket.setEstimatedPrice(new BigDecimal("1500"));
        ticket.setCustomerApproval(true);
        when(ticketRepository.findByShopIdAndId(shopId, ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TicketRequest request(String items, String price, Boolean approval, Boolean reEstimate) {
        TicketRequest r = new TicketRequest();
        r.setPriceItemsJson(items);
        r.setEstimatedPrice(new BigDecimal(price));
        r.setCustomerApproval(approval);
        r.setReEstimate(reEstimate);
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "IN_DIAGNOSIS", "APPROVED", "IN_REPAIR", "READY"})
    void editWithChangedServicesKeepsStatus(String status) {
        ticket.setStatus(status);
        service.update(shopId, ticketId, request(NEW_ITEMS, "2400", true, false));
        assertEquals(status, ticket.getStatus());
    }

    @Test
    void deliveredServiceIsReadOnly() {
        // A returning device gets a linked Re-Service ticket; the delivered one never changes.
        ticket.setStatus("DELIVERED");
        ticket.setPriceItemsJson(OLD_ITEMS);
        var e = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.update(shopId, ticketId, request(NEW_ITEMS, "2400", true, false)));
        assertEquals(409, e.getStatusCode().value());
        assertEquals("DELIVERED", ticket.getStatus());
        assertEquals(OLD_ITEMS, ticket.getPriceItemsJson());
    }

    @Test
    void editOfDetailsOnlyKeepsInRepair() {
        ticket.setStatus("IN_REPAIR");
        service.update(shopId, ticketId, request(OLD_ITEMS, "1500", true, false));
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval());
    }

    @Test
    void editWithoutApprovalFieldKeepsApproval() {
        ticket.setStatus("IN_REPAIR");
        service.update(shopId, ticketId, request(NEW_ITEMS, "2400", null, false));
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval());
    }

    @Test
    void reEstimateWithNewIssueMovesToQuoted() {
        ticket.setStatus("IN_REPAIR");
        service.update(shopId, ticketId, request(NEW_ITEMS, "2400", true, true));
        assertEquals("QUOTED", ticket.getStatus());
    }

    @Test
    void reEstimateWithoutChangeKeepsStatus() {
        ticket.setStatus("IN_REPAIR");
        service.update(shopId, ticketId, request(OLD_ITEMS, "1500", true, true));
        assertEquals("IN_REPAIR", ticket.getStatus());
    }

    @Test
    void olderAppWithoutFlagStillReEstimatesOnChange() {
        ticket.setStatus("IN_REPAIR");
        service.update(shopId, ticketId, request(NEW_ITEMS, "2400", true, null));
        assertEquals("QUOTED", ticket.getStatus());
    }

    @Test
    void patchBookingDetailsKeepsStatusPriceTechnicianAndSchedule() {
        UUID tech = UUID.randomUUID();
        java.time.Instant delivery = java.time.Instant.parse("2026-11-03T10:00:00Z");
        ticket.setStatus("IN_REPAIR");
        ticket.setAssignedTechnicianId(tech);
        ticket.setEstimatedDeliveryAt(delivery);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("customerName", "Nandha K");
        body.put("customerPhone", "8939615914");
        body.put("issueDescription", "Display damaged after drop");
        body.put("missingPartsJson", "[]");
        body.put("deviceSecurityType", "PATTERN");
        body.put("deviceSecurityValue", "1,5,9,6");
        service.patch(shopId, ticketId, body);
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertEquals(tech, ticket.getAssignedTechnicianId());
        assertEquals(new BigDecimal("1500"), ticket.getEstimatedPrice());
        assertEquals(OLD_ITEMS, ticket.getPriceItemsJson());
        assertEquals(delivery, ticket.getEstimatedDeliveryAt());
        assertEquals("Nandha K", ticket.getCustomerName());
        assertEquals("PATTERN", ticket.getDeviceSecurityType());
    }

    private com.repairshop.saas.ticket.dto.ReEstimateRequest reEst(String reason, Boolean approved, Object[]... rows) {
        com.repairshop.saas.ticket.dto.ReEstimateRequest r = new com.repairshop.saas.ticket.dto.ReEstimateRequest();
        java.util.List<com.repairshop.saas.ticket.dto.ReEstimateRequest.Item> list = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            com.repairshop.saas.ticket.dto.ReEstimateRequest.Item it = new com.repairshop.saas.ticket.dto.ReEstimateRequest.Item();
            it.setId((String) row[0]);
            it.setLabel((String) row[1]);
            it.setAmount(row[2] == null ? null : new BigDecimal(String.valueOf(row[2])));
            list.add(it);
        }
        r.setItems(list);
        r.setReason(reason);
        r.setCustomerApproved(approved);
        return r;
    }

    @Test
    void reEstimateAddsOnTopKeepsOriginalAndAwaitsApproval() {
        UUID tech = UUID.randomUUID();
        ticket.setStatus("IN_REPAIR");
        ticket.setAssignedTechnicianId(tech);
        service.reEstimate(shopId, ticketId, reEst("Charging port damage found", null, new Object[] { "s2", "Charging Port", 800 }));
        assertEquals(new BigDecimal("2300"), ticket.getEstimatedPrice());
        assertTrue(ticket.getPriceItemsJson().contains("\"s1\"") && ticket.getPriceItemsJson().contains("1500"));
        assertTrue(ticket.getPriceItemsJson().contains("Charging Port"));
        assertEquals("QUOTED", ticket.getStatus());
        assertNull(ticket.getCustomerApproval());
        assertEquals(tech, ticket.getAssignedTechnicianId());
    }

    @Test
    void reEstimateApprovedAtCounterKeepsApproval() {
        ticket.setStatus("IN_REPAIR");
        service.reEstimate(shopId, ticketId, reEst("Battery swelling found", true, new Object[] { "s2", "Battery", 900 }));
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval());
        assertEquals(new BigDecimal("2400"), ticket.getEstimatedPrice());
    }

    @Test
    void reEstimateOfAnIssueAlreadyInTheEstimateIsRejected() {
        ticket.setStatus("IN_REPAIR");
        org.springframework.web.server.ResponseStatusException ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.reEstimate(shopId, ticketId, reEst("Again", null, new Object[] { "s1", "Display", 1500 })));
        assertEquals(409, ex.getStatusCode().value());
        assertEquals(new BigDecimal("1500"), ticket.getEstimatedPrice());
        assertEquals("IN_REPAIR", ticket.getStatus());
    }

    @Test
    void reEstimateNeedsAReasonAndAPrice() {
        ticket.setStatus("IN_REPAIR");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.reEstimate(shopId, ticketId, reEst("", null, new Object[] { "s2", "Battery", 900 })));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.reEstimate(shopId, ticketId, reEst("Battery swelling", null, new Object[] { "s2", "Battery", null })));
        assertEquals("IN_REPAIR", ticket.getStatus());
    }

    private com.repairshop.saas.ticket.dto.PriceRevisionRequest revise(String reason, Boolean approved, Object[]... rows) {
        com.repairshop.saas.ticket.dto.PriceRevisionRequest r = new com.repairshop.saas.ticket.dto.PriceRevisionRequest();
        java.util.List<com.repairshop.saas.ticket.dto.PriceRevisionRequest.Change> list = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            com.repairshop.saas.ticket.dto.PriceRevisionRequest.Change c = new com.repairshop.saas.ticket.dto.PriceRevisionRequest.Change();
            c.setIndex((Integer) row[0]);
            c.setIdentity((String) row[1]);
            c.setOldAmount(new BigDecimal(String.valueOf(row[2])));
            c.setNewAmount(row[3] == null ? null : new BigDecimal(String.valueOf(row[3])));
            c.setCancel(row.length > 4 ? (Boolean) row[4] : null);
            list.add(c);
        }
        r.setChanges(list);
        r.setReason(reason);
        r.setCustomerApproved(approved);
        return r;
    }

    @Test
    void priceDecreaseKeepsStatusAndApproval() {
        UUID tech = UUID.randomUUID();
        ticket.setStatus("IN_REPAIR");
        ticket.setAssignedTechnicianId(tech);
        service.revisePrices(shopId, ticketId, revise("Customer Discount", null, new Object[] { 0, "s1", 1500, 1200 }));
        assertEquals(new BigDecimal("1200"), ticket.getEstimatedPrice());
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval());
        assertEquals(tech, ticket.getAssignedTechnicianId());
    }

    @Test
    void priceIncreaseAwaitsApprovalWithoutStatusChange() {
        ticket.setStatus("APPROVED");
        service.revisePrices(shopId, ticketId, revise("Spare Part Price Change", null, new Object[] { 0, "s1", 1500, 1800 }));
        assertEquals(new BigDecimal("1800"), ticket.getEstimatedPrice());
        assertEquals("APPROVED", ticket.getStatus());
        assertNull(ticket.getCustomerApproval());
    }

    @Test
    void cancellingAServiceDropsItFromTheTotal() {
        ticket.setStatus("IN_REPAIR");
        ticket.setPriceItemsJson(NEW_ITEMS);
        ticket.setEstimatedPrice(new BigDecimal("2400"));
        service.revisePrices(shopId, ticketId, revise("Service Cancelled", true, new Object[] { 1, "s2", 900, null, true }));
        assertEquals(new BigDecimal("1500"), ticket.getEstimatedPrice());
        assertFalse(ticket.getPriceItemsJson().contains("Battery"));
        assertEquals("IN_REPAIR", ticket.getStatus());
        assertEquals(Boolean.TRUE, ticket.getCustomerApproval());
    }

    @Test
    void staleRevisionIsRejectedAndNothingChanges() {
        ticket.setStatus("IN_REPAIR");
        org.springframework.web.server.ResponseStatusException ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.revisePrices(shopId, ticketId, revise("Price Correction", null, new Object[] { 0, "s1", 1400, 1200 })));
        assertEquals(409, ex.getStatusCode().value());
        assertEquals(new BigDecimal("1500"), ticket.getEstimatedPrice());
        assertEquals(OLD_ITEMS, ticket.getPriceItemsJson());
    }

    @Test
    void lastServiceCannotBeCancelled() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.revisePrices(shopId, ticketId, revise("Service Cancelled", null, new Object[] { 0, "s1", 1500, null, true })));
        assertEquals(OLD_ITEMS, ticket.getPriceItemsJson());
    }

    @Test
    void isReEstimateTable() {
        assertFalse(TicketService.isReEstimate(false, true));
        assertFalse(TicketService.isReEstimate(true, false));
        assertFalse(TicketService.isReEstimate(null, false));
        assertTrue(TicketService.isReEstimate(true, true));
        assertTrue(TicketService.isReEstimate(null, true));
    }
}
