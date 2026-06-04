-- =====================================================================================
-- V34 — radiologies: apply ADR-0022 D2 Correction to cross-module patient reference,
--       and add the local 'settled' boolean column (not present in V25).
--
-- V25 created radiologies with:
--   patient_id BIGINT NOT NULL FK → patients(id)  (cross-module id-FK — MUST become uid)
--   [no settled column]                            (needed for the radiology worklist gate)
--
-- ADR-0022 D2 mandates that cross-module id-FK columns be replaced with loose uid columns.
-- CR-INC05-01 mandates a local 'settled' boolean on radiologies to gate the radiology worklist
-- (mirroring the V33 'settled' column on lab_tests and the V29 column on consultations).
--
-- LOSS-FREE migration (mirrors V33 / V30 / V32 house style exactly):
--   (1) ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3) Set patient_uid NOT NULL after backfill.
--   (4) DROP idx_radiologies_patient (keyed on patient_id).
--   (5) ADD index on patient_uid for the worklist/lookup finders.
--   (6) DROP fk_radiologies_patient constraint.
--   (7) DROP patient_id column.
--   (8) ADD settled BOOLEAN NOT NULL DEFAULT FALSE (worklist gate — CR-INC05-01 parity).
--   (9) ADD index on settled for the worklist query.
--
-- The consultation_id FK → consultations(id) and non_consultation_id FK → non_consultations(id)
-- are INTRA-MODULE FKs (clinical owns all three tables) and are RETAINED — no change.
-- The radiology_attachments.radiology_id FK is CASCADE and is RETAINED — no change.
--
-- Legacy citations:
--   Radiology.java:42-150 (patient FK — replaced with loose uid per ADR-0022)
--   PatientServiceImpl.java — radiology order create path, settlement context
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only,
--   no cross-module DB FK).
--   CR-INC05-01 (settled gate at worklist — mirrors lab_tests.settled from V33).
-- =====================================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE radiologies ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE radiologies r
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = r.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE radiologies ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V25 convention)
DROP INDEX idx_radiologies_patient;

-- (5) Add index on patient_uid for the worklist/lookup finders
CREATE INDEX idx_radiologies_patient_uid
    ON radiologies (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE radiologies DROP CONSTRAINT fk_radiologies_patient;

-- (7) Drop the old patient_id column
ALTER TABLE radiologies DROP COLUMN patient_id;

-- (8) Add 'settled' boolean column (NOT NULL DEFAULT FALSE — mirrors V33 lab_tests.settled)
--     This is the local settlement projection for the radiology worklist gate (CR-INC05-01).
--     Set TRUE at charge time for INSURANCE/COVERED; FALSE for CASH-OPD.
--     Flipped to TRUE when the cash bill is PAID (deferred event seam — same pattern as lab_tests).
ALTER TABLE radiologies ADD COLUMN settled BOOLEAN NOT NULL DEFAULT FALSE;

-- (9) Index on settled to support the worklist query (settled=true AND status IN (...))
CREATE INDEX idx_radiologies_settled ON radiologies (settled);
