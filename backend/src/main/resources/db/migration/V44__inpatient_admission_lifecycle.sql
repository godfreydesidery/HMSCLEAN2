-- =====================================================================================
-- Increment 07a — Inpatient bounded context — Chunk 07a-1 (SCHEMA)
-- Admission lifecycle core: admissions + admission_beds.
--
-- Re-models the legacy flat Admission + AdmissionBed entities with:
--   * ULID uid CHAR(26) + BIGINT GENERATED ALWAYS AS IDENTITY (ADR-0003, ADR-0005)
--   * BigDecimal NUMERIC(19,2) for any monetary fields (pre-approved data-type change)
--   * Typed status via AdmissionStatusConverter; exact legacy CHECK vocabulary preserved
--   * @Version optimistic-locking column (inherited from AuditableEntity)
--   * Named constraints: pk_/fk_/uq_/ck_/idx_
--
-- Net-new deltas (labelled, NOT parity assertions):
--   * admission_beds.patient_bill_uid — stores the ward-bed PatientBill uid for
--     settlement-listener matching (inc-07 07a AdmissionSettlementListener). Legacy stored
--     the FK as a surrogate id; we store the loose uid (ADR-0008 §1).
--   * ck_admissions_status — DB CHECK backstop for the five-value vocabulary.
--
-- Cross-module refs are loose *_uid VARCHAR(26) columns (NO physical FK — patient, ward-bed,
-- insurance-plan, patient-bill live in different modules, ADR-0008 §1).
-- admission_beds.admission_uid → admissions is INTRA-module so it IS a real FK (no cascade).
--
-- Conventions: identical to V39/V40/V41/V42/V43.
--
-- Legacy citations:
--   domain/Admission.java:45 (free-text status); PatientServiceImpl.java:1701-2021
--   (doAdmission — Admission + AdmissionBed creation);
--   PatientBillResource.java:352-365 (payment-driven activation).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- admissions — the inpatient stay aggregate root (1:1 re-model of legacy Admission).
-- NO admission-number column: legacy Admission.java has no no/number field.
-- -------------------------------------------------------------------------------------
CREATE TABLE admissions (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Loose cross-module refs (NO physical FK — ADR-0008 §1)
    patient_uid                 VARCHAR(26)     NOT NULL,
    ward_bed_uid                VARCHAR(26)     NOT NULL,
    insurance_plan_uid          VARCHAR(26),                        -- nullable for CASH patients
    membership_no               VARCHAR(100)    NOT NULL DEFAULT '',

    -- Payment mode: CASH or INSURANCE (denormalised from patient at admission time)
    payment_type                VARCHAR(20)     NOT NULL,

    -- Lifecycle status — exact legacy free-text values (hyphenated IN-PROCESS/SIGNED-OUT)
    status                      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- Timestamps
    admitted_at                 TIMESTAMPTZ     NOT NULL,
    discharged_at               TIMESTAMPTZ,

    -- Audit columns (AuditableEntity)
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_admissions                PRIMARY KEY (id),
    CONSTRAINT uq_admissions_uid            UNIQUE (uid),

    -- Exact legacy status vocabulary (PatientServiceImpl.java:1719, :1959, etc.)
    CONSTRAINT ck_admissions_status CHECK (
        status IN ('PENDING', 'IN-PROCESS', 'STOPPED', 'HELD', 'SIGNED-OUT')
    ),
    CONSTRAINT ck_admissions_payment_type CHECK (
        payment_type IN ('CASH', 'INSURANCE')
    )
);

CREATE INDEX idx_admissions_patient_uid     ON admissions (patient_uid);
CREATE INDEX idx_admissions_ward_bed_uid    ON admissions (ward_bed_uid);
-- Supports the "already admitted" guard query (status IN ('PENDING','IN-PROCESS'))
CREATE INDEX idx_admissions_patient_status  ON admissions (patient_uid, status);

-- -------------------------------------------------------------------------------------
-- admission_beds — inpatient occupancy/billing ledger (1:1 re-model of legacy AdmissionBed).
-- Tracks which physical bed was used, the ward-bed bill uid (for settlement matching),
-- and the OPENED/CLOSED lifecycle flag.
-- -------------------------------------------------------------------------------------
CREATE TABLE admission_beds (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Intra-module real FK to admissions (no cascade — explicit lookup preferred)
    admission_uid               VARCHAR(26)     NOT NULL,

    -- Loose cross-module refs (NO physical FK — ADR-0008 §1)
    ward_bed_uid                VARCHAR(26)     NOT NULL,
    patient_uid                 VARCHAR(26)     NOT NULL,

    -- Loose ref to the ward-bed PatientBill created at doAdmission.
    -- KEY for AdmissionSettlementListener: BillSettledEvent carries only billUid;
    -- this column is the lookup target (PatientBillResource.java:352-365 parity).
    patient_bill_uid            VARCHAR(26)     NOT NULL,

    -- Occupancy status: OPENED (at admission) / CLOSED (at discharge — 07a-3)
    status                      VARCHAR(20)     NOT NULL DEFAULT 'OPENED',

    opened_at                   TIMESTAMPTZ     NOT NULL,
    closed_at                   TIMESTAMPTZ,

    -- Audit columns (AuditableEntity)
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_admission_beds                    PRIMARY KEY (id),
    CONSTRAINT uq_admission_beds_uid                UNIQUE (uid),
    CONSTRAINT uq_admission_beds_patient_bill_uid   UNIQUE (patient_bill_uid),

    CONSTRAINT fk_admission_beds_admission
        FOREIGN KEY (admission_uid) REFERENCES admissions (uid),

    CONSTRAINT ck_admission_beds_status CHECK (
        status IN ('OPENED', 'CLOSED')
    )
);

CREATE INDEX idx_admission_beds_admission_uid    ON admission_beds (admission_uid);
CREATE INDEX idx_admission_beds_patient_uid      ON admission_beds (patient_uid);
-- Supports settlement-listener lookup by bill uid (O(1) on the unique index above)
CREATE INDEX idx_admission_beds_bill_uid         ON admission_beds (patient_bill_uid);
