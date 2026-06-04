-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- The encounter clinical-record cluster:
--   clinical_notes, general_examinations, patient_vitals, working_diagnoses, final_diagnoses
--
-- ENCOUNTER BINDING (all five tables): exactly ONE of the nullable encounter links is set.
--   Enforced by ck_<table>_one_encounter CHECK using num_nonnulls(...) = 1 (Postgres 16).
--   Notes/exam/vitals bind to one of: consultation_id | non_consultation_id | admission_uid.
--   Diagnoses bind to one of: consultation_id | admission_uid (diagnosis entities have NO
--   non_consultation link).
--   consultation_id / non_consultation_id = real intra-module FKs.
--   admission_uid = loose VARCHAR(26) (admissions module NOT built; ManyToOne in legacy; no FK).
--
-- UPSERT vs APPEND asymmetry (CR-INC05-07, faithfully reproduced):
--   notes/exam/vitals: ONE row per consultation and ONE per non_consultation (partial UNIQUE
--     on consultation_id / non_consultation_id — upsert). Admission path has NO unique (append).
--   diagnoses: duplicate-(consultation, diagnosis_type) is an APP-LAYER guard for consultation
--     paths ONLY (existsByConsultationAndDiagnosisType). Admission path is intentionally
--     unguarded. Therefore NO DB unique on (admission_uid, diagnosis_type_uid).
--
-- VITALS ARE FREE-TEXT (CR-INC05-13 REJECT): all vital signs are VARCHAR with no numeric
--   typing / range validation / server-side BMI/BSA computation — exact-process fidelity.
--
-- TWO SEPARATE DIAGNOSIS TABLES (drift correction): working_diagnoses + final_diagnoses are
--   byte-for-byte structural twins, NOT one kind-discriminated table. Table names normalised
--   from the legacy mis-spelled working_diagnosises / final_diagnosises.
--
-- Conventions: identical to V15/V19. Named constraints: pk_/fk_/uq_/ck_/idx_.
--
-- Legacy citations:
--   ClinicalNote.java:34-75; GeneralExamination.java:32-75 (:42-51 free-text vitals);
--   PatientVital.java:23-67; WorkingDiagnosis.java:28-65 (table working_diagnosises:32);
--   FinalDiagnosis.java:31-68 (table final_diagnosises:35);
--   PatientResource.java:1298-1307 (auto-create EMPTY vital), :1662/:1782 (consultation-only
--   diagnosis duplicate guard).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- clinical_notes — the SOAP note (ClinicalNote.java:34-75). 8 nullable free-text fields,
-- no bean validation. One note per consultation / per non_consultation (upsert).
-- -------------------------------------------------------------------------------------
CREATE TABLE clinical_notes (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    main_complain               VARCHAR(500),
    present_illness_history     TEXT,
    past_medical_history        TEXT,
    family_and_social_history   TEXT,
    drugs_and_allergy_history   TEXT,
    review_of_other_systems     TEXT,
    physical_examination        TEXT,
    management_plan             TEXT,

    -- Encounter binding (exactly one non-null — see ck_..._one_encounter)
    consultation_id             BIGINT,                         -- real FK (@OneToOne)
    non_consultation_id         BIGINT,                         -- real FK (@OneToOne)
    admission_uid               VARCHAR(26),                    -- loose, no FK

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80)     NOT NULL,
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_clinical_notes                PRIMARY KEY (id),
    CONSTRAINT uq_clinical_notes_uid            UNIQUE (uid),
    CONSTRAINT fk_clinical_notes_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_clinical_notes_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT ck_clinical_notes_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

-- One note per consultation / per non_consultation (upsert); admission path appends.
CREATE UNIQUE INDEX uq_clinical_notes_consultation
    ON clinical_notes (consultation_id) WHERE consultation_id IS NOT NULL;
CREATE UNIQUE INDEX uq_clinical_notes_non_consultation
    ON clinical_notes (non_consultation_id) WHERE non_consultation_id IS NOT NULL;

