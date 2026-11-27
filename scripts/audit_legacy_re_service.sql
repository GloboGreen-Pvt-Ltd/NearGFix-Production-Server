-- =============================================================================
-- audit_legacy_re_service.sql — READ-ONLY. Nothing here writes.
--
-- Bookings re-serviced by the OLD flow (POST /tickets/{id}/reopen) reused the
-- delivered ticket: status → CREATED, then the new repair's complaint,
-- estimate, payment and photos were written over it. The repair timeline kept
-- every row (a RE_SERVICE marker splits the cycles), but one-per-ticket rows —
-- invoice (uq_invoices_ticket_id) and delivery (repair_deliveries.ticket_id) —
-- could only hold one service.
--
-- Run on a verified backup / read replica first. Use the output to decide,
-- per booking, what is recoverable before any correction is planned.
-- =============================================================================

-- 1. Every reopened booking: what survived and what was likely overwritten.
WITH markers AS (
    SELECT b.ticket_id,
           COUNT(*)          AS re_service_count,
           MIN(e.created_at) AS first_reopen,
           MAX(e.created_at) AS last_reopen
    FROM repair_booking_events e
    JOIN repair_bookings b ON b.id = e.booking_id
    WHERE UPPER(e.status) = 'RE_SERVICE'
    GROUP BY b.ticket_id
)
SELECT t.shop_id,
       t.tracking_id,
       t.status                                   AS current_status,
       m.re_service_count,
       m.first_reopen,
       m.last_reopen,
       -- Timeline: rows before the first marker = the original service's history (kept).
       (SELECT COUNT(*) FROM repair_booking_events e2 JOIN repair_bookings b2 ON b2.id = e2.booking_id
         WHERE b2.ticket_id = t.id AND e2.created_at < m.first_reopen)       AS original_history_rows,
       -- Invoice: one row per ticket. Generated/updated after the reopen = the
       -- original invoice was overwritten (its old figures are not recoverable here).
       i.invoice_no,
       i.final_payable_amount,
       i.amount_paid,
       i.generated_at                             AS invoice_generated_at,
       (COALESCE(i.updated_at, i.generated_at) > m.first_reopen)             AS invoice_overwritten_after_reopen,
       -- Delivery: one row per ticket. Touched after the reopen = the original
       -- delivery record was replaced by the re-service's.
       d.status                                   AS delivery_status,
       d.delivered_at,
       (d.updated_at > m.first_reopen)                                        AS delivery_overwritten_after_reopen,
       -- Ledger rows are separate per transaction, so originals survive; split
       -- them by the reopen time to see which belong to which service.
       (SELECT COUNT(*) FROM shop_ledger_entries l WHERE l.ticket_id = t.id AND l.created_at < m.first_reopen)  AS ledger_rows_original,
       (SELECT COUNT(*) FROM shop_ledger_entries l WHERE l.ticket_id = t.id AND l.created_at >= m.first_reopen) AS ledger_rows_after_reopen,
       -- Verdict per booking. History and ledger rows are always kept (split by
       -- the marker / time); the one-per-ticket invoice and delivery decide it.
       CASE
         WHEN (i.id IS NOT NULL AND COALESCE(i.updated_at, i.generated_at) > m.first_reopen)
           OR (d.id IS NOT NULL AND d.updated_at > m.first_reopen)
           THEN 'MANUAL REVIEW: original invoice/delivery figures were rewritten after the reopen and are not in the database (check shared PDFs / receipts). History + ledger rows are intact.'
         WHEN i.id IS NULL AND d.id IS NULL
           THEN 'RECOVERABLE: history splits at the RE_SERVICE marker; no invoice/delivery to split.'
         ELSE 'RECOVERABLE: invoice/delivery predate the reopen and belong to the original service; history splits at the marker.'
       END AS verdict
FROM markers m
JOIN tickets t            ON t.id = m.ticket_id
LEFT JOIN invoices i      ON i.ticket_id = t.id
LEFT JOIN repair_deliveries d ON d.ticket_id = t.id
ORDER BY m.last_reopen DESC;

-- 2. Totals by shop (how big the clean-up is).
SELECT t.shop_id, COUNT(DISTINCT t.id) AS reopened_bookings
FROM repair_booking_events e
JOIN repair_bookings b ON b.id = e.booking_id
JOIN tickets t         ON t.id = b.ticket_id
WHERE UPPER(e.status) = 'RE_SERVICE'
GROUP BY t.shop_id
ORDER BY reopened_bookings DESC;
