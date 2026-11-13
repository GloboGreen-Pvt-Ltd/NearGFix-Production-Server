package com.repairshop.saas.ticket.service;

import com.repairshop.saas.common.subscription.LimitCheck;
import com.repairshop.saas.common.subscription.SubscriptionFeature;
import com.repairshop.saas.common.subscription.SubscriptionLimitService;
import com.repairshop.saas.ticket.dto.*;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.TechnicianAttendance;
import com.repairshop.saas.ticket.entity.TechnicianLeave;
import com.repairshop.saas.ticket.entity.TechnicianSalaryAdvance;
import com.repairshop.saas.ticket.entity.TechnicianExperience;
import com.repairshop.saas.ticket.exception.AttendanceBlockedException;
import com.repairshop.saas.ticket.exception.ResourceNotFoundException;
import com.repairshop.saas.ticket.repository.TechnicianAttendanceRepository;
import com.repairshop.saas.ticket.repository.TechnicianLeaveRepository;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.repository.TechnicianSalaryAdvanceRepository;
import com.repairshop.saas.ticket.repository.TechnicianExperienceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TechnicianService {

    private final TechnicianRepository technicianRepository;
    private final TechnicianAttendanceRepository attendanceRepository;
    private final TechnicianLeaveRepository leaveRepository;
    private final TechnicianSalaryAdvanceRepository advanceRepository;
    private final TechnicianExperienceRepository experienceRepository;
    private final JdbcTemplate jdbc;
    private final SubscriptionLimitService subscriptionLimits;

    // 100m shop geofence for attendance punches — same radius the product spec
    // asked for (the pickup-person "Reached Shop" gate uses 50m separately).
    private static final double SHOP_ATTENDANCE_RADIUS_METERS = 100.0;
    // EC2 JVM default zone is UTC; attendance wall-clock must be IST regardless of host zone.
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // A check-in within this many minutes of the duty start still counts as
    // on-time (the "5-minute grace"). Applied both when the row is first
    // classified and when toAttendanceRecord re-derives the effective status.
    private static final int LATE_GRACE_MINUTES = 5;
    // Leave types that permit an early check-out (before the duty end time).
    private static final Set<String> EARLY_CHECKOUT_LEAVE_TYPES =
            new HashSet<>(Arrays.asList("HALF_DAY", "PERMISSION"));

    @Transactional(readOnly = true)
    public List<TechnicianResponse> listByShop(UUID shopId) {
        return technicianRepository.findByShopIdOrderByNameAsc(shopId).stream()
                .filter(t -> !t.isOwner()) // owner's own row is "Assign to Myself", not an employee
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TechnicianResponse getById(UUID shopId, UUID id) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + id));
        return toResponse(t);
    }

    /** Current technician profile by JWT userId (must belong to shop from JWT). */
    @Transactional(readOnly = true)
    public TechnicianResponse getByUserId(UUID shopId, UUID userId) {
        Technician t = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician profile not found"));
        TechnicianResponse resp = toResponse(t);
        // Only /me carries the shop's coordinates — the employee app needs them
        // for the 100m attendance geofence. Kept off the list/getById responses
        // so those don't pay a per-row shops lookup.
        resp.setShopId(t.getShopId());
        double[] coords = lookupShopCoords(t.getShopId());
        if (coords != null) {
            resp.setShopLatitude(coords[0]);
            resp.setShopLongitude(coords[1]);
        }
        return resp;
    }

    /** Shop's saved lat/lng, or null if the shop has none configured. */
    private double[] lookupShopCoords(UUID shopId) {
        if (shopId == null) return null;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT latitude, longitude FROM shops WHERE id = CAST(? AS UUID)",
                    shopId.toString());
            Double lat = toDouble(row.get("latitude"));
            Double lng = toDouble(row.get("longitude"));
            if (lat == null || lng == null) return null;
            return new double[] { lat, lng };
        } catch (Exception e) {
            return null;
        }
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Delete an employee: removes the technician row and, importantly, the auth
     * `users` login row it was linked to — so no orphaned login survives.
     *
     * The technician's owned HR data (attendance / leave / salary_advance /
     * experience) is removed automatically by ON DELETE CASCADE, and
     * tickets.assigned_technician_id is nulled by ON DELETE SET NULL. KYC
     * documents have no FK cascade, so they're deleted explicitly first.
     * repair_bookings assignment pointers have no FK and are left as historical.
     */
    @Transactional
    public void deleteTechnician(UUID shopId, UUID id) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + id));
        UUID userId = t.getUserId();

        // KYC docs (no FK cascade) — clear before the row goes.
        try {
            jdbc.update("DELETE FROM technician_kyc_documents WHERE technician_id = CAST(? AS uuid)",
                    id.toString());
        } catch (Exception ignore) {
            // best-effort; absence of the table/rows must not block deletion
        }

        // Delete the technician; DB cascades handle attendance/leave/advance/
        // experience and null out ticket assignments. Flush so the row (and its
        // FK to users) is actually gone before we delete the login below.
        technicianRepository.delete(t);
        technicianRepository.flush();

        // Remove the linked auth login (users table lives in the shared DB).
        if (userId != null) {
            try {
                jdbc.update("DELETE FROM users WHERE id = CAST(? AS uuid)", userId.toString());
            } catch (Exception ignore) {
                // best-effort — the employee is already removed; a user referenced
                // elsewhere simply stays. Log-free to avoid noise on shared rows.
            }
        }
    }

    /** Self-update: only name, phone, photoUrl, defaultCheckIn, defaultCheckOut. */
    @Transactional
    public TechnicianResponse updateMe(UUID shopId, UUID userId, UpdateTechnicianRequest request) {
        Technician t = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician profile not found"));
        if (request.getName() != null) t.setName(request.getName().trim());
        if (request.getPhone() != null) t.setPhone(request.getPhone().trim());
        if (request.getPhotoUrl() != null) t.setPhotoUrl(request.getPhotoUrl().trim());
        if (request.getDefaultCheckIn() != null) t.setDefaultCheckIn(request.getDefaultCheckIn());
        if (request.getDefaultCheckOut() != null) t.setDefaultCheckOut(request.getDefaultCheckOut());
        t = technicianRepository.save(t);
        return toResponse(t);
    }

    /**
     * The shop's employee allowance and how much of it is used — the single
     * calculation behind the "3/3" counter, the disabled Add button, and the
     * rejection thrown by {@link #create}.
     *
     * <p>Exposed so the app can render the state it is about to be held to
     * instead of guessing at it; the guess is what produced "4/4 Active" on a
     * plan that allows three.
     */
    @Transactional(readOnly = true)
    public LimitCheck employeeLimit(UUID shopId) {
        return subscriptionLimits.checkForShop(
                shopId, SubscriptionFeature.EMPLOYEES, subscriptionLimits.countActiveEmployees(shopId));
    }

    @Transactional
    public TechnicianResponse create(UUID shopId, CreateTechnicianRequest request) {
        // The backend is the authority: the client's own pre-check is a courtesy
        // that keeps the user out of a form they cannot submit, and nothing the
        // client sends is consulted here. The plan and the count are both read
        // server-side from the authenticated shop.
        subscriptionLimits.requireCapacity(shopId, SubscriptionFeature.EMPLOYEES,
                subscriptionLimits.countActiveEmployees(shopId));

        Technician t = Technician.builder()
                .shopId(shopId)
                .userId(request.getUserId())
                .name(request.getName() != null ? request.getName().trim() : null)
                .email(request.getEmail() != null ? request.getEmail().trim() : null)
                .phone(request.getPhone() != null ? request.getPhone().trim() : null)
                .roleLabel(request.getRoleLabel() != null ? request.getRoleLabel().trim() : null)
                .isAvailable(true)
                .salaryAmount(request.getSalaryAmount() != null ? request.getSalaryAmount().trim() : null)
                .salaryPeriod(request.getSalaryPeriod() != null ? request.getSalaryPeriod().trim() : null)
                .idVerificationType(request.getIdVerificationType() != null ? request.getIdVerificationType().trim() : null)
                .idNumber(request.getIdNumber() != null ? request.getIdNumber().trim() : null)
                .aadharNumber(request.getAadharNumber() != null ? request.getAadharNumber().trim() : null)
                .aadharFrontUrl(request.getAadharFrontUrl() != null ? request.getAadharFrontUrl().trim() : null)
                .aadharBackUrl(request.getAadharBackUrl() != null ? request.getAadharBackUrl().trim() : null)
                .panNumber(request.getPanNumber() != null ? request.getPanNumber().trim() : null)
                .panFrontUrl(request.getPanFrontUrl() != null ? request.getPanFrontUrl().trim() : null)
                .panBackUrl(request.getPanBackUrl() != null ? request.getPanBackUrl().trim() : null)
                .dailyWage(request.getDailyWage() != null ? request.getDailyWage().trim() : null)
                .dateOfBirth(request.getDateOfBirth())
                .dateOfJoin(request.getDateOfJoin())
                .defaultCheckIn(request.getDefaultCheckIn())
                .defaultCheckOut(request.getDefaultCheckOut())
                .photoUrl(request.getPhotoUrl() != null ? request.getPhotoUrl().trim() : null)
                .build();
        t = technicianRepository.save(t);
        return toResponse(t);
    }

    @Transactional
    public TechnicianResponse update(UUID shopId, UUID id, UpdateTechnicianRequest request) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + id));
        if (request.getName() != null) t.setName(request.getName().trim());
        if (request.getEmail() != null) t.setEmail(request.getEmail().trim());
        if (request.getPhone() != null) t.setPhone(request.getPhone().trim());
        if (request.getRoleLabel() != null) t.setRoleLabel(request.getRoleLabel().trim());
        // Re-activation consumes a seat, so it is gated the same way creation is
        // — otherwise the limit is trivially bypassed by deactivating someone,
        // hiring a replacement, and switching the first one back on. Only a
        // genuine off→on transition is checked: re-saving an already-active
        // employee must not count them against the allowance a second time.
        if (request.getIsAvailable() != null) {
            if (request.getIsAvailable() && !t.isAvailable()) {
                subscriptionLimits.requireCapacity(shopId, SubscriptionFeature.EMPLOYEES,
                        subscriptionLimits.countActiveEmployees(shopId));
            }
            t.setAvailable(request.getIsAvailable());
        }
        if (request.getSalaryAmount() != null) t.setSalaryAmount(request.getSalaryAmount().trim());
        if (request.getSalaryPeriod() != null) t.setSalaryPeriod(request.getSalaryPeriod().trim());
        if (request.getIdVerificationType() != null) t.setIdVerificationType(request.getIdVerificationType().trim());
        if (request.getIdNumber() != null) t.setIdNumber(request.getIdNumber().trim());
        if (request.getAadharNumber() != null) t.setAadharNumber(request.getAadharNumber().trim());
        if (request.getAadharFrontUrl() != null) t.setAadharFrontUrl(request.getAadharFrontUrl().trim());
        if (request.getAadharBackUrl() != null) t.setAadharBackUrl(request.getAadharBackUrl().trim());
        if (request.getPanNumber() != null) t.setPanNumber(request.getPanNumber().trim());
        if (request.getPanFrontUrl() != null) t.setPanFrontUrl(request.getPanFrontUrl().trim());
        if (request.getPanBackUrl() != null) t.setPanBackUrl(request.getPanBackUrl().trim());
        if (request.getDailyWage() != null) t.setDailyWage(request.getDailyWage().trim());
        if (request.getDateOfBirth() != null) t.setDateOfBirth(request.getDateOfBirth());
        if (request.getDateOfJoin() != null) t.setDateOfJoin(request.getDateOfJoin());
        if (request.getDefaultCheckIn() != null) t.setDefaultCheckIn(request.getDefaultCheckIn());
        if (request.getDefaultCheckOut() != null) t.setDefaultCheckOut(request.getDefaultCheckOut());
        if (request.getPhotoUrl() != null) t.setPhotoUrl(request.getPhotoUrl().trim());
        t = technicianRepository.save(t);
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getAttendance(UUID shopId, UUID technicianId, int month, int year) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<TechnicianAttendance> list = attendanceRepository.findByTechnicianIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                t.getId(), start, end);

        // Expand approved leave requests overlapping the month into a set of dates so they
        // count even when no per-day attendance row was created.
        Set<LocalDate> approvedLeaveDates = new HashSet<>();
        leaveRepository.findByTechnicianIdAndStartDateBetweenOrderByRequestedAtDesc(t.getId(), start, end)
                .forEach(l -> {
                    if (!"APPROVED".equalsIgnoreCase(l.getStatus())) return;
                    LocalDate cursor = l.getStartDate();
                    LocalDate endDate = l.getEndDate() != null ? l.getEndDate() : cursor;
                    while (!cursor.isAfter(endDate)) {
                        if (!cursor.isBefore(start) && !cursor.isAfter(end)) approvedLeaveDates.add(cursor);
                        cursor = cursor.plusDays(1);
                    }
                });

        Map<LocalDate, TechnicianAttendance> byDate = new HashMap<>();
        for (TechnicianAttendance a : list) byDate.put(a.getAttendanceDate(), a);

        LocalTime defaultCheckIn = t.getDefaultCheckIn();
        int present = 0, leave = 0, permission = 0, holiday = 0;
        long lateMinutes = 0;

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            TechnicianAttendance a = byDate.get(d);
            boolean isSunday = d.getDayOfWeek() == DayOfWeek.SUNDAY;

            if (a == null) {
                if (approvedLeaveDates.contains(d)) leave++;
                else if (isSunday) holiday++;
                continue;
            }

            // Normalise: a row with a check-in but no status is effectively a present day.
            String status = a.getStatus() != null ? a.getStatus().toUpperCase(Locale.ROOT) : null;
            if (status == null || status.isBlank()) {
                status = a.getCheckInTime() != null ? "GENERAL" : (isSunday ? "WEEK_OFF" : null);
            }
            if (status == null) continue;

            switch (status) {
                case "GENERAL":
                case "LATE":
                case "PERMISSION":
                    present++;
                    if ("PERMISSION".equals(status)) permission++;
                    // Late-hours accrue for any present-style day where the actual
                    // check-in is later than the configured default.
                    if (a.getCheckInTime() != null && defaultCheckIn != null
                            && a.getCheckInTime().isAfter(defaultCheckIn)) {
                        lateMinutes += Duration.between(defaultCheckIn, a.getCheckInTime()).toMinutes();
                    }
                    break;
                case "LEAVE":
                    leave++;
                    break;
                case "HOLIDAY":
                case "WEEK_OFF":
                    holiday++;
                    break;
                default:
                    break;
            }
        }

        String lateHours;
        if (lateMinutes <= 0) {
            lateHours = "0";
        } else {
            double hours = lateMinutes / 60.0;
            lateHours = String.format(Locale.ROOT, "%.1f", hours).replaceAll("\\.0$", "");
        }

        final LocalTime techDefaultCheckIn = defaultCheckIn;
        List<AttendanceRecordResponse> records = list.stream()
                .map(rec -> toAttendanceRecord(rec, techDefaultCheckIn))
                .collect(Collectors.toList());
        return AttendanceSummaryResponse.builder()
                .month(month)
                .year(year)
                .presentDays(present)
                .lateHours(lateHours)
                .permissionCount(permission)
                .leaveDays(leave)
                .holidayCount(holiday)
                .dailyRecords(records)
                .build();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> getLeaves(UUID shopId, UUID technicianId, Integer month, Integer year) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        List<TechnicianLeave> list;
        if (month != null && year != null) {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            list = leaveRepository.findByTechnicianIdAndStartDateBetweenOrderByRequestedAtDesc(t.getId(), start, end);
        } else {
            list = leaveRepository.findByTechnicianIdOrderByRequestedAtDesc(t.getId());
        }
        return list.stream().map(this::toLeaveResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PayslipResponse> getPayslips(UUID shopId, UUID technicianId, int year) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        List<PayslipResponse> result = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            result.add(buildPayslip(t, month, year));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PayslipResponse getPayslip(UUID shopId, UUID technicianId, int month, int year) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        return buildPayslip(t, month, year);
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecordResponse> getAttendanceForDay(UUID shopId, UUID technicianId, LocalDate date) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        return attendanceRepository.findByTechnicianIdAndAttendanceDate(t.getId(), date)
                .map(a -> toAttendanceRecord(a, t.getDefaultCheckIn()));
    }

    @Transactional(readOnly = true)
    public List<SalaryAdvanceResponse> getAdvances(UUID shopId, UUID technicianId) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        return advanceRepository.findTop10ByTechnicianIdOrderByRequestedAtDesc(t.getId()).stream()
                .map(this::toAdvanceResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public SalaryAdvanceResponse createAdvance(UUID shopId, UUID technicianId, CreateSalaryAdvanceRequest request) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        LocalDate advanceDate = request.getAdvanceDate() != null ? request.getAdvanceDate() : LocalDate.now();
        TechnicianSalaryAdvance a = TechnicianSalaryAdvance.builder()
                .technicianId(t.getId())
                .amount(request.getAmount())
                .advanceDate(advanceDate)
                .status("UNPAID")
                .notes(request.getNotes())
                .build();
        a = advanceRepository.save(a);
        return toAdvanceResponse(a);
    }

    @Transactional(readOnly = true)
    public List<ExperienceResponse> getExperiences(UUID shopId, UUID technicianId) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        return experienceRepository.findByTechnicianIdOrderByJoinDateDesc(t.getId()).stream()
                .map(this::toExperienceResponse)
                .collect(Collectors.toList());
    }

    /**
     * Allowed leave types (validated up-front so 400s are returned for typos
     * instead of letting an opaque string through to the column). Kept as a
     * constant set so the same list backs the OpenAPI schema and the unit
     * tests without drifting.
     */
    private static final Set<String> ALLOWED_LEAVE_TYPES = new HashSet<>(Arrays.asList(
            "CASUAL_LEAVE", "SICK_LEAVE", "EMERGENCY_LEAVE", "PERMISSION", "HALF_DAY", "OTHER"));

    @Transactional
    public LeaveRequestResponse createLeave(UUID shopId, UUID technicianId, CreateLeaveRequest request) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));

        // Field-level validation. @Valid catches @NotNull on start/end dates; the
        // remaining checks (range ordering, reason required, type whitelist,
        // overlap with existing PENDING/APPROVED leave) live here so the
        // controller can stay thin and so every entry point (`/me/leaves`
        // and `/{id}/leaves`) gets the same guarantees.
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("Start date and end date are required");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (request.getReason() == null || request.getReason().trim().isEmpty()) {
            throw new IllegalArgumentException("Reason is required");
        }
        String leaveType = request.getLeaveType() != null ? request.getLeaveType().trim().toUpperCase() : null;
        if (leaveType != null && !leaveType.isEmpty() && !ALLOWED_LEAVE_TYPES.contains(leaveType)) {
            throw new IllegalArgumentException("Invalid leaveType: " + request.getLeaveType()
                    + " (allowed: " + ALLOWED_LEAVE_TYPES + ")");
        }

        List<TechnicianLeave> overlapping = leaveRepository.findOverlapping(
                t.getId(), request.getStartDate(), request.getEndDate());
        if (!overlapping.isEmpty()) {
            throw new IllegalArgumentException(
                    "You already have a pending or approved leave overlapping this date range");
        }

        BigDecimal totalDays = request.getTotalDays();
        if (totalDays == null) {
            long span = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
            // Half-day applications always represent 0.5 regardless of the date span,
            // so the salary math downstream stays consistent.
            totalDays = "HALF_DAY".equals(leaveType)
                    ? new BigDecimal("0.5")
                    : BigDecimal.valueOf(span);
        }

        TechnicianLeave leave = TechnicianLeave.builder()
                .technicianId(t.getId())
                .leaveType(leaveType)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .totalDays(totalDays)
                .reason(request.getReason().trim())
                .attachmentUrl(request.getAttachmentUrl())
                .status("PENDING")
                .build();
        leave = leaveRepository.save(leave);
        return toLeaveResponse(leave, t.getName());
    }

    @Transactional
    public LeaveRequestResponse updateLeaveStatus(UUID shopId, UUID technicianId, UUID leaveId,
                                                  UUID actorUserId, LeaveStatusUpdateRequest request) {
        Technician t = technicianRepository.findByShopIdAndId(shopId, technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician not found: " + technicianId));
        TechnicianLeave leave = leaveRepository.findByTechnicianIdAndId(t.getId(), leaveId)
                .orElseThrow(() -> new ResourceNotFoundException("Leave not found: " + leaveId));

        String status = request.getStatus() != null ? request.getStatus().trim().toUpperCase() : null;
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("status must be APPROVED or REJECTED");
        }
        // Only transition from a still-open state. Re-approving an already
        // approved (or already rejected) leave would silently overwrite the
        // original approver/timestamp; surface as a 400 instead.
        String current = leave.getStatus() != null ? leave.getStatus().toUpperCase() : "";
        if (!"PENDING".equals(current) && !"PROCESSING".equals(current)) {
            throw new IllegalArgumentException("Leave is already " + current + " and can no longer be modified");
        }

        Instant now = Instant.now();
        if ("APPROVED".equals(status)) {
            leave.setStatus("APPROVED");
            leave.setApprovedBy(actorUserId);
            leave.setApprovedAt(now);
            leave.setRemarks(request.getRemarks());
        } else {
            if (request.getRejectionReason() == null || request.getRejectionReason().trim().isEmpty()) {
                throw new IllegalArgumentException("rejectionReason is required when rejecting a leave");
            }
            leave.setStatus("REJECTED");
            leave.setRejectedBy(actorUserId);
            leave.setRejectedAt(now);
            leave.setRejectionReason(request.getRejectionReason().trim());
        }
        leave = leaveRepository.save(leave);
        return toLeaveResponse(leave, t.getName());
    }

    /** Technician check-in for today. Creates or updates today's attendance with checkInTime. */
    @Transactional
    public AttendanceRecordResponse recordCheckIn(UUID shopId, UUID userId, String notes,
                                                  Double latitude, Double longitude) {
        Technician t = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician profile not found"));
        // 100m shop geofence — must be at the shop to punch in. Returns the
        // measured distance (null when the shop has no coordinates configured,
        // in which case the gate is skipped).
        Integer distanceMeters = enforceShopGeofence(t.getShopId(), latitude, longitude);
        LocalDate today = LocalDate.now(IST);
        TechnicianAttendance a = attendanceRepository.findByTechnicianIdAndAttendanceDate(t.getId(), today)
                .orElse(TechnicianAttendance.builder()
                        .technicianId(t.getId())
                        .attendanceDate(today)
                        .status("GENERAL")
                        .build());
        LocalTime now = LocalTime.now(IST);
        a.setCheckInTime(now);
        // Auto-classify LATE if check-in exceeds the configured default plus the
        // grace window. Leave PERMISSION / LEAVE statuses alone so an explicit
        // owner override isn't clobbered.
        String currentStatus = a.getStatus();
        if (currentStatus == null || "GENERAL".equalsIgnoreCase(currentStatus) || "LATE".equalsIgnoreCase(currentStatus)) {
            a.setStatus(isLate(t.getDefaultCheckIn(), now) ? "LATE" : "GENERAL");
        }
        if (latitude != null) a.setCheckInLatitude(BigDecimal.valueOf(latitude));
        if (longitude != null) a.setCheckInLongitude(BigDecimal.valueOf(longitude));
        if (distanceMeters != null) a.setCheckInDistanceMeters(distanceMeters);
        if (notes != null && !notes.isBlank()) a.setNotes(notes);
        a = attendanceRepository.save(a);
        return toAttendanceRecord(a, t.getDefaultCheckIn());
    }

    /** Technician check-out for today. Updates today's attendance with checkOutTime. */
    @Transactional
    public AttendanceRecordResponse recordCheckOut(UUID shopId, UUID userId, String notes,
                                                   Double latitude, Double longitude) {
        Technician t = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician profile not found"));
        LocalDate today = LocalDate.now(IST);
        TechnicianAttendance a = attendanceRepository.findByTechnicianIdAndAttendanceDate(t.getId(), today)
                .orElseThrow(() -> new ResourceNotFoundException("No check-in found for today. Check in first."));
        LocalTime now = LocalTime.now(IST);
        // Same 100m geofence as check-in — you must be at the shop to punch out.
        enforceShopGeofence(t.getShopId(), latitude, longitude);
        // Early-checkout guard: no leaving before the duty end time unless an
        // approved HALF_DAY / PERMISSION covers today.
        enforceCheckoutTimeGate(t, now);
        a.setCheckOutTime(now);
        if (a.getCheckInTime() != null) {
            long min = Duration.between(a.getCheckInTime(), now).toMinutes();
            a.setWorkingMinutes((int) Math.max(0, min));
        }
        if (latitude != null) a.setCheckOutLatitude(BigDecimal.valueOf(latitude));
        if (longitude != null) a.setCheckOutLongitude(BigDecimal.valueOf(longitude));
        if (notes != null && !notes.isBlank()) a.setNotes(a.getNotes() != null ? a.getNotes() + "; " + notes : notes);
        a = attendanceRepository.save(a);
        return toAttendanceRecord(a, t.getDefaultCheckIn());
    }

    /**
     * 100m shop geofence for attendance punches. Returns the measured distance
     * in metres, or null when the shop has no coordinates configured (gate is
     * skipped — can't enforce what isn't set). Throws {@link
     * AttendanceBlockedException} (422) when location is missing or out of range.
     */
    private Integer enforceShopGeofence(UUID shopId, Double lat, Double lng) {
        double[] shop = lookupShopCoords(shopId);
        if (shop == null) return null; // shop has no saved coordinates → fail open
        if (lat == null || lng == null) {
            throw new AttendanceBlockedException("LOCATION_REQUIRED", "Enable location and try again.");
        }
        double meters = haversineMeters(lat, lng, shop[0], shop[1]);
        int distance = (int) Math.round(meters);
        if (meters > SHOP_ATTENDANCE_RADIUS_METERS) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("distanceMeters", distance);
            details.put("radiusMeters", (int) SHOP_ATTENDANCE_RADIUS_METERS);
            throw new AttendanceBlockedException(422, "OUT_OF_RADIUS",
                    "You are " + distance + "m from the shop. Get within "
                            + (int) SHOP_ATTENDANCE_RADIUS_METERS + "m to continue.",
                    details);
        }
        return distance;
    }

    /**
     * Blocks check-out before the technician's duty end time unless an approved
     * HALF_DAY / PERMISSION leave covers today. No duty end configured → allowed.
     */
    private void enforceCheckoutTimeGate(Technician t, LocalTime now) {
        LocalTime dutyEnd = t.getDefaultCheckOut();
        if (dutyEnd == null || !now.isBefore(dutyEnd)) return; // on-time or late → fine
        if (hasApprovedEarlyLeaveToday(t.getId())) return;     // half-day / permission → fine
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("allowedTime", dutyEnd.toString());
        throw new AttendanceBlockedException(422, "EARLY_CHECKOUT_BLOCKED",
                "You can check out only after " + dutyEnd
                        + ". Apply for a half-day or permission to leave early.",
                details);
    }

    /** True when the technician has an APPROVED HALF_DAY/PERMISSION covering today. */
    private boolean hasApprovedEarlyLeaveToday(UUID technicianId) {
        LocalDate today = LocalDate.now(IST);
        return leaveRepository.findOverlapping(technicianId, today, today).stream()
                .anyMatch(l -> "APPROVED".equalsIgnoreCase(l.getStatus())
                        && l.getLeaveType() != null
                        && EARLY_CHECKOUT_LEAVE_TYPES.contains(l.getLeaveType().toUpperCase()));
    }

    /** On-time when check-in is at/before the duty start plus the grace window. */
    private static boolean isLate(LocalTime defaultCheckIn, LocalTime checkIn) {
        if (defaultCheckIn == null || checkIn == null) return false;
        return checkIn.isAfter(defaultCheckIn.plusMinutes(LATE_GRACE_MINUTES));
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double EARTH_RADIUS_M = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecordResponse> getTodayAttendance(UUID shopId, UUID userId) {
        Technician t = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician profile not found"));
        return attendanceRepository.findByTechnicianIdAndAttendanceDate(t.getId(), LocalDate.now(IST))
                .map(a -> toAttendanceRecord(a, t.getDefaultCheckIn()));
    }

    @Transactional
    public LeaveRequestResponse createLeaveForMe(UUID shopId, UUID userId, CreateLeaveRequest request) {
        Technician t = technicianRepository.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician profile not found"));
        return createLeave(shopId, t.getId(), request);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listPendingLeavesForShop(UUID shopId) {
        List<Technician> technicians = technicianRepository.findByShopIdOrderByNameAsc(shopId);
        if (technicians.isEmpty()) return new ArrayList<>();
        List<UUID> techIds = technicians.stream().map(Technician::getId).collect(Collectors.toList());
        java.util.Map<UUID, String> nameByTechId = technicians.stream().collect(Collectors.toMap(Technician::getId, t -> t.getName() != null ? t.getName() : "—"));
        // Match both the canonical PENDING and the legacy PROCESSING value
        // so rows written before migration 46 still surface to the owner.
        List<TechnicianLeave> pending = new ArrayList<>();
        pending.addAll(leaveRepository.findByTechnicianIdInAndStatusOrderByRequestedAtDesc(techIds, "PENDING"));
        pending.addAll(leaveRepository.findByTechnicianIdInAndStatusOrderByRequestedAtDesc(techIds, "PROCESSING"));
        return pending.stream()
                .map(l -> toLeaveResponse(l, nameByTechId.getOrDefault(l.getTechnicianId(), "—")))
                .collect(Collectors.toList());
    }

    /**
     * Generic shop-wide leave list filtered by a status. Backs the
     * Approved / Rejected (and any future) tabs on the owner Leave Requests
     * screen. Null/blank status returns every leave the shop's technicians
     * have ever filed — order is "most recently requested first".
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listLeavesForShopByStatus(UUID shopId, String status) {
        List<Technician> technicians = technicianRepository.findByShopIdOrderByNameAsc(shopId);
        if (technicians.isEmpty()) return new ArrayList<>();
        List<UUID> techIds = technicians.stream().map(Technician::getId).collect(Collectors.toList());
        java.util.Map<UUID, String> nameByTechId = technicians.stream().collect(
                Collectors.toMap(Technician::getId, t -> t.getName() != null ? t.getName() : "—"));

        List<TechnicianLeave> rows = new ArrayList<>();
        if (status == null || status.isBlank()) {
            // No filter — every leave the shop's technicians have ever filed.
            for (String s : new String[] { "PENDING", "PROCESSING", "APPROVED", "REJECTED" }) {
                rows.addAll(leaveRepository.findByTechnicianIdInAndStatusOrderByRequestedAtDesc(techIds, s));
            }
        } else {
            String norm = status.trim().toUpperCase();
            rows.addAll(leaveRepository.findByTechnicianIdInAndStatusOrderByRequestedAtDesc(techIds, norm));
            // Legacy alias: rows written before migration 46 stored PENDING as
            // PROCESSING. Keep PENDING tab inclusive.
            if ("PENDING".equals(norm)) {
                rows.addAll(leaveRepository.findByTechnicianIdInAndStatusOrderByRequestedAtDesc(techIds, "PROCESSING"));
            }
        }
        return rows.stream()
                .map(l -> toLeaveResponse(l, nameByTechId.getOrDefault(l.getTechnicianId(), "—")))
                .collect(Collectors.toList());
    }

    private PayslipResponse buildPayslip(Technician t, int month, int year) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<TechnicianAttendance> list = attendanceRepository.findByTechnicianIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                t.getId(), start, end);
        int present = (int) list.stream().filter(a -> "GENERAL".equals(a.getStatus()) || "LATE".equals(a.getStatus()) || "PERMISSION".equals(a.getStatus())).count();
        String salary = t.getSalaryAmount() != null ? t.getSalaryAmount() : "0";
        int gross = parseAmount(salary);
        int net = present > 0 ? (gross * present / 30) : 0; // simplified: proportional by days
        return PayslipResponse.builder()
                .month(month)
                .year(year)
                .periodStart(start)
                .periodEnd(end)
                .presentDays(present)
                .dailyWageDays(present)
                .regularSalary(salary)
                .regularWage("0")
                .netSalary(String.valueOf(net))
                .netWage("0")
                .build();
    }

    private int parseAmount(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private AttendanceRecordResponse toAttendanceRecord(TechnicianAttendance a) {
        return toAttendanceRecord(a, null);
    }

    // The overload accepts the technician's configured duty start so we can:
    //   - compute lateMinutes per row (UI shows "Late by Xh Ym")
    //   - promote a stored status of GENERAL to LATE when the row is in fact late;
    //     stored status can lag if defaultCheckIn was null when the check-in was punched.
    private AttendanceRecordResponse toAttendanceRecord(TechnicianAttendance a, LocalTime defaultCheckIn) {
        // Leave null when there's no check-out yet so the mobile UI shows "—" rather than "0".
        String workingHours = null;
        if (a.getWorkingMinutes() != null && a.getWorkingMinutes() > 0) {
            long h = a.getWorkingMinutes() / 60;
            long m = a.getWorkingMinutes() % 60;
            workingHours = String.format("%02d:%02d:00", h, m);
        } else if (a.getCheckInTime() != null && a.getCheckOutTime() != null) {
            long min = Duration.between(a.getCheckInTime(), a.getCheckOutTime()).toMinutes();
            workingHours = String.format("%02d:%02d:00", min / 60, min % 60);
        }

        // Honour the 5-minute grace: a check-in within the grace window is not
        // late, so lateMinutes stays 0 and the status is not promoted to LATE.
        int lateMinutes = 0;
        if (isLate(defaultCheckIn, a.getCheckInTime())) {
            lateMinutes = (int) Duration.between(defaultCheckIn, a.getCheckInTime()).toMinutes();
        }

        String storedStatus = a.getStatus() != null ? a.getStatus() : "GENERAL";
        String effectiveStatus = storedStatus;
        if (lateMinutes > 0 && ("GENERAL".equalsIgnoreCase(storedStatus) || storedStatus.isBlank())) {
            effectiveStatus = "LATE";
        }

        String dayLabel = a.getAttendanceDate().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return AttendanceRecordResponse.builder()
                .date(a.getAttendanceDate())
                .dayLabel(dayLabel)
                .checkInTime(a.getCheckInTime())
                .checkOutTime(a.getCheckOutTime())
                .status(effectiveStatus)
                .workingHours(workingHours)
                .notes(a.getNotes())
                .lateMinutes(lateMinutes)
                .build();
    }

    private LeaveRequestResponse toLeaveResponse(TechnicianLeave l) {
        return toLeaveResponse(l, null);
    }

    private LeaveRequestResponse toLeaveResponse(TechnicianLeave l, String technicianName) {
        // Prefer the persisted totalDays (carries fractional half-day values);
        // fall back to a span calc for rows from before migration 46.
        BigDecimal totalDays = l.getTotalDays();
        if (totalDays == null) {
            long span = ChronoUnit.DAYS.between(l.getStartDate(), l.getEndDate()) + 1;
            totalDays = BigDecimal.valueOf(span);
        }
        String appliedDaysLabel;
        if (totalDays.compareTo(BigDecimal.ONE) < 0) {
            // 0.5 → "Half Day"; cosmetic so the existing list UI doesn't render "0 Days".
            appliedDaysLabel = "Half Day";
        } else if (totalDays.compareTo(BigDecimal.ONE) == 0) {
            appliedDaysLabel = "1 Day";
        } else {
            appliedDaysLabel = totalDays.stripTrailingZeros().toPlainString() + " Days";
        }
        LeaveRequestResponse.LeaveRequestResponseBuilder b = LeaveRequestResponse.builder()
                .id(l.getId())
                .leaveType(l.getLeaveType())
                .startDate(l.getStartDate())
                .endDate(l.getEndDate())
                .totalDays(totalDays)
                .reason(l.getReason())
                .attachmentUrl(l.getAttachmentUrl())
                .status(l.getStatus())
                .requestedAt(l.getRequestedAt())
                .appliedDaysLabel(appliedDaysLabel)
                .technicianId(l.getTechnicianId())
                .approvedBy(l.getApprovedBy())
                .approvedAt(l.getApprovedAt())
                .rejectedBy(l.getRejectedBy())
                .rejectedAt(l.getRejectedAt())
                .rejectionReason(l.getRejectionReason())
                .remarks(l.getRemarks());
        if (technicianName != null) b.technicianName(technicianName);
        return b.build();
    }

    private TechnicianResponse toResponse(Technician t) {
        return TechnicianResponse.builder()
                .id(t.getId())
                .userId(t.getUserId())
                .name(t.getName())
                .email(t.getEmail())
                .phone(t.getPhone())
                .roleLabel(t.getRoleLabel())
                .isAvailable(t.isAvailable())
                .salaryAmount(t.getSalaryAmount())
                .salaryPeriod(t.getSalaryPeriod())
                .idVerificationType(t.getIdVerificationType())
                .idNumber(t.getIdNumber())
                .aadharNumber(t.getAadharNumber())
                .aadharFrontUrl(t.getAadharFrontUrl())
                .aadharBackUrl(t.getAadharBackUrl())
                .panNumber(t.getPanNumber())
                .panFrontUrl(t.getPanFrontUrl())
                .panBackUrl(t.getPanBackUrl())
                .dailyWage(t.getDailyWage())
                .dateOfBirth(t.getDateOfBirth())
                .dateOfJoin(t.getDateOfJoin())
                .defaultCheckIn(t.getDefaultCheckIn())
                .defaultCheckOut(t.getDefaultCheckOut())
                .photoUrl(t.getPhotoUrl())
                .build();
    }

    private SalaryAdvanceResponse toAdvanceResponse(TechnicianSalaryAdvance a) {
        return SalaryAdvanceResponse.builder()
                .id(a.getId())
                .amount(a.getAmount())
                .advanceDate(a.getAdvanceDate())
                .status(a.getStatus())
                .requestedAt(a.getRequestedAt())
                .notes(a.getNotes())
                .build();
    }

    private ExperienceResponse toExperienceResponse(TechnicianExperience e) {
        LocalDate end = e.getRelievingDate() != null ? e.getRelievingDate() : LocalDate.now();
        long months = ChronoUnit.MONTHS.between(e.getJoinDate().withDayOfMonth(1), end.withDayOfMonth(1));
        long years = months / 12;
        long remMonths = months % 12;
        String duration = years + " Year(s) " + remMonths + " Month(s)";
        return ExperienceResponse.builder()
                .id(e.getId())
                .shopName(e.getShopName())
                .location(e.getLocation())
                .joinDate(e.getJoinDate())
                .relievingDate(e.getRelievingDate())
                .workingType(e.getWorkingType())
                .lastSalary(e.getLastSalary())
                .totalDuration(duration)
                .totalService(e.getTotalService())
                .completedCount(e.getCompletedCount())
                .returnCount(e.getReturnCount())
                .build();
    }
}
