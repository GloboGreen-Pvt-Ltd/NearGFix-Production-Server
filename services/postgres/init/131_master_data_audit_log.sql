-- 131: Audit trail for master-data changes made from the admin portal.
--
-- First writer: the bulk brand logo upload (one row per file: UPLOADED,
-- SKIPPED or FAILED, with the batch id, file name, mode and old/new URL in
-- details). Written best-effort by master-data-service (MasterAuditLog), which
-- also creates the table on start-up when it is missing (dev databases).
--
-- Additive only: a new table; nothing existing is changed.
BEGIN;

CREATE TABLE IF NOT EXISTS master_data_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    actor_user_id UUID,
    action        VARCHAR(60)  NOT NULL,
    entity_type   VARCHAR(40)  NOT NULL,
    entity_id     UUID,
    details       TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_master_data_audit_log_entity
    ON master_data_audit_log (entity_type, entity_id, created_at DESC);

COMMIT;
