-- =====================================================================================
-- Increment 04-P1 — Collections (cashier reconciliation ledger)
--
-- One row per paid bill; the REAL legacy cash-up source (PatientBillResource.java:327-337).
-- Attributed to the logged-in user (created_by) — NOT to a CashierShift (deferred CR-04).
-- payment_channel is hard-coded 'Cash' at every legacy call site (PARITY).
--
-- Legacy citations:
--   Collection: domain/Collection.java:38
--   Write site: PatientBillResource.java:327-337
--   EOD report: reports/models/CollectionReport.java (SUM GROUP BY item_name, payment_channel)
-- =====================================================================================

CREATE TABLE collections (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                     VARCHAR(26)   NOT NULL,

    -- loose cross-module ref
    patient_uid             VARCHAR(26),

    -- nullable FK — bill may have been voided; FK for intra-module consistency
    patient_bill_id         BIGINT,

    amount                  NUMERIC(19,2) NOT NULL,
    amount_currency         VARCHAR(3)    NOT NULL DEFAULT 'TZS',

    item_name               VARCHAR(200)  NOT NULL DEFAULT 'NA',
    payment_channel         VARCHAR(60)   NOT NULL DEFAULT 'Cash',
    payment_reference_no    VARCHAR(200)  NOT NULL DEFAULT 'NA',

    -- loose cross-module ref
    business_day_uid        VARCHAR(26)   NOT NULL,

    -- audit columns
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(80),
    updated_by              VARCHAR(80),
    version                 BIGINT        NOT NULL,

    CONSTRAINT pk_collections PRIMARY KEY (id),
    CONSTRAINT uq_collections_uid UNIQUE (uid),
    CONSTRAINT fk_collections_bill
        FOREIGN KEY (patient_bill_id) REFERENCES patient_bills(id)
);

CREATE INDEX idx_collections_patient_uid      ON collections (patient_uid);
CREATE INDEX idx_collections_patient_bill_id  ON collections (patient_bill_id);
CREATE INDEX idx_collections_business_day_uid ON collections (business_day_uid);
CREATE INDEX idx_collections_created_by       ON collections (created_by);
-- Support EOD report date-range queries (PatientBillResource.java:415+ cashier screens)
CREATE INDEX idx_collections_created_at       ON collections (created_at);
