package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.common.notify.PushService;
import com.repairshop.saas.common.notify.ShopNotificationWriter;
import com.repairshop.saas.common.notify.ShopPushService;
import com.repairshop.saas.ticket.dto.StaffTaskDtos.StatusRequest;
import com.repairshop.saas.ticket.entity.StaffTask;
import com.repairshop.saas.ticket.entity.StaffTaskAssignee;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.repository.StaffTaskRepository;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** An employee's task status change reaches the shop owner (feed row + push); the owner's own does not. */
class StaffTaskShopNotifyTest {

    private final StaffTaskRepository repository = mock(StaffTaskRepository.class);
    private final TechnicianRepository technicians = mock(TechnicianRepository.class);
    private final ShopNotificationWriter feed = mock(ShopNotificationWriter.class);
    private final ShopPushService shopPush = mock(ShopPushService.class);
    private final StaffTaskService service = new StaffTaskService(repository, new ObjectMapper(), technicians,
            mock(PushService.class), feed, shopPush);

    private final UUID shopId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID techId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        StaffTask t = StaffTask.builder().id(taskId).shopId(shopId).taskType("DELIVERY").title("Paal packet vangittu vaaa")
                .taskDate(LocalDate.of(2026, 11, 1)).startTime(LocalTime.of(16, 0)).endTime(LocalTime.of(17, 0))
                .status("PENDING").historyJson("[]").checklistJson("[]").build();
        t.getAssignees().add(StaffTaskAssignee.builder().task(t).technicianId(techId).name("Nivetha").build());
        when(repository.findByShopIdAndId(shopId, taskId)).thenReturn(Optional.of(t));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(technicians.findByShopIdAndId(shopId, techId)).thenReturn(Optional.of(Technician.builder().id(techId).name("Nivetha").build()));
        when(feed.insert(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        // afterCommit runs the task immediately in this test.
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(shopPush).afterCommit(any());
    }

    @Test
    void employeeCompletingATaskIsNotAPartnerNotification() {
        // Partner allowlist: task completion stays in the task history only.
        service.setStatus(shopId, taskId, techId, new StatusRequest("COMPLETED", null));

        verifyNoInteractions(feed);
        verify(shopPush, never()).pushToShop(any(), any(), any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unableToCompleteNotifiesTheOwner() {
        service.setStatus(shopId, taskId, techId, new StatusRequest("INCOMPLETE", "Shop closed"));

        verify(feed).insert(eq(shopId), eq("team"), eq("STAFF_TASK_INCOMPLETE"), eq("Task not completed"),
                eq("Nivetha · Paal packet vangittu vaaa — Shop closed"), isNull(), isNull(), isNull());
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(shopPush).pushToShop(eq(shopId), eq("Task not completed"), eq("Nivetha · Paal packet vangittu vaaa — Shop closed"), data.capture());
        assertEquals("STAFF_TASK", data.getValue().get("kind"));
        assertEquals(taskId.toString(), data.getValue().get("taskId"));
    }

    @Test
    void unableToCompleteCarriesTheReason() {
        service.setStatus(shopId, taskId, techId, new StatusRequest("INCOMPLETE", "Shop closed"));
        verify(shopPush).pushToShop(eq(shopId), eq("Task not completed"), eq("Nivetha · Paal packet vangittu vaaa — Shop closed"), anyMap());
    }

    @Test
    void ownerChangingStatusDoesNotNotifyThemself() {
        service.setStatus(shopId, taskId, null, new StatusRequest("COMPLETED", null));
        verifyNoInteractions(feed);
        verify(shopPush, never()).pushToShop(any(), any(), any(), anyMap());
    }
}
