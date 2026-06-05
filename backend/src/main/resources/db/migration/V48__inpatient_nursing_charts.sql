-- =====================================================================================
-- Increment 07b — Nursing charts + dressing masterdata (SCHEMA)
--
-- New tables (clinical-owned, loose admission/patient/nurse refs — ADR-0008 §1):
--   patient_nursing_charts     — 8 free-text columns (PatientNursingChart.java:38-45)
--   patient_nursing_care_plans — 4 free-text columns (PatientNursingCarePlan.java:38-41)
--   patient_nursing_progress_notes — single note TEXT (PatientNursingProgressNote.java:38)
--   patient_dressing_charts    — billing record with mandatory procedure_type + patient_bill
--   dressings                  — masterdata registry of procedure types listed as dressings
--
-- Design rules:
--   * ULID uid VARCHAR(26) + BIGINT GENERATED ALWAYS AS IDENTITY (ADR-0003, ADR-0005)
--   * All cross-module refs are loose *_uid VARCHAR(26) (NO physical FK — ADR-0008 §1)
--   * NO status column on nursing_charts/care_plans/progress_notes (AC-07B-NCA-01/NCP-01/NPR-01)
--   * NO wound_status/wound_description on dressing_charts (AC-07B-DRS-01)
--   * audit columns: created_at/updated_at/created_by/updated_by/version (AuditableEntity)
--
-- Legacy citations:
--   PatientNursingChart.java:38-45; PatientNursingCarePlan.java:38-41;
--   PatientNursingProgressNote.java:38; PatientDressingChart.java:40-95;
--   Dressing.java:35-49; PatientServiceImpl.java:2078-2245 (dressing billing logic);
--   PatientResource.java:3135-3138 (24h delete guard).
--
-- inc-07 07b / AC-07B-FLY-01 / AC-07B-NCA-01 / AC-07B-NCP-01 / AC-07B-NPR-01 / AC-07B-DRS-01
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- dressings — masterdata registry of procedure types listed as dressings
-- (Dressing.java:35-49; PatientServiceImpl.java:2094 "Procedure type is not listed as dressing")
-- -------------------------------------------------------------------------------------
CREATE TABLE dressings (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Loose ref to the procedure_type (no physical FK — ADR-0008 §1)
    procedure_type_uid          VARCHAR(26)     NOT NULL,

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_dressings                 PRIMARY KEY (id),
    CONSTRAINT uq_dressings_uid             UNIQUE (uid),
    CONSTRAINT uq_dressings_proc_type_uid   UNIQUE (procedure_type_uid)
);

CREATE INDEX idx_dressings_procedure_type_uid ON dressings (procedure_type_uid);

-- -------------------------------------------------------------------------------------
-- patient_nursing_charts — eight free-text observation columns (PatientNursingChart.java:38-45)
-- NO status column per AC-07B-NCA-01.
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_nursing_charts (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Eight free-text observation columns (PatientNursingChart.java:38-45)
    feeding                     VARCHAR(500),
    changing_position           VARCHAR(500),
    bed_bathing                 VARCHAR(500),
    random_blood_sugar          VARCHAR(500),
    full_blood_sugar            VARCHAR(500),
    drainage_output             VARCHAR(500),
    fluid_intake                VARCHAR(500),
    urine_output                VARCHAR(500),

    -- Loose cross-module refs (NO physical FK — ADR-0008 §1)
    admission_uid               VARCHAR(26),
    patient_uid                 VARCHAR(26)     NOT NULL,
    nurse_uid                   VARCHAR(26),

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_nursing_charts    PRIMARY KEY (id),
    CONSTRAINT uq_patient_nursing_charts    UNIQUE (uid)
);

CREATE INDEX idx_pnc_admission_uid ON patient_nursing_charts (admission_uid);
CREATE INDEX idx_pnc_patient_uid   ON patient_nursing_charts (patient_uid);

-- -------------------------------------------------------------------------------------
-- patient_nursing_care_plans — four free-text columns (PatientNursingCarePlan.java:38-41)
-- NO status / NO lifecycle per AC-07B-NCP-01.
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_nursing_care_plans (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Four free-text columns (PatientNursingCarePlan.java:38-41)
    nursing_diagnosis           TEXT,
    expected_outcome            TEXT,
    implementation              TEXT,
    evaluation                  TEXT,

    -- Loose cross-module refs
    admission_uid               VARCHAR(26),
    patient_uid                 VARCHAR(26)     NOT NULL,
    nurse_uid                   VARCHAR(26),

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_nursing_care_plans    PRIMARY KEY (id),
    CONSTRAINT uq_patient_nursing_care_plans    UNIQUE (uid)
);

CREATE INDEX idx_pncp_admission_uid ON patient_nursing_care_plans (admission_uid);
CREATE INDEX idx_pncp_patient_uid   ON patient_nursing_care_plans (patient_uid);

-- -------------------------------------------------------------------------------------
-- patient_nursing_progress_notes — single note TEXT column (PatientNursingProgressNote.java:38)
-- NO kind/category column per AC-07B-NPR-01.
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_nursing_progress_notes (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Single free-text note (PatientNursingProgressNote.java:38)
    note                        TEXT,

    -- Loose cross-module refs
    admission_uid               VARCHAR(26),
    patient_uid                 VARCHAR(26)     NOT NULL,
    nurse_uid                   VARCHAR(26),

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_nursing_progress_notes    PRIMARY KEY (id),
    CONSTRAINT uq_patient_nursing_progress_notes    UNIQUE (uid)
);

CREATE INDEX idx_pnpn_admission_uid ON patient_nursing_progress_notes (admission_uid);
CREATE INDEX idx_pnpn_patient_uid   ON patient_nursing_progress_notes (patient_uid);

-- -------------------------------------------------------------------------------------
-- patient_dressing_charts — billing record (PatientDressingChart.java:40-95)
-- Mandatory procedure_type_uid (loose) + patient_bill_uid (loose @OneToOne equivalent).
-- NO wound_status/wound_description per AC-07B-DRS-01.
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_dressing_charts (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Billing fields (PatientDressingChart.java:40-43)
    qty                         NUMERIC(19,2)   NOT NULL,
    payment_type                VARCHAR(50),
    membership_no               VARCHAR(100),

    -- Mandatory loose refs (ADR-0008 §1 — no physical FK to billing or masterdata tables)
    patient_bill_uid            VARCHAR(26)     NOT NULL,
    procedure_type_uid          VARCHAR(26)     NOT NULL,

    -- Nullable loose refs
    admission_uid               VARCHAR(26),
    clinician_uid               VARCHAR(26),
    nurse_uid                   VARCHAR(26),
    insurance_plan_uid          VARCHAR(26),
    patient_uid                 VARCHAR(26)     NOT NULL,

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_dressing_charts   PRIMARY KEY (id),
    CONSTRAINT uq_patient_dressing_charts   UNIQUE (uid),
    CONSTRAINT uq_pdc_patient_bill_uid      UNIQUE (patient_bill_uid)
);

CREATE INDEX idx_pdc_admission_uid      ON patient_dressing_charts (admission_uid);
CREATE INDEX idx_pdc_patient_uid        ON patient_dressing_charts (patient_uid);
CREATE INDEX idx_pdc_patient_bill_uid   ON patient_dressing_charts (patient_bill_uid);
