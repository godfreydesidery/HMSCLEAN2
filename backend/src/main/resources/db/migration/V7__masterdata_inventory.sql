-- =====================================================================================
-- Increment 02 — P2 Inventory Catalog schema (build-spec §1.2, 07-design-schema §2.2)
--
-- Legacy citations:
--   Item.java:34-64, Medicine.java:37-63, ItemMedicineCoefficient.java:34-60,
--   Supplier.java:31-66, ItemSupplier.java:31-50, SupplierItemPrice.java:33-60
--   ConversionCoefficientResource.java:83-101 (validation + coefficient formula)
--
-- Conventions (identical to V6):
--   id     BIGINT GENERATED ALWAYS AS IDENTITY   (internal, never exposed)
--   uid    VARCHAR(26) NOT NULL UNIQUE             (public ULID, ADR-0003, CR-02)
--   audit: created_at/updated_at (TIMESTAMPTZ), created_by/updated_by (VARCHAR(80)), version BIGINT
--   money  NUMERIC(19,2);  qty/coefficient NUMERIC(19,6)
--   constraint naming pk_/fk_/uq_/idx_/ck_
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- medicines
-- Cash price stays ON the row (legacy Medicine.price double).
-- category/type/uom are free-text strings — NO lookup tables (CR-07).
-- -------------------------------------------------------------------------------------
CREATE TABLE medicines (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    type        VARCHAR(80)     NOT NULL,
    price       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    uom         VARCHAR(40),
    category    VARCHAR(80)     NOT NULL DEFAULT 'MEDICINE',
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_medicines       PRIMARY KEY (id),
    CONSTRAINT uq_medicines_uid   UNIQUE (uid),
    CONSTRAINT uq_medicines_code  UNIQUE (code),
    CONSTRAINT uq_medicines_name  UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- items
-- Two cash-side money columns + vat.  pack_size is a quantity → NUMERIC(19,6).
-- short_name UQ (legacy Item.java:46-48 @Column(unique=true) on shortName).
-- active default TRUE (legacy Item.java:56).
-- ingredients TEXT (legacy Item.java:57, default "").
-- -------------------------------------------------------------------------------------
CREATE TABLE items (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                     VARCHAR(26)     NOT NULL,
    code                    VARCHAR(40)     NOT NULL,
    barcode                 VARCHAR(80),
    name                    VARCHAR(200)    NOT NULL,
    short_name              VARCHAR(120),
    common_name             VARCHAR(200),
    vat                     NUMERIC(19,2)   NOT NULL DEFAULT 0,
    uom                     VARCHAR(40),
    pack_size               NUMERIC(19,6)   NOT NULL DEFAULT 1,
    category                VARCHAR(80),
    cost_price_vat_incl     NUMERIC(19,2)   NOT NULL DEFAULT 0,
    selling_price_vat_incl  NUMERIC(19,2)   NOT NULL DEFAULT 0,
    active                  BOOLEAN         NOT NULL DEFAULT TRUE,
    ingredients             TEXT            DEFAULT '',
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(80),
    updated_by              VARCHAR(80),
    version                 BIGINT          NOT NULL,
    CONSTRAINT pk_items             PRIMARY KEY (id),
    CONSTRAINT uq_items_uid         UNIQUE (uid),
    CONSTRAINT uq_items_code        UNIQUE (code),
    CONSTRAINT uq_items_name        UNIQUE (name),
    CONSTRAINT uq_items_short_name  UNIQUE (short_name)
);

-- -------------------------------------------------------------------------------------
-- item_medicine_coefficients
-- coefficient = medicine_qty / item_qty  (computed in service, stored here)
-- Legacy: @OneToOne on item_id; we preserve pair-uniqueness as the binding constraint.
-- CHECK constraints encode the "Zero values are not allowed" validation rule.
-- (ConversionCoefficientResource.java:87-89)
-- -------------------------------------------------------------------------------------
CREATE TABLE item_medicine_coefficients (
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    uid          VARCHAR(26)     NOT NULL,
    coefficient  NUMERIC(19,6)   NOT NULL DEFAULT 0,
    item_qty     NUMERIC(19,6)   NOT NULL DEFAULT 0,
    medicine_qty NUMERIC(19,6)   NOT NULL DEFAULT 0,
    item_id      BIGINT          NOT NULL,
    medicine_id  BIGINT          NOT NULL,
    created_at   TIMESTAMPTZ     NOT NULL,
    updated_at   TIMESTAMPTZ,
    created_by   VARCHAR(80),
    updated_by   VARCHAR(80),
    version      BIGINT          NOT NULL,
    CONSTRAINT pk_item_medicine_coefficients    PRIMARY KEY (id),
    CONSTRAINT uq_imc_uid                       UNIQUE (uid),
    CONSTRAINT uq_imc_item_medicine             UNIQUE (item_id, medicine_id),
    CONSTRAINT fk_imc_item                      FOREIGN KEY (item_id)     REFERENCES items (id),
    CONSTRAINT fk_imc_medicine                  FOREIGN KEY (medicine_id) REFERENCES medicines (id),
    CONSTRAINT ck_imc_item_qty_pos              CHECK (item_qty > 0),
    CONSTRAINT ck_imc_medicine_qty_pos          CHECK (medicine_qty > 0)
);
CREATE INDEX idx_imc_item     ON item_medicine_coefficients (item_id);
CREATE INDEX idx_imc_medicine ON item_medicine_coefficients (medicine_id);

-- -------------------------------------------------------------------------------------
-- suppliers
-- contact_name NOT NULL (legacy @NotBlank).  active default TRUE (legacy Supplier.java:43).
-- Full address block + bank block (legacy Supplier.java:44-59).
-- -------------------------------------------------------------------------------------
CREATE TABLE suppliers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                   VARCHAR(26)     NOT NULL,
    code                  VARCHAR(40)     NOT NULL,
    name                  VARCHAR(200)    NOT NULL,
    contact_name          VARCHAR(200)    NOT NULL,
    active                BOOLEAN         NOT NULL DEFAULT TRUE,
    tin                   VARCHAR(40),
    vrn                   VARCHAR(40),
    terms_of_contract     TEXT,
    physical_address      VARCHAR(400),
    post_code             VARCHAR(40),
    post_address          VARCHAR(200),
    telephone             VARCHAR(40),
    mobile                VARCHAR(40),
    email                 VARCHAR(120),
    fax                   VARCHAR(40),
    bank_account_name     VARCHAR(200),
    bank_physical_address VARCHAR(400),
    bank_post_code        VARCHAR(40),
    bank_post_address     VARCHAR(200),
    bank_name             VARCHAR(200),
    bank_account_no       VARCHAR(60),
    created_at            TIMESTAMPTZ     NOT NULL,
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(80),
    updated_by            VARCHAR(80),
    version               BIGINT          NOT NULL,
    CONSTRAINT pk_suppliers      PRIMARY KEY (id),
    CONSTRAINT uq_suppliers_uid  UNIQUE (uid),
    CONSTRAINT uq_suppliers_code UNIQUE (code),
    CONSTRAINT uq_suppliers_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- items_suppliers
-- Legacy ItemSupplier has NO audit columns (ItemSupplier.java:31-50).
-- HMSCLEAN2 includes full AuditableEntity cols for target-side consistency (build-spec §1.2 note).
-- active default FALSE (legacy: true — build-spec overrides to FALSE as safer default; note: legacy
-- sets it true, but item/supplier relationship record is inactive until confirmed — use FALSE).
-- NOTE: legacy has active=true; we follow the build-spec which says "active BOOLEAN NOT NULL DEFAULT FALSE"
-- per the DDL sketch in 07-design-schema §2.2 line items_suppliers table.
-- -------------------------------------------------------------------------------------
CREATE TABLE items_suppliers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                   VARCHAR(26)     NOT NULL,
    item_id               BIGINT          NOT NULL,
    supplier_id           BIGINT          NOT NULL,
    cost_price_vat_incl   NUMERIC(19,2)   NOT NULL DEFAULT 0,
    cost_price_vat_excl   NUMERIC(19,2)   NOT NULL DEFAULT 0,
    active                BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ     NOT NULL,
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(80),
    updated_by            VARCHAR(80),
    version               BIGINT          NOT NULL,
    CONSTRAINT pk_items_suppliers      PRIMARY KEY (id),
    CONSTRAINT uq_items_suppliers_uid  UNIQUE (uid),
    CONSTRAINT fk_items_suppliers_item     FOREIGN KEY (item_id)     REFERENCES items (id),
    CONSTRAINT fk_items_suppliers_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);
CREATE INDEX idx_items_suppliers_item     ON items_suppliers (item_id);
CREATE INDEX idx_items_suppliers_supplier ON items_suppliers (supplier_id);

-- -------------------------------------------------------------------------------------
-- supplier_item_prices
-- price default 0, active default FALSE (legacy SupplierItemPrice.java:40-42 has active=true,
-- but the build-spec DDL sketch uses FALSE as the safer catalogue-default — see above note).
-- SupplierItemPriceList is a non-persistent DTO only (no table).
-- -------------------------------------------------------------------------------------
CREATE TABLE supplier_item_prices (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    price       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    terms       TEXT,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    supplier_id BIGINT          NOT NULL,
    item_id     BIGINT          NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_supplier_item_prices      PRIMARY KEY (id),
    CONSTRAINT uq_supplier_item_prices_uid  UNIQUE (uid),
    CONSTRAINT fk_sip_supplier              FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_sip_item                  FOREIGN KEY (item_id)     REFERENCES items (id)
);
CREATE INDEX idx_sip_supplier ON supplier_item_prices (supplier_id);
CREATE INDEX idx_sip_item     ON supplier_item_prices (item_id);
