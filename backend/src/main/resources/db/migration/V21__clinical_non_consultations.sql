-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- non_consultations: the walk-in / no-doctor encounter track (NonConsultation.java:44-80).
--
-- Distinct from consultations: a NonConsultation has NO patient_bill, NO clinic, NO
-- clinician, NO follow_up. It exists so OUTSIDER / walk-in orders (lab/radiology/etc.)
-- can bind to an encounter without a doctor consultation.
--
-- MUST precede V23-V27: clinical_notes, general_examinations, patient_vitals, lab_tests,
-- radiologies, procedures, prescriptions, patient_prescription_charts all carry a real
-- FK to non_consultations(id).
--
-- Conventions: identical to V15/V19.
--   id BIGINT GENERATED ALWAYS AS IDENTITY (hidden) + uid VARCHAR(26) NOT NULL UNIQUE
--   patient_id / visit_id = real intra-schema FKs (registration co-located, ADR-0008-R2)
--   insurance_plan_uid / business_day_uid = loose VARCHAR(26), NO FK (ADR-0008)
--   Named constraints: pk_/fk_/uq_/ck_/idx_
--
-- Legacy citations:
--   NonConsultation.java:44-80 (:48-49 paymentType, :54-60 patient FK, :62-68 visit FK,
--   :70-73 insurancePlan loose). Status values observed IN-PROCESS / SIGNED-OUT
--   (PatientServiceImpl.java:791; PatientResource.java:350).
-- =====================================================================================

CREATE TABLE non_consultations (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,

    -- paymentType: CASH | INSURANCE | '' (NonConsultation.java:48-49; default '')
    payment_type        VARCHAR(20)     NOT NULL            DEFAULT '',

    -- insurance membership number; default '' mirrors legacy
    membership_no       VARCHAR(100)                        DEFAULT '',

    -- lifecycle: free-text in legacy; observed IN-PROCESS -> SIGNED-OUT
    status              VARCHAR(20)     NOT NULL,

    -- Intra-schema real FKs (ManyToOne) — patient/visit co-located (ADR-0008-R2)
    patient_id          BIGINT          NOT NULL,
    visit_id            BIGINT          NOT NULL,

    -- Loose cross-module ref to masterdata insurance_plans (no FK, NonConsultation.java:70-73)
    insurance_plan_uid  VARCHAR(26),

    -- Loose cross-module ref to the open business day (no FK)
    business_day_uid    VARCHAR(26)     NOT NULL,

    -- Audit columns (platform standard)
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,

    CONSTRAINT pk_non_consultations             PRIMARY KEY (id),
    CONSTRAINT uq_non_consultations_uid         UNIQUE (uid),
    CONSTRAINT fk_non_consultations_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_non_consultations_visit
        FOREIGN KEY (visit_id) REFERENCES visits (id),
    CONSTRAINT ck_non_consultations_status CHECK (
        status IN ('IN-PROCESS', 'SIGNED-OUT')
    ),
    CONSTRAINT ck_non_consultations_payment_type CHECK (
        payment_type IN ('CASH', 'INSURANCE', '')
    )
);

CREATE INDEX idx_non_consultations_patient ON non_consultations (patient_id);
CREATE INDEX idx_non_consultations_visit   ON non_consultations (visit_id);
CREATE INDEX idx_non_consultations_status  ON non_consultations (status);
