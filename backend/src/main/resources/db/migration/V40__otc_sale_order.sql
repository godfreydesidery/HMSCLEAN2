-- =====================================================================================
-- Increment 08a — Pharmacy bounded context — Chunk 4 (OTC Walk-in PharmacySaleOrder)
--
-- Tables: pharmacy_customers + pharmacy_sale_orders + pharmacy_sale_order_details
-- Sequences: seq_pso_no (PSO numbering) + seq_pcst_no (PharmacyCustomer numbering)
--
-- Numbering (CR-09-NUM1): PSO/{seq} and PCST/{seq} do NOT use the {PREFIX}{yyyyMMdd}-{seq}
-- format of DocumentNumberService. Instead dedicated sequences are allocated here and the
-- application formats 'PSO/' + nextval and 'PCST/' + nextval. This gives traceable provenance
-- (PSO/5 no longer implies PK 5) while matching the verbatim legacy format strings.
--
-- Entities re-model legacy PharmacyCustomer.java:40-64,
-- PharmacySaleOrder.java:36-100, PharmacySaleOrderDetail.java:29-126.
--
-- Modernisation (pre-approved):
--   * double  -> NUMERIC(19,6) for qty/issued/balance; NUMERIC(19,2) for money (ADR-0009 §3)
--   * raw PK  -> BIGINT IDENTITY + VARCHAR(26) uid (ADR-0003/0005)
--   * literal id-refs -> loose uid cross-refs (ADR-0008 §1) + intra-module FKs
--   * OtcOrderStatus enum: PENDING|APPROVED|ARCHIVED|CANCELED (AC-OTC-03)
--   * OtcPayStatus enum: UNPAID|PAID (per-detail) (AC-OTC-27)
--   * detail.status stored as VARCHAR 'NOT-GIVEN'/'GIVEN' (verbatim legacy strings)
--
-- Conventions: identical to V39. Named constraints: pk_/fk_/uq_/ck_/idx_.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Numbering sequences (CR-09-NUM1)
-- -------------------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS seq_pso_no  START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_pcst_no START WITH 1 INCREMENT BY 1;

-- -------------------------------------------------------------------------------------
-- pharmacy_customers — stand-alone walk-in customer; NOT a clinical Patient.
-- Legacy PharmacyCustomer.java:40-64.
-- -------------------------------------------------------------------------------------
CREATE TABLE pharmacy_customers (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    no                          VARCHAR(40)     NOT NULL,           -- PCST/{seq}, unique
    name                        VARCHAR(200)    NOT NULL,
    gender                      VARCHAR(20),
    phone_no                    VARCHAR(50),
    address                     VARCHAR(500),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_pharmacy_customers          PRIMARY KEY (id),
    CONSTRAINT uq_pharmacy_customers_uid      UNIQUE (uid),
    CONSTRAINT uq_pharmacy_customers_no       UNIQUE (no)
);

CREATE INDEX idx_pharmacy_customers_no ON pharmacy_customers (no);

-- -------------------------------------------------------------------------------------
-- pharmacy_sale_orders — OTC order header.
-- Legacy PharmacySaleOrder.java:36-100.
-- Status string values: PENDING | APPROVED | ARCHIVED | CANCELED  (OtcOrderStatus enum).
-- -------------------------------------------------------------------------------------
CREATE TABLE pharmacy_sale_orders (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    no                          VARCHAR(40)     NOT NULL,           -- PSO/{seq}, unique
    payment_type                VARCHAR(40)     NOT NULL,           -- hardcoded 'CASH' (Q9)
    status                      VARCHAR(20)     NOT NULL,
    comments                    VARCHAR(1000),

    -- Loose cross-module refs (no physical FK — ADR-0008 §1)
    pharmacy_uid                VARCHAR(26)     NOT NULL,
    pharmacist_uid              VARCHAR(26)     NOT NULL,

    -- Intra-module real FK to pharmacy_customers
    pharmacy_customer_id        BIGINT          NOT NULL,

    -- Created / approved / canceled audit triplets
    created_by_username         VARCHAR(80),
    created_on_day_uid          VARCHAR(26),
    created_at_ts               TIMESTAMPTZ,

    approved_by_username        VARCHAR(80),
    approved_on_day_uid         VARCHAR(26),
    approved_at_ts              TIMESTAMPTZ,

    canceled_by_username        VARCHAR(80),
    canceled_on_day_uid         VARCHAR(26),
    canceled_at_ts              TIMESTAMPTZ,

    -- Spring Data JPA audit columns (from AuditableEntity)
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_pharmacy_sale_orders          PRIMARY KEY (id),
    CONSTRAINT uq_pharmacy_sale_orders_uid      UNIQUE (uid),
    CONSTRAINT uq_pharmacy_sale_orders_no       UNIQUE (no),
    CONSTRAINT fk_pso_customer
        FOREIGN KEY (pharmacy_customer_id) REFERENCES pharmacy_customers (id),
    CONSTRAINT ck_pso_status CHECK (
        status IN ('PENDING', 'APPROVED', 'ARCHIVED', 'CANCELED')
    )
);

