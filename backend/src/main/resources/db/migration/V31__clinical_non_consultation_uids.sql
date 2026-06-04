-- =====================================================================================
-- V31 — NonConsultation: apply ADR-0022 D2 Correction to patient/visit cross-module refs
--
-- V21 created non_consultations with:
--   patient_id BIGINT NOT NULL FK → patients(id)
--   visit_id   BIGINT NOT NULL FK → visits(id)
-- These are cross-module id-FK columns. The ADR-0022 D2 CORRECTION mandates that such
-- columns be DROPPED once the entity replaces them with loose uid columns:
--   "a NOT-NULL FK column that nothing populates cannot be retained; the uid columns
--    FULLY replace them. Clinical references registration aggregates by uid only, no
--    cross-module DB FK (ADR-0008)."
--
-- LOSS-FREE migration (mirrors V29 / V30 house style exactly):
--   (1) ADD patient_uid VARCHAR(26) — initially nullable for backfill.
--   (2) ADD visit_uid   VARCHAR(26) — initially nullable for backfill.
--   (3) Backfill patient_uid from patients.uid via the existing patient_id FK.
--   (4) Backfill visit_uid from visits.uid via the existing visit_id FK (NOT NULL in V21,
--       so all rows have a visit_id — a plain UPDATE without WHERE is correct).
--   (5) Set patient_uid NOT NULL after backfill (every walk-in has a patient).
--   (6) Set visit_uid   NOT NULL after backfill (V21 declared visit_id NOT NULL).
--   (7) Index on patient_uid for the get-or-create IN_PROCESS lookup finder.
--   (8) Index on visit_uid for completeness / future order-save wiring (C7-C9).
--   (9) DROP the old idx_non_consultations_patient (keyed on patient_id) — removed
--       implicitly when the column is dropped, but named explicitly for clarity.
--  (10) DROP idx_non_consultations_visit (keyed on visit_id) — same reason.
--  (11) DROP the fk_non_consultations_patient FK constraint.
--  (12) DROP the fk_non_consultations_visit FK constraint.
--  (13) DROP patient_id column.
--  (14) DROP visit_id column.
--
-- Legacy citations:
--   NonConsultation.java:44-80 (:54-60 patient FK, :62-68 visit FK)
--   PatientServiceImpl.java:790-806 (OUTSIDER lab get-or-create)
--   ADR-0022 D2 + Correction; ADR-0008 §1 (uid-only cross-module refs)
-- =====================================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE non_consultations ADD COLUMN patient_uid VARCHAR(26);

-- (2) Add visit_uid column (initially nullable for backfill)
ALTER TABLE non_consultations ADD COLUMN visit_uid VARCHAR(26);

-- (3) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE non_consultations nc
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = nc.patient_id
);

-- (4) Backfill visit_uid from visits.uid via the existing visit_id FK
--     V21 declared visit_id NOT NULL, so all rows have a visit_id — no WHERE needed.
UPDATE non_consultations nc
SET visit_uid = (
    SELECT v.uid
    FROM visits v
    WHERE v.id = nc.visit_id
);

-- (5) Enforce NOT NULL on patient_uid after backfill (every walk-in has a patient)
ALTER TABLE non_consultations ALTER COLUMN patient_uid SET NOT NULL;

-- (6) Enforce NOT NULL on visit_uid after backfill (mirroring V21's NOT NULL on visit_id)
ALTER TABLE non_consultations ALTER COLUMN visit_uid SET NOT NULL;

-- (7) Index on patient_uid — supports the get-or-create IN_PROCESS lookup
--     (NonConsultationRepository.findByPatientUidAndStatus)
CREATE INDEX idx_non_consultations_patient_uid ON non_consultations (patient_uid);

-- (8) Index on visit_uid — supports future order-save wiring (C7-C9, deferred)
CREATE INDEX idx_non_consultations_visit_uid ON non_consultations (visit_uid);

-- (9)-(10) Drop the old id-based indexes before dropping the columns.
--          PostgreSQL drops indexes automatically with the column; the explicit DROP makes
--          the migration self-documenting (mirrors V30 pattern).
DROP INDEX IF EXISTS idx_non_consultations_patient;
DROP INDEX IF EXISTS idx_non_consultations_visit;

-- (11) Drop the cross-module FK constraints (clinical → registration boundary violation)
ALTER TABLE non_consultations DROP CONSTRAINT fk_non_consultations_patient;
ALTER TABLE non_consultations DROP CONSTRAINT fk_non_consultations_visit;

-- (13) Drop the now-dead cross-module id-FK columns
ALTER TABLE non_consultations DROP COLUMN patient_id;
ALTER TABLE non_consultations DROP COLUMN visit_id;
