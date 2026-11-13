package com.repairshop.saas.ticket.repository;

import com.repairshop.saas.ticket.entity.Technician;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TechnicianRepository extends JpaRepository<Technician, UUID> {

    Optional<Technician> findByShopIdAndId(UUID shopId, UUID id);

    Optional<Technician> findByShopIdAndUserId(UUID shopId, UUID userId);

    Optional<Technician> findFirstByUserId(UUID userId);

    @org.springframework.data.jpa.repository.Query("SELECT t FROM Technician t WHERE t.shopId = :shopId AND t.isOwner = true")
    Optional<Technician> findOwnerRow(@org.springframework.data.repository.query.Param("shopId") UUID shopId);

    List<Technician> findByShopIdOrderByNameAsc(UUID shopId);
}
