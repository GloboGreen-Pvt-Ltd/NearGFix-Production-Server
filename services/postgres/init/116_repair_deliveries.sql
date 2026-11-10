-- =============================================================================
-- 116_repair_deliveries.sql
--
-- Repair return delivery (ticket-service RepairDeliveryController): after the
-- repair is finished (QC passed, invoiced) the shop delivers the device back.
-- One row per repair ticket (attempt counts retries) + its step history.
-- ticket-service also creates these at startup (DatabaseSchemaInitializer).
--
-- One transaction; idempotent; new tables only, no data changes.
-- =============================================================================
BEGIN;

CREATE TABLE IF NOT EXISTS repair_deliveries (
    id                    UUID PRIMARY KEY,
    ticket_id             UUID NOT NULL,
    shop_id               UUID NOT NULL,
    customer_user_id      UUID,
    status                VARCHAR(30) NOT NULL,
    attempt               INTEGER NOT NULL DEFAULT 1,
    scheduled_date        DATE,
    slot_start            TIME,
    slot_end              TIME,
    technician_id         UUID,
    technician_name       VARCHAR(120),
    technician_phone      VARCHAR(30),
    assigned_at           TIMESTAMPTZ,
    accepted_at           TIMESTAMPTZ,
    declined_reason       TEXT,
    shop_address          TEXT,
    customer_address      TEXT,
    device_identifier     VARCHAR(64),
    released_at           TIMESTAMPTZ,
    collected_at          TIMESTAMPTZ,
    collected_identifier  VARCHAR(64),
    accessories_json      TEXT,
    collect_photos_json   TEXT,
    started_at            TIMESTAMPTZ,
    arrived_at            TIMESTAMPTZ,
    otp                   VARCHAR(6),
    otp_sent_at           TIMESTAMPTZ,
    otp_attempts          INTEGER NOT NULL DEFAULT 0,
    otp_verified_at       TIMESTAMPTZ,
    amount_due            NUMERIC(14, 2),
    amount_collected      NUMERIC(14, 2),
    payment_method        VARCHAR(20),
    payment_ref           VARCHAR(40),
    delivery_photos_json  TEXT,
    delivered_at          TIMESTAMPTZ,
    failed_at             TIMESTAMPTZ,
    fail_reason           TEXT,
    returning_at          TIMESTAMPTZ,
    returned_at           TIMESTAMPTZ,
    note                  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_repair_deliveries_ticket ON repair_deliveries (ticket_id);
CREATE INDEX IF NOT EXISTS ix_repair_deliveries_technician ON repair_deliveries (shop_id, technician_id);

CREATE TABLE IF NOT EXISTS repair_delivery_events (
    id           UUID PRIMARY KEY,
    delivery_id  UUID NOT NULL,
    ticket_id    UUID NOT NULL,
    status       VARCHAR(30) NOT NULL,
    note         TEXT,
    actor        VARCHAR(20),
    actor_name   VARCHAR(120),
    attempt      INTEGER,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_repair_delivery_events_delivery ON repair_delivery_events (delivery_id, created_at);

COMMIT;
