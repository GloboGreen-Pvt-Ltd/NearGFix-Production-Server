-- Owner as Technician: the owner's own technicians row (reused if one already
-- exists for the owner's user id) carries the shop's ON/OFF setting.
-- Apply BEFORE deploying ticket-service (ddl-auto: validate).
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS is_owner BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS owner_tech_enabled BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS ux_technicians_shop_owner ON technicians (shop_id) WHERE is_owner;
