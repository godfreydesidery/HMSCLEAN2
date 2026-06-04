-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- prescriptions (+ prescription_batches, patient_prescription_charts):
-- the clinical medication order (Prescription.java:38-144).
--
-- A SEPARATE entity. STATUS has EXACTLY TWO values ever: NOT-GIVEN -> GIVEN
-- (Prescription.java:50; PatientServiceImpl.java:1532; PatientResource.java:3230). The
-- planning-doc PENDING->...->SOLD 6+3-state lifecycle + payStatus belongs to
-- PharmacySaleOrderDetail (pharmacy context, OUT OF SCOPE) — NOT here (drift correction).
--
-- FLOAT -> NUMERIC (money/qty guardrail, pre-approved): qty/issued/balance are legacy double;
-- migrated to NUMERIC(19,6). balance starts = qty, balance -= issued on dispense.
-- ck_prescriptions_qty_nonneg guards qty/issued/balance >= 0.
--
-- dosage/frequency/route/days are FREE-TEXT VARCHAR (NOT FK to any Dosage/Frequency/Route
-- master). 'days' is numeric-as-string, parsed in the unfinished-course alert
-- (PatientResource.java:4556). Price flows via billing through patient_bill_uid — NO money col.
--
-- Lifecycle audit: ordered_* is written; accepted_*/held_*/rejected_*/verified_* are DECLARED
-- but NEVER written (boilerplate — columns kept for fidelity); approved_*+approved_at is THE
-- dispense audit (the only group ever populated, Prescription.java:139-143).
--
-- Conventions: identical to V15/V19. Named constraints: pk_/fk_/uq_/ck_/idx_.
--
-- Legacy citations:
--   Prescription.java:38-144 (:50 two-status, :72-75 medicine mandatory, :97-100 issuePharmacy,
--   :139-143 approved dispense audit); PrescriptionBatch.java:34-48 (:38-39 free-text no,
--   :44-47 prescription FK); PatientPrescriptionChart.java:34-82 (:57-60 prescription FK,
--   :72-75 nurse loose); PatientResource.java:4496,4556 (alert queries).
-- =====================================================================================

