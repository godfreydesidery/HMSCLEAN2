-- =====================================================================================
-- Increment 02 — P1 Organizational Units schema (build-spec §1.1, 07-design-schema §2.1)
--
-- Legacy citations:
--   Clinic.java:43-55, Ward.java:39-57, WardType.java:31-40, WardCategory.java:38-45,
--   WardBed.java:38-49, Pharmacy.java:35-47, Store.java:37-49, Theatre.java:30-39
--
-- Conventions (DIRECTIVE 2 — identical to V1):
--   id     BIGINT GENERATED ALWAYS AS IDENTITY   (internal, never exposed)
--   uid    VARCHAR(26) NOT NULL UNIQUE             (public ULID, ADR-0003, CR-02)
--   audit: created_at/updated_at (TIMESTAMPTZ), created_by/updated_by (VARCHAR(80)), version BIGINT
--   money  NUMERIC(19,2); constraint naming pk_/fk_/uq_/idx_/ck_
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- clinics
-- Cash consultation fee stays ON the clinic row (legacy Clinic.consultationFee).
-- NO clinic_type column — CR-17: no ClinicType exists in legacy (01-extract-org-units §Clinic).
-- -------------------------------------------------------------------------------------
CREATE TABLE clinics (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    uid              VARCHAR(26)     NOT NULL,
    code             VARCHAR(40)     NOT NULL,
    name             VARCHAR(200)    NOT NULL,
    description      TEXT,
    consultation_fee NUMERIC(19,2)   NOT NULL DEFAULT 0,
    active           BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ     NOT NULL,
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(80),
    updated_by       VARCHAR(80),
    version          BIGINT          NOT NULL,
    CONSTRAINT pk_clinics          PRIMARY KEY (id),
    CONSTRAINT uq_clinics_uid      UNIQUE (uid),
    CONSTRAINT uq_clinics_code     UNIQUE (code),
    CONSTRAINT uq_clinics_name     UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- ward_types
-- Cash per-stay ward charge lives on the WardType row (legacy WardType.price).
-- CR-12: no per-ward price override — WardType-only pricing (build-spec §1.1, §2.2).
-- -------------------------------------------------------------------------------------
CREATE TABLE ward_types (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    price       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_ward_types      PRIMARY KEY (id),
    CONSTRAINT uq_ward_types_uid  UNIQUE (uid),
    CONSTRAINT uq_ward_types_code UNIQUE (code),
    CONSTRAINT uq_ward_types_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- ward_categories
-- Descriptive grouping only — NO price column (legacy WardCategory has none).
-- -------------------------------------------------------------------------------------
CREATE TABLE ward_categories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_ward_categories      PRIMARY KEY (id),
    CONSTRAINT uq_ward_categories_uid  UNIQUE (uid),
    CONSTRAINT uq_ward_categories_code UNIQUE (code),
    CONSTRAINT uq_ward_categories_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- wards
-- FKs to ward_categories and ward_types, both NOT NULL (legacy optional=false).
-- NO per-ward price column (CR-12 / legacy: Ward has no price field).
-- @OnDelete NO_ACTION in legacy → no CASCADE rule here.
-- -------------------------------------------------------------------------------------
CREATE TABLE wards (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    uid              VARCHAR(26)     NOT NULL,
    code             VARCHAR(40)     NOT NULL,
    name             VARCHAR(200)    NOT NULL,
    no_of_beds       INTEGER         NOT NULL DEFAULT 0,
    active           BOOLEAN         NOT NULL DEFAULT FALSE,
    ward_category_id BIGINT          NOT NULL,
    ward_type_id     BIGINT          NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL,
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(80),
    updated_by       VARCHAR(80),
    version          BIGINT          NOT NULL,
    CONSTRAINT pk_wards                 PRIMARY KEY (id),
    CONSTRAINT uq_wards_uid             UNIQUE (uid),
    CONSTRAINT uq_wards_code            UNIQUE (code),
    CONSTRAINT uq_wards_name            UNIQUE (name),
    CONSTRAINT fk_wards_ward_category   FOREIGN KEY (ward_category_id) REFERENCES ward_categories (id),
    CONSTRAINT fk_wards_ward_type       FOREIGN KEY (ward_type_id)     REFERENCES ward_types (id)
);
CREATE INDEX idx_wards_ward_category ON wards (ward_category_id);
CREATE INDEX idx_wards_ward_type     ON wards (ward_type_id);

-- -------------------------------------------------------------------------------------
-- ward_beds
-- Physical bed master. `no` is NOT unique (CR-16 / legacy WardBed.no has no unique constraint).
-- ward_id NOT NULL, updatable=false in legacy (join column updatable=false).
-- -------------------------------------------------------------------------------------
CREATE TABLE ward_beds (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    uid        VARCHAR(26)     NOT NULL,
    no         VARCHAR(40)     NOT NULL,
    status     VARCHAR(40),
    active     BOOLEAN         NOT NULL DEFAULT FALSE,
    ward_id    BIGINT          NOT NULL,
    created_at TIMESTAMPTZ     NOT NULL,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(80),
    updated_by VARCHAR(80),
    version    BIGINT          NOT NULL,
    CONSTRAINT pk_ward_beds      PRIMARY KEY (id),
    CONSTRAINT uq_ward_beds_uid  UNIQUE (uid),
    CONSTRAINT fk_ward_beds_ward FOREIGN KEY (ward_id) REFERENCES wards (id)
    -- DECISION FLAG (CR-16): do NOT add uq_ward_beds_ward_no — not unique in legacy.
);
CREATE INDEX idx_ward_beds_ward ON ward_beds (ward_id);

-- -------------------------------------------------------------------------------------
-- pharmacies
-- category is free-text (no enum/FK) — legacy Pharmacy.category VARCHAR.
-- -------------------------------------------------------------------------------------
CREATE TABLE pharmacies (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    location    VARCHAR(200),
    category    VARCHAR(80),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_pharmacies      PRIMARY KEY (id),
    CONSTRAINT uq_pharmacies_uid  UNIQUE (uid),
    CONSTRAINT uq_pharmacies_code UNIQUE (code),
    CONSTRAINT uq_pharmacies_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- stores
-- Same shape as pharmacies (legacy Store mirrors Pharmacy field-for-field).
-- -------------------------------------------------------------------------------------
CREATE TABLE stores (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    location    VARCHAR(200),
    category    VARCHAR(80),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_stores      PRIMARY KEY (id),
    CONSTRAINT uq_stores_uid  UNIQUE (uid),
    CONSTRAINT uq_stores_code UNIQUE (code),
    CONSTRAINT uq_stores_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- theatres
-- legacy Theatre.java:30-39: code(uq,nn), name(uq,nn), description, location, active.
-- -------------------------------------------------------------------------------------
CREATE TABLE theatres (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    location    VARCHAR(200),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_theatres      PRIMARY KEY (id),
    CONSTRAINT uq_theatres_uid  UNIQUE (uid),
    CONSTRAINT uq_theatres_code UNIQUE (code),
    CONSTRAINT uq_theatres_name UNIQUE (name)
);
