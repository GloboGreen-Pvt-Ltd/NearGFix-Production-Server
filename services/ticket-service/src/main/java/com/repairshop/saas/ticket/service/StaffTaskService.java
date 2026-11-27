package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.common.notify.PushApp;
import com.repairshop.saas.common.notify.PushService;
import com.repairshop.saas.ticket.dto.StaffTaskDtos.*;
import com.repairshop.saas.ticket.entity.StaffTask;
import com.repairshop.saas.ticket.entity.StaffTaskAssignee;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.repository.StaffTaskRepository;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Staff tasks for a shop: the owner creates and manages them; an assigned
 * employee reads their own and moves status / ticks the checklist.
 * Every change appends to the task's history.
 */
@Service
@RequiredArgsConstructor
public class StaffTaskService {

    private static final Set<String> TYPES = Set.of("SERVICE", "DELIVERY", "PARCEL", "SUPPLIER", "BANK_WORK", "SHOP_WORK", "OTHER");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> REPEATS = Set.of("NEVER", "DAILY", "WEEKLY", "MONTHLY");
    private static final Set<Integer> REMINDERS = Set.of(0, 5, 15, 30);
    private static final Set<String> STATUSES = Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "INCOMPLETE", "CANCELLED");
    /** What an employee may set; CANCELLED stays with the owner. */
    private static final Set<String> EMPLOYEE_STATUSES = Set.of("IN_PROGRESS", "COMPLETED", "INCOMPLETE");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final StaffTaskRepository repository;
    private final ObjectMapper objectMapper;
    private final TechnicianRepository technicianRepository;
    private final PushService pushService;
    private final com.repairshop.saas.common.notify.ShopNotificationWriter shopNotifications;
    private final com.repairshop.saas.common.notify.ShopPushService shopPush;

    // ---- Reads -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TaskResponse> listForShop(UUID shopId, LocalDate from, LocalDate to) {
        return repository.findByShopIdAndTaskDateBetweenOrderByTaskDateAscStartTimeAsc(shopId, from, to)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listForTechnician(UUID shopId, UUID technicianId, LocalDate from, LocalDate to) {
        return repository.findForTechnician(shopId, technicianId, from, to)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** technicianId null = owner (any task of the shop). */
    @Transactional(readOnly = true)
    public TaskResponse get(UUID shopId, UUID id, UUID technicianId) {
        return toResponse(load(shopId, id, technicianId));
    }

    // ---- Owner writes ----------------------------------------------------------

    @Transactional
    public TaskResponse create(UUID shopId, UUID userId, TaskRequest req) {
        String clientRef = trim(req.getClientRef(), 64);
        if (clientRef != null) {
            Optional<StaffTask> existing = repository.findByShopIdAndClientRef(shopId, clientRef);
            if (existing.isPresent()) return toResponse(existing.get());
        }
        String title = trim(req.getTitle(), 160);
        if (title == null) throw bad("Enter a task title.");
        LocalDate date = parseDate(req.getDate());
        LocalTime start = parseTime(req.getStartTime());
        LocalTime end = parseTime(req.getEndTime());
        checkTimes(start, end);
        List<Assignee> assignees = req.getAssignees();
        if (assignees == null || assignees.isEmpty()) throw bad("Assign at least one employee.");

        StaffTask t = StaffTask.builder()
                .shopId(shopId)
                .taskType(oneOf(req.getType(), TYPES, "OTHER"))
                .title(title)
                .description(trim(req.getDescription(), 4000))
                .taskDate(date)
                .startTime(start)
                .endTime(end)
                .priority(oneOf(req.getPriority(), PRIORITIES, "MEDIUM"))
                .reminderMinutes(req.getReminder() != null && REMINDERS.contains(req.getReminder()) ? req.getReminder() : 0)
                .repeatRule(oneOf(req.getRepeat(), REPEATS, "NEVER"))
                .location(trim(req.getLocation(), 500))
                .latitude(req.getCoords() != null ? req.getCoords().getLatitude() : null)
                .longitude(req.getCoords() != null ? req.getCoords().getLongitude() : null)
                .checklistJson(write(cleanChecklist(req.getChecklist(), true)))
                .status("PENDING")
                .clientRef(clientRef)
                .createdByUserId(userId)
                .build();
        replaceAssignees(t, assignees);
        appendHistory(t, "CREATED", "Assigned to " + names(t), "OWNER");
        StaffTask saved = repository.save(t);
        pushAssigned(saved, assigneeIds(saved), userId);
        return toResponse(saved);
    }

    /** Only the fields sent change; schedule and assignee changes are logged as such. */
    @Transactional
    public TaskResponse update(UUID shopId, UUID id, UUID userId, TaskRequest req) {
        StaffTask t = load(shopId, id, null);
        boolean edited = false;

        if (req.getTitle() != null) {
            String title = trim(req.getTitle(), 160);
            if (title == null) throw bad("Enter a task title.");
            t.setTitle(title); edited = true;
        }
        if (req.getDescription() != null) { t.setDescription(trim(req.getDescription(), 4000)); edited = true; }
        if (req.getType() != null) { t.setTaskType(oneOf(req.getType(), TYPES, t.getTaskType())); edited = true; }
        if (req.getPriority() != null) { t.setPriority(oneOf(req.getPriority(), PRIORITIES, t.getPriority())); edited = true; }
        if (req.getReminder() != null) { t.setReminderMinutes(REMINDERS.contains(req.getReminder()) ? req.getReminder() : 0); edited = true; }
        if (req.getRepeat() != null) { t.setRepeatRule(oneOf(req.getRepeat(), REPEATS, t.getRepeatRule())); edited = true; }
        if (req.getLocation() != null) {
            t.setLocation(trim(req.getLocation(), 500));
            t.setLatitude(req.getCoords() != null ? req.getCoords().getLatitude() : null);
            t.setLongitude(req.getCoords() != null ? req.getCoords().getLongitude() : null);
            edited = true;
        }
        if (req.getChecklist() != null) { t.setChecklistJson(write(cleanChecklist(req.getChecklist(), false))); edited = true; }
        if (edited) appendHistory(t, "EDITED", null, "OWNER");

        if (req.getDate() != null || req.getStartTime() != null || req.getEndTime() != null) {
            LocalDate date = req.getDate() != null ? parseDate(req.getDate()) : t.getTaskDate();
            LocalTime start = req.getStartTime() != null ? parseTime(req.getStartTime()) : t.getStartTime();
            LocalTime end = req.getEndTime() != null ? parseTime(req.getEndTime()) : t.getEndTime();
            checkTimes(start, end);
            t.setTaskDate(date); t.setStartTime(start); t.setEndTime(end);
            // A rescheduled "couldn't finish" task goes back to the queue.
            if ("INCOMPLETE".equals(t.getStatus())) { t.setStatus("PENDING"); t.setIncompleteReason(null); }
            appendHistory(t, "RESCHEDULED", date + " " + start.format(HHMM) + " - " + end.format(HHMM), "OWNER");
        }

        Set<UUID> newlyAssigned = new HashSet<>();
        if (req.getAssignees() != null) {
            if (req.getAssignees().isEmpty()) throw bad("Assign at least one employee.");
            String before = names(t);
            Set<UUID> previous = assigneeIds(t);
            replaceAssignees(t, req.getAssignees());
            appendHistory(t, "REASSIGNED", before + " -> " + names(t), "OWNER");
            newlyAssigned = assigneeIds(t);
            newlyAssigned.removeAll(previous);
        }
        StaffTask saved = repository.save(t);
        pushAssigned(saved, newlyAssigned, userId);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID shopId, UUID id) {
        repository.delete(load(shopId, id, null));
    }

    // ---- Owner or assigned employee ---------------------------------------------

    /** technicianId null = owner. */
    @Transactional
    public TaskResponse setStatus(UUID shopId, UUID id, UUID technicianId, StatusRequest req) {
        StaffTask t = load(shopId, id, technicianId);
        String status = req.getStatus() == null ? "" : req.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw bad("Choose a valid status.");
        if (technicianId != null && !EMPLOYEE_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop owner can do this.");
        }
        String note = trim(req.getNote(), 500);
        if ("INCOMPLETE".equals(status) && note == null) throw bad("Add a short reason.");
        t.setStatus(status);
        t.setIncompleteReason("INCOMPLETE".equals(status) ? note : null);
        appendHistory(t, status, note, technicianId == null ? "OWNER" : "EMPLOYEE");
        TaskResponse saved = toResponse(repository.save(t));
        if (technicianId != null) notifyShopOfEmployeeStatus(t, technicianId, status, note);
        return saved;
    }

    // Partner App: an employee moved a task on (started / completed / could not
    // complete) — the owner hears about it: a feed row (Team tab) and a push
    // (kind STAFF_TASK, opens the task). After commit, on the push thread, so it
    // can never undo the employee's status change.
    private void notifyShopOfEmployeeStatus(StaffTask t, UUID technicianId, String status, String note) {
        // Partner allowlist: only "Task not completed" notifies; started / completed / updated
        // stay in the task's history (PartnerNotificationPolicy).
        if (!com.repairshop.saas.common.notify.PartnerNotificationPolicy.allows("team", "STAFF_TASK_" + status)) return;
        String who = technicianRepository.findByShopIdAndId(t.getShopId(), technicianId)
                .map(Technician::getName).filter(n -> n != null && !n.isBlank()).map(String::trim)
                .orElse("An employee");
        String title = switch (status) {
            case "IN_PROGRESS" -> "Task started";
            case "COMPLETED" -> "Task completed";
            case "INCOMPLETE" -> "Task not completed";
            default -> "Task updated";
        };
        String body = who + " · " + t.getTitle() + ("INCOMPLETE".equals(status) && note != null ? " — " + note : "");
        UUID shopId = t.getShopId();
        String taskId = String.valueOf(t.getId());
        shopPush.afterCommit(() -> {
            UUID notificationId = shopNotifications.insert(shopId, "team", "STAFF_TASK_" + status, title, body,
                    null, null, null);
            Map<String, Object> data = new HashMap<>();
            data.put("kind", "STAFF_TASK");
            data.put("taskId", taskId);
            data.put("notificationId", notificationId.toString());
            data.put("statusKey", "STAFF_TASK_" + status);
            shopPush.pushToShop(shopId, title, body, data);
        });
    }

    @Transactional
    public TaskResponse toggleChecklistItem(UUID shopId, UUID id, UUID technicianId, String itemId) {
        StaffTask t = load(shopId, id, technicianId);
        List<ChecklistItem> items = readChecklist(t.getChecklistJson());
        boolean found = false;
        for (ChecklistItem c : items) {
            if (Objects.equals(c.getId(), itemId)) { c.setDone(!c.isDone()); found = true; }
        }
        if (!found) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found.");
        t.setChecklistJson(write(items));
        return toResponse(repository.save(t));
    }

    // ---- Helpers ---------------------------------------------------------------

    /** A task of this shop; for an employee, only one they are assigned to. */
    private StaffTask load(UUID shopId, UUID id, UUID technicianId) {
        StaffTask t = repository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found."));
        if (technicianId != null && t.getAssignees().stream().noneMatch(a -> technicianId.equals(a.getTechnicianId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found.");
        }
        return t;
    }

    private static Set<UUID> assigneeIds(StaffTask t) {
        return t.getAssignees().stream().map(StaffTaskAssignee::getTechnicianId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    // Employee app: each newly assigned employee hears about the task. Assignees
    // hold technicians.id; technicians.user_id is the auth user id the device is
    // registered under. Never the owner who made the change. Sent after commit.
    private void pushAssigned(StaffTask t, Set<UUID> technicianIds, UUID actorUserId) {
        if (technicianIds.isEmpty()) return;
        List<UUID> userIds = new ArrayList<>();
        for (UUID techId : technicianIds) {
            technicianRepository.findByShopIdAndId(t.getShopId(), techId)
                    .map(Technician::getUserId)
                    .filter(u -> !u.equals(actorUserId))
                    .ifPresent(userIds::add);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("kind", "TASK_ASSIGNED");
        data.put("taskId", t.getId());
        pushService.pushToUsers(PushApp.EMPLOYEE, userIds, "New task assigned", t.getTitle(), data);
    }

    private void replaceAssignees(StaffTask t, List<Assignee> list) {
        t.getAssignees().clear();
        Set<UUID> seen = new HashSet<>();
        for (Assignee a : list) {
            UUID techId;
            try { techId = UUID.fromString(String.valueOf(a.getId())); }
            catch (IllegalArgumentException e) { throw bad("Choose employees from the list."); }
            if (!seen.add(techId)) continue;
            t.getAssignees().add(StaffTaskAssignee.builder()
                    .task(t)
                    .technicianId(techId)
                    .name(trim(a.getName(), 120))
                    .roleLabel(trim(a.getRoleLabel(), 60))
                    .phone(trim(a.getPhone(), 20))
                    .photoUrl(trim(a.getPhotoUrl(), 500))
                    .build());
        }
    }

    private List<ChecklistItem> cleanChecklist(List<ChecklistItem> items, boolean resetDone) {
        if (items == null) return new ArrayList<>();
        List<ChecklistItem> out = new ArrayList<>();
        for (ChecklistItem c : items) {
            String text = trim(c.getText(), 200);
            if (text == null) continue;
            String cid = c.getId() == null || c.getId().isBlank() || c.getId().startsWith("new_")
                    ? UUID.randomUUID().toString() : c.getId();
            out.add(new ChecklistItem(cid, text, !resetDone && c.isDone()));
        }
        return out;
    }

    private void appendHistory(StaffTask t, String action, String note, String by) {
        List<HistoryItem> h = readHistory(t.getHistoryJson());
        h.add(new HistoryItem(Instant.now().toString(), action, note, by));
        t.setHistoryJson(write(h));
    }

    private TaskResponse toResponse(StaffTask t) {
        Coords coords = t.getLatitude() != null && t.getLongitude() != null ? new Coords(t.getLatitude(), t.getLongitude()) : null;
        return TaskResponse.builder()
                .id(t.getId().toString())
                .type(t.getTaskType())
                .title(t.getTitle())
                .description(t.getDescription() == null ? "" : t.getDescription())
                .date(t.getTaskDate().toString())
                .startTime(t.getStartTime().format(HHMM))
                .endTime(t.getEndTime().format(HHMM))
                .assignees(t.getAssignees().stream().map(a -> new Assignee(
                        a.getTechnicianId().toString(), a.getName(), a.getRoleLabel(), a.getPhone(), a.getPhotoUrl()))
                        .collect(Collectors.toList()))
                .priority(t.getPriority())
                .reminder(t.getReminderMinutes())
                .repeat(t.getRepeatRule())
                .location(t.getLocation() == null ? "" : t.getLocation())
                .coords(coords)
                .checklist(readChecklist(t.getChecklistJson()))
                .status(t.getStatus())
                .incompleteReason(t.getIncompleteReason())
                .history(readHistory(t.getHistoryJson()))
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    private static String names(StaffTask t) {
        return t.getAssignees().stream().map(a -> a.getName() == null ? "Employee" : a.getName()).collect(Collectors.joining(", "));
    }

    private List<ChecklistItem> readChecklist(String json) {
        return read(json, new TypeReference<List<ChecklistItem>>() {});
    }

    private List<HistoryItem> readHistory(String json) {
        return read(json, new TypeReference<List<HistoryItem>>() {});
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<T> list = objectMapper.readValue(json, type);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the task.");
        }
    }

    private static LocalDate parseDate(String raw) {
        try { return LocalDate.parse(String.valueOf(raw).trim()); }
        catch (DateTimeParseException e) { throw bad("Choose a date."); }
    }

    private static LocalTime parseTime(String raw) {
        try { return LocalTime.parse(String.valueOf(raw).trim(), HHMM); }
        catch (DateTimeParseException e) { throw bad("Enter start and end time as HH:mm (24-hour)."); }
    }

    private static void checkTimes(LocalTime start, LocalTime end) {
        if (!end.isAfter(start)) throw bad("End time must be after the start time.");
    }

    private static String oneOf(String raw, Set<String> allowed, String fallback) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
