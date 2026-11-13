package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Technician re-assignment rules on PATCH /tickets/{id}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketReassignTest {

    @Mock TicketRepository ticketRepository;
    @Mock TechnicianRepository technicianRepository;
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
    @Mock com.repairshop.saas.common.notify.PushService pushService;

    @InjectMocks TicketService service;

    private final UUID shopId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final UUID oldUser = UUID.randomUUID();
    private Ticket ticket;
    private Technician oldTech;
    private Technician newTech;

    @BeforeEach
    void setUp() {
        oldTech = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(oldUser).name("Arun").isAvailable(true).build();
        newTech = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(UUID.randomUUID()).name("Bala").isAvailable(true).build();
        ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setShopId(shopId);
        ticket.setStatus("IN_REPAIR");
        ticket.setAssignedTechnicianId(oldTech.getId());
        ticket.setTechnicianAcceptedAt(Instant.now());
        ticket.setTechnicianPhotosJson("[\"https://x/p1.jpg\"]");
        ticket.setIssueDescription("Display broken");
        when(ticketRepository.findByShopIdAndId(shopId, ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.lockByShopIdAndId(shopId, ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(technicianRepository.findByShopIdAndId(shopId, newTech.getId())).thenReturn(Optional.of(newTech));
        when(technicianRepository.findByShopIdAndId(shopId, oldTech.getId())).thenReturn(Optional.of(oldTech));
        when(technicianRepository.findById(oldTech.getId())).thenReturn(Optional.of(oldTech));
        when(technicianRepository.findByShopIdAndUserId(shopId, oldUser)).thenReturn(Optional.of(oldTech));
        when(platformRepairBookingRepository.findByTicketId(any())).thenReturn(Optional.empty());
    }

    private Map<String, Object> assign(UUID techId) {
        Map<String, Object> b = new HashMap<>();
        b.put("assignedTechnicianId", techId.toString());
        return b;
    }

    @Test
    void reassignDuringRepairKeepsProgressAndClearsAcceptance() {
        service.patch(shopId, ticketId, assign(newTech.getId()), "OWNER");
        assertEquals(newTech.getId(), ticket.getAssignedTechnicianId());
        assertNull(ticket.getTechnicianAcceptedAt(), "new technician must accept");
        assertEquals("IN_REPAIR", ticket.getStatus(), "repair status preserved");
        assertEquals(ticketId, ticket.getId());
        assertEquals("[\"https://x/p1.jpg\"]", ticket.getTechnicianPhotosJson());
        assertEquals("Display broken", ticket.getIssueDescription());
    }

    @Test
    void sameTechnicianIsNoOp() {
        Instant accepted = ticket.getTechnicianAcceptedAt();
        service.patch(shopId, ticketId, assign(oldTech.getId()), "OWNER");
        assertEquals(accepted, ticket.getTechnicianAcceptedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERED", "DELIVERED_PROCESSING", "CANCELLED", "CLOSED", "RETURNED", "RETURN_DELIVERY"})
    void blockedFromOutForDeliveryAndTerminal(String status) {
        ticket.setStatus(status);
        assertThrows(ResponseStatusException.class,
                () -> service.patch(shopId, ticketId, assign(newTech.getId()), "OWNER"));
        assertEquals(oldTech.getId(), ticket.getAssignedTechnicianId());
    }

    @Test
    void inactiveTechnicianRejected() {
        newTech.setAvailable(false);
        assertThrows(ResponseStatusException.class,
                () -> service.patch(shopId, ticketId, assign(newTech.getId()), "OWNER"));
    }

    @Test
    void staleExpectedTechnicianIsConflict() {
        Map<String, Object> b = assign(newTech.getId());
        b.put("expectedTechnicianId", UUID.randomUUID().toString());
        assertThrows(ResponseStatusException.class, () -> service.patch(shopId, ticketId, b, "OWNER"));
        assertEquals(oldTech.getId(), ticket.getAssignedTechnicianId());
    }

    @Test
    void ownerRowNeedsSettingOn() {
        newTech.setOwner(true);
        newTech.setOwnerTechEnabled(false);
        assertThrows(ResponseStatusException.class,
                () -> service.patch(shopId, ticketId, assign(newTech.getId()), "OWNER"));
        newTech.setOwnerTechEnabled(true);
        service.patch(shopId, ticketId, assign(newTech.getId()), "OWNER");
        assertEquals(newTech.getId(), ticket.getAssignedTechnicianId());
    }

    @Test
    void oldTechnicianLosesAccessAfterReassign() {
        service.requireCurrentTechnicianUnlessOwner(shopId, ticketId, oldUser, false); // still assigned: ok
        service.patch(shopId, ticketId, assign(newTech.getId()), "OWNER");
        assertThrows(ResponseStatusException.class,
                () -> service.requireCurrentTechnicianUnlessOwner(shopId, ticketId, oldUser, false));
        service.requireCurrentTechnicianUnlessOwner(shopId, ticketId, oldUser, true); // owner unaffected
    }
}
