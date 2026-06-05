-- =====================================================================================
-- Increment 08b — Chunk 6 — Pharmacy<->Store transfer chain (3 documents)
--
-- (1) pharmacy_to_store_ros (PSR)  — pharmacy's requisition; moves NO stock.
-- (2) store_to_pharmacy_tos (SPTO) — store's transfer/issue order; STORE stock decrements at issue.
-- (3) store_to_pharmacy_rns (PGRN) — pharmacy's goods-received note; PHARMACY stock increments at approve.
--
-- Numbering: PSR (client request, but server-assigned here for concurrency-safety per ADR-0009 §5),
-- SPTO + PGRN via the shared DocumentNumberService (seq_psr_no / seq_spto_no / seq_pgrn_no, V13).
-- CR-10: SPTO replaces the legacy 'SPT' collision prefix.
--
-- Coefficient conversion on the TO: pharmacy_sku_qty = store_sku_qty * coefficient (NUMERIC(19,6)).
-- FLOAT -> NUMERIC(19,6) everywhere. Loose cross-module uids (pharmacy_uid/store_uid/item_uid/
-- medicine_uid; no FK). Intra-module real FK header<->detail<->batch.
--
-- Legacy citations: PharmacyToStoreROServiceImpl, StoreToPharmacyTOServiceImpl,
-- StoreToPharmacyRNServiceImpl, InternalOrderResource.
-- =====================================================================================

-- ====================== (1) PHARMACY -> STORE RO (PSR) ====================

CREATE TABLE pharmacy_to_store_ros (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    status_description  VARCHAR(200),
    pharmacy_uid        VARCHAR(26)     NOT NULL,            -- requesting pharmacy
    store_uid           VARCHAR(26)     NOT NULL,            -- target store
    business_day_uid    VARCHAR(26),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pharmacy_to_store_ros     PRIMARY KEY (id),
    CONSTRAINT uq_pharmacy_to_store_ros_uid UNIQUE (uid),
    CONSTRAINT uq_pharmacy_to_store_ros_no  UNIQUE (no),
    CONSTRAINT ck_ps_ro_status CHECK (status IN
        ('PENDING','VERIFIED','APPROVED','SUBMITTED','IN-PROCESS','GOODS-ISSUED','COMPLETED','RETURNED','REJECTED'))
);
CREATE INDEX idx_ps_ro_status ON pharmacy_to_store_ros (status);
CREATE INDEX idx_ps_ro_pharmacy ON pharmacy_to_store_ros (pharmacy_uid);
CREATE INDEX idx_ps_ro_store ON pharmacy_to_store_ros (store_uid);

CREATE TABLE pharmacy_to_store_ro_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    pharmacy_to_store_ro_id BIGINT      NOT NULL,
    medicine_uid        VARCHAR(26)     NOT NULL,
    ordered_qty         NUMERIC(19,6)   NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_ps_ro_details     PRIMARY KEY (id),
    CONSTRAINT uq_ps_ro_details_uid UNIQUE (uid),
    CONSTRAINT fk_ps_ro_details_ro
        FOREIGN KEY (pharmacy_to_store_ro_id) REFERENCES pharmacy_to_store_ros (id),
    CONSTRAINT ck_ps_ro_details_qty CHECK (ordered_qty >= 0)
);
CREATE INDEX idx_ps_ro_details_ro ON pharmacy_to_store_ro_details (pharmacy_to_store_ro_id);

-- ====================== (2) STORE -> PHARMACY TO (SPTO) ====================

CREATE TABLE store_to_pharmacy_tos (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    status_description  VARCHAR(200),
    store_uid           VARCHAR(26)     NOT NULL,            -- issuing store
    pharmacy_uid        VARCHAR(26)     NOT NULL,            -- receiving pharmacy
    pharmacy_to_store_ro_id BIGINT,                          -- source RO (intra-module)
    business_day_uid    VARCHAR(26),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_store_to_pharmacy_tos     PRIMARY KEY (id),
    CONSTRAINT uq_store_to_pharmacy_tos_uid UNIQUE (uid),
    CONSTRAINT uq_store_to_pharmacy_tos_no  UNIQUE (no),
    CONSTRAINT fk_sp_to_ro
        FOREIGN KEY (pharmacy_to_store_ro_id) REFERENCES pharmacy_to_store_ros (id),
    CONSTRAINT ck_sp_to_status CHECK (status IN
        ('PENDING','VERIFIED','APPROVED','GOODS-ISSUED','COMPLETED'))
);
CREATE INDEX idx_sp_to_status ON store_to_pharmacy_tos (status);
CREATE INDEX idx_sp_to_store ON store_to_pharmacy_tos (store_uid);

