-- =====================================================================================
-- Increment 08b — Inventory bounded context — Chunk 5 (Store stock + LPO -> GRN procurement)
--
-- Tables:
--   store_item / store_item_batch / store_stock_movement  — store-side stock (mirror of the
--     pharmacy V39 model; D16-CORRECTED re-model of legacy StoreItem.stock + StoreItemBatch +
--     StoreStockCard).
--   local_purchase_orders / local_purchase_order_details  — LPO (LocalPurchaseOrder.java).
--   goods_received_notes / goods_received_note_details / goods_received_note_detail_batches — GRN.
--   purchases — the Purchase ledger row written by GRN.approve (legacy Purchase.java:40-41).
--
-- NO three-way match / SupplierInvoice (Q3 — confirmed PHANTOM, dropped from baseline).
-- Doc numbers GRN/LPO via the shared DocumentNumberService (seq_grn_no/seq_lpo_no, V13) — NO new
-- sequences here. FLOAT -> NUMERIC(19,6) qty / NUMERIC(19,2) money (ADR-0009 §3). Loose cross-module
-- uids (store_uid, item_uid, supplier_uid -> masterdata; no physical FK). Intra-module real FKs for
-- header<->detail<->batch. Named constraints pk_/uq_/fk_/ck_/idx_, conventions per V39.
--
-- Legacy citations: LocalPurchaseOrderServiceImpl.java:84-334; GoodsReceivedNoteServiceImpl.java:64-215;
-- StoreItem/StoreItemBatch/StoreStockCard/Purchase domain.
-- =====================================================================================

-- ====================== STORE STOCK MODEL (mirror of V39 pharmacy) ====================

CREATE TABLE store_item (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    store_uid           VARCHAR(26)     NOT NULL,
    item_uid            VARCHAR(26)     NOT NULL,
    stock               NUMERIC(19,6)   NOT NULL    DEFAULT 0,
    business_day_uid    VARCHAR(26),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_store_item                PRIMARY KEY (id),
    CONSTRAINT uq_store_item_uid            UNIQUE (uid),
    CONSTRAINT uq_store_item_store_item     UNIQUE (store_uid, item_uid),
    CONSTRAINT ck_store_item_stock_nonneg   CHECK (stock >= 0)
);
CREATE INDEX idx_store_item_store_uid ON store_item (store_uid);
CREATE INDEX idx_store_item_item_uid  ON store_item (item_uid);

CREATE TABLE store_item_batch (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    store_uid           VARCHAR(26)     NOT NULL,
    item_uid            VARCHAR(26)     NOT NULL,
    store_item_id       BIGINT          NOT NULL,
    batch_no            VARCHAR(100)    NOT NULL,
    manufactured_date   DATE,
    expiry_date         DATE,
    received_qty        NUMERIC(19,6)   NOT NULL    DEFAULT 0,
    remaining_qty       NUMERIC(19,6)   NOT NULL    DEFAULT 0,
    business_day_uid    VARCHAR(26),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_store_item_batch          PRIMARY KEY (id),
    CONSTRAINT uq_store_item_batch_uid      UNIQUE (uid),
    CONSTRAINT fk_store_item_batch_store_item
        FOREIGN KEY (store_item_id) REFERENCES store_item (id),
    CONSTRAINT ck_store_item_batch_qty CHECK (
        received_qty >= 0 AND remaining_qty >= 0 AND remaining_qty <= received_qty
    )
);
CREATE INDEX idx_store_item_batch_fefo
    ON store_item_batch (store_item_id, expiry_date, id) WHERE remaining_qty > 0;
CREATE INDEX idx_store_item_batch_store_item ON store_item_batch (store_item_id);

