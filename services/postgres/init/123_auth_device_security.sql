-- =============================================================================
-- 123_auth_device_security.sql
-- Supersedes the unreleased local drafts 116_auth_security_mpin_tpin_sessions /
-- 117_auth_tpin_shop_mobile (numbers taken upstream). Banking-style auth: single-device sessions, refresh rotation, MPIN/TPIN,
-- one-time transaction authorization, security audit. Forward-only, additive,
-- idempotent. No existing data is modified or deleted.
-- principal_type: SHOP_OWNER (users.id) | SHOP_MOBILE (shops.id)
--                 | EMPLOYEE (technicians.id) | CUSTOMER (customer_users.id)
-- =============================================================================

CREATE TABLE IF NOT EXISTS auth_device_sessions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type   VARCHAR(30)  NOT NULL CHECK (principal_type IN ('SHOP_OWNER','SHOP_MOBILE','EMPLOYEE','CUSTOMER')),
    principal_id     UUID         NOT NULL,
    app_type         VARCHAR(20)  NOT NULL CHECK (app_type IN ('PARTNER','EMPLOYEE','CUSTOMER')),
    device_id_hash   VARCHAR(255) NOT NULL,
    device_public_key TEXT,
    device_name      VARCHAR(150),
    platform         VARCHAR(20),
    otp_verified_at  TIMESTAMPTZ  NOT NULL,
    otp_expires_at   TIMESTAMPTZ  NOT NULL,
    last_active_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at       TIMESTAMPTZ,
    revoke_reason    VARCHAR(100),
    CHECK (otp_expires_at > otp_verified_at)
);
-- One active device per canonical account (type + id), not per phone number.
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_session_active
    ON auth_device_sessions (principal_type, principal_id, app_type) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_auth_session_expiry ON auth_device_sessions (otp_expires_at) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS auth_session_refresh_tokens (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id           UUID NOT NULL REFERENCES auth_device_sessions(id) ON DELETE CASCADE,
    token_hash           VARCHAR(255) NOT NULL UNIQUE,
    issued_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    rotated_at           TIMESTAMPTZ,
    revoked_at           TIMESTAMPTZ,
    replaced_by_token_id UUID REFERENCES auth_session_refresh_tokens(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_rt_session ON auth_session_refresh_tokens (session_id);
CREATE INDEX IF NOT EXISTS idx_auth_rt_expiry  ON auth_session_refresh_tokens (expires_at);

CREATE TABLE IF NOT EXISTS auth_pin_credentials (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type     VARCHAR(30) NOT NULL,
    principal_id       UUID        NOT NULL,
    app_type           VARCHAR(20) NOT NULL,
    pin_type           VARCHAR(10) NOT NULL CHECK (pin_type IN ('MPIN','TPIN')),
    pin_hash           TEXT        NOT NULL,          -- Argon2id + server pepper; never raw
    hash_algorithm     VARCHAR(30) NOT NULL DEFAULT 'ARGON2ID',
    credential_version INTEGER     NOT NULL DEFAULT 1,
    failed_attempts    INTEGER     NOT NULL DEFAULT 0,
    locked_until       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    reset_at           TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (pin_type = 'MPIN' OR principal_type IN ('SHOP_OWNER','SHOP_MOBILE','EMPLOYEE'))  -- no customer TPIN
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_pin_cred
    ON auth_pin_credentials (principal_type, principal_id, app_type, pin_type);

CREATE TABLE IF NOT EXISTS auth_pin_attempts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_id  UUID NOT NULL REFERENCES auth_pin_credentials(id) ON DELETE CASCADE,
    session_id     UUID REFERENCES auth_device_sessions(id) ON DELETE SET NULL,
    attempted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    success        BOOLEAN NOT NULL,
    failure_reason VARCHAR(100),
    client_ip_hash VARCHAR(255),
    device_id_hash VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_auth_pin_att_cred ON auth_pin_attempts (credential_id, attempted_at DESC);

CREATE TABLE IF NOT EXISTS auth_pin_reset_requests (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type        VARCHAR(30) NOT NULL,
    principal_id          UUID        NOT NULL,
    app_type              VARCHAR(20) NOT NULL,
    pin_type              VARCHAR(10) NOT NULL CHECK (pin_type IN ('MPIN','TPIN')),
    session_id            UUID REFERENCES auth_device_sessions(id) ON DELETE CASCADE,
    otp_challenge_ref     VARCHAR(100),               -- existing OTP store reference; no OTP plaintext
    reset_token_hash      VARCHAR(255) UNIQUE,
    biometric_verified_at TIMESTAMPTZ,
    verification_method   VARCHAR(50) NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    consumed_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auth_pin_reset_principal ON auth_pin_reset_requests (principal_type, principal_id, pin_type);
CREATE INDEX IF NOT EXISTS idx_auth_pin_reset_expiry    ON auth_pin_reset_requests (expires_at);

CREATE TABLE IF NOT EXISTS auth_transaction_authorizations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id         UUID NOT NULL REFERENCES auth_device_sessions(id) ON DELETE CASCADE,
    actor_type         VARCHAR(30) NOT NULL CHECK (actor_type IN ('SHOP_OWNER','SHOP_MOBILE','EMPLOYEE')),
    actor_id           UUID NOT NULL,
    shop_id            UUID NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    action_type        VARCHAR(100) NOT NULL,
    target_type        VARCHAR(50),
    target_id          VARCHAR(100),                  -- booking/order ids vary; or draft id
    context_hash       VARCHAR(255) NOT NULL,
    auth_token_hash    VARCHAR(255) NOT NULL UNIQUE,
    idempotency_key    VARCHAR(100),
    credential_version INTEGER NOT NULL,
    attendance_id      UUID REFERENCES technician_attendance(id) ON DELETE SET NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auth_txn_session ON auth_transaction_authorizations (session_id) WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_auth_txn_expiry  ON auth_transaction_authorizations (expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_txn_idem
    ON auth_transaction_authorizations (actor_id, action_type, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS auth_security_audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type VARCHAR(30),
    principal_id   UUID,
    app_type       VARCHAR(20),
    session_id     UUID,
    shop_id        UUID,
    event_type     VARCHAR(100) NOT NULL,
    action_type    VARCHAR(100),
    target_type    VARCHAR(50),
    target_id      VARCHAR(100),
    result         VARCHAR(30) NOT NULL,
    reason_code    VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auth_audit_principal ON auth_security_audit_logs (principal_type, principal_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auth_audit_created   ON auth_security_audit_logs (created_at);

-- Attendance: reuse technician_attendance. Add UTC instants + shop tz (existing TIME columns kept).
ALTER TABLE shops ADD COLUMN IF NOT EXISTS timezone VARCHAR(50) DEFAULT 'Asia/Kolkata';
ALTER TABLE technician_attendance
    ADD COLUMN IF NOT EXISTS shop_id        UUID REFERENCES shops(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS check_in_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS check_out_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS check_in_txn_auth_id  UUID,
    ADD COLUMN IF NOT EXISTS check_out_txn_auth_id UUID;
DO $$ BEGIN
    ALTER TABLE technician_attendance ADD CONSTRAINT ck_tech_att_out_after_in
        CHECK (check_out_at IS NULL OR (check_in_at IS NOT NULL AND check_out_at >= check_in_at)) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
-- At most one open (TPIN) check-in per employee; overnight shifts stay open across midnight.
CREATE UNIQUE INDEX IF NOT EXISTS ux_tech_att_open
    ON technician_attendance (technician_id) WHERE check_in_at IS NOT NULL AND check_out_at IS NULL;

-- Databases that ran the pre-release draft (local 116/117) have the older
-- CHECKs without SHOP_MOBILE: replace them (idempotent).
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT conname, conrelid::regclass AS tbl FROM pg_constraint
            WHERE contype = 'c'
              AND ((conrelid = 'auth_pin_credentials'::regclass AND pg_get_constraintdef(oid) LIKE '%pin_type%principal_type%')
                OR (conrelid = 'auth_transaction_authorizations'::regclass AND pg_get_constraintdef(oid) LIKE '%actor_type%'))
              AND pg_get_constraintdef(oid) NOT LIKE '%SHOP_MOBILE%'
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'auth_pin_credentials'::regclass
                  AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%SHOP_MOBILE%') THEN
    ALTER TABLE auth_pin_credentials ADD CONSTRAINT ck_auth_pin_no_customer_tpin
      CHECK (pin_type = 'MPIN' OR principal_type IN ('SHOP_OWNER','SHOP_MOBILE','EMPLOYEE'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'auth_transaction_authorizations'::regclass
                  AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%SHOP_MOBILE%') THEN
    ALTER TABLE auth_transaction_authorizations ADD CONSTRAINT ck_auth_txn_actor_type
      CHECK (actor_type IN ('SHOP_OWNER','SHOP_MOBILE','EMPLOYEE'));
  END IF;
END $$;

-- Retention (run from scheduled job):
--   DELETE FROM auth_session_refresh_tokens WHERE expires_at < now() - interval '30 days';
--   DELETE FROM auth_transaction_authorizations WHERE expires_at < now() - interval '90 days';
--   DELETE FROM auth_pin_reset_requests WHERE expires_at < now() - interval '30 days';
--   DELETE FROM auth_pin_attempts WHERE attempted_at < now() - interval '180 days';
--   DELETE FROM auth_security_audit_logs WHERE created_at < now() - interval '1 year';
