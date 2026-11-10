package com.repairshop.saas.ticket.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DatabaseSchemaInitializer {

    private final JdbcTemplate jdbc;

    @Bean
    ApplicationRunner ensureTicketSchema() {
        return args -> {
            addColumnIfMissing("repair_bookings", "assigned_pickup_person_id", "UUID");
            addColumnIfMissing("repair_bookings", "pickup_person_name", "VARCHAR(120)");
            addColumnIfMissing("repair_bookings", "pickup_person_phone", "VARCHAR(30)");
            // Mirrors the order-service initializer — ticket-service can boot
            // first in some setups, and the pickup-person estimate flow reads
            // this column. Idempotent ADD COLUMN IF NOT EXISTS is safe.
            addColumnIfMissing("repair_booking_services", "warranty", "VARCHAR(20)");
            // Boot-time safety net for migration 48 (tickets.customer_address).
            // The owner Booking Details "Customer Details" card reads this
            // column; without it the JPA save fails on every ticket mint.
            addColumnIfMissing("tickets", "customer_address", "TEXT");
            // Doorstep device purchase (SellPickupController) — mirrors the order-service
            // initializer so either service can boot first.
            String[][] sell = {
                    // Same types as migrations 111 / 112 / 113 (services/postgres/init).
                    {"pickup_otp", "VARCHAR(6)"}, {"pickup_otp_sent_at", "TIMESTAMPTZ"}, {"pickup_otp_verified_at", "TIMESTAMPTZ"},
                    {"pickup_otp_attempts", "INTEGER NOT NULL DEFAULT 0"}, {"pickup_inspection_json", "TEXT"}, {"pickup_inspected_at", "TIMESTAMPTZ"},
                    {"revision_pickup_note", "TEXT"}, {"revision_shop_note", "TEXT"}, {"payout_proof_url", "VARCHAR(500)"},
                    {"pickup_arrived_at", "TIMESTAMPTZ"}, {"pickup_inspection_started_at", "TIMESTAMPTZ"},
                    {"handed_over_at", "TIMESTAMPTZ"}, {"handover_json", "TEXT"}, {"inventory_item_id", "UUID"},
            };
            for (String[] c : sell) addColumnIfMissing("sell_orders", c[0], c[1]);
            // Repair return delivery (RepairDeliveryController) — same as migration 116.
            createRepairDeliveryTables();
        };
    }

    private void createRepairDeliveryTables() {
        try {
            String sql = new String(new org.springframework.core.io.ClassPathResource("db/repair_deliveries.sql").getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            jdbc.execute(sql.replace("BEGIN;", "").replace("COMMIT;", ""));
        } catch (Exception e) {
            log.warn("Could not ensure repair delivery tables: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String typeSql) {
        try {
            jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + columnName + " " + typeSql);
        } catch (Exception e) {
            log.warn("Could not ensure {}.{} exists: {}", tableName, columnName, e.getMessage());
        }
    }
}
