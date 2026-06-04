-- =====================================================================================
-- V37 — deceased_notes + referral_plans: apply ADR-0022 D2 Correction to cross-module
--       patient reference on BOTH closure tables.
--
-- V28 created deceased_notes and referral_plans with patient_id BIGINT NOT NULL FK
-- → patients(id).  Per ADR-0022 D2 the cross-module id-FK is replaced with a loose uid
-- column — same pattern as V30-V36 (consultation_transfers, diagnoses, lab_tests,
-- radiologies, procedures, prescriptions).
--
-- The consultation_id FK → consultations(id) is an INTRA-MODULE FK (clinical owns both
-- closure tables and consultations) and is RETAINED — no change.
--
-- LOSS-FREE migration (mirrors V32 house style exactly):
--   (1) ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3) Set patient_uid NOT NULL after backfill.
--   (4) DROP the old index on patient_id.
--   (5) ADD index on patient_uid to support lookup finders.
--   (6) DROP the cross-module FK constraint.
--   (7) DROP the patient_id column.
--
-- Both tables (deceased_notes and referral_plans) are migrated identically.
--
-- Legacy citations:
--   DeceasedNote.java:60-63 (patient FK — replaced with loose uid per ADR-0022 D2)
--   ReferralPlan.java:54-57 (patient FK — replaced with loose uid per ADR-0022 D2)
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only,
--   no cross-module DB FK).
-- =====================================================================================

-- ==========================================================================
-- deceased_notes
-- ==========================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE deceased_notes ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE deceased_notes dn
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = dn.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE deceased_notes ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V28 convention)
DROP INDEX idx_deceased_notes_patient;

-- (5) Add index on patient_uid for the lookup finders
CREATE INDEX idx_deceased_notes_patient_uid
    ON deceased_notes (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE deceased_notes DROP CONSTRAINT fk_deceased_notes_patient;

-- (7) Drop the old patient_id column
ALTER TABLE deceased_notes DROP COLUMN patient_id;

-- ==========================================================================
-- referral_plans
-- ==========================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE referral_plans ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE referral_plans rp
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = rp.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE referral_plans ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V28 convention)
DROP INDEX idx_referral_plans_patient;

-- (5) Add index on patient_uid for the lookup finders
CREATE INDEX idx_referral_plans_patient_uid
    ON referral_plans (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE referral_plans DROP CONSTRAINT fk_referral_plans_patient;

-- (7) Drop the old patient_id column
ALTER TABLE referral_plans DROP COLUMN patient_id;
