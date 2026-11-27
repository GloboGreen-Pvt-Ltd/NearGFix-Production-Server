package com.repairshop.saas.order.repository;

import com.repairshop.saas.order.entity.ShopNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShopNotificationRepository extends JpaRepository<ShopNotification, UUID> {
    List<ShopNotification> findByShopIdOrderByCreatedAtDesc(UUID shopId);
    long countByShopIdAndReadFalse(UUID shopId);
    List<ShopNotification> findByShopIdAndReadFalse(UUID shopId);
}
