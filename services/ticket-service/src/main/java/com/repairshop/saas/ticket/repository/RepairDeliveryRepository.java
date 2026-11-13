package com.repairshop.saas.ticket.repository;

import com.repairshop.saas.ticket.entity.RepairDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepairDeliveryRepository extends JpaRepository<RepairDelivery, UUID> {
    Optional<RepairDelivery> findByTicketId(UUID ticketId);

    /** Row-locked — serialises every state change of one delivery (no double completion). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM RepairDelivery d WHERE d.ticketId = :ticketId")
    Optional<RepairDelivery> lockByTicketId(@Param("ticketId") UUID ticketId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM RepairDelivery d WHERE d.id = :id")
    Optional<RepairDelivery> lockById(@Param("id") UUID id);

    List<RepairDelivery> findByShopIdAndTechnicianIdOrderByUpdatedAtDesc(UUID shopId, UUID technicianId);
}
