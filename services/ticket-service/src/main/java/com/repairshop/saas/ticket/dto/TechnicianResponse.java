package com.repairshop.saas.ticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Technician (shop employee) response")
public class TechnicianResponse {

    @Schema(description = "Technician ID")
    private UUID id;

    @Schema(description = "User ID (for login; may be null if not yet linked)")
    private UUID userId;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Email")
    private String email;

    @Schema(description = "Phone")
    private String phone;

    @Schema(description = "Role label e.g. Junior Technician, Senior Technician")
    private String roleLabel;

    @Schema(description = "Whether available for assignment")
    private Boolean isAvailable;

    @Schema(description = "Checked in (no check-out) for the shop-local workday; only then assignable")
    private Boolean checkedIn;

    @Schema(description = "Salary amount (e.g. 25000)")
    private String salaryAmount;

    @Schema(description = "Salary period e.g. Monthly, Weekly")
    private String salaryPeriod;

    @Schema(description = "ID verification type e.g. Aadhar, PAN (legacy)")
    private String idVerificationType;

    @Schema(description = "ID number (legacy)")
    private String idNumber;

    @Schema(description = "Aadhar card number")
    private String aadharNumber;

    @Schema(description = "Aadhar card front image URL")
    private String aadharFrontUrl;

    @Schema(description = "Aadhar card back image URL")
    private String aadharBackUrl;

    @Schema(description = "PAN card number")
    private String panNumber;

    @Schema(description = "PAN card front image URL")
    private String panFrontUrl;

    @Schema(description = "PAN card back image URL")
    private String panBackUrl;

    @Schema(description = "Daily wage amount")
    private String dailyWage;

    @Schema(description = "Date of birth")
    private LocalDate dateOfBirth;

    @Schema(description = "Date of join")
    private LocalDate dateOfJoin;

    @Schema(description = "Default check-in time")
    private LocalTime defaultCheckIn;

    @Schema(description = "Default check-out time")
    private LocalTime defaultCheckOut;

    @Schema(description = "Photo URL")
    private String photoUrl;

    @Schema(description = "Shop this technician belongs to")
    private UUID shopId;

    @Schema(description = "Shop latitude (null if the shop has no saved coordinates) — used by the employee app's 100m attendance geofence")
    private Double shopLatitude;

    @Schema(description = "Shop longitude (null if the shop has no saved coordinates)")
    private Double shopLongitude;
}
