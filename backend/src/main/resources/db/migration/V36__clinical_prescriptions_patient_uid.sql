-- =====================================================================================
-- V36 — prescriptions / prescription_batches / patient_prescription_charts:
--       apply ADR-0022 D2 Correction to cross-module patient reference,
--       and add the local 'settled' boolean column to prescriptions.
--
-- V27 created prescriptions with:
--   patient_id BIGINT NOT NULL FK → patients(id)   (cross-module id-FK — MUST become uid)
--   [no settled column]                             (needed for CASH/INSURANCE settlement gate)
--
-- V27 created patient_prescription_charts with:
--   patient_id BIGINT NOT NULL FK → patients(id)   (cross-module id-FK — MUST become uid)
--
-- ADR-0022 D2 mandates that cross-module id-FK columns be replaced with loose uid columns.
-- CR-INC05-01 mandates a local 'settled' boolean on prescriptions to gate the pharmacy
-- worklist (mirrors V33 'settled' on lab_tests, V34 on radiologies, V35 on procedures).
--
-- LOSS-FREE migration (mirrors V33/V34/V35 house style exactly):
--
-- prescriptions:
--   (1)  ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2)  Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3)  Set patient_uid NOT NULL after backfill.
--   (4)  DROP idx_prescriptions_patient (keyed on patient_id).
--   (5)  ADD index on patient_uid.
--   (6)  DROP fk_prescriptions_patient constraint.
--   (7)  DROP patient_id column.
--   (8)  ADD settled BOOLEAN NOT NULL DEFAULT FALSE (settlement projection — CR-INC05-01).
--   (9)  ADD index on settled.
--
-- patient_prescription_charts:
--   (10) ADD patient_uid VARCHAR(26).
--   (11) Backfill patient_uid.
--   (12) Set patient_uid NOT NULL after backfill.
--   (13) DROP idx_patient_prescription_charts_patient (keyed on patient_id).
--   (14) ADD index on patient_uid.
--   (15) DROP fk_patient_prescription_charts_patient constraint.
--   (16) DROP patient_id column.
--
-- The consultation_id / non_consultation_id FKs are INTRA-MODULE FKs (clinical owns all tables)
-- and are RETAINED — no change. The prescription_batches table has no patient_id — no change.
--
-- Legacy citations:
--   Prescription.java:38-144 (patient FK — replaced with loose uid per ADR-0022)
--   PatientPrescriptionChart.java:34-82 (patient FK — replaced)
--   PatientServiceImpl.java (save_prescription, issueMedicine context)
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only).
--   CR-INC05-01 (settled gate at worklist — mirrors lab_tests.settled from V33).
-- =====================================================================================

-- ============================================================
-- prescriptions — patient_id → patient_uid + settled column
-- ============================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE prescriptions ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE prescriptions pr
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = pr.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE prescriptions ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V27 convention)
DROP INDEX idx_prescriptions_patient;

-- (5) Add index on patient_uid
CREATE INDEX idx_prescriptions_patient_uid
    ON prescriptions (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE prescriptions DROP CONSTRAINT fk_prescriptions_patient;

-- (7) Drop the old patient_id column
ALTER TABLE prescriptions DROP COLUMN patient_id;

-- (8) Add 'settled' boolean column (NOT NULL DEFAULT FALSE — mirrors V33 lab_tests.settled)
--     Set TRUE at charge time for INSURANCE/COVERED; FALSE for CASH-OPD.
--     Flipped to TRUE when the cash bill is PAID (deferred event seam — same pattern).
ALTER TABLE prescriptions ADD COLUMN settled BOOLEAN NOT NULL DEFAULT FALSE;

-- (9) Index on settled to support the worklist query
CREATE INDEX idx_prescriptions_settled ON prescriptions (settled);

-- ============================================================
-- patient_prescription_charts — patient_id → patient_uid
-- ============================================================

-- (10) Add patient_uid column (initially nullable for backfill)
ALTER TABLE patient_prescription_charts ADD COLUMN patient_uid VARCHAR(26);

-- (11) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE patient_prescription_charts ppc
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = ppc.patient_id
);

-- (12) Set patient_uid NOT NULL after backfill
--      NOTE: Chart rows may have been created before any patient existed in the test DB;
--      in production this is always non-null. Keeping NOT NULL consistent with spec.
ALTER TABLE patient_prescription_charts ALTER COLUMN patient_uid SET NOT NULL;

-- (13) Drop the old index on patient_id
DROP INDEX idx_patient_prescription_charts_patient;

-- (14) Add index on patient_uid
CREATE INDEX idx_patient_prescription_charts_patient_uid
    ON patient_prescription_charts (patient_uid);

-- (15) Drop the now-dead cross-module FK constraint
ALTER TABLE patient_prescription_charts DROP CONSTRAINT fk_patient_prescription_charts_patient;

-- (16) Drop the old patient_id column
ALTER TABLE patient_prescription_charts DROP COLUMN patient_id;
