package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OwnerTechnicianServiceTest {

    @Mock TechnicianRepository technicianRepository;
    @Mock TicketRepository ticketRepository;
    @InjectMocks OwnerTechnicianService service;

    private final UUID shopId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private Ticket ticket(String status, boolean accepted) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setShopId(shopId);
        t.setStatus(status);
        if (accepted) t.setTechnicianAcceptedAt(Instant.now());
        return t;
    }

    private Technician ownerRow(boolean enabled) {
        Technician t = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(ownerId).name("Owner").build();
        t.setOwner(true);
        t.setOwnerTechEnabled(enabled);
        return t;
    }

    @Test
    void defaultsToOff() {
        when(technicianRepository.findOwnerRow(shopId)).thenReturn(Optional.empty());
        when(technicianRepository.findByShopIdAndUserId(shopId, ownerId)).thenReturn(Optional.empty());
        Map<String, Object> s = service.get(shopId, ownerId);
        assertEquals(false, s.get("enabled"));
        assertNull(s.get("technicianId"));
        assertEquals(0L, s.get("allCount"));
    }

    @Test
    void turningOnPersistsAndReusesExistingRow() {
        Technician existing = Technician.builder().id(UUID.randomUUID()).shopId(shopId).userId(ownerId).name("Ravi").build();
        when(technicianRepository.findOwnerRow(shopId)).thenReturn(Optional.empty());
        when(technicianRepository.findByShopIdAndUserId(shopId, ownerId)).thenReturn(Optional.of(existing));
        service.set(shopId, ownerId, true, "Ravi");
        ArgumentCaptor<Technician> saved = ArgumentCaptor.forClass(Technician.class);
        verify(technicianRepository).save(saved.capture());
        assertSame(existing, saved.getValue()); // no duplicate technician row
        assertTrue(saved.getValue().isOwner());
        assertTrue(saved.getValue().isOwnerTechEnabled());
    }

    @Test
    void offIsBlockedWhileActiveJobsRemain() {
        Technician row = ownerRow(true);
        when(technicianRepository.findOwnerRow(shopId)).thenReturn(Optional.of(row));
        when(ticketRepository.findByShopIdAndAssignedTechnicianId(shopId, row.getId()))
                .thenReturn(List.of(ticket("IN_REPAIR", true)));
        assertThrows(ResponseStatusException.class, () -> service.set(shopId, ownerId, false, null));
        assertTrue(row.isOwnerTechEnabled());
        verify(technicianRepository, never()).save(any());
    }

    @Test
    void offAllowedWhenOnlyCompletedJobs() {
        Technician row = ownerRow(true);
        when(technicianRepository.findOwnerRow(shopId)).thenReturn(Optional.of(row));
        when(ticketRepository.findByShopIdAndAssignedTechnicianId(shopId, row.getId()))
                .thenReturn(List.of(ticket("DELIVERED", true)));
        service.set(shopId, ownerId, false, null);
        assertFalse(row.isOwnerTechEnabled());
    }

    @Test
    void countsOnlyOwnersOwnJobs() {
        Technician row = ownerRow(true);
        when(technicianRepository.findOwnerRow(shopId)).thenReturn(Optional.of(row));
        when(ticketRepository.findByShopIdAndAssignedTechnicianId(shopId, row.getId())).thenReturn(List.of(
                ticket("CREATED", false), ticket("IN_REPAIR", true), ticket("READY", true)));
        Map<String, Object> s = service.get(shopId, ownerId);
        assertEquals(1L, s.get("assignedCount"));
        assertEquals(1L, s.get("pendingCount"));
        assertEquals(1L, s.get("completedCount"));
        assertEquals(3L, s.get("allCount"));
        verify(ticketRepository, never()).findByShopId(any(), any());
    }

    @Test
    void anotherUserCannotUseTheOwnerRow() {
        when(technicianRepository.findOwnerRow(shopId)).thenReturn(Optional.of(ownerRow(true)));
        assertThrows(ResponseStatusException.class, () -> service.get(shopId, UUID.randomUUID()));
    }
}
