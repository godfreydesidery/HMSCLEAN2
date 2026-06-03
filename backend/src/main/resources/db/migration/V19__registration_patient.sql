-- =====================================================================================
-- Increment 03 — Registration & Patient bounded context
-- Build-spec: docs/delivery/increments/03-registration-discovery/00-build-spec.md §1.2
--
-- Conventions: identical to V1/V6/V15.
--   id BIGINT GENERATED ALWAYS AS IDENTITY (hidden surrogate, never exposed)
--   uid VARCHAR(26) NOT NULL UNIQUE (ULID public identifier)
--   TIMESTAMPTZ audit columns (created_at/updated_at/created_by/updated_by/version)
--   Cross-module refs = loose VARCHAR(26) uid, NO FK (ADR-0008)
--   Named constraints: pk_/fk_/uq_/ck_/idx_
--
-- seq_mrno ALREADY EXISTS (V13__masterdata_document_sequences.sql:57-58) — NOT recreated here.
--
-- Legacy citations:
--   Patient.java:36-107, Visit.java:33-61, Registration.java:34-62, Consultation.java:47-110
--   PatientServiceImpl.java:226-422, PatientServiceImpl.java:739-744
-- =====================================================================================

-- pg_trgm required for GIN trigram index on search_key (build-spec §1.2)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- -------------------------------------------------------------------------------------
-- patients — master demographic record (legacy domain/Patient.java:36-107)
--
-- gender ships as VARCHAR(20) NOT NULL with NO CHECK constraint (build-spec §1.2, CR-17):
--   legacy is free-text @NotBlank String (Patient.java:61-62); DB does not constrain it.
--
-- Next-of-kin = 3 flat nullable columns (Patient.java:83-85, CR-14):
--   no child entity; exactly ONE kin record per patient.
--
-- no/MRN is nullable-until-assigned: assigned after seq_mrno.nextval in service layer
--   (PatientServiceImpl.java:250; build-spec §2.1 CR-02).
--
-- insurance_plan_uid: loose cross-module ref to masterdata insurance_plans (no FK, ADR-0008).
--   NULL for CASH patients; non-NULL for INSURANCE.
--
-- business_day_uid: loose cross-module ref to masterdata business_days (no FK).
-- -------------------------------------------------------------------------------------
CREATE TABLE patients (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,

    -- MRN: format MRNO/{EAT-year}/{nextval seq_mrno} (build-spec §2.1)
    -- Nullable until assigned in the same transaction as patient creation.
    no                  VARCHAR(40),

    -- searchKey: no + firstName + middleName + lastName + phoneNo, sanitized
    -- (PatientServiceImpl.java:739-744, Sanitizer.java:11-17, build-spec §2.2 CR-09)
    -- NOT NULL: must be assigned before/at first INSERT alongside no.
    search_key          TEXT            NOT NULL,

    first_name          TEXT            NOT NULL,           -- @NotBlank (Patient.java:54-55)
    middle_name         TEXT,                               -- nullable  (Patient.java:56)
    last_name           TEXT            NOT NULL,           -- @NotBlank (Patient.java:57-58)
    date_of_birth       DATE            NOT NULL,           -- @NotNull  (Patient.java:59-60)

    -- Free-text @NotBlank String, no DB CHECK (CR-17, Patient.java:61-62)
    gender              VARCHAR(20)     NOT NULL,

    -- patientType: OUTPATIENT|OUTSIDER|INPATIENT|DECEASED (Patient.java:63-64, CR-11)
    type                VARCHAR(20)     NOT NULL            DEFAULT 'OUTPATIENT',

    -- paymentType: CASH|INSURANCE only (Patient.java:68-69, CR-10)
    payment_type        VARCHAR(20)     NOT NULL            DEFAULT 'CASH',

    -- insurance membership number; default '' mirrors legacy (Patient.java:70)
    membership_no       VARCHAR(100)                        DEFAULT '',

    -- contact details (Patient.java:74-79)
    phone_no            VARCHAR(40),
    address             VARCHAR(400),
    email               VARCHAR(120),
    nationality         VARCHAR(80),
    national_id         VARCHAR(60),
    passport_no         VARCHAR(60),

    -- next-of-kin: single flat record, 3 columns (Patient.java:83-85, CR-14)
    kin_full_name       VARCHAR(200),
    kin_relationship    VARCHAR(80),
    kin_phone_no        VARCHAR(40),

    active              BOOLEAN         NOT NULL            DEFAULT TRUE,  -- Patient.java:89

    -- Cross-module loose ref to masterdata insurance plan (no FK, ADR-0008)
    -- NULL for CASH; non-NULL for INSURANCE (ck_patients_insurance_consistency enforces).
    insurance_plan_uid  VARCHAR(26),

    -- Cross-module loose ref to the open business day at registration time (no FK)
    business_day_uid    VARCHAR(26)     NOT NULL,

    -- Audit columns (platform standard — AuditableEntity.java:61-79)
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,

    CONSTRAINT pk_patients                  PRIMARY KEY (id),
    CONSTRAINT uq_patients_uid              UNIQUE (uid),
    -- Nullable until assigned; UNIQUE once set (Patient.java:46-47, build-spec §1.2)
    CONSTRAINT uq_patients_no               UNIQUE (no),
    -- Derived PHI field; UNIQUE (Patient.java:48-50, build-spec §2.2)
    CONSTRAINT uq_patients_search_key       UNIQUE (search_key),

    -- patientType vocabulary (build-spec §1.2, CR-11)
    CONSTRAINT ck_patients_type CHECK (
        type IN ('OUTPATIENT', 'OUTSIDER', 'INPATIENT', 'DECEASED')
    ),
    -- paymentType vocabulary (build-spec §1.2, CR-10)
    CONSTRAINT ck_patients_payment_type CHECK (
        payment_type IN ('CASH', 'INSURANCE')
    ),
    -- Business rule: INSURANCE => plan+membership required; CASH => plan null
    -- (PatientResource.java:296-305, :359-373; build-spec §1.2)
    CONSTRAINT ck_patients_insurance_consistency CHECK (
        (payment_type = 'INSURANCE'
            AND insurance_plan_uid IS NOT NULL
            AND membership_no IS NOT NULL
            AND membership_no <> '')
        OR (payment_type = 'CASH' AND insurance_plan_uid IS NULL)
    )
);

