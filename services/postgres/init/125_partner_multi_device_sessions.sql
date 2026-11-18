-- 125: Partner app (owner / shop login) may be signed in on several devices at
-- once. Single-device stays enforced for every other app (EMPLOYEE, CUSTOMER).
-- Partner sessions stay unique per device, so a re-login on the same phone
-- still replaces that phone's old session. Session expiry, refresh-token
-- rotation and revocation are unchanged. Idempotent.
BEGIN;
DROP INDEX IF EXISTS ux_auth_session_active;
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_session_active
    ON auth_device_sessions (principal_type, principal_id, app_type)
    WHERE revoked_at IS NULL AND app_type <> 'PARTNER';
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_session_partner_device
    ON auth_device_sessions (principal_type, principal_id, app_type, device_id_hash)
    WHERE revoked_at IS NULL AND app_type = 'PARTNER';
COMMIT;
