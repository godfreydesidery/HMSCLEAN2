-- =====================================================================================
-- Increment 08a — Pharmacy bounded context — Chunk 0 (SCHEMA FIRST)
-- Pharmacy stock core: pharmacy_medicine + stock_batch + stock_movement.
--
-- A ratified MODERN RE-MODEL (D16-CORRECTED) of the legacy single-ledger + scalar-stock
-- structure — NOT a 1:1 field reproduction. The legacy structure is:
--   * PharmacyMedicine.stock  (plain double, the AUTHORITATIVE aggregate; PharmacyMedicine.java:38)
--   * PharmacyMedicineBatch.qty (single in-place-decremented double; PharmacyMedicineBatch.java:35-53)
--   * PharmacyStockCard (qtyIn/qtyOut/balance doubles + free-text reference; PharmacyStockCard.java:37-57)
-- There is NO separate StockBalance entity in legacy (D6/D16 — the planning-doc two-table split
-- is REJECTED): pharmacy_medicine.stock IS the aggregate; the ledger/batches do NOT derive it.
--
-- FLOAT -> NUMERIC(19,6) (money/qty guardrail, pre-approved ADR-0009 §3): every stock/qty/balance
-- column. NO float/double anywhere (AC-STK-14).
--
-- Net-new deltas (explicitly labelled, NOT parity assertions — AC-STK-02/04/06/10):
--   * stock_batch.received_qty/remaining_qty SPLIT (legacy single in-place qty;
--     remaining_qty reproduces the legacy post-decrement value — the parity invariant, AC-STK-04).
--   * stock_movement.movement_type typed enum (legacy has NO type column; type was conveyed only
--     by the free-text reference, which is STILL persisted verbatim in `reference`, AC-STK-06).
--   * @Version optimistic lock inherited from AuditableEntity (legacy batch had none).
--   * ck_pharmacy_medicine_stock_nonneg DB CHECK (legacy column is unconstrained double — the
--     FROZEN parity element is the app-layer 422 INSUFFICIENT_STOCK reject; the CHECK is a backstop).
--
-- BLOCKED (frozen:false) — NOT in this migration:
--   * NO SELECT-FOR-UPDATE / lock column (Q4/ADR-0017 — CR-08-Q4). Only inherited @Version (AC-STK-10).
--   * NO seq_pso_no/seq_pcst_no (CR-09-NUM1 — V40, owner-gated). NO new sequences here (AC-STK-15).
--   * FEFO selection SEMANTICS reproduce the legacy null-expiry EXCLUSION (Q8 baseline); NULLS-LAST
--     is parked (HDE). The index just supports expiry ASC, id ASC over positive-remaining lots.
--
-- Cross-module refs are loose *_uid columns (NO physical FK — masterdata is a different module).
-- stock_batch -> pharmacy_medicine is INTRA-module so it IS a real surrogate FK (no cascade —
-- legacy has none). NO coefficient column in 08a stock-core (08b pharmacy<->store path only).
--
-- Conventions: identical to V7/V27. Named constraints: pk_/fk_/uq_/ck_/idx_. Migrations resume V39.
--
-- Legacy citations:
--   PharmacyMedicine.java:35-48 (:38 scalar stock); PharmacyMedicineBatch.java:35-53 (free-text
--   batch no, nullable dates, single qty); PharmacyStockCard.java:37-57 (qtyIn/qtyOut/balance +
--   reference, append-only); PharmacyServiceImpl.java:67-91 (opening stock eager creation);
--   PharmacyResource.java:199-231 (manual stock OVERWRITE); PatientResource.java:3252-3376
--   (dispense stock-card + FEFO); reference strings :3252-3264, :6281-6293.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- pharmacy_medicine — the AUTHORITATIVE scalar aggregate balance (1:1 re-model of
-- PharmacyMedicine.stock). One row per (pharmacy, medicine). NOT derived from ledger/batches.
-- -------------------------------------------------------------------------------------
CREATE TABLE pharmacy_medicine (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Loose cross-module refs to masterdata (no FK)
    pharmacy_uid                VARCHAR(26)     NOT NULL,
    medicine_uid                VARCHAR(26)     NOT NULL,

    -- Authoritative aggregate: legacy double -> NUMERIC(19,6)
    stock                       NUMERIC(19,6)   NOT NULL        DEFAULT 0,

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_pharmacy_medicine             PRIMARY KEY (id),
    CONSTRAINT uq_pharmacy_medicine_uid         UNIQUE (uid),
    CONSTRAINT uq_pharmacy_medicine_pharmacy_medicine
        UNIQUE (pharmacy_uid, medicine_uid),
    -- Net-new DB backstop; frozen parity = app-layer 422 INSUFFICIENT_STOCK reject (AC-STK-02)
    CONSTRAINT ck_pharmacy_medicine_stock_nonneg CHECK (stock >= 0)
);