-- GIN trigram index for fuzzy/substring search on composite search_key
-- (build-spec §1.2, §6; PatientServiceImpl.java:739-744)
-- Requires pg_trgm (CREATE EXTENSION above)
CREATE INDEX idx_patients_search_key_trgm
    ON patients USING gin (search_key gin_trgm_ops);

-- Per-field btree indexes for exact lookups and the OR-LIKE search legs (build-spec §1.2)
CREATE INDEX idx_patients_no              ON patients (no);
CREATE INDEX idx_patients_last_name       ON patients (last_name);
CREATE INDEX idx_patients_phone_no        ON patients (phone_no);
CREATE INDEX idx_patients_membership_no   ON patients (membership_no);
CREATE INDEX idx_patients_insurance_plan_uid ON patients (insurance_plan_uid);

-- -------------------------------------------------------------------------------------
-- registrations — thin Patient<->registration-fee-bill join (Registration.java:34-62)
--
-- OneToOne to patient: one registration per patient (Registration.java:46-49).
-- patient_bill_uid: loose cross-module ref to billing.patient_bills (no FK, ADR-0008).
--   NOT NULL: legacy PatientBill field is non-null (Registration.java:51-54).
-- status = 'ACTIVE' only in inc-03 (build-spec §1.2, CR-18).
--   Later increments that need additional statuses must widen via additive migration.
-- business_day_uid: loose cross-module ref to masterdata business_days (no FK).
-- -------------------------------------------------------------------------------------
CREATE TABLE registrations (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,

    -- Intra-module real FK to patients (OneToOne enforced by UNIQUE constraint below)
    patient_id          BIGINT          NOT NULL,

    -- Loose cross-module ref to the registration-fee PatientBill in billing module
    -- (Registration.java:51-54; ADR-0008: no FK across modules)
    patient_bill_uid    VARCHAR(26)     NOT NULL,

    -- Only 'ACTIVE' in inc-03 (build-spec §1.2, CR-18)
    status              VARCHAR(20)     NOT NULL            DEFAULT 'ACTIVE',

    -- Cross-module loose ref to the open business day (no FK)
    business_day_uid    VARCHAR(26)     NOT NULL,

    -- Audit columns
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,

    CONSTRAINT pk_registrations             PRIMARY KEY (id),
    CONSTRAINT uq_registrations_uid         UNIQUE (uid),
    -- OneToOne: exactly one registration per patient (Registration.java:46-49)
    CONSTRAINT uq_registrations_patient     UNIQUE (patient_id),
    CONSTRAINT fk_registrations_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    -- Only inc-03 value; widen via additive migration in later increments (CR-18)
    CONSTRAINT ck_registrations_status CHECK (status IN ('ACTIVE'))
);

