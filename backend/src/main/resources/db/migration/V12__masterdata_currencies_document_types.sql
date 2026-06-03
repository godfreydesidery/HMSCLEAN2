-- =====================================================================================
-- Increment 02 — P5 MdCurrency + MdDocumentType schema (build-spec §1.5, CR-09, CR-10)
--
-- md_currencies: single-default partial-unique index enforces at most one default row.
-- md_document_types: kind UQ, prefix NOT NULL — no legacy source (03-extract §3),
--   prefixes are config driven in HMSCLEAN2.
--
-- Legacy citations:
--   05-extract-stakeholders-system §2 (currency: does NOT exist as modeled concept)
--   05-extract-stakeholders-system §3 (document_type: does NOT exist — hardcoded literals)
--   05-extract-stakeholders-system §4 (prefix table, CR-09 adopts DB sequences)
--   CR-10: SPTO/PPTO fix replacing legacy SPT collision defect
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- md_currencies
-- Legacy has NO Currency concept; this is a net-new system config table.
-- Partial unique index: at most one row with is_default = TRUE.
-- -------------------------------------------------------------------------------------
CREATE TABLE md_currencies (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(3)   NOT NULL,
    name        VARCHAR(120) NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    CONSTRAINT pk_md_currencies      PRIMARY KEY (id),
    CONSTRAINT uq_md_currencies_uid  UNIQUE (uid),
    CONSTRAINT uq_md_currencies_code UNIQUE (code)
);

-- At most one default currency at any time (partial unique index)
CREATE UNIQUE INDEX uq_md_currencies_default ON md_currencies (is_default) WHERE is_default = TRUE;

-- -------------------------------------------------------------------------------------
-- md_document_types
-- No legacy entity — HMSCLEAN2 config table for document prefix registry (CR-09).
-- kind: machine-readable document category name (e.g. GOODS_RECEIVED_NOTE).
-- prefix: the short string prefix emitted on every document number of that kind.
-- -------------------------------------------------------------------------------------
CREATE TABLE md_document_types (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    kind        VARCHAR(60)  NOT NULL,
    prefix      VARCHAR(12)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    CONSTRAINT pk_md_document_types      PRIMARY KEY (id),
    CONSTRAINT uq_md_document_types_uid  UNIQUE (uid),
    CONSTRAINT uq_md_document_types_kind UNIQUE (kind)
);
