-- 119_pickup_shop_handover_tpin.sql
-- Customer pickup → shop handover confirmed by a shop member's own TPIN.
-- Employee requests the handover (REACHED_SHOP); Owner / Shop account /
-- active same-shop Employee confirms on the Partner App with their TPIN.
ALTER TABLE repair_bookings ADD COLUMN IF NOT EXISTS handover_requested_at TIMESTAMPTZ;
ALTER TABLE repair_bookings ADD COLUMN IF NOT EXISTS handover_verified_role VARCHAR(20);
ALTER TABLE repair_bookings ADD COLUMN IF NOT EXISTS handover_started_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS pickup_handover_audit (
    id                     UUID PRIMARY KEY,
    booking_id             UUID NOT NULL UNIQUE,      -- one completed handover per booking
    booking_number         VARCHAR(60),
    shop_id                UUID NOT NULL,
    pickup_employee_id     UUID,                      -- technicians.id of the pickup person
    verified_user_id       UUID NOT NULL,
    verified_user_name     VARCHAR(200),
    verified_role          VARCHAR(20) NOT NULL,      -- OWNER | SHOP | EMPLOYEE
    tpin_authorization_id  UUID NOT NULL,
    confirmed_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pickup_handover_audit_shop ON pickup_handover_audit (shop_id, confirmed_at DESC);
