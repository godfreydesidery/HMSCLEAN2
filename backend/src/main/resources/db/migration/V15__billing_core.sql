-- =====================================================================================
-- Increment 04-P1 — Billing core tables
--
-- Conventions (DIRECTIVE 2): plural snake_case; id BIGINT GENERATED ALWAYS AS IDENTITY;
-- uid VARCHAR(26) NOT NULL UNIQUE; NUMERIC(19,2) for money; NUMERIC(19,6) for qty.
-- Cross-module refs: loose VARCHAR(26) uids, NO FK (patient_uid, plan_uid).
-- Intra-module FKs: real named constraints (fk_/pk_/uq_/ck_/idx_).
--
-- Legacy citations:
--   PatientBill:         domain/PatientBill.java:40 (PatientServiceImpl.java:821-849)
--   PatientInvoice:      domain/PatientInvoice.java:43 (PARITY {PENDING,APPROVED})
--   PatientInvoiceDetail:domain/PatientInvoiceDetail.java:39 (CR-14 NOT NULL bill FK)
--   PatientPayment:      domain/PatientPayment.java:39
--   PatientPaymentDetail:domain/PatientPaymentDetail.java:37 (no amount col — PARITY)
--
-- CR-14: DROP dead amount_allocated/amount_unallocated from patient_invoices
-- CR-14: patient_invoice_details.patient_bill_id NOT NULL + UNIQUE
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- patient_bills — the atomic charge line (one per chargeable clinical/registration/ward item)
-- Self-referencing FKs for ward top-up linkage (CR-11 plumbing — selection logic DEFERRED)
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_bills (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                     VARCHAR(26)      NOT NULL,

    -- loose cross-module ref (no FK — patient is in a different module)
    patient_uid             VARCHAR(26)      NOT NULL,

    bill_item               VARCHAR(60)      NOT NULL DEFAULT 'NA',
    description             VARCHAR(500)     NOT NULL DEFAULT 'NA',

    -- ServiceKind enum stored as VARCHAR
    kind                    VARCHAR(20)      NOT NULL,

    qty                     NUMERIC(19,6)    NOT NULL DEFAULT 1,

    -- Money embeddable columns (three: amount / paid / balance)
    amount                  NUMERIC(19,2)    NOT NULL,
    amount_currency         VARCHAR(3)       NOT NULL DEFAULT 'TZS',
    paid                    NUMERIC(19,2)    NOT NULL DEFAULT 0,
    paid_currency           VARCHAR(3)       NOT NULL DEFAULT 'TZS',
    balance                 NUMERIC(19,2)    NOT NULL DEFAULT 0,
    balance_currency        VARCHAR(3)       NOT NULL DEFAULT 'TZS',

    status                  VARCHAR(20)      NOT NULL,
    payment_type            VARCHAR(20)      NOT NULL DEFAULT 'CASH',
    membership_no           VARCHAR(100),

    -- loose cross-module ref (no FK)
    plan_uid                VARCHAR(26),

    -- self-referencing FKs for ward top-up split (CR-11 — plumbing only in P1)
    principal_bill_id       BIGINT,
    supplementary_bill_id   BIGINT,

    -- loose cross-module ref for business day (no FK)
    business_day_uid        VARCHAR(26)      NOT NULL,

    -- audit columns
    created_at              TIMESTAMPTZ      NOT NULL,
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(80),
    updated_by              VARCHAR(80),
    version                 BIGINT           NOT NULL,

    CONSTRAINT pk_patient_bills PRIMARY KEY (id),
    CONSTRAINT uq_patient_bills_uid UNIQUE (uid),
    CONSTRAINT ck_patient_bills_status CHECK (
        status IN ('UNPAID','VERIFIED','COVERED','PAID','NONE','CANCELED')
    ),
    CONSTRAINT ck_patient_bills_kind CHECK (
        kind IN ('REGISTRATION','CONSULTATION','LAB_TEST','MEDICINE','PROCEDURE','RADIOLOGY','WARD')
    ),
    CONSTRAINT ck_patient_bills_payment_type CHECK (
        payment_type IN ('CASH','INSURANCE')
    ),
    CONSTRAINT fk_patient_bills_principal
        FOREIGN KEY (principal_bill_id) REFERENCES patient_bills(id),
    CONSTRAINT fk_patient_bills_supplementary
        FOREIGN KEY (supplementary_bill_id) REFERENCES patient_bills(id)
);