CREATE TABLE prescriptions (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Free-text directives (NOT FK)
    dosage                      VARCHAR(200),
    frequency                   VARCHAR(200),
    route                       VARCHAR(200),
    days                        VARCHAR(40),                    -- numeric-as-string (parsed in alert)

    -- Quantities: legacy double -> NUMERIC(19,6) (no float, money/qty guardrail)
    qty                         NUMERIC(19,6)   NOT NULL,
    issued                      NUMERIC(19,6)   NOT NULL        DEFAULT 0,
    balance                     NUMERIC(19,6)   NOT NULL,

    -- lifecycle: EXACTLY two values
    status                      VARCHAR(20)     NOT NULL        DEFAULT 'NOT-GIVEN',

    reference                   TEXT,
    instructions                TEXT,
    payment_type                VARCHAR(20),
    membership_no               VARCHAR(100),

    -- MANDATORY loose ref to masterdata medicines (no FK)
    medicine_uid                VARCHAR(26)     NOT NULL,

    -- Encounter binding (exactly one non-null)
    consultation_id             BIGINT,                         -- real FK
    non_consultation_id         BIGINT,                         -- real FK
    admission_uid               VARCHAR(26),                    -- loose, no FK

    patient_id                  BIGINT          NOT NULL,

    -- Loose cross-module refs (no FK)
    patient_bill_uid            VARCHAR(26)     NOT NULL,        -- @OneToOne one bill
    clinician_user_uid          VARCHAR(26),
    insurance_plan_uid          VARCHAR(26),
    issue_pharmacy_uid          VARCHAR(26),                    -- set on dispense

    -- Lifecycle audit triplets (loose uids, no FK).
    -- ordered_* written; accepted/held/rejected/verified declared-but-unwritten (kept for fidelity).
    ordered_by_user_uid         VARCHAR(26),
    ordered_on_day_uid          VARCHAR(26),
    ordered_at                  TIMESTAMPTZ,
    accepted_by_user_uid        VARCHAR(26),
    accepted_on_day_uid         VARCHAR(26),
    accepted_at                 TIMESTAMPTZ,
    held_by_user_uid            VARCHAR(26),
    held_on_day_uid             VARCHAR(26),
    held_at                     TIMESTAMPTZ,
    rejected_by_user_uid        VARCHAR(26),
    rejected_on_day_uid         VARCHAR(26),
    rejected_at                 TIMESTAMPTZ,
    reject_comment              TEXT,
    verified_by_user_uid        VARCHAR(26),
    verified_on_day_uid         VARCHAR(26),
    verified_at                 TIMESTAMPTZ,
    -- THE dispense audit (only group populated)
    approved_by_user_uid        VARCHAR(26),
    approved_on_day_uid         VARCHAR(26),
    approved_at                 TIMESTAMPTZ,
    created_by_user_uid         VARCHAR(26),
    created_on_day_uid          VARCHAR(26),

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_prescriptions                 PRIMARY KEY (id),
    CONSTRAINT uq_prescriptions_uid             UNIQUE (uid),
    CONSTRAINT fk_prescriptions_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_prescriptions_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT fk_prescriptions_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_prescriptions_status CHECK (
        status IN ('NOT-GIVEN', 'GIVEN')
    ),
    CONSTRAINT ck_prescriptions_qty_nonneg CHECK (
        qty >= 0 AND issued >= 0 AND balance >= 0
    ),
    CONSTRAINT ck_prescriptions_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_prescriptions_consultation      ON prescriptions (consultation_id);
CREATE INDEX idx_prescriptions_non_consultation  ON prescriptions (non_consultation_id);
CREATE INDEX idx_prescriptions_admission_uid     ON prescriptions (admission_uid);
CREATE INDEX idx_prescriptions_patient           ON prescriptions (patient_id);
CREATE INDEX idx_prescriptions_status            ON prescriptions (status);
CREATE INDEX idx_prescriptions_medicine_uid      ON prescriptions (medicine_uid);
CREATE INDEX idx_prescriptions_patient_bill_uid  ON prescriptions (patient_bill_uid);

-- Drives BOTH advisory alert queries: same-medicine-30-day (findAllByPatientAndMedicineAndStatus
-- '...GIVEN' + approvedAt, PatientResource.java:4496) and unfinished-course (same fetch + days
-- parse, :4556).
CREATE INDEX idx_prescriptions_patient_medicine_status
    ON prescriptions (patient_id, medicine_uid, status);

-- Pharmacy dispense queue.
CREATE INDEX idx_prescriptions_pharmacy_worklist
    ON prescriptions (status) WHERE status = 'NOT-GIVEN';

-- -------------------------------------------------------------------------------------
-- prescription_batches — batch linkage (PrescriptionBatch.java:34-48).
-- Near-inert: no consuming logic. 'no' = free-text batch number (no generator).
-- qty: legacy double -> NUMERIC(19,6).
-- -------------------------------------------------------------------------------------
CREATE TABLE prescription_batches (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    no                          VARCHAR(100)    NOT NULL,       -- @NotBlank free-text batch no
    manufactured_date           DATE,
    expiry_date                 DATE,
    qty                         NUMERIC(19,6)   NOT NULL        DEFAULT 0,

    -- Intra-module real FK to prescriptions (@ManyToOne)
    prescription_id             BIGINT          NOT NULL,

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_prescription_batches          PRIMARY KEY (id),
    CONSTRAINT uq_prescription_batches_uid      UNIQUE (uid),
    CONSTRAINT fk_prescription_batches_prescription
        FOREIGN KEY (prescription_id) REFERENCES prescriptions (id)
);

CREATE INDEX idx_prescription_batches_prescription ON prescription_batches (prescription_id);

-- -------------------------------------------------------------------------------------
-- patient_prescription_charts — inpatient drug-administration record
-- (PatientPrescriptionChart.java:34-82). NO status field. Free-text dosage/output/remark.
-- Encounter binding: exactly one of consultation_id | non_consultation_id | admission_uid.
-- App rules (NOT DB): linked prescription must be GIVEN (PatientServiceImpl.java:2544);
-- admission-only IN-PROCESS + nurse required (:2564-2577).
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_prescription_charts (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    dosage                      VARCHAR(200),
    output                      VARCHAR(200),
    remark                      TEXT,

    -- MANDATORY real FK to prescriptions (@ManyToOne)
    prescription_id             BIGINT          NOT NULL,

    -- Encounter binding (exactly one non-null)
    consultation_id             BIGINT,                         -- real FK
    non_consultation_id         BIGINT,                         -- real FK
    admission_uid               VARCHAR(26),                    -- loose, no FK

    patient_id                  BIGINT          NOT NULL,

    -- Loose cross-module refs (no FK)
    clinician_user_uid          VARCHAR(26),
    nurse_uid                   VARCHAR(26),                    -- iam.nurses (no FK)

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_prescription_charts   PRIMARY KEY (id),
    CONSTRAINT uq_patient_prescription_charts_uid UNIQUE (uid),
    CONSTRAINT fk_patient_prescription_charts_prescription
        FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
    CONSTRAINT fk_patient_prescription_charts_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_patient_prescription_charts_non_consultation
        FOREIGN KEY (non_consultation_id) REFERENCES non_consultations (id),
    CONSTRAINT fk_patient_prescription_charts_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_patient_prescription_charts_one_encounter CHECK (
        num_nonnulls(consultation_id, non_consultation_id, admission_uid) = 1
    )
);

CREATE INDEX idx_patient_prescription_charts_prescription
    ON patient_prescription_charts (prescription_id);
CREATE INDEX idx_patient_prescription_charts_admission_uid
    ON patient_prescription_charts (admission_uid);
CREATE INDEX idx_patient_prescription_charts_patient
    ON patient_prescription_charts (patient_id);