CREATE INDEX idx_clinical_notes_consultation     ON clinical_notes (consultation_id);
CREATE INDEX idx_clinical_notes_non_consultation ON clinical_notes (non_consultation_id);
CREATE INDEX idx_clinical_notes_admission_uid    ON clinical_notes (admission_uid);

-- -------------------------------------------------------------------------------------
-- general_examinations — vitals + exam record (GeneralExamination.java:32-75).
-- ALL vitals free-text VARCHAR (CR-INC05-13). BMI/BSA NOT computed server-side.
-- -------------------------------------------------------------------------------------
CREATE TABLE general_examinations (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    pressure                    VARCHAR(40),
    temperature                 VARCHAR(40),
    pulse_rate                  VARCHAR(40),
    weight                      VARCHAR(40),
    height                      VARCHAR(40),
    body_mass_index             VARCHAR(40),
    body_mass_index_comment     VARCHAR(200),
    body_surface_area           VARCHAR(40),
    saturation_oxygen           VARCHAR(40),
    respiratory_rate            VARCHAR(40),
    description                 VARCHAR(1000),

    consultation_id             BIGINT,                         -- real FK (@OneToOne)
    non_consultation_id         BIGINT,                         -- real FK (@OneToOne)
    admission_uid               VARCHAR(26),                    -- loose, no FK

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_general_examinations          PRIMARY KEY (id),
    CONSTRAINT uq_general_examinations_uid      UNIQUE (uid),
    CONSTRAINT fk_general_examinations_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_general_examinations_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT ck_general_examinations_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE UNIQUE INDEX uq_general_examinations_consultation
    ON general_examinations (consultation_id) WHERE consultation_id IS NOT NULL;
CREATE UNIQUE INDEX uq_general_examinations_non_consultation
    ON general_examinations (non_consultation_id) WHERE non_consultation_id IS NOT NULL;

CREATE INDEX idx_general_examinations_consultation     ON general_examinations (consultation_id);
CREATE INDEX idx_general_examinations_non_consultation ON general_examinations (non_consultation_id);
CREATE INDEX idx_general_examinations_admission_uid    ON general_examinations (admission_uid);

-- -------------------------------------------------------------------------------------
-- patient_vitals — nurse vitals-capture staging entity (PatientVital.java:23-67).
-- Mirrors general_examinations field set; adds a status lifecycle EMPTY->SUBMITTED->ARCHIVED.
-- Auto-created EMPTY per encounter (PatientResource.java:1298-1307) -> partial unique upsert.
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_vitals (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    pressure                    VARCHAR(40),
    temperature                 VARCHAR(40),
    pulse_rate                  VARCHAR(40),
    weight                      VARCHAR(40),
    height                      VARCHAR(40),
    body_mass_index             VARCHAR(40),
    body_mass_index_comment     VARCHAR(200),
    body_surface_area           VARCHAR(40),
    saturation_oxygen           VARCHAR(40),
    respiratory_rate            VARCHAR(40),
    description                 VARCHAR(1000),

    -- staging lifecycle (PatientVital.java:45)
    status                      VARCHAR(20)     NOT NULL        DEFAULT 'EMPTY',

    consultation_id             BIGINT,                         -- real FK (@OneToOne)
    non_consultation_id         BIGINT,                         -- real FK (@OneToOne)
    admission_uid               VARCHAR(26),                    -- loose, no FK

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_vitals                PRIMARY KEY (id),
    CONSTRAINT uq_patient_vitals_uid            UNIQUE (uid),
    CONSTRAINT fk_patient_vitals_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_patient_vitals_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT ck_patient_vitals_status CHECK (
        status IN ('EMPTY', 'SUBMITTED', 'ARCHIVED')
    ),
    CONSTRAINT ck_patient_vitals_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE UNIQUE INDEX uq_patient_vitals_consultation
    ON patient_vitals (consultation_id) WHERE consultation_id IS NOT NULL;
CREATE UNIQUE INDEX uq_patient_vitals_non_consultation
    ON patient_vitals (non_consultation_id) WHERE non_consultation_id IS NOT NULL;

CREATE INDEX idx_patient_vitals_consultation     ON patient_vitals (consultation_id);
CREATE INDEX idx_patient_vitals_non_consultation ON patient_vitals (non_consultation_id);
CREATE INDEX idx_patient_vitals_admission_uid    ON patient_vitals (admission_uid);

-- Nurse -> doctor handoff queue (load submitted vitals, PatientResource.java:1321).
CREATE INDEX idx_patient_vitals_submitted
    ON patient_vitals (status) WHERE status = 'SUBMITTED';

-- -------------------------------------------------------------------------------------
-- working_diagnoses — provisional diagnosis (WorkingDiagnosis.java:28-65).
-- diagnosis_type_uid MANDATORY loose ref (updatable=false). patient_id MANDATORY real FK.
-- Binds to exactly one of consultation_id | admission_uid (NO non_consultation link).
-- NO DB unique on (admission_uid, diagnosis_type_uid) — duplicate guard is app-layer
-- consultation-only (CR-INC05-07 asymmetry).
-- -------------------------------------------------------------------------------------
CREATE TABLE working_diagnoses (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    description                 TEXT,

    -- MANDATORY loose ref to masterdata diagnosis_types (updatable=false; no FK)
    diagnosis_type_uid          VARCHAR(26)     NOT NULL,

    consultation_id             BIGINT,                         -- real FK (@ManyToOne)
    admission_uid               VARCHAR(26),                    -- loose, no FK

    -- MANDATORY real FK to patient (updatable=false)
    patient_id                  BIGINT          NOT NULL,

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80)     NOT NULL,
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_working_diagnoses             PRIMARY KEY (id),
    CONSTRAINT uq_working_diagnoses_uid         UNIQUE (uid),
    CONSTRAINT fk_working_diagnoses_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_working_diagnoses_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_working_diagnoses_one_encounter CHECK (
        num_nonnulls(consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_working_diagnoses_consultation        ON working_diagnoses (consultation_id);
CREATE INDEX idx_working_diagnoses_admission_uid       ON working_diagnoses (admission_uid);
CREATE INDEX idx_working_diagnoses_patient             ON working_diagnoses (patient_id);
CREATE INDEX idx_working_diagnoses_diagnosis_type_uid  ON working_diagnoses (diagnosis_type_uid);

-- -------------------------------------------------------------------------------------
-- final_diagnoses — confirmed diagnosis (FinalDiagnosis.java:31-68).
-- Byte-for-byte structural twin of working_diagnoses (SEPARATE table — confirms two
-- entities, not one). Same consultation-only app-layer duplicate guard; admission unguarded.
-- -------------------------------------------------------------------------------------
CREATE TABLE final_diagnoses (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    description                 TEXT,

    diagnosis_type_uid          VARCHAR(26)     NOT NULL,

    consultation_id             BIGINT,                         -- real FK (@ManyToOne)
    admission_uid               VARCHAR(26),                    -- loose, no FK

    patient_id                  BIGINT          NOT NULL,

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80)     NOT NULL,
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_final_diagnoses               PRIMARY KEY (id),
    CONSTRAINT uq_final_diagnoses_uid           UNIQUE (uid),
    CONSTRAINT fk_final_diagnoses_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_final_diagnoses_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_final_diagnoses_one_encounter CHECK (
        num_nonnulls(consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_final_diagnoses_consultation        ON final_diagnoses (consultation_id);
CREATE INDEX idx_final_diagnoses_admission_uid       ON final_diagnoses (admission_uid);
CREATE INDEX idx_final_diagnoses_patient             ON final_diagnoses (patient_id);
CREATE INDEX idx_final_diagnoses_diagnosis_type_uid  ON final_diagnoses (diagnosis_type_uid);
