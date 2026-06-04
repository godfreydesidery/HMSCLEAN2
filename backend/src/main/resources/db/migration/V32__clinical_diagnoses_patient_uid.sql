-- =====================================================================================
-- V32 — WorkingDiagnosis + FinalDiagnosis: apply ADR-0022 D2 Correction to cross-module
--       patient reference on BOTH diagnosis tables.
--
-- V23 created working_diagnoses and final_diagnoses with patient_id BIGINT NOT NULL FK
-- → patients(id).  Per ADR-0022 D2 the cross-module id-FK is replaced with a loose uid
-- column — same pattern as V30 (consultation_transfers).
--
-- The consultation_id FK → consultations(id) is an INTRA-MODULE FK (clinical owns both
-- working_diagnoses/final_diagnoses and consultations) and is RETAINED — no change.
--
-- LOSS-FREE migration (mirrors V30 house style exactly):
--   (1) ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3) Set patient_uid NOT NULL after backfill.
--   (4) DROP the old index on patient_id.
--   (5) ADD index on patient_uid to support the lookup finders.
--   (6) DROP the cross-module FK constraint.
--   (7) DROP the patient_id column.
--
-- Both tables (working_diagnoses and final_diagnoses) are migrated identically.
--
-- Legacy citations:
--   WorkingDiagnosis.java:28-65 (patient FK — replaced with loose uid per ADR-0022)
--   FinalDiagnosis.java:31-68   (patient FK — replaced with loose uid per ADR-0022)
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only,
--   no cross-module DB FK).
-- =====================================================================================

-- ==========================================================================
-- working_diagnoses
-- ==========================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE working_diagnoses ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE working_diagnoses wd
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = wd.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE working_diagnoses ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V23 convention)
DROP INDEX idx_working_diagnoses_patient;

-- (5) Add index on patient_uid for the lookup finders
CREATE INDEX idx_working_diagnoses_patient_uid
    ON working_diagnoses (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE working_diagnoses DROP CONSTRAINT fk_working_diagnoses_patient;

-- (7) Drop the old patient_id column
ALTER TABLE working_diagnoses DROP COLUMN patient_id;

-- ==========================================================================
-- final_diagnoses
-- ==========================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE final_diagnoses ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE final_diagnoses fd
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = fd.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE final_diagnoses ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V23 convention)
DROP INDEX idx_final_diagnoses_patient;

-- (5) Add index on patient_uid for the lookup finders
CREATE INDEX idx_final_diagnoses_patient_uid
    ON final_diagnoses (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE final_diagnoses DROP CONSTRAINT fk_final_diagnoses_patient;

-- (7) Drop the old patient_id column
ALTER TABLE final_diagnoses DROP COLUMN patient_id;
