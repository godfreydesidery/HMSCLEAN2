-- =====================================================================================
-- V30 — ConsultationTransfer: apply ADR-0022 D2 Correction to patient cross-module ref
--
-- V22 created consultation_transfers with patient_id BIGINT NOT NULL FK → patients(id).
-- This mirrors the original V19 pattern for consultations. The ADR-0022 D2 CORRECTION
-- (documented in the ADR under "Correction (2026-06-04)") mandates that cross-module
-- id-FK columns be DROPPED once the entity replaces them with loose uid columns:
--   "a NOT-NULL FK column that nothing populates cannot be retained; the uid columns
--    FULLY replace them."
--
-- The consultation_id FK → consultations(id) is an INTRA-MODULE FK (clinical owns both
-- consultation_transfers and consultations) and is RETAINED — no change.
--
-- LOSS-FREE migration:
--   (1) ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3) Set patient_uid NOT NULL after backfill.
--   (4) ADD index on patient_uid to support the queue/lookup finders.
--   (5) DROP patient_id + the fk_consultation_transfers_patient FK constraint.
--
-- The partial-unique index uq_consultation_transfers_one_pending_per_patient was keyed on
-- (patient_id) WHERE status='PENDING'. It must be DROPPED and RECREATED on (patient_uid).
--
-- Legacy citations:
--   ConsultationTransfer.java:45-48 (patient FK — replaced with loose uid per ADR-0022)
--   PatientServiceImpl.java:2764-2767 (at-most-one-PENDING-per-patient invariant)
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only, no
--   cross-module DB FK).
-- =====================================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE consultation_transfers ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE consultation_transfers ct
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = ct.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE consultation_transfers ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Index on patient_uid for the lookup finders
CREATE INDEX idx_consultation_transfers_patient_uid
    ON consultation_transfers (patient_uid);

-- (5) Drop the old partial-unique index on patient_id before dropping the column
DROP INDEX uq_consultation_transfers_one_pending_per_patient;

-- (6) Recreate the partial-unique on patient_uid (the invariant: at most one PENDING per patient)
CREATE UNIQUE INDEX uq_consultation_transfers_one_pending_per_patient
    ON consultation_transfers (patient_uid) WHERE status = 'PENDING';

-- (7) Drop the now-dead cross-module id-FK column + its foreign key constraint
ALTER TABLE consultation_transfers DROP CONSTRAINT fk_consultation_transfers_patient;
ALTER TABLE consultation_transfers DROP COLUMN patient_id;