CREATE INDEX idx_patient_bills_patient_uid       ON patient_bills (patient_uid);
CREATE INDEX idx_patient_bills_status            ON patient_bills (status);
CREATE INDEX idx_patient_bills_plan_uid          ON patient_bills (plan_uid);
CREATE INDEX idx_patient_bills_business_day_uid  ON patient_bills (business_day_uid);
-- Partial index for the cashier UNPAID/VERIFIED queue
CREATE INDEX idx_patient_bills_collectable
    ON patient_bills (patient_uid, status)
    WHERE status IN ('UNPAID','VERIFIED');

-- -------------------------------------------------------------------------------------
-- patient_invoices — insurance claim accumulator (one PENDING per patient+plan)
-- PARITY: status ∈ {PENDING, APPROVED} only (CR-01 richer lifecycle deferred)
-- CR-14: amount_allocated and amount_unallocated are dropped (never written in legacy)
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_invoices (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)   NOT NULL,

    -- loose cross-module ref
    patient_uid     VARCHAR(26)   NOT NULL,

    -- NULL = cash invoice; non-NULL = insurance claim accumulator
    plan_uid        VARCHAR(26),

    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',

    -- running sum of paid detail amounts (PatientBillResource.java:341-349)
    amount_paid     NUMERIC(19,2) NOT NULL DEFAULT 0,

    -- loose cross-module ref
    business_day_uid VARCHAR(26)  NOT NULL,

    -- audit columns
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT        NOT NULL,

    CONSTRAINT pk_patient_invoices PRIMARY KEY (id),
    CONSTRAINT uq_patient_invoices_uid UNIQUE (uid),
    CONSTRAINT ck_patient_invoices_status CHECK (status IN ('PENDING','APPROVED'))
);

CREATE INDEX idx_patient_invoices_patient_uid      ON patient_invoices (patient_uid);
CREATE INDEX idx_patient_invoices_plan_uid         ON patient_invoices (plan_uid);
CREATE INDEX idx_patient_invoices_status           ON patient_invoices (status);
-- Partial index: find the single PENDING invoice per (patient, plan) efficiently
CREATE UNIQUE INDEX idx_patient_invoices_pending_per_patient_plan
    ON patient_invoices (patient_uid, plan_uid)
    WHERE status = 'PENDING' AND plan_uid IS NOT NULL;
CREATE UNIQUE INDEX idx_patient_invoices_pending_cash
    ON patient_invoices (patient_uid)
    WHERE status = 'PENDING' AND plan_uid IS NULL;

-- -------------------------------------------------------------------------------------
-- patient_invoice_details — one claim line per covered PatientBill
-- CR-14: patient_bill_id NOT NULL + UNIQUE (de-facto one detail per bill)
-- ON DELETE CASCADE from invoice (orphanRemoval=true)
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_invoice_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)      NOT NULL,

    patient_invoice_id  BIGINT           NOT NULL,
    patient_bill_id     BIGINT           NOT NULL,

    description         VARCHAR(500)     NOT NULL DEFAULT 'NA',
    qty                 NUMERIC(19,6)    NOT NULL DEFAULT 1,
    amount              NUMERIC(19,2)    NOT NULL,

    -- NULL = unpaid detail; 'PAID' = paid at cashier (PatientBillResource.java:341)
    status              VARCHAR(20),

    -- Snapshot of bill coverage at attach time
    coverage_status     VARCHAR(20)      NOT NULL DEFAULT 'UNPAID',

    -- audit columns
    created_at          TIMESTAMPTZ      NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT           NOT NULL,

    CONSTRAINT pk_patient_invoice_details PRIMARY KEY (id),
    CONSTRAINT uq_patient_invoice_details_uid UNIQUE (uid),
    CONSTRAINT uq_patient_invoice_details_bill UNIQUE (patient_bill_id),
    CONSTRAINT fk_patient_invoice_details_invoice
        FOREIGN KEY (patient_invoice_id) REFERENCES patient_invoices(id) ON DELETE CASCADE,
    CONSTRAINT fk_patient_invoice_details_bill
        FOREIGN KEY (patient_bill_id) REFERENCES patient_bills(id),
    CONSTRAINT ck_patient_invoice_details_status CHECK (
        status IS NULL OR status IN ('PAID')
    ),
    CONSTRAINT ck_patient_invoice_details_coverage_status CHECK (
        coverage_status IN ('UNPAID','COVERED','VERIFIED')
    )
);