CREATE TABLE store_stock_movement (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    store_uid           VARCHAR(26)     NOT NULL,
    item_uid            VARCHAR(26)     NOT NULL,
    store_item_id       BIGINT,
    movement_type       VARCHAR(20)     NOT NULL,
    qty_in              NUMERIC(19,6)   NOT NULL    DEFAULT 0,
    qty_out             NUMERIC(19,6)   NOT NULL    DEFAULT 0,
    running_balance     NUMERIC(19,6)   NOT NULL,
    reference           TEXT,
    occurred_at         TIMESTAMPTZ     NOT NULL,
    business_day_uid    VARCHAR(26),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_store_stock_movement      PRIMARY KEY (id),
    CONSTRAINT uq_store_stock_movement_uid  UNIQUE (uid),
    CONSTRAINT fk_store_stock_movement_store_item
        FOREIGN KEY (store_item_id) REFERENCES store_item (id),
    CONSTRAINT ck_store_stock_movement_type CHECK (
        movement_type IN ('RECEIPT', 'DISPENSE', 'TRANSFER_OUT', 'TRANSFER_IN', 'ADJUSTMENT', 'OPENING')
    ),
    CONSTRAINT ck_store_stock_movement_qty CHECK (qty_in >= 0 AND qty_out >= 0)
);
CREATE INDEX idx_store_stock_movement_store_item
    ON store_stock_movement (store_uid, item_uid, occurred_at);
CREATE INDEX idx_store_stock_movement_store_item_id ON store_stock_movement (store_item_id);

-- ====================== LOCAL PURCHASE ORDER ====================

CREATE TABLE local_purchase_orders (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    status_description  VARCHAR(200),
    -- Loose cross-module refs (no FK)
    store_uid           VARCHAR(26)     NOT NULL,
    supplier_uid        VARCHAR(26)     NOT NULL,
    order_date          DATE,
    valid_until         DATE,                                   -- only mutable field on edit
    verified_by_username  VARCHAR(80),  verified_on_day_uid  VARCHAR(26),  verified_at_ts  TIMESTAMPTZ,
    approved_by_username  VARCHAR(80),  approved_on_day_uid  VARCHAR(26),  approved_at_ts  TIMESTAMPTZ,
    received_by_username  VARCHAR(80),  received_on_day_uid  VARCHAR(26),  received_at_ts  TIMESTAMPTZ,
    business_day_uid    VARCHAR(26),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_local_purchase_orders     PRIMARY KEY (id),
    CONSTRAINT uq_local_purchase_orders_uid UNIQUE (uid),
    CONSTRAINT uq_local_purchase_orders_no  UNIQUE (no),
    CONSTRAINT ck_lpo_status CHECK (
        status IN ('PENDING','VERIFIED','APPROVED','SUBMITTED','RECEIVED','REJECTED','RETURNED')
    )
);
CREATE INDEX idx_lpo_status      ON local_purchase_orders (status);
CREATE INDEX idx_lpo_store_uid   ON local_purchase_orders (store_uid);
CREATE INDEX idx_lpo_supplier_uid ON local_purchase_orders (supplier_uid);

CREATE TABLE local_purchase_order_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    local_purchase_order_id BIGINT      NOT NULL,
    item_uid            VARCHAR(26)     NOT NULL,
    qty                 NUMERIC(19,6)   NOT NULL,
    price               NUMERIC(19,2)   NOT NULL,                -- copied from SupplierItemPrice
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_lpo_details               PRIMARY KEY (id),
    CONSTRAINT uq_lpo_details_uid           UNIQUE (uid),
    CONSTRAINT fk_lpo_details_order
        FOREIGN KEY (local_purchase_order_id) REFERENCES local_purchase_orders (id),
    CONSTRAINT ck_lpo_details_qty CHECK (qty >= 0 AND price >= 0)
);
CREATE INDEX idx_lpo_details_order    ON local_purchase_order_details (local_purchase_order_id);
CREATE INDEX idx_lpo_details_item_uid ON local_purchase_order_details (item_uid);

-- ====================== GOODS RECEIVED NOTE ====================

