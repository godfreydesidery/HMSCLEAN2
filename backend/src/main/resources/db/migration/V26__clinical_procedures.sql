-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- procedures: the theatre / clinical procedure order (Procedure.java:40-147).
--
-- A SEPARATE entity (drift correction). STATUS: PENDING / ACCEPTED / REJECTED / VERIFIED —
-- NO APPROVED, NO COLLECTED. The planning-doc M14 "approve" step is fabricated; approve()
-- does not exist (CR drift correction). There are NO collected_* columns.
--
-- held_* columns are kept (vestigial): the entity DECLARES the hold audit triplet
-- (Procedure.java:128-132) but there is no hold endpoint — preserved for fidelity.
--
-- FLOAT -> NUMERIC (money/qty guardrail, pre-approved): legacy hours/minutes are double;
-- migrated to NUMERIC(19,6). NO float/double anywhere. proc_time TIME (legacy @Column time_),
-- proc_date DATE (legacy @Column date_).
--
-- Price flows via billing through patient_bill_uid — NO money column on this row.
--
-- ENCOUNTER binding: exactly one of consultation_id | non_consultation_id | admission_uid.
--
-- Conventions: identical to V15/V19. Named constraints: pk_/fk_/uq_/ck_/idx_.
--
-- Legacy citations:
--   Procedure.java:40-147 (:44-45 note 10000, :47-48 time_, :50-51 date_, hours/minutes double,
--   :54 status, :59-62 theatre loose, :85-88 procedureType, :128-132 vestigial hold).
-- =====================================================================================

CREATE TABLE procedures (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- the procedure result narrative (legacy length 10000)
    note                        TEXT,
    type                        VARCHAR(60),
    proc_time                   TIME,                           -- legacy @Column time_
    diagnosis                   TEXT,
    proc_date                   DATE,                           -- legacy @Column date_

    -- legacy double -> NUMERIC(19,6) (no float, money/qty guardrail)
    hours                       NUMERIC(19,6),
    minutes                     NUMERIC(19,6),

    -- lifecycle: NO APPROVED, NO COLLECTED
    status                      VARCHAR(20)     NOT NULL,

    payment_type                VARCHAR(20),
    membership_no               VARCHAR(100),

    -- Loose ref to masterdata theatres (nullable, no FK)
    theatre_uid                 VARCHAR(26),

    -- MANDATORY loose ref to masterdata procedure_types (no FK)
    procedure_type_uid          VARCHAR(26)     NOT NULL,

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

    -- Lifecycle audit triplets (loose uids, no FK). NO collected_* (no collected state).
    ordered_by_user_uid         VARCHAR(26),
    ordered_on_day_uid          VARCHAR(26),
    ordered_at                  TIMESTAMPTZ,
    accepted_by_user_uid        VARCHAR(26),
    accepted_on_day_uid         VARCHAR(26),
    accepted_at                 TIMESTAMPTZ,
    held_by_user_uid            VARCHAR(26),                    -- vestigial (no hold endpoint)
    held_on_day_uid             VARCHAR(26),
    held_at                     TIMESTAMPTZ,
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

    CONSTRAINT pk_procedures                    PRIMARY KEY (id),
    CONSTRAINT uq_procedures_uid                UNIQUE (uid),
    CONSTRAINT fk_procedures_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_procedures_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT fk_procedures_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_procedures_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'VERIFIED')
    ),
    CONSTRAINT ck_procedures_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_procedures_consultation      ON procedures (consultation_id);
CREATE INDEX idx_procedures_non_consultation  ON procedures (non_consultation_id);
CREATE INDEX idx_procedures_admission_uid     ON procedures (admission_uid);
CREATE INDEX idx_procedures_patient           ON procedures (patient_id);
CREATE INDEX idx_procedures_status            ON procedures (status);
CREATE INDEX idx_procedures_patient_bill_uid  ON procedures (patient_bill_uid);

-- Theatre-scheduled procedures (partial — only theatre-assigned rows).
CREATE INDEX idx_procedures_theatre_uid
    ON procedures (theatre_uid) WHERE theatre_uid IS NOT NULL;

-- Procedure worklist (active path PENDING/ACCEPTED).
CREATE INDEX idx_procedures_worklist
    ON procedures (status) WHERE status IN ('PENDING', 'ACCEPTED');
