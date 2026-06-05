-- =====================================================================================
-- Increment 07c-i — Consumable charts + consumables masterdata (SCHEMA)
--
-- New tables (two additions):
--   consumables                    — masterdata registry of medicines listed as consumables
--   patient_consumable_charts      — clinical-owned inpatient consumable issue record
--
-- Design rules:
--   * ULID uid VARCHAR(26) + BIGINT GENERATED ALWAYS AS IDENTITY (ADR-0003, ADR-0005)
--   * All cross-module refs are loose *_uid VARCHAR(26) (NO physical FK — ADR-0008 §1)
--   * status VARCHAR(20) fixed "NOT-GIVEN" (legacy quirk reproduced — PatientServiceImpl.java:2305)
--   * audit columns: created_at/updated_at/created_by/updated_by/version (AuditableEntity)
--
-- Legacy citations:
--   Consumable.java (domain/Consumable.java); PatientServiceImpl.java:2259-2262 (guard);
--   PatientServiceImpl.java:2250-2475 (savePatientConsumableChart);
--   PatientConsumableChart.java (domain entity).
--
-- inc-07 07c-i / CR-07-consumable-stock / CR-07-Q11 / CR-07-Q13-billing-display
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- consumables — masterdata registry of medicines listed as consumables
-- (Consumable.java; PatientServiceImpl.java:2259-2262
--  "Medicine is not listed as consumable")
-- Mirrors the V48 dressings table structure.
-- -------------------------------------------------------------------------------------
CREATE TABLE consumables (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Loose ref to the medicine (no physical FK — ADR-0008 §1)
    medicine_uid                VARCHAR(26)     NOT NULL,

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_consumables               PRIMARY KEY (id),
    CONSTRAINT uq_consumables_uid           UNIQUE (uid),
    CONSTRAINT uq_consumables_medicine_uid  UNIQUE (medicine_uid)
);

CREATE INDEX idx_consumables_medicine_uid ON consumables (medicine_uid);

-- -------------------------------------------------------------------------------------
-- patient_consumable_charts — inpatient consumable issue record
-- (PatientConsumableChart.java; PatientServiceImpl.java:2250-2475)
--
-- Clinical-owned, loose admission/patient/nurse/medicine/bill refs.
-- status is always "NOT-GIVEN" (legacy quirk, PatientServiceImpl.java:2305 — reproduced).
-- qty NUMERIC(19,2): consumable qty ordered/issued.
-- payment_type / membership_no: passed through for billing context.
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_consumable_charts (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Quantity (NUMERIC(19,2) — mirrors legacy double qty, BigDecimal modernisation)
    qty                         NUMERIC(19,2)   NOT NULL,

    -- Status: always "NOT-GIVEN" (legacy hard-coded, PatientServiceImpl.java:2305)
    status                      VARCHAR(20)     NOT NULL    DEFAULT 'NOT-GIVEN',

    -- Payment context (passed through for billing; null for cash patients)
    payment_type                VARCHAR(50),
    membership_no               VARCHAR(100),

    -- Mandatory loose refs (ADR-0008 §1 — no physical FK to other tables)
    patient_bill_uid            VARCHAR(26)     NOT NULL,
    medicine_uid                VARCHAR(26)     NOT NULL,
    patient_uid                 VARCHAR(26)     NOT NULL,

    -- Nullable loose refs
    admission_uid               VARCHAR(26),
    nurse_uid                   VARCHAR(26),
    insurance_plan_uid          VARCHAR(26),
    pharmacy_uid                VARCHAR(26),

    -- Audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_patient_consumable_charts     PRIMARY KEY (id),
    CONSTRAINT uq_patient_consumable_charts     UNIQUE (uid),
    CONSTRAINT uq_pcc_patient_bill_uid          UNIQUE (patient_bill_uid)
);

CREATE INDEX idx_pcc_admission_uid      ON patient_consumable_charts (admission_uid);
CREATE INDEX idx_pcc_patient_uid        ON patient_consumable_charts (patient_uid);
CREATE INDEX idx_pcc_patient_bill_uid   ON patient_consumable_charts (patient_bill_uid);
CREATE INDEX idx_pcc_medicine_uid       ON patient_consumable_charts (medicine_uid);

-- -------------------------------------------------------------------------------------
-- Extend stock_movement.movement_type CHECK constraint to include the two new values
-- added for the inpatient consumable-stock seam (CR-07-consumable-stock, inc-07 07c-i):
--   CONSUMPTION          — stock issued on inpatient consumable chart
--   CONSUMPTION_REVERSAL — stock restored when a consumable chart is deleted within 24h
--
-- The V39 constraint ck_stock_movement_type only allowed the original 6 values
-- (RECEIPT/DISPENSE/TRANSFER_OUT/TRANSFER_IN/ADJUSTMENT/OPENING).
-- PostgreSQL requires DROP + ADD CONSTRAINT to replace a named check constraint.
-- -------------------------------------------------------------------------------------
ALTER TABLE stock_movement
    DROP CONSTRAINT IF EXISTS ck_stock_movement_type;

ALTER TABLE stock_movement
    ADD CONSTRAINT ck_stock_movement_type CHECK (
        movement_type IN (
            'RECEIPT', 'DISPENSE', 'TRANSFER_OUT', 'TRANSFER_IN',
            'ADJUSTMENT', 'OPENING',
            'CONSUMPTION', 'CONSUMPTION_REVERSAL'
        )
    );
