-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- lab_tests (+ lab_test_attachments): the laboratory order (LabTest.java:43-154).
--
-- A SEPARATE entity, NOT a polymorphic ClinicalOrder (drift correction). Results are
-- COLUMNS on the row (result/report/description/range/level/unit); there is no OrderResult
-- entity. Price flows via billing through patient_bill_uid — NO money column on this row.
--
-- NOTE: 'rrange' column name (LabTest.java:51-52). Legacy uses @Column("rrange") because
-- 'range' is a reserved word; preserved verbatim so ddl-auto=validate matches the entity.
--
-- STATUS: PENDING / ACCEPTED / REJECTED / COLLECTED / VERIFIED (free-text in legacy; CHECK
-- pins the observed set). Lifecycle audit triplets (legacy camelCase setters) normalise to
-- *_by_user_uid VARCHAR(26) / *_on_day_uid VARCHAR(26) / *_at TIMESTAMPTZ per phase:
-- ordered / accepted / held / collected / verified / rejected (+ reject_comment) / created.
--
-- ENCOUNTER binding: exactly one of consultation_id | non_consultation_id | admission_uid
-- (ck_lab_tests_one_encounter, num_nonnulls=1). consultation_id / non_consultation_id real
-- FKs; admission_uid loose (no FK).
--
-- ATTACHMENTS: lab_test_attachments FK ON DELETE CASCADE (orphanRemoval=true,
-- LabTest.java:149-153). App rules (NOT DB): max 5 per test, attach only when
-- status=COLLECTED (PatientServiceImpl.java:2828,2832).
--
-- Conventions: identical to V15/V19. Named constraints: pk_/fk_/uq_/ck_/idx_.
--
-- Legacy citations:
--   LabTest.java:43-154 (:51-52 rrange, :55 status, :65-78 encounter, :80-83 labTestType,
--   :85-88 patientBill OneToOne, :149-153 attachments orphanRemoval);
--   LabTestAttachment.java:35-57; PatientResource.java:3668-3717 (lab worklist).
-- =====================================================================================

CREATE TABLE lab_tests (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Result columns
    result                      TEXT,
    report                      TEXT,                           -- legacy length 10000
    description                 TEXT,
    rrange                      VARCHAR(200),                   -- legacy @Column rrange (reserved 'range')
    level                       VARCHAR(200),
    unit                        VARCHAR(60),

    -- lifecycle: free-text in legacy; CHECK pins the observed set
    status                      VARCHAR(20)     NOT NULL,

    payment_type                VARCHAR(20),
    membership_no               VARCHAR(100),

    -- MANDATORY loose ref to masterdata lab_test_types (no FK)
    lab_test_type_uid           VARCHAR(26)     NOT NULL,

    -- Encounter binding (exactly one non-null)
    consultation_id             BIGINT,                         -- real FK
    non_consultation_id         BIGINT,                         -- real FK
    admission_uid               VARCHAR(26),                    -- loose, no FK

    -- MANDATORY real FK to patient
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

    CONSTRAINT pk_lab_tests                     PRIMARY KEY (id),
    CONSTRAINT uq_lab_tests_uid                 UNIQUE (uid),
    CONSTRAINT fk_lab_tests_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_lab_tests_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT fk_lab_tests_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_lab_tests_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'COLLECTED', 'VERIFIED')
    ),
    CONSTRAINT ck_lab_tests_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_lab_tests_consultation      ON lab_tests (consultation_id);
CREATE INDEX idx_lab_tests_non_consultation  ON lab_tests (non_consultation_id);
CREATE INDEX idx_lab_tests_admission_uid     ON lab_tests (admission_uid);
CREATE INDEX idx_lab_tests_patient           ON lab_tests (patient_id);
CREATE INDEX idx_lab_tests_status            ON lab_tests (status);
CREATE INDEX idx_lab_tests_patient_bill_uid  ON lab_tests (patient_bill_uid);

-- Lab worklist: orders pending action (PatientResource.java:3668-3717).
CREATE INDEX idx_lab_tests_worklist
    ON lab_tests (status) WHERE status IN ('PENDING', 'ACCEPTED', 'COLLECTED');

-- -------------------------------------------------------------------------------------
-- lab_test_attachments — result file attachments (LabTestAttachment.java:35-57).
-- file_name globally UNIQUE (@NotBlank unique). FK ON DELETE CASCADE (orphanRemoval=true).
-- -------------------------------------------------------------------------------------
CREATE TABLE lab_test_attachments (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    name                        VARCHAR(200),
    file_name                   VARCHAR(400)    NOT NULL,

    -- Intra-module real FK to lab_tests (@ManyToOne updatable=false)
    lab_test_id                 BIGINT          NOT NULL,

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_lab_test_attachments          PRIMARY KEY (id),
    CONSTRAINT uq_lab_test_attachments_uid      UNIQUE (uid),
    CONSTRAINT uq_lab_test_attachments_file_name UNIQUE (file_name),
    CONSTRAINT fk_lab_test_attachments_lab_test
        FOREIGN KEY (lab_test_id) REFERENCES lab_tests (id) ON DELETE CASCADE
);

CREATE INDEX idx_lab_test_attachments_lab_test ON lab_test_attachments (lab_test_id);
