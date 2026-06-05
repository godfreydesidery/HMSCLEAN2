-- =====================================================================================
-- Increment 08b — Chunk 7 — Pharmacy<->Pharmacy transfer chain (3 documents)
--
-- (1) pharmacy_to_pharmacy_ros (PPR)  — requesting pharmacy's requisition; moves NO stock.
-- (2) pharmacy_to_pharmacy_tos (PPTO) — delivering pharmacy's transfer order; SOURCE pharmacy stock
--     decrements at TO.issue (FEFO over the delivering pharmacy's PharmacyMedicineBatch).
-- (3) pharmacy_to_pharmacy_rns (PPRN) — requesting pharmacy's receiving note; DESTINATION pharmacy
--     stock increments at RN complete — AGGREGATE + IN card ONLY, **NO destination batch** (the
--     reproduced legacy gap, Q7 baseline; contrast with the store->pharmacy path which DOES create
--     destination batches).
--
-- 1:1 quantities (NO coefficient — both sides are pharmacies; D9). PPTO replaces the legacy 'SPT'
-- collision prefix (CR-10). Numbering via shared DocumentNumberService (seq_ppr_no/seq_ppto_no/
-- seq_pprn_no, V13). All NUMERIC(19,6). Loose cross-module uids; intra-module FK header<->detail<->batch.
--
-- Legacy citations: PharmacyToPharmacyROServiceImpl, PharmacyToPharmacyTOServiceImpl,
-- PharmacyToPharmacyRNServiceImpl, InternalOrderResource:1219-1401.
-- =====================================================================================

-- ====================== (1) PHARMACY -> PHARMACY RO (PPR) ====================

CREATE TABLE pharmacy_to_pharmacy_ros (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    status_description  VARCHAR(200),
    requesting_pharmacy_uid VARCHAR(26) NOT NULL,
    delivering_pharmacy_uid VARCHAR(26) NOT NULL,
    valid_until         DATE,
    business_day_uid    VARCHAR(26),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_ros     PRIMARY KEY (id),
    CONSTRAINT uq_pp_ros_uid UNIQUE (uid),
    CONSTRAINT uq_pp_ros_no  UNIQUE (no),
    CONSTRAINT ck_pp_ro_status CHECK (status IN
        ('PENDING','VERIFIED','APPROVED','SUBMITTED','IN-PROCESS','GOODS-ISSUED','COMPLETED','RETURNED','REJECTED'))
);
CREATE INDEX idx_pp_ro_status ON pharmacy_to_pharmacy_ros (status);
CREATE INDEX idx_pp_ro_requesting ON pharmacy_to_pharmacy_ros (requesting_pharmacy_uid);

CREATE TABLE pharmacy_to_pharmacy_ro_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    pharmacy_to_pharmacy_ro_id BIGINT   NOT NULL,
    medicine_uid        VARCHAR(26)     NOT NULL,
    ordered_qty         NUMERIC(19,6)   NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_ro_details     PRIMARY KEY (id),
    CONSTRAINT uq_pp_ro_details_uid UNIQUE (uid),
    CONSTRAINT fk_pp_ro_details_ro
        FOREIGN KEY (pharmacy_to_pharmacy_ro_id) REFERENCES pharmacy_to_pharmacy_ros (id),
    CONSTRAINT ck_pp_ro_details_qty CHECK (ordered_qty >= 0)
);
CREATE INDEX idx_pp_ro_details_ro ON pharmacy_to_pharmacy_ro_details (pharmacy_to_pharmacy_ro_id);

-- ====================== (2) PHARMACY -> PHARMACY TO (PPTO) ====================

CREATE TABLE pharmacy_to_pharmacy_tos (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    status_description  VARCHAR(200),
    requesting_pharmacy_uid VARCHAR(26) NOT NULL,
    delivering_pharmacy_uid VARCHAR(26) NOT NULL,
    pharmacy_to_pharmacy_ro_id BIGINT,
    business_day_uid    VARCHAR(26),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_tos     PRIMARY KEY (id),
    CONSTRAINT uq_pp_tos_uid UNIQUE (uid),
    CONSTRAINT uq_pp_tos_no  UNIQUE (no),
    CONSTRAINT fk_pp_to_ro
        FOREIGN KEY (pharmacy_to_pharmacy_ro_id) REFERENCES pharmacy_to_pharmacy_ros (id),
    CONSTRAINT ck_pp_to_status CHECK (status IN
        ('PENDING','VERIFIED','APPROVED','GOODS-ISSUED','COMPLETED'))
);
CREATE INDEX idx_pp_to_status ON pharmacy_to_pharmacy_tos (status);

