-- =====================================================================================
-- Increment 02 — P5 CompanyProfile delta (build-spec §1.5, CR-14)
--
-- Additive ALTER on company_profiles — V1 columns (name/address/phone) are IMMUTABLE.
-- Every new column is NULLABLE (or has a DEFAULT) so the existing seeded row (V3) stays
-- valid without an UPDATE.
--
-- Legacy citations:
--   CompanyProfile.java:34-81 (05-extract-stakeholders-system §1)
--   registrationFee double → NUMERIC(19,2) (pre-approved double→BigDecimal directive)
--   employeePrefix default 'EMP' (CompanyProfile.java:80-81)
-- =====================================================================================

ALTER TABLE company_profiles
    ADD COLUMN IF NOT EXISTS contact_name          VARCHAR(200),
    ADD COLUMN IF NOT EXISTS logo                  BYTEA,
    ADD COLUMN IF NOT EXISTS tin                   VARCHAR(80),
    ADD COLUMN IF NOT EXISTS vrn                   VARCHAR(80),
    ADD COLUMN IF NOT EXISTS physical_address      VARCHAR(400),
    ADD COLUMN IF NOT EXISTS post_code             VARCHAR(20),
    ADD COLUMN IF NOT EXISTS post_address          VARCHAR(200),
    ADD COLUMN IF NOT EXISTS telephone             VARCHAR(40),
    ADD COLUMN IF NOT EXISTS mobile                VARCHAR(40),
    ADD COLUMN IF NOT EXISTS email                 VARCHAR(120),
    ADD COLUMN IF NOT EXISTS fax                   VARCHAR(40),
    ADD COLUMN IF NOT EXISTS website               VARCHAR(200),
    -- Bank block 1
    ADD COLUMN IF NOT EXISTS bank_account_name     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_physical_address VARCHAR(400),
    ADD COLUMN IF NOT EXISTS bank_post_code        VARCHAR(20),
    ADD COLUMN IF NOT EXISTS bank_post_address     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_name             VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_account_no       VARCHAR(80),
    -- Bank block 2
    ADD COLUMN IF NOT EXISTS bank_account_name2     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_physical_address2 VARCHAR(400),
    ADD COLUMN IF NOT EXISTS bank_post_code2        VARCHAR(20),
    ADD COLUMN IF NOT EXISTS bank_post_address2     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_name2             VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_account_no2       VARCHAR(80),
    -- Bank block 3
    ADD COLUMN IF NOT EXISTS bank_account_name3     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_physical_address3 VARCHAR(400),
    ADD COLUMN IF NOT EXISTS bank_post_code3        VARCHAR(20),
    ADD COLUMN IF NOT EXISTS bank_post_address3     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_name3             VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_account_no3       VARCHAR(80),
    -- Notes
    ADD COLUMN IF NOT EXISTS quotation_notes       TEXT,
    ADD COLUMN IF NOT EXISTS sales_invoice_notes   TEXT,
    -- Registration fee — NOT NULL DEFAULT 0 so the seeded row is valid without UPDATE
    ADD COLUMN IF NOT EXISTS registration_fee      NUMERIC(19,2) NOT NULL DEFAULT 0,
    -- Paths / prefixes
    ADD COLUMN IF NOT EXISTS public_path           VARCHAR(400),
    ADD COLUMN IF NOT EXISTS employee_prefix       VARCHAR(12)  DEFAULT 'EMP';
