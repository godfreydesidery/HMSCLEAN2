-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- consultations: ADDITIVE extension of the V19 registration stub (ADR-0008-R2, CR-21).
--
-- The consultations table physically exists from V19__registration_patient.sql (the
-- inc-03 PENDING booking stub). inc-05 takes OWNERSHIP (CR-21) and EXTENDS it additively.
-- It is NEVER recreated. patient_id/visit_id remain real intra-schema FKs (V19 already
-- FKs them); clinic_uid/clinician_user_uid/patient_bill_uid/business_day_uid stay loose.
--
-- Conventions: identical to V15/V19.
--   Cross-module refs = loose VARCHAR(26) uid, NO FK (ADR-0008)
--   Named constraints: pk_/fk_/uq_/ck_/idx_
--
-- Changes in this migration:
--   (1) WIDEN ck_consultations_status from {PENDING} to the full 6-value legacy set.
--       EXACT legacy spellings: hyphenated 'IN-PROCESS', single-R 'TRANSFERED',
--       single-L 'CANCELED'. STOPPED is a query-only ghost (no setStatus) — EXCLUDED.
--       Existing PENDING rows remain valid; no backfill needed (constraint-widening).
--   (2) ADD membership_no VARCHAR(100) DEFAULT '' (set from patient.membershipNo when
--       INSURANCE, else '').
--   (3) ADD insurance_plan_uid VARCHAR(26) NULL (loose cross-module ref, no FK).
--   (4) ADD the three-doctor-worklist composite index + insurance-plan partial index.
--
-- Legacy citations:
--   Consultation.java:54 (membershipNo), :55-56 (status enum), :99-102 (insurancePlan)
--   PatientServiceImpl.java:494,556; PatientResource.java:817,844,871,886,5753
--   Status set + HELD@5753 verified in 02-verifications.md; STOPPED ghost excluded.
-- =====================================================================================

-- (1) Widen the status CHECK to the full legacy lifecycle vocabulary.
ALTER TABLE consultations DROP CONSTRAINT ck_consultations_status;
ALTER TABLE consultations ADD CONSTRAINT ck_consultations_status CHECK (
    status IN ('PENDING', 'IN-PROCESS', 'TRANSFERED', 'CANCELED', 'SIGNED-OUT', 'HELD')
);

-- (2) membership_no: set from patient.membershipNo on INSURANCE booking, else ''
--     (Consultation.java:54; PatientServiceImpl.java:556).
ALTER TABLE consultations ADD COLUMN membership_no VARCHAR(100) DEFAULT '';

-- (3) insurance_plan_uid: optional loose cross-module ref to masterdata insurance_plans
--     (Consultation.java:99-102; no FK, ADR-0008).
ALTER TABLE consultations ADD COLUMN insurance_plan_uid VARCHAR(26);

-- (4a) Doctor worklist composite: covers all three clinician worklists —
--      load_pending_consultations_by_clinician_id (clinician + followUp=false + PENDING,
--      PatientResource.java:817), load_follow_up_list (clinician + followUp=true +
--      status IN PENDING,IN-PROCESS, :844), load_in_process (clinician + followUp=false +
--      status IN IN-PROCESS,TRANSFERED, :871).
CREATE INDEX idx_consultations_clinician_status_followup
    ON consultations (clinician_user_uid, status, follow_up);

-- (4b) insurance re-pricing / claim joins (partial — only insured consultations).
CREATE INDEX idx_consultations_insurance_plan_uid
    ON consultations (insurance_plan_uid) WHERE insurance_plan_uid IS NOT NULL;
