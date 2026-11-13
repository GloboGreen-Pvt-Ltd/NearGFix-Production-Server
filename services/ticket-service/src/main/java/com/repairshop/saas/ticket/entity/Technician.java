package com.repairshop.saas.ticket.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "technicians", indexes = {
    @Index(name = "idx_technicians_shop_id", columnList = "shop_id"),
    @Index(name = "idx_technicians_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Technician {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "role_label", length = 100)
    private String roleLabel;

    /** The shop owner's own row ("Owner as Technician"); hidden from employee lists. */
    @Column(name = "is_owner", nullable = false)
    @Builder.Default
    private boolean isOwner = false;

    /** Owner as Technician ON/OFF — only meaningful on the is_owner row. */
    @Column(name = "owner_tech_enabled", nullable = false)
    @Builder.Default
    private boolean ownerTechEnabled = false;

    @Column(name = "is_available", nullable = false)
    private boolean isAvailable = true;

    @Column(name = "salary_amount", length = 50)
    private String salaryAmount;

    @Column(name = "salary_period", length = 50)
    private String salaryPeriod;

    @Column(name = "id_verification_type", length = 50)
    private String idVerificationType;

    @Column(name = "id_number", length = 100)
    private String idNumber;

    @Column(name = "aadhar_number", length = 50)
    private String aadharNumber;

    @Column(name = "aadhar_front_url", length = 500)
    private String aadharFrontUrl;

    @Column(name = "aadhar_back_url", length = 500)
    private String aadharBackUrl;

    @Column(name = "pan_number", length = 50)
    private String panNumber;

    @Column(name = "pan_front_url", length = 500)
    private String panFrontUrl;

    @Column(name = "pan_back_url", length = 500)
    private String panBackUrl;

    @Column(name = "daily_wage", length = 50)
    private String dailyWage;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "date_of_join")
    private LocalDate dateOfJoin;

    @Column(name = "default_check_in")
    private LocalTime defaultCheckIn;

    @Column(name = "default_check_out")
    private LocalTime defaultCheckOut;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
