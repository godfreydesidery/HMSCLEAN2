-- =====================================================================================
-- V35 — procedures: apply ADR-0022 D2 Correction to cross-module patient reference,
--       and add the local 'settled' boolean column (not present in V26).
--
-- V26 created procedures with:
--   patient_id BIGINT NOT NULL FK → patients(id)  (cross-module id-FK — MUST become uid)
--   [no settled column]                            (needed for the procedure add_note gate)
--
-- ADR-0022 D2 mandates that cross-module id-FK columns be replaced with loose uid columns.
-- CR-INC05-01 mandates a local 'settled' boolean on procedures to gate the add_note transition
-- (PatientResource.java:3408-3414 — the in-method bill gate).
-- This mirrors V33 (lab_tests) and V34 (radiologies) exactly.
--
-- LOSS-FREE migration (mirrors V33 / V34 house style exactly):
--   (1) ADD patient_uid VARCHAR(26) — loose cross-module ref (ADR-0008 §1, ADR-0022 D2).
--   (2) Backfill patient_uid from patients.uid via the (about-to-be-dropped) patient_id FK.
--   (3) Set patient_uid NOT NULL after backfill.
--   (4) DROP idx_procedures_patient (keyed on patient_id).
--   (5) ADD index on patient_uid for the worklist/lookup finders.
--   (6) DROP fk_procedures_patient constraint.
--   (7) DROP patient_id column.
--   (8) ADD settled BOOLEAN NOT NULL DEFAULT FALSE (add_note gate — CR-INC05-01 parity).
--   (9) ADD index on settled for the worklist query.
--
-- The consultation_id FK → consultations(id) and non_consultation_id FK → non_consultations(id)
-- are INTRA-MODULE FKs (clinical owns all three tables) and are RETAINED — no change.
--
-- Legacy citations:
--   Procedure.java:40-147 (patient FK — replaced with loose uid per ADR-0022)
--   PatientResource.java:3408-3414 — add_note settlement gate (settled required)
--   ADR-0022 D2 + Correction (clinical references registration aggregates by uid only,
--   no cross-module DB FK).
--   CR-INC05-01 (settled gate — mirrors lab_tests.settled from V33 and radiologies.settled from V34).
-- =====================================================================================

-- (1) Add patient_uid column (initially nullable for backfill)
ALTER TABLE procedures ADD COLUMN patient_uid VARCHAR(26);

-- (2) Backfill patient_uid from patients.uid via the existing patient_id FK
UPDATE procedures pr
SET patient_uid = (
    SELECT p.uid
    FROM patients p
    WHERE p.id = pr.patient_id
);

-- (3) Enforce NOT NULL on patient_uid after backfill
ALTER TABLE procedures ALTER COLUMN patient_uid SET NOT NULL;

-- (4) Drop the old index on patient_id (named per V26 convention)
DROP INDEX idx_procedures_patient;

-- (5) Add index on patient_uid for the worklist/lookup finders
CREATE INDEX idx_procedures_patient_uid
    ON procedures (patient_uid);

-- (6) Drop the now-dead cross-module FK constraint
ALTER TABLE procedures DROP CONSTRAINT fk_procedures_patient;

-- (7) Drop the old patient_id column
ALTER TABLE procedures DROP COLUMN patient_id;

-- (8) Add 'settled' boolean column (NOT NULL DEFAULT FALSE — mirrors V33 lab_tests.settled
--     and V34 radiologies.settled).
--     This is the local settlement projection for the add_note gate (CR-INC05-01).
--     Set TRUE at charge time for INSURANCE/COVERED; FALSE for CASH-OPD.
--     Flipped to TRUE when the cash bill is PAID (deferred event seam — same pattern as lab/radiology).
ALTER TABLE procedures ADD COLUMN settled BOOLEAN NOT NULL DEFAULT FALSE;

-- (9) Index on settled to support the worklist query (settled=true AND status IN (...))
CREATE INDEX idx_procedures_settled ON procedures (settled);