CREATE INDEX idx_patient_invoice_details_invoice ON patient_invoice_details (patient_invoice_id);
CREATE INDEX idx_patient_invoice_details_bill    ON patient_invoice_details (patient_bill_id);

-- -------------------------------------------------------------------------------------
-- patient_payments — payment receipt header (one per cashier payment action)
-- PatientPayment.java:39 — PARITY (patient_uid, payment_type, business_day_uid added
-- as net-new attribution fields per build-spec §1.2)
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_payments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)   NOT NULL,

    -- loose cross-module ref (nullable — legacy has none, net-new attribution)
    patient_uid     VARCHAR(26),

    -- tendered total amount
    amount          NUMERIC(19,2) NOT NULL,
    amount_currency VARCHAR(3)    NOT NULL DEFAULT 'TZS',

    payment_type    VARCHAR(20)   NOT NULL DEFAULT 'CASH',
    status          VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',

    -- loose cross-module ref
    business_day_uid VARCHAR(26)  NOT NULL,

    -- audit columns
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT        NOT NULL,

    CONSTRAINT pk_patient_payments PRIMARY KEY (id),
    CONSTRAINT uq_patient_payments_uid UNIQUE (uid),
    CONSTRAINT ck_patient_payments_payment_type CHECK (payment_type IN ('CASH','INSURANCE')),
    CONSTRAINT ck_patient_payments_status CHECK (status IN ('RECEIVED'))
);

CREATE INDEX idx_patient_payments_patient_uid      ON patient_payments (patient_uid);
CREATE INDEX idx_patient_payments_business_day_uid ON patient_payments (business_day_uid);

-- -------------------------------------------------------------------------------------
-- patient_payment_details — links one paid PatientBill to one PatientPayment
-- PatientPaymentDetail.java:37 — NO amount column (PARITY — CR-02/CR-03 deferred)
-- -------------------------------------------------------------------------------------
CREATE TABLE patient_payment_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)   NOT NULL,

    patient_payment_id  BIGINT        NOT NULL,
    patient_bill_id     BIGINT        NOT NULL,

    description         VARCHAR(500),
    status              VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',

    -- audit columns
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT        NOT NULL,

    CONSTRAINT pk_patient_payment_details PRIMARY KEY (id),
    CONSTRAINT uq_patient_payment_details_uid UNIQUE (uid),
    CONSTRAINT uq_patient_payment_details_bill UNIQUE (patient_bill_id),
    CONSTRAINT fk_patient_payment_details_payment
        FOREIGN KEY (patient_payment_id) REFERENCES patient_payments(id),
    CONSTRAINT fk_patient_payment_details_bill
        FOREIGN KEY (patient_bill_id) REFERENCES patient_bills(id),
    CONSTRAINT ck_patient_payment_details_status CHECK (
        status IN ('RECEIVED','REFUNDED')
    )
);

CREATE INDEX idx_patient_payment_details_payment ON patient_payment_details (patient_payment_id);
CREATE INDEX idx_patient_payment_details_bill    ON patient_payment_details (patient_bill_id);
