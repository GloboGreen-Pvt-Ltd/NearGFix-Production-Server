-- 126: Multi-device TESTING for allow-listed Employee / Customer accounts
-- (auth-service DEVICE_LIMIT_TEST_ACCOUNTS). Sessions created for an allow-listed
-- account are flagged multi_device = true; they are unique per device instead of
-- per account. Every other Employee / Customer session stays single-device
-- (ux_auth_session_active). Partner (125) unchanged. Idempotent.
BEGIN;
ALTER TABLE auth_device_sessions ADD COLUMN IF NOT EXISTS multi_device BOOLEAN NOT NULL DEFAULT false;
DROP INDEX IF EXISTS ux_auth_session_active;
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_session_active
    ON auth_device_sessions (principal_type, principal_id, app_type)
    WHERE revoked_at IS NULL AND app_type <> 'PARTNER' AND multi_device = false;
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_session_test_device
    ON auth_device_sessions (principal_type, principal_id, app_type, device_id_hash)
    WHERE revoked_at IS NULL AND app_type <> 'PARTNER' AND multi_device = true;
COMMIT;
