-- 130: Partner notification idempotency + record reference.
--
-- event_key  identity of the business event that raised the row (e.g.
--            ATTENDANCE_CHECKED_IN:<attendance>:<session>). One event → one
--            row per shop, however often it is raised (API retries,
--            concurrent requests, re-processing). NULL = no de-duplication,
--            which is every row written before this migration.
-- entity_ref id of the record the row opens when it has no booking / ticket
--            (Team rows: the employee).
--
-- Additive only: no existing row is changed or removed.

BEGIN;

ALTER TABLE shop_notifications ADD COLUMN IF NOT EXISTS event_key  VARCHAR(200);
ALTER TABLE shop_notifications ADD COLUMN IF NOT EXISTS entity_ref VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_shop_notifications_event_key
    ON shop_notifications (shop_id, event_key)
    WHERE event_key IS NOT NULL;

COMMIT;
