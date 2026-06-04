-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- The encounter-closure cluster: deceased_notes + referral_plans.
--
-- deceased_notes (DeceasedNote.java:36-76): the death-recording note. Death drives
-- Patient.type='DECEASED' (a String, NOT a boolean — inc-03 confirmed). death_date/death_time
-- are client-supplied verbatim. status PENDING -> APPROVED -> ARCHIVED. Mutually-exclusive
-- encounter: consultation_id (real FK) | admission_uid (loose) — num_nonnulls=1. One note per
-- encounter (partial unique on each link). approved_by_user_uid is copied from the CREATOR in
-- legacy (a quirk, DeceasedNote.java:71-72); the modern build captures the true approver
-- (CR-INC05-03) but the COLUMN is unchanged — nullable loose uid.
--
-- referral_plans (ReferralPlan.java:35-80): clinical referral to an ExternalMedicalProvider,
-- SEPARATE from death. Seven narrative TEXT columns copied from the request body. status
-- PENDING -> APPROVED -> ARCHIVED. external_medical_provider_uid is a MANDATORY loose ref —
-- the referral.external_medical_providers table is NOT built yet, so loose uid is the only
-- correct choice (ReferralPlan.java:49-52). Same mutually-exclusive consultation|admission
-- encounter; same approved-by-copies-creator quirk.
--
-- Conventions: identical to V15/V19. Named constraints: pk_/fk_/uq_/ck_/idx_.
--
-- Legacy citations:
--   DeceasedNote.java:36-76 (:48 status, :50-53 admission OneToOne, :55-58 consultation OneToOne,
--   :60-63 patient OneToOne, :71-72 approvedBy-copies-creator quirk);
--   ReferralPlan.java:35-80 (:47 status, :49-52 provider loose, :54-62 encounter);
--   PatientResource.java:5826 (load_deceased_list hides ARCHIVED).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- deceased_notes — death-recording note (DeceasedNote.java:36-76).
-- -------------------------------------------------------------------------------------
CREATE TABLE deceased_notes (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    patient_summary             TEXT,                           -- REST enforces non-empty; no JPA constraint
    cause_of_death              TEXT,
    death_date                  DATE,                           -- legacy @Column date_
    death_time                  TIME,                           -- legacy @Column time_

    status                      VARCHAR(20)     NOT NULL        DEFAULT 'PENDING',

    -- Mutually-exclusive encounter (exactly one non-null)
    consultation_id             BIGINT,                         -- real FK (@OneToOne)
    admission_uid               VARCHAR(26),                    -- loose, no FK (@OneToOne)

    -- MANDATORY real FK to patient (@OneToOne optional=false)
    patient_id                  BIGINT          NOT NULL,

    -- Approval audit (loose uids, no FK). Legacy copies approved_by from creator (quirk);
    -- modern captures true approver (CR-INC05-03) — column shape unchanged.
    approved_by_user_uid        VARCHAR(26),
    approved_on_day_uid         VARCHAR(26),
    approved_at                 TIMESTAMPTZ,

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_deceased_notes                PRIMARY KEY (id),
    CONSTRAINT uq_deceased_notes_uid            UNIQUE (uid),
    CONSTRAINT fk_deceased_notes_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_deceased_notes_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_deceased_notes_status CHECK (
        status IN ('PENDING', 'APPROVED', 'ARCHIVED')
    ),
    CONSTRAINT ck_deceased_notes_one_encounter CHECK (
        num_nonnulls(consultation_id, admission_uid) = 1
    )
);

-- One note per encounter (partial unique on each link).
CREATE UNIQUE INDEX uq_deceased_notes_consultation
    ON deceased_notes (consultation_id) WHERE consultation_id IS NOT NULL;
CREATE UNIQUE INDEX uq_deceased_notes_admission
    ON deceased_notes (admission_uid) WHERE admission_uid IS NOT NULL;

CREATE INDEX idx_deceased_notes_consultation   ON deceased_notes (consultation_id);
CREATE INDEX idx_deceased_notes_admission_uid  ON deceased_notes (admission_uid);
CREATE INDEX idx_deceased_notes_patient        ON deceased_notes (patient_id);

-- load_deceased_list hides ARCHIVED (PatientResource.java:5826).
CREATE INDEX idx_deceased_notes_list
    ON deceased_notes (status) WHERE status IN ('PENDING', 'APPROVED');

-- -------------------------------------------------------------------------------------
-- referral_plans — external referral (ReferralPlan.java:35-80).
-- -------------------------------------------------------------------------------------
CREATE TABLE referral_plans (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Seven narrative columns (copied from request body)
    referring_diagnosis         TEXT,
    history                     TEXT,
    investigation               TEXT,
    management                  TEXT,
    operation_note              TEXT,
    icu_admission_note          TEXT,
    general_recommendation      TEXT,

    status                      VARCHAR(20)     NOT NULL        DEFAULT 'PENDING',

    -- MANDATORY loose ref to referral.external_medical_providers (table NOT built; no FK)
    external_medical_provider_uid VARCHAR(26)   NOT NULL,

    -- Mutually-exclusive encounter (exactly one non-null)
    consultation_id             BIGINT,                         -- real FK
    admission_uid               VARCHAR(26),                    -- loose, no FK

    patient_id                  BIGINT          NOT NULL,

    -- Approval audit (loose uids, no FK; approved-by-copies-creator quirk in legacy)
    approved_by_user_uid        VARCHAR(26),
    approved_on_day_uid         VARCHAR(26),
    approved_at                 TIMESTAMPTZ,

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_referral_plans                PRIMARY KEY (id),
    CONSTRAINT uq_referral_plans_uid            UNIQUE (uid),
    CONSTRAINT fk_referral_plans_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_referral_plans_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_referral_plans_status CHECK (
        status IN ('PENDING', 'APPROVED', 'ARCHIVED')
    ),
    CONSTRAINT ck_referral_plans_one_encounter CHECK (
        num_nonnulls(consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_referral_plans_consultation   ON referral_plans (consultation_id);
CREATE INDEX idx_referral_plans_admission_uid  ON referral_plans (admission_uid);
CREATE INDEX idx_referral_plans_patient        ON referral_plans (patient_id);
CREATE INDEX idx_referral_plans_provider       ON referral_plans (external_medical_provider_uid);

-- list partial index (hides ARCHIVED, mirrors deceased list pattern).
CREATE INDEX idx_referral_plans_list
    ON referral_plans (status) WHERE status IN ('PENDING', 'APPROVED');
