-- =====================================================================================
-- V29 — Clinical consultation aggregate ownership transfer (ADR-0022, CR-21)
--
-- LOSS-FREE migration: patient/visit identity is preserved as ULID uids before the
-- legacy id-FK columns are dropped. Reconciliation (data-migration-engineer): every row's
-- patient_uid must equal patients.uid for the pre-drop patient_id (100% match).
--
-- Changes:
--   (1) ADD patient_uid VARCHAR(26)  — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) ADD visit_uid   VARCHAR(26)  — loose cross-module ref.
--   (3) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (4) Backfill visit_uid from visits.uid via the visit_id FK (LEFT: visit_id is nullable).
--   (5) Set patient_uid NOT NULL after backfill (visit_uid stays nullable, mirroring V19's
--       nullable visit_id).
--   (6) ADD settled BOOLEAN NOT NULL DEFAULT FALSE — clinical-local settlement projection
--       (ADR-0022 D2/D4, inc-05 §5). Existing PENDING rows default false; acceptable —
--       they are inc-03 test rows, not live production data.
--   (7) ADD index on patient_uid for the cross-module lookup finders.
--   (8) DROP the now-dead id-FK columns patient_id / visit_id and their FOREIGN KEYs.
--
-- CORRECTION TO ADR-0022 D2 (recorded in the ADR's "Correction (2026-06-04)" note):
--   ADR-0022 D2 originally proposed RETAINING the real patient_id / visit_id DB FKs while
--   the clinical Consultation entity stopped MAPPING them. That is internally inconsistent:
--   once the entity no longer maps patient_id, the application never writes it, so a
--   NOT-NULL patient_id makes EVERY consultation INSERT fail ("null value in column
--   patient_id violates not-null constraint"). A NOT-NULL FK column that nothing populates
--   cannot be retained, and keeping it nullable-but-unwritten is dead weight that drifts
--   out of sync with patient_uid. The uid columns FULLY replace them. Patient/visit
--   referential integrity now follows the ADR-0008 loose-uid convention (no cross-module
--   FK) — exactly like clinic_uid / clinician_user_uid / patient_bill_uid already do.
--   This is the faithful Modulith boundary: clinical references registration aggregates by
--   uid only, with NO database FK across the module boundary.
--
-- Legacy citations:
--   Consultation.java:62-65 (patient ref), :93-97 (visit ref)
--   PatientServiceImpl.java:494 (booking — sets patient + visit)
--   ADR-0008 §1 (uid-only cross-module refs); ADR-0022 D2 + Correction
--   inc-05 11-DECISIONS-RATIFIED §5 (settled flag at booking pre-pass)
-- =====================================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE consultations ADD COLUMN patient_uid VARCHAR(26);

-- (2) Add visit_uid column (nullable — mirrors nullable visit_id)
ALTER TABLE consultations ADD COLUMN visit_uid VARCHAR(26);

-- (3) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE consultations c
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = c.patient_id
);

-- (4) Backfill visit_uid from visits.uid via the existing visit_id FK
--     LEFT: visit_id may be null on some rows, so only backfill where visit_id is not null.
UPDATE consultations c
SET visit_uid = (
    SELECT v.uid
    FROM visits v
    WHERE v.id = c.visit_id
)
WHERE c.visit_id IS NOT NULL;

-- (5) Enforce NOT NULL on patient_uid after the backfill (every consultation has a patient)
ALTER TABLE consultations ALTER COLUMN patient_uid SET NOT NULL;

-- (6) Add settled flag — local settlement projection (ADR-0022 D2/D4, inc-05 §5)
--     DEFAULT FALSE: existing PENDING rows are treated as unsettled until the
--     cash-PAID propagation seam lands (deferred seam — documented in Consultation.java).
ALTER TABLE consultations ADD COLUMN settled BOOLEAN NOT NULL DEFAULT FALSE;

-- (7) Index on patient_uid for existsByPatientUidAndStatus* finders (ADR-0022 D6)
CREATE INDEX idx_consultations_patient_uid ON consultations (patient_uid);

-- (8) Drop the now-dead legacy id-FK columns + their foreign keys (see CORRECTION above).
--     The uid columns fully replace them; clinical references patient/visit by uid only.
--     idx_consultations_patient_id (V19) is dropped implicitly with the column.
ALTER TABLE consultations DROP CONSTRAINT fk_consultations_patient;
ALTER TABLE consultations DROP CONSTRAINT fk_consultations_visit;
ALTER TABLE consultations DROP COLUMN patient_id;
ALTER TABLE consultations DROP COLUMN visit_id;
