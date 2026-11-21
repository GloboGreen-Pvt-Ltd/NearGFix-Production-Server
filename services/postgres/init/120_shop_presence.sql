-- =============================================================================
-- 120_shop_presence.sql
--
-- Shop presence monitoring during an attendance session (ShopPresenceController,
-- ticket-service). Manual check-in / check-out stay in technician_attendance;
-- these tables only add per-shop settings, the current presence state per
-- employee and the immutable exit / return / duty event log.
-- ticket-service also creates them at startup (DatabaseSchemaInitializer).
--
-- One transaction; idempotent; new tables only, no changes to existing data.
-- Rollback: DROP TABLE employee_presence_events, employee_presence_state,
--           shop_presence_settings;  (no other table references them)
-- =============================================================================
BEGIN;

CREATE TABLE IF NOT EXISTS shop_presence_settings (
    shop_id                 UUID PRIMARY KEY,
    monitoring_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    auto_freeze             BOOLEAN NOT NULL DEFAULT TRUE,
    radius_m                INTEGER NOT NULL DEFAULT 50 CHECK (radius_m BETWEEN 20 AND 500),
    grace_seconds           INTEGER NOT NULL DEFAULT 120 CHECK (grace_seconds BETWEEN 30 AND 1800),
    auto_unlock             BOOLEAN NOT NULL DEFAULT TRUE,
    record_events           BOOLEAN NOT NULL DEFAULT TRUE,
    duty_exemption          BOOLEAN NOT NULL DEFAULT TRUE,
    prolonged_alert_minutes INTEGER NOT NULL DEFAULT 0 CHECK (prolonged_alert_minutes BETWEEN 0 AND 480),
    version                 INTEGER NOT NULL DEFAULT 1,
    updated_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS employee_presence_state (
    technician_id           UUID PRIMARY KEY,
    shop_id                 UUID NOT NULL,
    attendance_id           UUID,
    state                   VARCHAR(30) NOT NULL DEFAULT 'INSIDE_SHOP',
    pending_outside_since   TIMESTAMPTZ,
    outside_since           TIMESTAMPTZ,
    duty_since              TIMESTAMPTZ,
    duty_ref                VARCHAR(120),
    last_observed_at        TIMESTAMPTZ,
    last_verified_at        TIMESTAMPTZ,
    last_distance_m         INTEGER,
    last_accuracy_m         INTEGER,
    last_exit_at            TIMESTAMPTZ,
    last_return_at          TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS employee_presence_events (
    id                      UUID PRIMARY KEY,
    technician_id           UUID NOT NULL,
    shop_id                 UUID NOT NULL,
    attendance_id           UUID NOT NULL,
    event_type              VARCHAR(30) NOT NULL,  -- EXITED, RETURNED, OUTSIDE_ENDED, DUTY_STARTED, DUTY_ENDED, LOCATION_UNVERIFIED
    observed_at             TIMESTAMPTZ NOT NULL,
    received_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    distance_m              INTEGER,
    accuracy_m              INTEGER,
    radius_m                INTEGER,
    duty_ref                VARCHAR(120),
    duration_seconds        BIGINT,
    source                  VARCHAR(20),
    UNIQUE (technician_id, attendance_id, event_type, observed_at)
);

CREATE INDEX IF NOT EXISTS idx_presence_events_session ON employee_presence_events (technician_id, attendance_id, observed_at);
CREATE INDEX IF NOT EXISTS idx_presence_events_shop ON employee_presence_events (shop_id, observed_at);

COMMIT;
