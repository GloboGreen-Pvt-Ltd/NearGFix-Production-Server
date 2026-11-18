-- 124: Employee MPIN login — a new device for an employee who already has an
-- active device session needs the shop owner's approval (MPIN alone never
-- replaces a trusted device). Idempotent.
CREATE TABLE IF NOT EXISTS auth_device_approvals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id     UUID         NOT NULL,
    shop_id         UUID,
    device_id_hash  VARCHAR(255) NOT NULL,
    device_name     VARCHAR(150),
    platform        VARCHAR(20),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','DENIED','USED','EXPIRED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    decided_by      UUID,
    decided_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_auth_dev_appr_emp ON auth_device_approvals (employee_id, status);
CREATE INDEX IF NOT EXISTS idx_auth_dev_appr_shop ON auth_device_approvals (shop_id, status);
-- One open (PENDING/APPROVED) request per employee + device.
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_dev_appr_open
    ON auth_device_approvals (employee_id, device_id_hash) WHERE status IN ('PENDING','APPROVED');
