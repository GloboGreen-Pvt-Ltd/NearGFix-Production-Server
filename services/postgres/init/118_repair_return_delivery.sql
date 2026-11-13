-- Doorstep RETURN delivery of a repaired (or returned-unrepaired) device.
-- One row per ticket; a failed attempt is retried on the same row (attempt + 1).
-- Apply BEFORE deploying ticket-service (ddl-auto: validate).

-- An earlier unmerged draft (branch feat/repair-return-delivery, de0ec7c) used
-- the same table names with other columns. If that layout exists AND is empty,
-- replace it; if it holds rows, stop so nothing is lost.
DO $$
BEGIN
  IF to_regclass('public.repair_deliveries') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_name = 'repair_deliveries' AND column_name = 'return_path') THEN
    IF EXISTS (SELECT 1 FROM repair_deliveries) THEN
      RAISE EXCEPTION 'repair_deliveries has rows in the old draft layout; migrate them by hand first';
    END IF;
    DROP TABLE IF EXISTS repair_delivery_events;
    DROP TABLE repair_deliveries;
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS repair_deliveries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id           UUID NOT NULL UNIQUE REFERENCES tickets(id) ON DELETE CASCADE,
    shop_id             UUID NOT NULL,
    repair_booking_id   UUID,
    customer_user_id    UUID,
    technician_id       UUID,
    status              VARCHAR(30) NOT NULL,
    attempt             INT NOT NULL DEFAULT 1,
    return_path         BOOLEAN NOT NULL DEFAULT FALSE,
    address             TEXT,
    latitude            DECIMAL(10, 7),
    longitude           DECIMAL(10, 7),
    scheduled_date      DATE,
    slot_start          TIME,
    slot_end            TIME,
    note                TEXT,
    amount_due          DECIMAL(12, 2) NOT NULL DEFAULT 0,
    amount_collected    DECIMAL(12, 2),
    payment_method      VARCHAR(20),
    payment_ref         VARCHAR(120),
    otp_code            VARCHAR(10),
    otp_expires_at      TIMESTAMPTZ,
    otp_sent_count      INT NOT NULL DEFAULT 0,
    otp_failed_attempts INT NOT NULL DEFAULT 0,
    collected_identifier VARCHAR(60),
    accessories_json    TEXT,
    collect_photos_json TEXT,
    proof_photos_json   TEXT,
    declined_reason     TEXT,
    fail_reason         TEXT,
    accepted_at         TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,
    collected_at        TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    arrived_at          TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    returned_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_repair_deliveries_shop ON repair_deliveries (shop_id);
CREATE INDEX IF NOT EXISTS idx_repair_deliveries_technician ON repair_deliveries (technician_id);

CREATE TABLE IF NOT EXISTS repair_delivery_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id  UUID NOT NULL REFERENCES repair_deliveries(id) ON DELETE CASCADE,
    status       VARCHAR(30) NOT NULL,
    label        VARCHAR(120) NOT NULL,
    note         TEXT,
    actor        VARCHAR(20),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_repair_delivery_events_delivery ON repair_delivery_events (delivery_id, created_at);