CREATE TABLE goods_received_notes (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,                -- header: PENDING -> APPROVED only
    status_description  VARCHAR(200),
    store_uid           VARCHAR(26)     NOT NULL,
    -- Intra-module 1:1 link to the LPO (nullable — a GRN may exist without an LPO, legacy allows it)
    local_purchase_order_id BIGINT,
    approved_by_username  VARCHAR(80),  approved_on_day_uid  VARCHAR(26),  approved_at_ts  TIMESTAMPTZ,
    business_day_uid    VARCHAR(26),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_goods_received_notes      PRIMARY KEY (id),
    CONSTRAINT uq_goods_received_notes_uid  UNIQUE (uid),
    CONSTRAINT uq_goods_received_notes_no   UNIQUE (no),
    CONSTRAINT uq_grn_lpo                    UNIQUE (local_purchase_order_id),  -- one GRN per LPO
    CONSTRAINT fk_grn_lpo
        FOREIGN KEY (local_purchase_order_id) REFERENCES local_purchase_orders (id),
    CONSTRAINT ck_grn_status CHECK (status IN ('PENDING','APPROVED'))
);
CREATE INDEX idx_grn_status    ON goods_received_notes (status);
CREATE INDEX idx_grn_store_uid ON goods_received_notes (store_uid);

CREATE TABLE goods_received_note_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    goods_received_note_id BIGINT       NOT NULL,
    item_uid            VARCHAR(26)     NOT NULL,
    ordered_qty         NUMERIC(19,6)   NOT NULL,
    received_qty        NUMERIC(19,6)   NOT NULL    DEFAULT 0,
    price               NUMERIC(19,2)   NOT NULL,
    status              VARCHAR(20)     NOT NULL    DEFAULT 'NOT-VERIFIED',  -- NOT-VERIFIED -> VERIFIED
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_grn_details               PRIMARY KEY (id),
    CONSTRAINT uq_grn_details_uid           UNIQUE (uid),
    CONSTRAINT fk_grn_details_grn
        FOREIGN KEY (goods_received_note_id) REFERENCES goods_received_notes (id),
    CONSTRAINT ck_grn_details_status CHECK (status IN ('NOT-VERIFIED','VERIFIED')),
    CONSTRAINT ck_grn_details_qty CHECK (
        ordered_qty >= 0 AND received_qty >= 0 AND received_qty <= ordered_qty AND price >= 0
    )
);
CREATE INDEX idx_grn_details_grn      ON goods_received_note_details (goods_received_note_id);
CREATE INDEX idx_grn_details_item_uid ON goods_received_note_details (item_uid);

CREATE TABLE goods_received_note_detail_batches (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    goods_received_note_detail_id BIGINT NOT NULL,
    batch_no            VARCHAR(100)    NOT NULL,
    manufactured_date   DATE,
    expiry_date         DATE,
    qty                 NUMERIC(19,6)   NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_grn_detail_batches        PRIMARY KEY (id),
    CONSTRAINT uq_grn_detail_batches_uid    UNIQUE (uid),
    CONSTRAINT fk_grn_detail_batches_detail
        FOREIGN KEY (goods_received_note_detail_id) REFERENCES goods_received_note_details (id),
    CONSTRAINT ck_grn_detail_batches_qty CHECK (qty >= 0)
);
CREATE INDEX idx_grn_detail_batches_detail
    ON goods_received_note_detail_batches (goods_received_note_detail_id);

-- ====================== PURCHASE LEDGER ====================

CREATE TABLE purchases (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    -- written by GRN.approve per receivedQty>0 detail (legacy Purchase.java:40-41)
    goods_received_note_id BIGINT,
    item_uid            VARCHAR(26)     NOT NULL,
    qty                 NUMERIC(19,6)   NOT NULL,
    amount              NUMERIC(19,2)   NOT NULL,                -- receivedQty * price
    business_day_uid    VARCHAR(26),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,
    CONSTRAINT pk_purchases             PRIMARY KEY (id),
    CONSTRAINT uq_purchases_uid         UNIQUE (uid),
    CONSTRAINT fk_purchases_grn
        FOREIGN KEY (goods_received_note_id) REFERENCES goods_received_notes (id),
    CONSTRAINT ck_purchases_qty CHECK (qty >= 0 AND amount >= 0)
);
CREATE INDEX idx_purchases_grn      ON purchases (goods_received_note_id);
CREATE INDEX idx_purchases_item_uid ON purchases (item_uid);