CREATE TABLE pharmacy_to_pharmacy_to_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    pharmacy_to_pharmacy_to_id BIGINT   NOT NULL,
    medicine_uid        VARCHAR(26)     NOT NULL,
    ordered_qty         NUMERIC(19,6)   NOT NULL,
    transfered_qty      NUMERIC(19,6)   NOT NULL DEFAULT 0,    -- 1:1 (no coefficient)
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_to_details     PRIMARY KEY (id),
    CONSTRAINT uq_pp_to_details_uid UNIQUE (uid),
    CONSTRAINT fk_pp_to_details_to
        FOREIGN KEY (pharmacy_to_pharmacy_to_id) REFERENCES pharmacy_to_pharmacy_tos (id),
    CONSTRAINT ck_pp_to_details_qty CHECK (ordered_qty >= 0 AND transfered_qty >= 0)
);
CREATE INDEX idx_pp_to_details_to ON pharmacy_to_pharmacy_to_details (pharmacy_to_pharmacy_to_id);

-- Transfer trace batches (display/traceability only; NOT promoted to destination PharmacyMedicineBatch).
CREATE TABLE pharmacy_to_pharmacy_batches (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    pharmacy_to_pharmacy_to_detail_id BIGINT,
    pharmacy_to_pharmacy_rn_detail_id BIGINT,
    batch_no            VARCHAR(100)    NOT NULL,
    manufactured_date   DATE,
    expiry_date         DATE,
    qty                 NUMERIC(19,6)   NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_batches     PRIMARY KEY (id),
    CONSTRAINT uq_pp_batches_uid UNIQUE (uid),
    CONSTRAINT ck_pp_batches_qty CHECK (qty >= 0)
);
CREATE INDEX idx_pp_batches_to_detail ON pharmacy_to_pharmacy_batches (pharmacy_to_pharmacy_to_detail_id);
CREATE INDEX idx_pp_batches_rn_detail ON pharmacy_to_pharmacy_batches (pharmacy_to_pharmacy_rn_detail_id);

-- ====================== (3) PHARMACY -> PHARMACY RN (PPRN) ====================

CREATE TABLE pharmacy_to_pharmacy_rns (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    no                  VARCHAR(40)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,            -- PENDING -> COMPLETED
    status_description  VARCHAR(200),
    requesting_pharmacy_uid VARCHAR(26) NOT NULL,
    delivering_pharmacy_uid VARCHAR(26) NOT NULL,
    pharmacy_to_pharmacy_to_id BIGINT,
    business_day_uid    VARCHAR(26),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_rns     PRIMARY KEY (id),
    CONSTRAINT uq_pp_rns_uid UNIQUE (uid),
    CONSTRAINT uq_pp_rns_no  UNIQUE (no),
    CONSTRAINT fk_pp_rn_to
        FOREIGN KEY (pharmacy_to_pharmacy_to_id) REFERENCES pharmacy_to_pharmacy_tos (id),
    CONSTRAINT ck_pp_rn_status CHECK (status IN ('PENDING','COMPLETED'))
);
CREATE INDEX idx_pp_rn_status ON pharmacy_to_pharmacy_rns (status);
CREATE INDEX idx_pp_rn_requesting ON pharmacy_to_pharmacy_rns (requesting_pharmacy_uid);

CREATE TABLE pharmacy_to_pharmacy_rn_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,
    pharmacy_to_pharmacy_rn_id BIGINT   NOT NULL,
    medicine_uid        VARCHAR(26)     NOT NULL,
    ordered_qty         NUMERIC(19,6)   NOT NULL,
    received_qty        NUMERIC(19,6)   NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80),
    updated_by VARCHAR(80), version BIGINT NOT NULL,
    CONSTRAINT pk_pp_rn_details     PRIMARY KEY (id),
    CONSTRAINT uq_pp_rn_details_uid UNIQUE (uid),
    CONSTRAINT fk_pp_rn_details_rn
        FOREIGN KEY (pharmacy_to_pharmacy_rn_id) REFERENCES pharmacy_to_pharmacy_rns (id),
    CONSTRAINT ck_pp_rn_details_qty CHECK (ordered_qty >= 0 AND received_qty >= 0)
);
CREATE INDEX idx_pp_rn_details_rn ON pharmacy_to_pharmacy_rn_details (pharmacy_to_pharmacy_rn_id);

ALTER TABLE pharmacy_to_pharmacy_batches
    ADD CONSTRAINT fk_pp_batches_to_detail
        FOREIGN KEY (pharmacy_to_pharmacy_to_detail_id) REFERENCES pharmacy_to_pharmacy_to_details (id);
ALTER TABLE pharmacy_to_pharmacy_batches
    ADD CONSTRAINT fk_pp_batches_rn_detail
        FOREIGN KEY (pharmacy_to_pharmacy_rn_detail_id) REFERENCES pharmacy_to_pharmacy_rn_details (id);