CREATE INDEX idx_registrations_patient_bill_uid ON registrations (patient_bill_uid);

-- -------------------------------------------------------------------------------------
-- visits — per-encounter log (Visit.java:33-61)
--
-- ManyToOne to patient: many visits per patient (Visit.java:49-52).
-- sequence: FIRST on initial registration; SUBSEQUENT on send-to-doctor;
--   SUBSEQUENT_FOR_ADMISSION on admission booking (build-spec §1.2).
--   NOTE on hyphen: the build-spec vocabulary set is {FIRST, SUBSEQUENT, SUBSEQUENT-FOR-ADMISSION}.
--   The stored token is SUBSEQUENT_FOR_ADMISSION (underscore) in BOTH the DB CHECK and the
--   Java enum. This deviates from the raw hyphenated form in the legacy data-architect sketch
--   because Java enum constant names cannot contain hyphens. The 3-value set is unchanged;
--   only the token spelling is normalised to underscore. (build-spec §7, C1 enum note.)
-- type: copied from patient.type at the time of visit creation (Visit.java:44-45).
-- status: only 'PENDING' in inc-03 (build-spec §1.2, CR-18).
-- business_day_uid: loose cross-module ref (no FK).
-- -------------------------------------------------------------------------------------
CREATE TABLE visits (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,

    -- Intra-module real FK to patients (ManyToOne)
    patient_id          BIGINT          NOT NULL,

    -- Visit sequence (Visit.java:42-43; underscore form used — see NOTE above)
    sequence            VARCHAR(30)     NOT NULL,

    -- Patient type at time of visit creation (Visit.java:44-45)
    type                VARCHAR(20)     NOT NULL,

    -- Only 'PENDING' in inc-03 (build-spec §1.2, CR-18)
    status              VARCHAR(20)     NOT NULL,

    -- Cross-module loose ref to the open business day (no FK)
    business_day_uid    VARCHAR(26)     NOT NULL,

    -- Audit columns
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,

    CONSTRAINT pk_visits                    PRIMARY KEY (id),
    CONSTRAINT uq_visits_uid                UNIQUE (uid),
    CONSTRAINT fk_visits_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    -- Underscore form used (see NOTE in table-level comment above)
    CONSTRAINT ck_visits_sequence CHECK (
        sequence IN ('FIRST', 'SUBSEQUENT', 'SUBSEQUENT_FOR_ADMISSION')
    ),
    -- Only inc-03 value; widen via additive migration for admission/discharge (CR-18)
    CONSTRAINT ck_visits_status CHECK (status IN ('PENDING'))
);

