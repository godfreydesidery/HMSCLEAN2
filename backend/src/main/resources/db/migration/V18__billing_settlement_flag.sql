-- =====================================================================================
-- Increment 04-P4 — Settlement flag (CR-05, RATIFIED scoped)
--
-- Net-new hardening: legacy has NO `settled` field and NO hard pay-before-service gate
-- (only a UI filter — 03-extract-cashier-collection-eod.md §5). The flag is set on the
-- cash PAID transition by SettlementDispatcher (billing → encounter direction only,
-- ADR-0008 §6; same tx; no async; no reverse edge).
--
-- Scope: added to patient_bills ONLY (the unit of the pay-before-service gate). The
-- invoice-level settled / claim-settlement ledger is the CR-06 insurance-claim concern
-- (DEFERRED) — adding it here would be dead schema. The clinical-side LOCAL settled flags
-- ship in inc-05/06 migrations, where the gate is actually enforced at accept().
-- =====================================================================================

ALTER TABLE patient_bills
    ADD COLUMN settled    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN settled_at TIMESTAMPTZ;

-- Partial index: the cashier "unsettled cash queue" lookup (CASH charges awaiting payment)
CREATE INDEX idx_patient_bills_unsettled
    ON patient_bills (patient_uid)
    WHERE settled = FALSE AND payment_type = 'CASH';
