package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.entity.*;
import com.repairshop.saas.ticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepairDeliveryServiceTest {

    @Mock RepairDeliveryRepository deliveries;
    @Mock RepairDeliveryEventRepository events;
    @Mock TicketRepository tickets;
    @Mock TechnicianRepository technicians;
    @Mock PlatformRepairBookingRepository bookings;
    @Mock PlatformRepairBookingEventRepository bookingEvents;
    @Mock InvoiceRepository invoices;
    @Mock TicketService ticketService;
    @Mock JdbcTemplate jdbc;
    @InjectMocks RepairDeliveryService service;

    final UUID shopId = UUID.randomUUID();
    final UUID ticketId = UUID.randomUUID();
    final UUID empUser = UUID.randomUUID();
    final UUID customer = UUID.randomUUID();
    Ticket ticket;
    PlatformRepairBooking booking;
    Technician emp;
    RepairDelivery saved; // the row the "DB" holds
    final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setShopId(shopId);
        ticket.setStatus("DELIVERED_PROCESSING");
        ticket.setImei("356789012345678");
        ticket.setCustomerId(customer);
        ticket.setBalanceAmount(new BigDecimal("1500"));
        booking = PlatformRepairBooking.builder().id(UUID.randomUUID()).ticketId(ticketId).customerUserId(customer).serviceMode("PICKUP").build();
        emp = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(empUser).name("Ravi").isAvailable(true).build();
        when(tickets.findByShopIdAndId(shopId, ticketId)).thenReturn(Optional.of(ticket));
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(bookings.findByTicketId(ticketId)).thenReturn(Optional.of(booking));
        when(bookingEvents.findCurrentCycle(booking.getId())).thenAnswer(inv -> keys.stream()
                .map(k -> PlatformRepairBookingEvent.builder().status(k).build()).toList());
        when(invoices.findByTicketId(ticketId)).thenReturn(Optional.of(new Invoice()));
        when(technicians.findByShopIdAndId(shopId, emp.getId())).thenReturn(Optional.of(emp));
        when(technicians.findByShopIdAndUserId(shopId, empUser)).thenReturn(Optional.of(emp));
        when(technicians.findById(emp.getId())).thenReturn(Optional.of(emp));
        when(deliveries.save(any())).thenAnswer(inv -> {
            RepairDelivery d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            saved = d;
            return d;
        });
        when(deliveries.findByTicketId(ticketId)).thenAnswer(inv -> Optional.ofNullable(saved));
        when(deliveries.lockByTicketId(ticketId)).thenAnswer(inv -> Optional.ofNullable(saved));
        when(deliveries.lockById(any())).thenAnswer(inv -> Optional.ofNullable(saved));
        when(deliveries.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved));
    }

    private Map<String, Object> slot(UUID tech) {
        Map<String, Object> b = new HashMap<>();
        b.put("date", LocalDate.now().plusDays(1).toString());
        b.put("slotStart", "10:00");
        b.put("slotEnd", "12:00");
        if (tech != null) b.put("technicianId", tech.toString());
        return b;
    }

    private Map<String, Object> act(String action, Map<String, Object> body) {
        return service.employeeAction(shopId, empUser, saved.getId(), action, body);
    }

    /** Scheduled → accepted → released → collected → started. */
    private void toOutForDelivery() {
        service.schedule(shopId, ticketId, slot(emp.getId()));
        act("accept", null);
        service.release(shopId, ticketId);
        act("collect", Map.of("identifier", "35678 90123 45678", "accessories", List.of("Charger"),
                "photos", List.of("https://cdn/x1.jpg", "https://cdn/x2.jpg")));
        act("start", null);
    }

    @Test
    void normalRepairFullFlowMarksDeliveredOnlyAfterOtpAndPayment() {
        keys.add("QUALITY_CHECK_COMPLETED");
        toOutForDelivery();
        assertEquals("OUT_FOR_DELIVERY", saved.getStatus());
        verify(ticketService, never()).updateStatus(any(), any(), eq("DELIVERED"), any());
        act("arrive", null);
        String otp = saved.getOtpCode();
        act("complete", Map.of("otp", otp, "amountCollected", "1500", "paymentMethod", "CASH"));
        assertEquals("DELIVERED", saved.getStatus());
        assertEquals(new BigDecimal("1500"), saved.getAmountCollected());
        verify(ticketService).updateStatus(shopId, ticketId, "DELIVERED", "TECHNICIAN");
        verify(jdbc).update(contains("UPDATE tickets SET payment_amount"), any(), any(), eq(ticketId));
    }

    @Test
    void duplicateCompletionIsRejected() {
        keys.add("QUALITY_CHECK_COMPLETED");
        toOutForDelivery();
        String otp = saved.getOtpCode();
        act("complete", Map.of("otp", otp, "amountCollected", "1500", "paymentMethod", "CASH"));
        assertThrows(ResponseStatusException.class,
                () -> act("complete", Map.of("otp", otp, "amountCollected", "1500", "paymentMethod", "CASH")));
        verify(ticketService, times(1)).updateStatus(shopId, ticketId, "DELIVERED", "TECHNICIAN");
        verify(jdbc, times(1)).update(anyString(), any(), any(), any());
    }

    @Test
    void normalRepairNeedsQcAndInvoice() {
        when(invoices.findByTicketId(ticketId)).thenReturn(Optional.empty());
        ticket.setStatus("IN_REPAIR");
        Map<String, Object> el = service.eligibility(ticket, null);
        assertEquals(false, el.get("canSchedule"));
        assertThrows(ResponseStatusException.class, () -> service.schedule(shopId, ticketId, slot(null)));
    }

    @Test
    void unableToRepairReturnSchedulesWithoutInvoiceAndCollectsNothing() {
        when(invoices.findByTicketId(ticketId)).thenReturn(Optional.empty());
        ticket.setStatus("DELIVERED_PROCESSING");
        keys.add("REPAIR_NOT_COMPLETED");
        service.schedule(shopId, ticketId, slot(null));
        assertTrue(saved.isReturnPath());
        assertEquals(0, saved.getAmountDue().signum());
        assertEquals("UNASSIGNED", saved.getStatus());
    }

    @Test
    void customerRejectedIsAReturnPath() {
        when(invoices.findByTicketId(ticketId)).thenReturn(Optional.empty());
        keys.add("CUSTOMER_REJECTED");
        assertEquals(true, service.eligibility(ticket, null).get("returnPath"));
        assertEquals(true, service.eligibility(ticket, null).get("canSchedule"));
    }

    @Test
    void shopVisitBookingsAreNotEligible() {
        booking.setServiceMode("WALK_IN");
        keys.add("QUALITY_CHECK_COMPLETED");
        assertEquals(false, service.eligibility(ticket, null).get("canSchedule"));
    }

    @Test
    void wrongOtpCountsAndLocksAfterLimit() {
        keys.add("QUALITY_CHECK_COMPLETED");
        toOutForDelivery();
        String right = saved.getOtpCode();
        String wrong = right.equals("0000") ? "1111" : "0000";
        for (int i = 0; i < RepairDeliveryService.OTP_MAX_FAILED; i++) {
            assertThrows(ResponseStatusException.class, () -> act("complete", Map.of("otp", wrong, "amountCollected", "1500", "paymentMethod", "CASH")));
        }
        // Even the right code is refused until a resend.
        assertThrows(ResponseStatusException.class, () -> act("complete", Map.of("otp", right, "amountCollected", "1500", "paymentMethod", "CASH")));
        act("otp/resend", null);
        act("complete", Map.of("otp", saved.getOtpCode(), "amountCollected", "1500", "paymentMethod", "CASH"));
        assertEquals("DELIVERED", saved.getStatus());
    }

    @Test
    void expiredOtpAndResendLimit() {
        keys.add("QUALITY_CHECK_COMPLETED");
        toOutForDelivery();
        saved.setOtpExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(ResponseStatusException.class, () -> act("complete", Map.of("otp", saved.getOtpCode(), "amountCollected", "1500", "paymentMethod", "CASH")));
        act("otp/resend", null);
        act("otp/resend", null); // 3rd send
        assertThrows(ResponseStatusException.class, () -> act("otp/resend", null));
    }

    @Test
    void paymentMustMatchAndUpiNeedsProof() {
        keys.add("QUALITY_CHECK_COMPLETED");
        toOutForDelivery();
        String otp = saved.getOtpCode();
        assertThrows(ResponseStatusException.class, () -> act("complete", Map.of("otp", otp, "amountCollected", "1000", "paymentMethod", "CASH")));
        assertThrows(ResponseStatusException.class, () -> act("complete", Map.of("otp", otp, "amountCollected", "1500", "paymentMethod", "UPI")));
        act("complete", Map.of("otp", otp, "amountCollected", "1500", "paymentMethod", "UPI", "paymentRef", "UTR123"));
        assertEquals("UPI", saved.getPaymentMethod());
    }

    @Test
    void reassignAllowedBeforeReleaseOnly() {
        keys.add("QUALITY_CHECK_COMPLETED");
        Technician other = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(UUID.randomUUID()).name("Kumar").isAvailable(true).build();
        when(technicians.findByShopIdAndId(shopId, other.getId())).thenReturn(Optional.of(other));
        service.schedule(shopId, ticketId, slot(emp.getId()));
        act("accept", null);
        service.assign(shopId, ticketId, other.getId());
        assertEquals(other.getId(), saved.getTechnicianId());
        assertEquals("ASSIGNED", saved.getStatus());
        saved.setStatus("ACCEPTED");
        saved.setReleasedAt(Instant.now());
        assertThrows(ResponseStatusException.class, () -> service.assign(shopId, ticketId, emp.getId()));
    }

    @Test
    void otherShopEmployeeCannotBeAssigned() {
        keys.add("QUALITY_CHECK_COMPLETED");
        UUID stranger = UUID.randomUUID();
        when(technicians.findByShopIdAndId(shopId, stranger)).thenReturn(Optional.empty());
        when(technicians.findByShopIdAndUserId(shopId, stranger)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.schedule(shopId, ticketId, slot(stranger)));
    }

    @Test
    void failedDeliveryReturnsAndRetries() {
        keys.add("QUALITY_CHECK_COMPLETED");
        toOutForDelivery();
        act("fail", Map.of("reason", "Customer not home"));
        assertEquals("FAILED", saved.getStatus());
        assertNull(saved.getOtpCode());
        act("return", null);
        assertThrows(ResponseStatusException.class, () -> service.schedule(shopId, ticketId, slot(emp.getId()))); // not back yet
        service.receiveReturn(shopId, ticketId);
        assertEquals("RETURNED", saved.getStatus());
        service.schedule(shopId, ticketId, slot(emp.getId()));
        assertEquals(2, saved.getAttempt());
        assertEquals("ASSIGNED", saved.getStatus());
        assertNull(saved.getReleasedAt());
        verify(ticketService, never()).updateStatus(any(), any(), eq("DELIVERED"), any());
    }

    @Test
    void collectNeedsReleaseMatchingImeiAndPhotos() {
        keys.add("QUALITY_CHECK_COMPLETED");
        service.schedule(shopId, ticketId, slot(emp.getId()));
        act("accept", null);
        Map<String, Object> ok = Map.of("identifier", "356789012345678", "photos", List.of("https://a", "https://b"));
        assertThrows(ResponseStatusException.class, () -> act("collect", ok)); // not released
        service.release(shopId, ticketId);
        assertThrows(ResponseStatusException.class, () -> act("collect", Map.of("identifier", "111", "photos", List.of("https://a", "https://b"))));
        assertThrows(ResponseStatusException.class, () -> act("collect", Map.of("identifier", "356789012345678", "photos", List.of("https://a"))));
        act("collect", ok);
        assertEquals("COLLECTED", saved.getStatus());
    }

    @Test
    void unauthorizedEmployeeAndCustomerAreRejected() {
        keys.add("QUALITY_CHECK_COMPLETED");
        service.schedule(shopId, ticketId, slot(emp.getId()));
        UUID intruder = UUID.randomUUID();
        Technician other = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(intruder).name("X").isAvailable(true).build();
        when(technicians.findByShopIdAndUserId(shopId, intruder)).thenReturn(Optional.of(other));
        assertThrows(ResponseStatusException.class, () -> service.employeeAction(shopId, intruder, saved.getId(), "accept", null));
        assertThrows(ResponseStatusException.class, () -> service.getForCustomer(UUID.randomUUID(), ticketId));
    }

    @Test
    void customerSeesOtpOnlyWhileOnTheWay() {
        keys.add("QUALITY_CHECK_COMPLETED");
        service.schedule(shopId, ticketId, slot(emp.getId()));
        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) service.getForCustomer(customer, ticketId).get("delivery");
        assertFalse(before.containsKey("otp"));
        act("accept", null);
        service.release(shopId, ticketId);
        act("collect", Map.of("identifier", "356789012345678", "photos", List.of("https://a", "https://b")));
        act("start", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> during = (Map<String, Object>) service.getForCustomer(customer, ticketId).get("delivery");
        assertEquals(saved.getOtpCode(), during.get("otp"));
        // The shop and employee views never carry the OTP.
        assertFalse(((Map<?, ?>) service.getForShop(shopId, ticketId).get("delivery")).containsKey("otp"));
        assertFalse(service.getMine(shopId, empUser, saved.getId()).containsKey("otp"));
    }
}