-- Supports "last visit" ORDER BY created_at DESC LIMIT 1 efficiently (build-spec §6, CR-08)
-- and per-patient encounter list with created_at filtering.
CREATE INDEX idx_visits_patient_created_at ON visits (patient_id, created_at DESC);

-- -------------------------------------------------------------------------------------
-- consultations — PENDING booking stub (Consultation.java:47-110, build-spec §3, CR-21)
--
-- This is the minimal inc-03 stub. Full clinical consultation (open/free/transfer,
-- clinical notes, diagnosis) is deferred to inc-05 (clinical module, ADR-0008-R1).
-- The PENDING Consultation aggregate lives here temporarily; inc-05 spec carries
-- the ownership-transfer plan (CR-21).
--
-- patient_id: intra-module real FK to patients (Consultation.java:62-65).
-- visit_id: intra-module real FK to visits (Consultation.java:93-97); nullable ok here
--   (the inc-03 send-to-doctor flow creates the visit and consultation atomically).
-- clinic_uid: loose cross-module ref to masterdata clinics (no FK, ADR-0008).
-- clinician_user_uid: loose cross-module ref to iam users/clinicians (no FK, ADR-0008).
-- patient_bill_uid: loose cross-module ref to billing.patient_bills (no FK, ADR-0008).
--   NOT NULL: legacy bill is non-null (Consultation.java:70-73).
-- payment_type: denormalised from patient at booking time (Consultation.java:53).
-- follow_up: boolean (Consultation.java:57); true => NONE bill status (PatientServiceImpl.java:467-469, CR-20).
-- status: only 'PENDING' in inc-03 (build-spec §1.2, CR-18).
-- business_day_uid: loose cross-module ref (no FK).
-- -------------------------------------------------------------------------------------
CREATE TABLE consultations (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,

    -- Intra-module real FK to patients (ManyToOne)
    patient_id          BIGINT          NOT NULL,

    -- Intra-module real FK to visits (ManyToOne); nullable — linked on creation
    visit_id            BIGINT,

    -- Cross-module loose refs (ADR-0008; no FK)
    clinic_uid          VARCHAR(26)     NOT NULL,
    clinician_user_uid  VARCHAR(26)     NOT NULL,

    -- Loose cross-module ref to the consultation-fee PatientBill (Consultation.java:70-73)
    patient_bill_uid    VARCHAR(26)     NOT NULL,

    -- Denormalised from patient.payment_type at booking time (Consultation.java:53)
    payment_type        VARCHAR(20)     NOT NULL,

    -- Follow-up flag: true => NONE bill (PatientServiceImpl.java:467-469, CR-20)
    follow_up           BOOLEAN         NOT NULL            DEFAULT FALSE,

    -- Only 'PENDING' in inc-03 (build-spec §1.2, CR-18)
    status              VARCHAR(20)     NOT NULL,

    -- Cross-module loose ref to the open business day (no FK)
    business_day_uid    VARCHAR(26)     NOT NULL,

    -- Audit columns
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,

    CONSTRAINT pk_consultations             PRIMARY KEY (id),
    CONSTRAINT uq_consultations_uid         UNIQUE (uid),
    CONSTRAINT fk_consultations_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_consultations_visit
        FOREIGN KEY (visit_id) REFERENCES visits (id),
    -- paymentType vocabulary (mirrors patients.payment_type)
    CONSTRAINT ck_consultations_payment_type CHECK (
        payment_type IN ('CASH', 'INSURANCE')
    ),
    -- Only inc-03 value; widen via additive migration in later increments (CR-18)
    CONSTRAINT ck_consultations_status CHECK (status IN ('PENDING'))
);

CREATE INDEX idx_consultations_patient_id      ON consultations (patient_id);
CREATE INDEX idx_consultations_visit_id        ON consultations (visit_id);
CREATE INDEX idx_consultations_patient_status  ON consultations (patient_id, status);
