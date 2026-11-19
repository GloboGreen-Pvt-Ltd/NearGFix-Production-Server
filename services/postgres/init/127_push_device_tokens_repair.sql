-- =============================================================================
-- 127_push_device_tokens_repair.sql
--
-- Production never got push_device_tokens: 105_push_device_tokens.sql is
-- numbered below the run-migrations.sh baseline (115), so the first scripted
-- run recorded it as "already applied" without executing it. Every
-- POST /shop/push-tokens (and /push-tokens) then failed with
--   relation "push_device_tokens" does not exist.
--
-- Same definition as 105 (what PushTokenRegistry reads/writes). Idempotent:
-- a no-op where 105 did run (local / test databases). Only creates; touches
-- no existing table or row.
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS push_device_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token       TEXT NOT NULL UNIQUE,
    app         TEXT NOT NULL CHECK (app IN ('PARTNER', 'CUSTOMER', 'EMPLOYEE')),
    provider    TEXT NOT NULL CHECK (provider IN ('FCM', 'EXPO')),
    user_id     UUID,
    shop_id     UUID,
    login_type  TEXT,                -- OWNER | SHOP (JWT loginScope), Partner only
    platform    TEXT,                -- android | ios
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (app <> 'PARTNER' OR shop_id IS NOT NULL),
    CHECK (app = 'PARTNER' OR user_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_push_device_tokens_shop
    ON push_device_tokens (shop_id) WHERE app = 'PARTNER';
CREATE INDEX IF NOT EXISTS idx_push_device_tokens_user
    ON push_device_tokens (app, user_id);

COMMIT;
