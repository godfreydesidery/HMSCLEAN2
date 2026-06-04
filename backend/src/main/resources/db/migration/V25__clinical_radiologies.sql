-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- radiologies (+ radiology_attachments): the imaging order (Radiology.java:42-150).
--
-- A SEPARATE entity (drift correction). Results are COLUMNS on the row. The 'attachment'
-- BYTEA column holds the legacy Byte[] image blob set at verify time (Radiology.java:50).
-- Price flows via billing through patient_bill_uid — NO money column.
--
-- STATUS: PENDING / ACCEPTED / REJECTED / COLLECTED / VERIFIED. COLLECTED is a DEAD state
-- reachable only via the malformed dead endpoint collect_radiology111 (PatientResource.java:4317,
-- CR-INC05-14); the active path is ACCEPTED -> VERIFIED. COLLECTED kept in the CHECK for
-- data fidelity (no live transition into it).
--
-- Lifecycle audit triplets (legacy lowercase 'by' setters: orderedby/acceptedby/...) normalise
-- to *_by_user_uid / *_on_day_uid / *_at per phase, identical shape to lab_tests.
--
-- ENCOUNTER binding: exactly one of consultation_id | non_consultation_id | admission_uid.
-- ATTACHMENTS: FK ON DELETE CASCADE (orphanRemoval=true). App rules (NOT DB): max 5, attach
-- when status=ACCEPTED (note: different gate than lab COLLECTED — PatientServiceImpl.java:2931).
--
-- Conventions: identical to V15/V19. Named constraints: pk_/fk_/uq_/ck_/idx_.
--
-- Legacy citations:
--   Radiology.java:42-150 (:50 attachment Byte[], :76-79 radiologyType, :85-88 patientBill);
--   RadiologyAttachment.java:28-50; PatientResource.java:4317 (dead collect endpoint).
-- =====================================================================================

CREATE TABLE radiologies (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Result columns
    result                      TEXT,
    report                      TEXT,                           -- legacy length 10000
    description                 TEXT,
    attachment                  BYTEA,                          -- legacy Byte[] image blob (set at verify)

    status                      VARCHAR(20)     NOT NULL,

    payment_type                VARCHAR(20),
    membership_no               VARCHAR(100),

    -- MANDATORY loose ref to masterdata radiology_types (no FK)
    radiology_type_uid          VARCHAR(26)     NOT NULL,

    -- Encounter binding (exactly one non-null)
    consultation_id             BIGINT,                         -- real FK
    non_consultation_id         BIGINT,                         -- real FK
    admission_uid               VARCHAR(26),                    -- loose, no FK

    patient_id                  BIGINT          NOT NULL,

    -- Loose cross-module refs (no FK)
    patient_bill_uid            VARCHAR(26)     NOT NULL,        -- @OneToOne one bill
    diagnosis_type_uid          VARCHAR(26),
    clinician_user_uid          VARCHAR(26),
    insurance_plan_uid          VARCHAR(26),

    -- Lifecycle audit triplets (loose uids, no FK)
    ordered_by_user_uid         VARCHAR(26),
    ordered_on_day_uid          VARCHAR(26),
    ordered_at                  TIMESTAMPTZ,
    accepted_by_user_uid        VARCHAR(26),
    accepted_on_day_uid         VARCHAR(26),
    accepted_at                 TIMESTAMPTZ,
    held_by_user_uid            VARCHAR(26),
    held_on_day_uid             VARCHAR(26),
    held_at                     TIMESTAMPTZ,
    collected_by_user_uid       VARCHAR(26),
    collected_on_day_uid        VARCHAR(26),
    collected_at                TIMESTAMPTZ,
    verified_by_user_uid        VARCHAR(26),
    verified_on_day_uid         VARCHAR(26),
    verified_at                 TIMESTAMPTZ,
    rejected_by_user_uid        VARCHAR(26),
    rejected_on_day_uid         VARCHAR(26),
    rejected_at                 TIMESTAMPTZ,
    reject_comment              TEXT,
    created_by_user_uid         VARCHAR(26),
    created_on_day_uid          VARCHAR(26),

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_radiologies                   PRIMARY KEY (id),
    CONSTRAINT uq_radiologies_uid               UNIQUE (uid),
    CONSTRAINT fk_radiologies_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_radiologies_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT fk_radiologies_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_radiologies_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'COLLECTED', 'VERIFIED')
    ),
    CONSTRAINT ck_radiologies_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_radiologies_consultation      ON radiologies (consultation_id);
CREATE INDEX idx_radiologies_non_consultation  ON radiologies (non_consultation_id);
CREATE INDEX idx_radiologies_admission_uid     ON radiologies (admission_uid);
CREATE INDEX idx_radiologies_patient           ON radiologies (patient_id);
CREATE INDEX idx_radiologies_status            ON radiologies (status);
CREATE INDEX idx_radiologies_patient_bill_uid  ON radiologies (patient_bill_uid);

-- Radiology worklist (active path PENDING/ACCEPTED).
CREATE INDEX idx_radiologies_worklist
    ON radiologies (status) WHERE status IN ('PENDING', 'ACCEPTED');

-- -------------------------------------------------------------------------------------
-- radiology_attachments — result file attachments (RadiologyAttachment.java:28-50).
-- file_name globally UNIQUE. FK ON DELETE CASCADE (orphanRemoval=true).
-- -------------------------------------------------------------------------------------
CREATE TABLE radiology_attachments (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    name                        VARCHAR(200),
    file_name                   VARCHAR(400)    NOT NULL,

    -- Intra-module real FK to radiologies (@ManyToOne updatable=false)
    radiology_id                BIGINT          NOT NULL,

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_radiology_attachments         PRIMARY KEY (id),
    CONSTRAINT uq_radiology_attachments_uid     UNIQUE (uid),
    CONSTRAINT uq_radiology_attachments_file_name UNIQUE (file_name),
    CONSTRAINT fk_radiology_attachments_radiology
        FOREIGN KEY (radiology_id) REFERENCES radiologies (id) ON DELETE CASCADE
);

CREATE INDEX idx_radiology_attachments_radiology ON radiology_attachments (radiology_id);
