package com.repairshop.saas.masterdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who changed master data, and how — master_data_audit_log (migration 131).
 *
 * Best-effort by design: the change it describes has already happened, so a
 * failed audit insert is logged and never fails the request. Every entry is
 * also written to the "AUDIT" logger, so it reaches the service log even if the
 * row cannot be written.
 */
@Configuration
public class MasterAuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(MasterAuditLog.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MasterAuditLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Dev databases built before migration 131 get the table too (no-op when it exists). */
    @Bean
    ApplicationRunner ensureMasterAuditLogTable() {
        return args -> {
            try {
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS master_data_audit_log (
                            id            BIGSERIAL PRIMARY KEY,
                            actor_user_id UUID,
                            action        VARCHAR(60)  NOT NULL,
                            entity_type   VARCHAR(40)  NOT NULL,
                            entity_id     UUID,
                            details       TEXT,
                            created_at    TIMESTAMPTZ  NOT NULL DEFAULT now())""");
            } catch (Exception e) {
                log.warn("Could not ensure master_data_audit_log exists: {}", e.getMessage());
            }
        };
    }

    public void record(UUID actor, String action, String entityType, UUID entityId, Map<String, ?> details) {
        String detailJson;
        try {
            detailJson = json.writeValueAsString(details == null ? Map.of() : new LinkedHashMap<>(details));
        } catch (Exception e) {
            detailJson = String.valueOf(details);
        }
        audit.info("{} {} {} by {} {}", action, entityType, entityId, actor, detailJson);
        try {
            jdbc.update("INSERT INTO master_data_audit_log (actor_user_id, action, entity_type, entity_id, details) "
                    + "VALUES (?, ?, ?, ?, ?)", actor, action, entityType, entityId, detailJson);
        } catch (Exception e) {
            log.warn("Audit row not written ({} {}): {}", action, entityId, e.getMessage());
        }
    }
}
