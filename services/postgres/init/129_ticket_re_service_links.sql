-- =============================================================================
-- 129_ticket_re_service_links.sql
--
-- Re-Service as linked child tickets. A delivered device that comes back gets
-- a NEW ticket (GTS-231-R01, -R02 …) linked to the one before it, instead of
-- reopening and overwriting the delivered ticket. Each ticket already owns its
-- invoice (uq_invoices_ticket_id), delivery (repair_deliveries.ticket_id),
-- ledger rows, events, photos and payment — so every service record stays
-- independent with no other table changes.
--
--   parent_ticket_id   the service this one follows (NULL = original service)
--   root_ticket_id     the original booking of the chain (NULL = original)
--   service_cycle_no   0/NULL = original, 1 = Re-Service 01, 2 = Re-Service 02 …
--   service_type       WARRANTY | PAID | FREE_REWORK (NULL = original)
--   return_reason      SAME_ISSUE | NEW_ISSUE | NOT_RESOLVED | REPEATED | OTHER
--   re_service_notes   JSON: complaint, condition, inspection notes, remarks,
--                      warranty reference / previous invoice reference
--   re_service_request_id  client idempotency key — a retried create returns
--                      the record the first attempt made
--
-- uq_tickets_parent_ticket_id: a service can be followed by at most ONE
-- Re-Service, and only a delivered, latest service can be re-serviced (checked
-- in ticket-service) — so a chain never has two active services at once.
--
-- Additive and idempotent; existing tickets stay NULL (= original service).
-- Apply BEFORE deploying ticket-service (ddl-auto: validate).
-- =============================================================================

BEGIN;

ALTER TABLE tickets ADD COLUMN IF NOT EXISTS parent_ticket_id      UUID REFERENCES tickets(id);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS root_ticket_id        UUID REFERENCES tickets(id);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS service_cycle_no      INTEGER;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS service_type          VARCHAR(20);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS return_reason         VARCHAR(30);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS re_service_notes      TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS re_service_request_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tickets_parent_ticket_id
    ON tickets (parent_ticket_id) WHERE parent_ticket_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_tickets_root_ticket_id
    ON tickets (root_ticket_id) WHERE root_ticket_id IS NOT NULL;

COMMIT;