CREATE TABLE store_to_pharmacy_to_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    store_to_pharmacy_to_id BIGINT      NOT NULL,
    item_uid            VARCHAR(26),                         -- store SKU (set on first add_batch)
    medicine_uid        VARCHAR(26)     NOT NULL,            -- pharmacy SKU
    ordered_pharmacy_sku_qty    NUMERIC(19,6)   NOT NULL,
    transfered_store_sku_qty    NUMERIC(19,6)   NOT NULL DEFAULT 0,
    transfered_pharmacy_sku_qty NUMERIC(19,6)   NOT NULL DEFAULT 0,  -- = store_sku * coefficient
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_sp_to_details     PRIMARY KEY (id),
    CONSTRAINT uq_sp_to_details_uid UNIQUE (uid),
    CONSTRAINT fk_sp_to_details_to
        FOREIGN KEY (store_to_pharmacy_to_id) REFERENCES store_to_pharmacy_tos (id),
    CONSTRAINT ck_sp_to_details_qty CHECK (
        ordered_pharmacy_sku_qty >= 0 AND transfered_store_sku_qty >= 0
        AND transfered_pharmacy_sku_qty >= 0)
);
CREATE INDEX idx_sp_to_details_to ON store_to_pharmacy_to_details (store_to_pharmacy_to_id);

-- Transfer batches: manually entered on the TO detail, carried through to the RN/pharmacy batch.
CREATE TABLE store_to_pharmacy_batches (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    store_to_pharmacy_to_detail_id BIGINT,                  -- parent at TO time (re-parented at RN)
    store_to_pharmacy_rn_detail_id BIGINT,                  -- parent after RN re-parenting
    batch_no            VARCHAR(100)    NOT NULL,
    manufactured_date   DATE,
    expiry_date         DATE,
    store_sku_qty       NUMERIC(19,6)   NOT NULL DEFAULT 0,
    pharmacy_sku_qty    NUMERIC(19,6)   NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_sp_batches     PRIMARY KEY (id),
    CONSTRAINT uq_sp_batches_uid UNIQUE (uid),
    CONSTRAINT ck_sp_batches_qty CHECK (store_sku_qty >= 0 AND pharmacy_sku_qty >= 0)
);
CREATE INDEX idx_sp_batches_to_detail ON store_to_pharmacy_batches (store_to_pharmacy_to_detail_id);
CREATE INDEX idx_sp_batches_rn_detail ON store_to_pharmacy_batches (store_to_pharmacy_rn_detail_id);

-- ====================== (3) STORE -> PHARMACY RN (PGRN) ====================

CREATE TABLE store_to_pharmacy_rns (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,            -- PENDING -> COMPLETED
    status_description  VARCHAR(200),
    pharmacy_uid        VARCHAR(26)     NOT NULL,
    store_uid           VARCHAR(26)     NOT NULL,
    store_to_pharmacy_to_id BIGINT,                          -- source TO
    business_day_uid    VARCHAR(26),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_store_to_pharmacy_rns     PRIMARY KEY (id),
    CONSTRAINT uq_store_to_pharmacy_rns_uid UNIQUE (uid),
    CONSTRAINT uq_store_to_pharmacy_rns_no  UNIQUE (no),
    CONSTRAINT fk_sp_rn_to
        FOREIGN KEY (store_to_pharmacy_to_id) REFERENCES store_to_pharmacy_tos (id),
    CONSTRAINT ck_sp_rn_status CHECK (status IN ('PENDING','COMPLETED'))
);
CREATE INDEX idx_sp_rn_status ON store_to_pharmacy_rns (status);
CREATE INDEX idx_sp_rn_pharmacy ON store_to_pharmacy_rns (pharmacy_uid);

CREATE TABLE store_to_pharmacy_rn_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    store_to_pharmacy_rn_id BIGINT      NOT NULL,
    item_uid            VARCHAR(26)     NOT NULL,
    medicine_uid        VARCHAR(26)     NOT NULL,
    ordered_pharmacy_sku_qty    NUMERIC(19,6)   NOT NULL,
    received_pharmacy_sku_qty   NUMERIC(19,6)   NOT NULL DEFAULT 0,
    received_store_sku_qty      NUMERIC(19,6)   NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_sp_rn_details     PRIMARY KEY (id),
    CONSTRAINT uq_sp_rn_details_uid UNIQUE (uid),
    CONSTRAINT fk_sp_rn_details_rn
        FOREIGN KEY (store_to_pharmacy_rn_id) REFERENCES store_to_pharmacy_rns (id),
    CONSTRAINT ck_sp_rn_details_qty CHECK (
        ordered_pharmacy_sku_qty >= 0 AND received_pharmacy_sku_qty >= 0
        AND received_store_sku_qty >= 0)
);
CREATE INDEX idx_sp_rn_details_rn ON store_to_pharmacy_rn_details (store_to_pharmacy_rn_id);

-- Re-parenting FKs for the transfer batches (added after both detail tables exist).
ALTER TABLE store_to_pharmacy_batches
    ADD CONSTRAINT fk_sp_batches_to_detail
        FOREIGN KEY (store_to_pharmacy_to_detail_id) REFERENCES store_to_pharmacy_to_details (id);
ALTER TABLE store_to_pharmacy_batches
    ADD CONSTRAINT fk_sp_batches_rn_detail
        FOREIGN KEY (store_to_pharmacy_rn_detail_id) REFERENCES store_to_pharmacy_rn_details (id);
