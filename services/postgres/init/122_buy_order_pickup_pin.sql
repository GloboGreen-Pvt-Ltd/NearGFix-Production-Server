-- Fixed-price home delivery: Shop Pickup PIN (shown to the shop, entered by the
-- assigned employee at pickup; replaces pickup photos for that step).
-- Regenerated on every assign / reassign; cleared once pickup is confirmed.
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS pickup_pin VARCHAR(6);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS pickup_pin_expires_at TIMESTAMPTZ;