CREATE INDEX idx_pso_pharmacy_uid ON pharmacy_sale_orders (pharmacy_uid);
CREATE INDEX idx_pso_status       ON pharmacy_sale_orders (status);

-- -------------------------------------------------------------------------------------
-- pharmacy_sale_order_details — OTC order line items.
-- Legacy PharmacySaleOrderDetail.java:29-126.
-- detail.status: 'NOT-GIVEN' | 'GIVEN'  (verbatim legacy strings, stored as VARCHAR).
-- pay_status: UNPAID | PAID  (OtcPayStatus enum).
-- -------------------------------------------------------------------------------------
CREATE TABLE pharmacy_sale_order_details (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Intra-module real FK to parent order
    pharmacy_sale_order_id      BIGINT          NOT NULL,

    -- Loose cross-module refs (no physical FK — ADR-0008 §1)
    medicine_uid                VARCHAR(26)     NOT NULL,
    patient_bill_uid            VARCHAR(26),        -- loose ref to billing.PatientBill
    issue_pharmacy_uid          VARCHAR(26),        -- set on dispense

    dosage                      VARCHAR(200),
    frequency                   VARCHAR(100),
    route                       VARCHAR(100),
    days                        VARCHAR(100),

    qty                         NUMERIC(19,6)   NOT NULL,
    issued                      NUMERIC(19,6)   NOT NULL DEFAULT 0,
    balance                     NUMERIC(19,6)   NOT NULL,

    -- Fulfilment status: 'NOT-GIVEN' | 'GIVEN' (verbatim legacy strings)
    status                      VARCHAR(20)     NOT NULL DEFAULT 'NOT-GIVEN',

    -- Payment status enum
    pay_status                  VARCHAR(20)     NOT NULL DEFAULT 'UNPAID',

    reference                   VARCHAR(500),
    instructions                VARCHAR(1000),

    -- Audit triplets: created, approved (=sold), sold
    created_by_username         VARCHAR(80),
    created_on_day_uid          VARCHAR(26),
    created_at_ts               TIMESTAMPTZ,

    approved_by_username        VARCHAR(80),
    approved_on_day_uid         VARCHAR(26),
    approved_at_ts              TIMESTAMPTZ,

    sold_by_username            VARCHAR(80),
    sold_on_day_uid             VARCHAR(26),
    sold_at_ts                  TIMESTAMPTZ,

    -- Spring Data JPA audit columns
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_psod               PRIMARY KEY (id),
    CONSTRAINT uq_psod_uid           UNIQUE (uid),
    CONSTRAINT fk_psod_order
        FOREIGN KEY (pharmacy_sale_order_id) REFERENCES pharmacy_sale_orders (id),
    CONSTRAINT ck_psod_status CHECK (
        status IN ('NOT-GIVEN', 'GIVEN')
    ),
    CONSTRAINT ck_psod_pay_status CHECK (
        pay_status IN ('UNPAID', 'PAID')
    ),
    CONSTRAINT ck_psod_qty CHECK (
        qty >= 0 AND issued >= 0 AND balance >= 0
    )
);

CREATE INDEX idx_psod_order_id         ON pharmacy_sale_order_details (pharmacy_sale_order_id);
CREATE INDEX idx_psod_medicine_uid     ON pharmacy_sale_order_details (medicine_uid);
CREATE INDEX idx_psod_patient_bill_uid ON pharmacy_sale_order_details (patient_bill_uid);

-- -------------------------------------------------------------------------------------
-- GENERAL dummy patient (required by OTC flat-cash bill path, PatientServiceImpl.java:3259)
-- Inserted idempotently. The uid is a fixed known value used by OTC billing only.
-- The no='GENERAL' unique row in patients (registration module) must exist.
-- We cannot INSERT into registration.patients from this migration (cross-module DDL boundary).
-- The application ensures the GENERAL patient via service-level bootstrap on first use.
-- (See PharmacySaleOrderService.ensureGeneralPatient)
-- -------------------------------------------------------------------------------------
