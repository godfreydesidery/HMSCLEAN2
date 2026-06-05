-- =====================================================================================
-- Increment 07a — Billing domain delta — Add admission_uid to patient_bills.
--
-- Adds the loose cross-module ref column patient_bills.admission_uid so that ward-bed
-- and consumable bills created during an inpatient admission can be linked back to their
-- owning admission for the discharge bills-cleared gate.
--
-- Reproduces the linkage required by BillingQueries.admissionHasOutstandingBills
-- (PatientResource.java:5342-5357 discharge, :5593-5603 referral, :5851-5882 deceased):
-- legacy rejects the discharge summary if ANY bill linked to the admission is UNPAID or
-- VERIFIED. Insurance-covered bills carry COVERED status → auto-pass.
--
-- Design:
--   * Nullable VARCHAR(26) — only ward/consumable bills from an admission have a value;
--     all existing OPD / registration / OTC bills leave this NULL.
--   * NO physical FK — Admission lives in the inpatient module (ADR-0008 §1).
--   * Index for the discharge-gate query:
--       SELECT 1 FROM patient_bills
--       WHERE admission_uid = ? AND status IN ('UNPAID','VERIFIED') LIMIT 1
--
-- This migration is paired with V44 (inpatient admission tables). They are in separate
-- scripts because patient_bills lives in the billing bounded context while admissions
-- lives in the inpatient bounded context; keeping the billing delta separate aids review
-- by the data-architect and makes the module ownership clear.
--
-- Legacy citation: PatientServiceImpl.java:1753-1774 (ward-bed bill creation);
-- PatientBillResource.java:352-365 (payment-driven activation);
-- PatientResource.java:5342-5357 (discharge bills-cleared gate). inc-07 07a.
-- =====================================================================================

ALTER TABLE patient_bills
    ADD COLUMN admission_uid VARCHAR(26);

-- Partial index: only ward/consumable bills (admission_uid IS NOT NULL) + UNPAID/VERIFIED.
-- Satisfies the discharge-gate EXISTS query in O(log n).
CREATE INDEX idx_patient_bills_admission_outstanding
    ON patient_bills (admission_uid, status)
    WHERE admission_uid IS NOT NULL
      AND status IN ('UNPAID', 'VERIFIED');
