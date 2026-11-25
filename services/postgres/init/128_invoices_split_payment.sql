-- =============================================================================
-- 128_invoices_split_payment.sql
--
-- A customer can pay one invoice partly in cash and partly by UPI. amount_paid
-- (migration 89) stays the total handed over now; these two record how it was
-- paid. cash_amount + upi_amount = amount_paid for invoices saved by an app
-- that sends the split. Older invoices, and older apps that send only
-- amount_paid, keep 0 / 0 here (method unknown); nothing else changes.
-- UPI is the amount the shop confirmed it received, not a gateway result.
-- Idempotent: safe to run more than once. Run BEFORE deploying ticket-service
-- (ddl-auto: validate).
-- =============================================================================
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS cash_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS upi_amount  NUMERIC(14, 2) NOT NULL DEFAULT 0;
