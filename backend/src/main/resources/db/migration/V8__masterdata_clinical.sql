-- =====================================================================================
-- Increment 02 — P3 Clinical Catalog schema (build-spec §1.3, 07-design-schema §2.3)
--
-- Legacy citations:
--   LabTestType.java:43-71, LabTestTypeRange.java:37-54,
--   RadiologyType.java:38-60, ProcedureType.java:38-59, DiagnosisType.java:37-55
--
-- Decision: reproduce LEGACY MODEL ONLY (CR-05/CR-06 — no analyte/reference-range/RangeFlag).
-- LabTestTypeRange is a named-string-label scoped to a LabTestType.  NOTHING ELSE.
-- DiagnosisType entity name = DiagnosisType (NOT "Diagnosis"); no price/uom/ICD/hierarchy (CR-06).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- lab_test_types
-- Legacy LabTestType.java:43-71: code(uq), name(uq), description, price(double),
-- uom, active(default false).
-- On update, code is IMMUTABLE (LabTestTypeServiceImpl.java:47-48 — legacy quirk AC-9.4).
-- -------------------------------------------------------------------------------------
CREATE TABLE lab_test_types (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    price       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    uom         VARCHAR(40),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_lab_test_types      PRIMARY KEY (id),
    CONSTRAINT uq_lab_test_types_uid  UNIQUE (uid),
    CONSTRAINT uq_lab_test_types_code UNIQUE (code),
    CONSTRAINT uq_lab_test_types_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- lab_test_type_ranges
-- A NAMED STRING LABEL only (LabTestTypeRange.java:37-54).
-- NO low/high/min/max/unit/sex/age/flag columns — those are net-new (CR-05).
-- ON DELETE CASCADE reproduces legacy orphanRemoval=true (LabTestType.java:60-64).
-- -------------------------------------------------------------------------------------
CREATE TABLE lab_test_type_ranges (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    uid              VARCHAR(26)     NOT NULL,
    name             VARCHAR(200)    NOT NULL DEFAULT '',
    lab_test_type_id BIGINT          NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL,
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(80),
    updated_by       VARCHAR(80),
    version          BIGINT          NOT NULL,
    CONSTRAINT pk_lab_test_type_ranges     PRIMARY KEY (id),
    CONSTRAINT uq_lab_test_type_ranges_uid UNIQUE (uid),
    CONSTRAINT fk_lab_test_type_ranges_type
        FOREIGN KEY (lab_test_type_id) REFERENCES lab_test_types (id) ON DELETE CASCADE
);
CREATE INDEX idx_lab_test_type_ranges_type ON lab_test_type_ranges (lab_test_type_id);

-- -------------------------------------------------------------------------------------
-- radiology_types
-- Legacy RadiologyType.java:38-60: code(uq), name(uq), description, price(double), uom, active.
-- -------------------------------------------------------------------------------------
CREATE TABLE radiology_types (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    price       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    uom         VARCHAR(40),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_radiology_types      PRIMARY KEY (id),
    CONSTRAINT uq_radiology_types_uid  UNIQUE (uid),
    CONSTRAINT uq_radiology_types_code UNIQUE (code),
    CONSTRAINT uq_radiology_types_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- procedure_types
-- Same shape as radiology_types (legacy ProcedureType.java:38-59).
-- -------------------------------------------------------------------------------------
CREATE TABLE procedure_types (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    price       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    uom         VARCHAR(40),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_procedure_types      PRIMARY KEY (id),
    CONSTRAINT uq_procedure_types_uid  UNIQUE (uid),
    CONSTRAINT uq_procedure_types_code UNIQUE (code),
    CONSTRAINT uq_procedure_types_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- diagnosis_types
-- Entity name: DiagnosisType (NOT "Diagnosis") — legacy DiagnosisType.java:37-55.
-- NO price/uom (unlike LabTestType/RadiologyType/ProcedureType).
-- NO ICD code, NO ICD version, NO parent/child hierarchy (CR-06).
-- -------------------------------------------------------------------------------------
CREATE TABLE diagnosis_types (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_diagnosis_types      PRIMARY KEY (id),
    CONSTRAINT uq_diagnosis_types_uid  UNIQUE (uid),
    CONSTRAINT uq_diagnosis_types_code UNIQUE (code),
    CONSTRAINT uq_diagnosis_types_name UNIQUE (name)
);
