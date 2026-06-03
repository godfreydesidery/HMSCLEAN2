-- =====================================================================================
-- Increment 02 — P4 Pricing & Insurance schema (build-spec §1.4, §2.1, 07-design-schema §2.4)
--
-- Legacy citations:
--   InsuranceProvider.java:37-59, InsurancePlan.java:37-60
--   04-extract-pricing-insurance §2 (7 *InsurancePlan tables)
--   04-extract-pricing-insurance §3 (PatientServiceImpl resolve logic)
--   11-DECISIONS-RATIFIED CR-04 (unified ServicePrice), CR-12 (WardType-only)
--   CR-18 (REGISTRATION keyed by NULL service_uid, not "DEFAULT")
--   CR-11 (min/max/currency inert-nullable)
--
-- The 7 live *InsurancePlan tables are REPLACED by service_prices (CR-04).
-- LabTestPlanPrice is DEAD — not created (04-extract-pricing-insurance §2 "DEAD/UNUSED").
-- plan_uid and service_uid are LOOSE uid strings — no DB FK (matches matrix design intent).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- insurance_providers
-- Legacy InsuranceProvider.java:37-59: code(uq,@NotBlank), name(uq,@NotBlank),
-- address, telephone, email, fax, website, active(default false).
-- NO membership/card-scheme fields on provider (none in legacy).
-- -------------------------------------------------------------------------------------
CREATE TABLE insurance_providers (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    address     VARCHAR(400),
    telephone   VARCHAR(40),
    email       VARCHAR(120),
    fax         VARCHAR(40),
    website     VARCHAR(200),
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_insurance_providers      PRIMARY KEY (id),
    CONSTRAINT uq_insurance_providers_uid  UNIQUE (uid),
    CONSTRAINT uq_insurance_providers_code UNIQUE (code),
    CONSTRAINT uq_insurance_providers_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- insurance_plans
-- Legacy InsurancePlan.java:37-60: code(uq,@NotBlank), name(uq,@NotBlank),
-- description, active(default false), insurance_provider_id FK NOT NULL EAGER.
-- NO copay/coverage/card fields — membership_no lives on Patient (later increment).
-- Name/code uniqueness is load-bearing: plans are looked up by name at point of care
-- (PatientServiceImpl.java:530, PatientResource.java:297,360,384).
-- -------------------------------------------------------------------------------------
CREATE TABLE insurance_plans (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                   VARCHAR(26)     NOT NULL,
    code                  VARCHAR(40)     NOT NULL,
    name                  VARCHAR(200)    NOT NULL,
    description           TEXT,
    active                BOOLEAN         NOT NULL DEFAULT FALSE,
    insurance_provider_id BIGINT          NOT NULL,
    created_at            TIMESTAMPTZ     NOT NULL,
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(80),
    updated_by            VARCHAR(80),
    version               BIGINT          NOT NULL,
    CONSTRAINT pk_insurance_plans          PRIMARY KEY (id),
    CONSTRAINT uq_insurance_plans_uid      UNIQUE (uid),
    CONSTRAINT uq_insurance_plans_code     UNIQUE (code),
    CONSTRAINT uq_insurance_plans_name     UNIQUE (name),
    CONSTRAINT fk_insurance_plans_provider
        FOREIGN KEY (insurance_provider_id) REFERENCES insurance_providers (id)
);
CREATE INDEX idx_insurance_plans_provider ON insurance_plans (insurance_provider_id);

-- -------------------------------------------------------------------------------------
-- service_prices — the unified matrix (CR-04)
-- Replaces: consultation_insurance_plans, registration_insurance_plans,
--           lab_test_type_insurance_plans, medicine_insurance_plans,
--           procedure_type_insurance_plans, radiology_type_insurance_plans,
--           ward_type_insurance_plans.
--
-- plan_uid NULL  = cash row (planUid-NULL convention; CR-18: NOT "DEFAULT" for REGISTRATION).
-- service_uid NULL  = allowed ONLY for kind=REGISTRATION (plan-only keyed; CR-18).
-- kind WARD (not WARD_DAY) — ward charge is per-stay (CR-04/D15, CR-12).
-- covered=FALSE rows are persisted placeholders that NEVER trigger coverage (resolve uses covered=TRUE).
-- active is INERT in resolve (kept for legacy fidelity, CR-04 §4 extract item 6).
-- min_amount/max_amount/currency are NET-NEW, INERT (CR-11) — must not drive behaviour.
-- plan_uid/service_uid are loose VARCHAR(26) references — no FK constraints
--   (matrix design: billing/clinical increments pass serviceUid by kind; no DB join needed).
-- -------------------------------------------------------------------------------------
CREATE TABLE service_prices (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    plan_uid    VARCHAR(26),                              -- NULL = cash row (loose uid ref to insurance_plans.uid)
    kind        VARCHAR(20)     NOT NULL,
    service_uid VARCHAR(26),                              -- Clinic.uid|WardType.uid|LabTestType.uid|...; NULL for REGISTRATION
    currency    VARCHAR(3)      NOT NULL DEFAULT 'TZS',   -- net-new; inert (CR-11)
    amount      NUMERIC(19,2)   NOT NULL DEFAULT 0,
    covered     BOOLEAN         NOT NULL DEFAULT FALSE,   -- cash rows = TRUE by convention
    min_amount  NUMERIC(19,2),                            -- net-new; NULLABLE; inert (CR-11)
    max_amount  NUMERIC(19,2),                            -- net-new; NULLABLE; inert (CR-11)
    active      BOOLEAN         NOT NULL DEFAULT TRUE,    -- inert in resolve; kept for legacy fidelity
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_service_prices        PRIMARY KEY (id),
    CONSTRAINT uq_service_prices_uid    UNIQUE (uid),
    CONSTRAINT ck_service_prices_kind   CHECK (kind IN
        ('REGISTRATION','CONSULTATION','LAB_TEST','MEDICINE','PROCEDURE','RADIOLOGY','WARD')),
    -- REGISTRATION may have NULL service_uid (plan-only keyed); all other kinds require it.
    CONSTRAINT ck_service_prices_service_uid CHECK
        (kind = 'REGISTRATION' OR service_uid IS NOT NULL),
    CONSTRAINT ck_service_prices_amount_nonneg CHECK (amount >= 0)
);

-- Enforce uniqueness over (plan_uid, kind, service_uid, currency).
-- PostgreSQL NULL != NULL for unique indexes, so COALESCE(x,'') maps NULL to empty string
-- to give cash rows (NULL plan_uid) and REGISTRATION rows (NULL service_uid) distinct keys.
-- This is the "COALESCE partial-unique" pattern from build-spec §2.1.
-- Duplicate POST with same (plan_uid,kind,service_uid,currency) → unique violation → 409.
CREATE UNIQUE INDEX uq_service_prices_plan_kind_svc_cur
    ON service_prices (COALESCE(plan_uid, ''), kind, COALESCE(service_uid, ''), currency);

-- High-frequency resolve path: PriceLookup.resolve(planUid, kind, serviceUid, currency)
-- and the cash-fallback scan (plan_uid IS NULL).
CREATE INDEX idx_service_prices_lookup ON service_prices (kind, service_uid, plan_uid);