CREATE INDEX idx_pharmacy_medicine_pharmacy_uid ON pharmacy_medicine (pharmacy_uid);
CREATE INDEX idx_pharmacy_medicine_medicine_uid ON pharmacy_medicine (medicine_uid);

-- -------------------------------------------------------------------------------------
-- stock_batch — per-lot FEFO inventory (re-model of PharmacyMedicineBatch).
-- received_qty/remaining_qty SPLIT is a net-new derived column (AC-STK-04): legacy has a single
-- in-place-decremented qty; remaining_qty reproduces the legacy post-decrement value.
-- dates nullable EXACTLY as legacy. Intra-module real FK to pharmacy_medicine (no cascade).
-- -------------------------------------------------------------------------------------
CREATE TABLE stock_batch (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    pharmacy_uid                VARCHAR(26)     NOT NULL,
    medicine_uid                VARCHAR(26)     NOT NULL,
    pharmacy_medicine_id        BIGINT          NOT NULL,

    batch_no                    VARCHAR(100)    NOT NULL,        -- free-text (no generator)
    manufactured_date           DATE,                           -- nullable (legacy)
    expiry_date                 DATE,                           -- nullable (legacy; null-expiry EXCLUSION on FEFO)

    received_qty                NUMERIC(19,6)   NOT NULL        DEFAULT 0,   -- immutable intake (net-new)
    remaining_qty               NUMERIC(19,6)   NOT NULL        DEFAULT 0,   -- = legacy in-place qty

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_stock_batch                   PRIMARY KEY (id),
    CONSTRAINT uq_stock_batch_uid               UNIQUE (uid),
    CONSTRAINT fk_stock_batch_pharmacy_medicine
        FOREIGN KEY (pharmacy_medicine_id) REFERENCES pharmacy_medicine (id),
    CONSTRAINT ck_stock_batch_qty CHECK (
        received_qty >= 0 AND remaining_qty >= 0 AND remaining_qty <= received_qty
    )
);

-- FEFO support: expiry ASC then id ASC over positive-remaining lots (id-ASC tiebreak pinned, N8).
-- Selection SEMANTICS (null-expiry EXCLUSION, Q8 baseline) live in the service, not the index.
CREATE INDEX idx_stock_batch_fefo
    ON stock_batch (pharmacy_medicine_id, expiry_date, id) WHERE remaining_qty > 0;
CREATE INDEX idx_stock_batch_pharmacy_medicine ON stock_batch (pharmacy_medicine_id);

-- -------------------------------------------------------------------------------------
-- stock_movement — append-only stock-card ledger (re-model of PharmacyStockCard).
-- running_balance is a STORED post-movement snapshot of pharmacy_medicine.stock (NOT recomputed).
-- movement_type is the net-new typed vehicle; the verbatim legacy reference string is STILL
-- persisted in `reference` so the stock-card report keeps parity (AC-STK-06).
-- Append-only by application contract (no UPDATE/DELETE repository surface). The precise
-- immutability/role-grant mechanism is BLOCKED on security-architect (AC-STK-07).
-- -------------------------------------------------------------------------------------
CREATE TABLE stock_movement (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    pharmacy_uid                VARCHAR(26)     NOT NULL,
    medicine_uid                VARCHAR(26)     NOT NULL,
    pharmacy_medicine_id        BIGINT,                         -- intra-module link (nullable for safety)

    movement_type               VARCHAR(20)     NOT NULL,
    qty_in                      NUMERIC(19,6)   NOT NULL        DEFAULT 0,
    qty_out                     NUMERIC(19,6)   NOT NULL        DEFAULT 0,
    running_balance             NUMERIC(19,6)   NOT NULL,       -- stored snapshot of pharmacy_medicine.stock
    reference                   TEXT,                           -- verbatim legacy reference string
    occurred_at                 TIMESTAMPTZ     NOT NULL,

    business_day_uid            VARCHAR(26),

    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_stock_movement                PRIMARY KEY (id),
    CONSTRAINT uq_stock_movement_uid            UNIQUE (uid),
    CONSTRAINT fk_stock_movement_pharmacy_medicine
        FOREIGN KEY (pharmacy_medicine_id) REFERENCES pharmacy_medicine (id),
    -- net-new typed enum (no WASTAGE — no legacy disposal path; AC-STK-06)
    CONSTRAINT ck_stock_movement_type CHECK (
        movement_type IN ('RECEIPT', 'DISPENSE', 'TRANSFER_OUT', 'TRANSFER_IN', 'ADJUSTMENT', 'OPENING')
    ),
    CONSTRAINT ck_stock_movement_qty CHECK (qty_in >= 0 AND qty_out >= 0)
);

CREATE INDEX idx_stock_movement_pharmacy_medicine
    ON stock_movement (pharmacy_uid, medicine_uid, occurred_at);
CREATE INDEX idx_stock_movement_pharmacy_medicine_id ON stock_movement (pharmacy_medicine_id);
