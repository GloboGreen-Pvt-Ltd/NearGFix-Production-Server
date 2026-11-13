package com.repairshop.saas.ticket.repository;

import com.repairshop.saas.ticket.entity.RepairDeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RepairDeliveryEventRepository extends JpaRepository<RepairDeliveryEvent, UUID> {
    List<RepairDeliveryEvent> findByDeliveryIdOrderByCreatedAtAsc(UUID deliveryId);
}
