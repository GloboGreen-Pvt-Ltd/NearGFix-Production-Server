-- Repair booking "Booked By": the employee (or owner) whose PIN was verified
-- server-side when the booking was submitted. Set only by ticket-service from a
-- signed booking-PIN token, never from a client-supplied id.
-- Apply BEFORE deploying ticket-service (ddl-auto: validate).
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS booked_by_user_id UUID;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS booked_by_name VARCHAR(150);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS booked_by_role VARCHAR(20);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS booked_at TIMESTAMP;
