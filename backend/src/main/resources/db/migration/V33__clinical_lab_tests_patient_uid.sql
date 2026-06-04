-- =====================================================================================
-- V33 — lab_tests: apply ADR-0022 D2 Correction to cross-module patient reference,
--       and add the local 'settled' boolean column (not present in V24).
--
-- V24 created lab_tests with:
--   patient_id BIGINT NOT NULL FK → patients(id)  (cross-module id-FK — MUST become uid)
--   [no settled column]                            (needed for the lab worklist gate)
--
-- ADR-0022 D2 mandates that cross-module id-FK columns be replaced with loose uid columns.
-- CR-INC05-01 mandates a local 'settled' boolean on lab_tests to gate the lab worklist
-- (mirroring the V29 'settled' column on consultations).
--
-- LOSS-FREE migration (mirrors V30 / V32 house style exactly):
--   (1) ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3) Set patient_uid NOT NULL after backfill.
--   (4) DROP idx_lab_tests_patient (keyed on patient_id).
--   (5) ADD index on patient_uid for the worklist/lookup finders.
--   (6) DROP fk_lab_tests_patient constraint.
--   (7) DROP patient_id column.
--   (8) ADD settled BOOLEAN NOT NULL DEFAULT FALSE (worklist gate — CR-INC05-01 parity).
--   (9) ADD index on settled for the worklist query.
--
-- The consultation_id FK → consultations(id) and non_consultation_id FK → non_consultations(id)
-- are INTRA-MODULE FKs (clinical owns all three tables) and are RETAINED — no change.
-- The lab_test_attachments.lab_test_id FK is CASCADE and is RETAINED — no change.
--
-- Legacy citations:
--   LabTest.java:43-154 (patient FK — replaced with loose uid per ADR-0022)
--   PatientServiceImpl.java:790-849 (lab order create path, settlement context)
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only,
--   no cross-module DB FK).
--   CR-INC05-01 (settled gate at worklist — mirrors consultations.settled from V29).
-- =====================================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE lab_tests ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE lab_tests lt
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = lt.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE lab_tests ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V24 convention)
DROP INDEX idx_lab_tests_patient;

-- (5) Add index on patient_uid for the worklist/lookup finders
CREATE INDEX idx_lab_tests_patient_uid
    ON lab_tests (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE lab_tests DROP CONSTRAINT fk_lab_tests_patient;

-- (7) Drop the old patient_id column
ALTER TABLE lab_tests DROP COLUMN patient_id;

-- (8) Add 'settled' boolean column (NOT NULL DEFAULT FALSE — mirrors V29 consultations.settled)
--     This is the local settlement projection for the lab worklist gate (CR-INC05-01).
--     Set TRUE at charge time for INSURANCE/COVERED; FALSE for CASH-OPD.
--     Flipped to TRUE when the cash bill is PAID (deferred event seam — same pattern as consultations).
ALTER TABLE lab_tests ADD COLUMN settled BOOLEAN NOT NULL DEFAULT FALSE;

-- (9) Index on settled to support the worklist query (settled=true AND status IN (...))
CREATE INDEX idx_lab_tests_settled ON lab_tests (settled);
