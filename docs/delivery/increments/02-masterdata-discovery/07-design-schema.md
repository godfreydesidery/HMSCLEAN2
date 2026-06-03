I now have everything needed. The existing `company_profiles` table (V1) is the minimal inc-00 shape (name/address/phone). The legacy CompanyProfile is far richer. I have the full picture. Let me produce the complete design.

I have all the information needed to produce the schema design, DDL sketches, migration breakdown, and deviation list.

---

# Increment 02 — Masterdata Schema & Migration Design (Flyway V6+)

Authoritative DDL sketch + migration breakdown for the `masterdata` module on the existing plural baseline. **Existing state: V1–V5 applied; `seq_usr_no` already exists (V4:31). NEXT migration = V6.** Conventions inherited from V1: `id BIGINT GENERATED ALWAYS AS IDENTITY` (hidden), `uid VARCHAR(26) NOT NULL UNIQUE`, audit columns `created_at TIMESTAMPTZ NOT NULL / updated_at / created_by / updated_by / version BIGINT NOT NULL`, money `NUMERIC(19,2)`, qty/coefficient `NUMERIC(19,6)`, constraint naming `pk_/fk_/uq_/idx_/ck_`.

---

## 1. MIGRATION FILE BREAKDOWN

| File | Purpose |
|---|---|
| `V6__masterdata_schema.sql` | All masterdata DDL: org units, inventory catalog, clinical catalog, stakeholders, ServicePrice, supporting tables. Pure DDL — no data. |
| `V7__masterdata_document_sequences.sql` | Document-number `SEQUENCE` objects (seq_grn_no … excluding seq_usr_no). |
| `V8__masterdata_company_profile_delta.sql` | ALTER `company_profiles` to add the full legacy CompanyProfile columns (additive; V1 created the minimal shape). |
| `V9__seed_md_document_type.sql` | `md_document_type` prefix seed (SPTO/PPTO + all real legacy prefixes — flagged below). |
| `V10__seed_md_currency.sql` | Single default currency seed (net-new; flagged). |
| `V11__seed_company_profile_delta.sql` | Backfill legacy CompanyProfile field values onto the existing seeded row (idempotent UPDATE). |
| `V12+__seed_<context>.sql` | Per-context legacy data seeds (clinics, wards, medicines, items, lab/rad/proc/diagnosis types, suppliers, insurance providers/plans, ServicePrice rows). One file per context for reviewable diffs; **counts verified against legacy MySQL extract per §6.** |

DDL (V6–V8) is one transactional unit per file. Seeds (V9+) split per context so data-migration-engineer reconciliation runs per file. **All seeds use `INSERT … ON CONFLICT (uid) DO NOTHING`** for idempotency.

---

## 2. DDL SKETCHES — `V6__masterdata_schema.sql`

Every table carries the standard column block (abbreviated below as `<audit>` = `created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ, created_by VARCHAR(80), updated_by VARCHAR(80), version BIGINT NOT NULL`) and `id BIGINT GENERATED ALWAYS AS IDENTITY` + `uid VARCHAR(26) NOT NULL` with `pk_<t> PRIMARY KEY (id)` and `uq_<t>_uid UNIQUE (uid)`.

### 2.1 Organizational units

```sql
-- clinics: cash consultation fee stays ON the clinic row (legacy Clinic.consultationFee).
CREATE TABLE clinics (
    id BIGINT GENERATED ALWAYS AS IDENTITY, uid VARCHAR(26) NOT NULL,
    code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL,
    description TEXT,
    consultation_fee NUMERIC(19,2) NOT NULL DEFAULT 0,   -- legacy double, @NotNull
    active BOOLEAN NOT NULL DEFAULT FALSE,
    <audit>,
    CONSTRAINT pk_clinics PRIMARY KEY (id),
    CONSTRAINT uq_clinics_uid UNIQUE (uid),
    CONSTRAINT uq_clinics_code UNIQUE (code),
    CONSTRAINT uq_clinics_name UNIQUE (name)
);
-- NO clinic_type column / NO ClinicType enum (legacy has none — DEVIATION D1).

CREATE TABLE ward_types (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
    price NUMERIC(19,2) NOT NULL DEFAULT 0,   -- the cash per-stay ward charge
    <audit>, pk_ward_types, uq_ward_types_uid, uq_ward_types_code, uq_ward_types_name );

CREATE TABLE ward_categories (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
    <audit>, pk/uq...);   -- NO price (descriptive grouping only)

CREATE TABLE wards (
    id ..., uid ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL,
    no_of_beds INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    ward_category_id BIGINT NOT NULL,
    ward_type_id     BIGINT NOT NULL,
    <audit>,
    CONSTRAINT pk_wards PRIMARY KEY (id), CONSTRAINT uq_wards_uid UNIQUE (uid),
    CONSTRAINT uq_wards_code UNIQUE (code), CONSTRAINT uq_wards_name UNIQUE (name),
    CONSTRAINT fk_wards_ward_category FOREIGN KEY (ward_category_id) REFERENCES ward_categories (id),
    CONSTRAINT fk_wards_ward_type     FOREIGN KEY (ward_type_id)     REFERENCES ward_types (id)
    -- legacy @OnDelete(NO_ACTION) => NO cascade. NO per-ward price column.
);
CREATE INDEX idx_wards_ward_type     ON wards (ward_type_id);
CREATE INDEX idx_wards_ward_category ON wards (ward_category_id);

-- ward_beds: physical bed master. Legacy "no" is NOT unique (DEVIATION D6 / decision flag).
CREATE TABLE ward_beds (
    ..., no VARCHAR(40) NOT NULL, status VARCHAR(40), active BOOLEAN NOT NULL DEFAULT FALSE,
    ward_id BIGINT NOT NULL, <audit>,
    CONSTRAINT fk_ward_beds_ward FOREIGN KEY (ward_id) REFERENCES wards (id) );
CREATE INDEX idx_ward_beds_ward ON ward_beds (ward_id);
-- DECISION FLAG: do NOT add uq_ward_beds_ward_no without engagement-lead approval (behavioural tightening).

CREATE TABLE pharmacies (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL,
    description TEXT, location VARCHAR(200), category VARCHAR(80),  -- free-text
    active BOOLEAN NOT NULL DEFAULT FALSE, <audit>, uq code+name+uid );

CREATE TABLE stores ( -- same shape as pharmacies
    ..., code, name, description, location, category, active, <audit>, uq... );

CREATE TABLE theatres (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL,
    description TEXT, location VARCHAR(200), active BOOLEAN NOT NULL DEFAULT FALSE, <audit>, uq... );
```

**`AdmissionBed` and the clinic↔clinician / store↔storeperson M2M join tables are OUT OF SCOPE for masterdata V6** — AdmissionBed is an inpatient/billing transaction (later increment); the clinic↔clinician join is owned by `iam.Clinician.clinics` in legacy (V4 deferred it). Per the memory decision, a masterdata-owned `md_clinic_clinician` is net-new and relocates ownership — **flagged for solution-architect/engagement-lead, NOT created here** (contradicts inc-02 spec line 15/94 — DEVIATION D7).

### 2.2 Inventory catalog

```sql
-- medicines: cash price stays ON the row (legacy Medicine.price). category/type/uom free-text.
CREATE TABLE medicines (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
    type VARCHAR(80) NOT NULL,          -- legacy @NotBlank "ORAL, ETC" free-text
    price NUMERIC(19,2) NOT NULL DEFAULT 0,
    uom  VARCHAR(40),
    category VARCHAR(80) NOT NULL DEFAULT 'MEDICINE',   -- legacy default literal
    active BOOLEAN NOT NULL DEFAULT FALSE, <audit>,
    uq_medicines_uid, uq_medicines_code, uq_medicines_name );

-- items: TWO cash-side money fields + vat; packSize is a quantity.
CREATE TABLE items (
    ..., code VARCHAR(40) NOT NULL, barcode VARCHAR(80), name VARCHAR(200) NOT NULL,
    short_name VARCHAR(120), common_name VARCHAR(200),
    vat NUMERIC(19,2) NOT NULL DEFAULT 0,
    uom VARCHAR(40),
    pack_size NUMERIC(19,6) NOT NULL DEFAULT 1,        -- quantity => 19,6
    category VARCHAR(80),
    cost_price_vat_incl    NUMERIC(19,2) NOT NULL DEFAULT 0,
    selling_price_vat_incl NUMERIC(19,2) NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,              -- legacy default TRUE
    ingredients TEXT DEFAULT '',
    <audit>,
    uq_items_uid, uq_items_code, uq_items_name,
    CONSTRAINT uq_items_short_name UNIQUE (short_name) );  -- legacy @Column(unique=true) on shortName

-- item_medicine_coefficients: coefficient = medicine_qty / item_qty (qty>0 each).
CREATE TABLE item_medicine_coefficients (
    ..., coefficient  NUMERIC(19,6) NOT NULL DEFAULT 0,
    item_qty     NUMERIC(19,6) NOT NULL DEFAULT 0,
    medicine_qty NUMERIC(19,6) NOT NULL DEFAULT 0,
    item_id     BIGINT NOT NULL,
    medicine_id BIGINT NOT NULL,
    <audit>,
    CONSTRAINT pk_item_medicine_coefficients PRIMARY KEY (id),
    CONSTRAINT uq_imc_uid UNIQUE (uid),
    CONSTRAINT uq_imc_item_medicine UNIQUE (item_id, medicine_id),  -- one per pair (legacy rule)
    CONSTRAINT fk_imc_item     FOREIGN KEY (item_id)     REFERENCES items (id),
    CONSTRAINT fk_imc_medicine FOREIGN KEY (medicine_id) REFERENCES medicines (id),
    CONSTRAINT ck_imc_item_qty_pos     CHECK (item_qty > 0),       -- legacy "Zero values not allowed"
    CONSTRAINT ck_imc_medicine_qty_pos CHECK (medicine_qty > 0)
);
-- item_id is @OneToOne in legacy but unique enforced via the (item,medicine) pair; keeping
-- item non-unique alone preserves "many medicines could derive from one item" listing
-- (findAllByMedicine). DECISION FLAG: legacy item is @OneToOne — confirm whether item_id
-- should be globally UNIQUE. Conservative: enforce only the pair-uniqueness legacy actually relies on.

CREATE TABLE suppliers (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL,
    contact_name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    tin VARCHAR(40), vrn VARCHAR(40), terms_of_contract TEXT,
    physical_address VARCHAR(400), post_code VARCHAR(40), post_address VARCHAR(200),
    telephone VARCHAR(40), mobile VARCHAR(40), email VARCHAR(120), fax VARCHAR(40),
    bank_account_name VARCHAR(200), bank_physical_address VARCHAR(400),
    bank_post_code VARCHAR(40), bank_post_address VARCHAR(200),
    bank_name VARCHAR(200), bank_account_no VARCHAR(60),
    <audit>, uq_suppliers_uid, uq_suppliers_code, uq_suppliers_name );

-- items_suppliers: legacy has NO audit columns on this entity (asymmetry preserved).
CREATE TABLE items_suppliers (
    id BIGINT GENERATED ALWAYS AS IDENTITY, uid VARCHAR(26) NOT NULL,
    item_id BIGINT NOT NULL, supplier_id BIGINT NOT NULL,
    cost_price_vat_incl NUMERIC(19,2) NOT NULL DEFAULT 0,
    cost_price_vat_excl NUMERIC(19,2) NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_items_suppliers PRIMARY KEY (id),
    CONSTRAINT uq_items_suppliers_uid UNIQUE (uid),
    CONSTRAINT fk_items_suppliers_item     FOREIGN KEY (item_id)     REFERENCES items (id),
    CONSTRAINT fk_items_suppliers_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id) );
CREATE INDEX idx_items_suppliers_item     ON items_suppliers (item_id);
CREATE INDEX idx_items_suppliers_supplier ON items_suppliers (supplier_id);

CREATE TABLE supplier_item_prices (
    ..., price NUMERIC(19,2) NOT NULL DEFAULT 0, terms TEXT, active BOOLEAN NOT NULL DEFAULT FALSE,
    supplier_id BIGINT NOT NULL, item_id BIGINT NOT NULL, <audit>,
    fk -> suppliers, fk -> items );
-- SupplierItemPriceList is a non-persistent DTO (no table). DEVIATION/NOTE.
-- ItemSupplier vs SupplierItemPrice overlap flagged for business-analyst (which is authoritative).
```

### 2.3 Clinical catalog — LEGACY MODEL ONLY (rich analyte model is net-new — DEVIATION D3)

```sql
CREATE TABLE lab_test_types (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
    price NUMERIC(19,2) NOT NULL DEFAULT 0, uom VARCHAR(40),
    active BOOLEAN NOT NULL DEFAULT FALSE, <audit>, uq uid/code/name );

-- lab_test_type_ranges: a NAMED STRING LABEL scoped to one lab_test_type. NOTHING ELSE.
-- NO low/high/min/max/unit/sex/age/flag — legacy LabTestTypeRange has only name + FK.
CREATE TABLE lab_test_type_ranges (
    ..., name VARCHAR(200) NOT NULL DEFAULT '',
    lab_test_type_id BIGINT NOT NULL, <audit>,
    CONSTRAINT fk_lab_test_type_ranges_type FOREIGN KEY (lab_test_type_id)
        REFERENCES lab_test_types (id) ON DELETE CASCADE );  -- legacy orphanRemoval=true
CREATE INDEX idx_lab_test_type_ranges_type ON lab_test_type_ranges (lab_test_type_id);

CREATE TABLE radiology_types ( ..., code, name, description, price NUMERIC(19,2), uom, active, <audit> );
CREATE TABLE procedure_types ( ..., code, name, description, price NUMERIC(19,2), uom, active, <audit> );

-- diagnosis_types: legacy entity is DiagnosisType (NOT "Diagnosis"). NO price/uom, NO ICD, NO hierarchy.
CREATE TABLE diagnosis_types (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
    active BOOLEAN NOT NULL DEFAULT FALSE, <audit>, uq uid/code/name );
```

**NOT created (net-new in spec, no legacy source):** `lab_test_analytes`, `lab_reference_ranges`, `range_flag` enum, `dosage_forms`, `routes`, `frequencies`, `item_categories`, `units_of_measure`, `diagnoses` (ICD). Legacy dosage/route/frequency are free-text columns on `Prescription` (a later pharmacy increment), not masterdata picklist tables; category/uom are free-text strings. These are **DEVIATIONS D3/D4/D5** — require engagement-lead change requests before any table is created.

### 2.4 Unified ServicePrice matrix (replaces the 7 live `*InsurancePlan` tables — DEVIATION D2)

```sql
CREATE TABLE insurance_providers (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL,
    address VARCHAR(400), telephone VARCHAR(40), email VARCHAR(120), fax VARCHAR(40), website VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT FALSE, <audit>, uq uid/code/name );

CREATE TABLE insurance_plans (
    ..., code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    insurance_provider_id BIGINT NOT NULL, <audit>,
    uq uid/code/name,
    CONSTRAINT fk_insurance_plans_provider FOREIGN KEY (insurance_provider_id)
        REFERENCES insurance_providers (id) );
CREATE INDEX idx_insurance_plans_provider ON insurance_plans (insurance_provider_id);
-- NO membership/card scheme on plan (legacy has none; membership_no lives on Patient) — DEVIATION vs spec line 27.

-- ServicePrice: plan_uid NULL = cash row. service_uid NULL allowed for REGISTRATION.
CREATE TABLE service_prices (
    id BIGINT GENERATED ALWAYS AS IDENTITY, uid VARCHAR(26) NOT NULL,
    plan_uid    VARCHAR(26),                         -- NULL = cash (loose uid ref to insurance_plans.uid)
    kind        VARCHAR(20) NOT NULL,
    service_uid VARCHAR(26),                          -- Clinic.uid | WardType.uid | LabTestType.uid | ... ; NULL for REGISTRATION
    currency    VARCHAR(3)  NOT NULL DEFAULT 'TZS',   -- NET-NEW, inert (no legacy source)
    amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    covered     BOOLEAN NOT NULL DEFAULT FALSE,       -- legacy covered flag; cash rows = TRUE by convention
    min_amount  NUMERIC(19,2),                        -- NET-NEW, NULLABLE, inert (no legacy source)
    max_amount  NUMERIC(19,2),                        -- NET-NEW, NULLABLE, inert
    active      BOOLEAN NOT NULL DEFAULT TRUE,         -- legacy 'active' (inert in resolve; kept for fidelity)
    <audit>,
    CONSTRAINT pk_service_prices PRIMARY KEY (id),
    CONSTRAINT uq_service_prices_uid UNIQUE (uid),
    CONSTRAINT ck_service_prices_kind CHECK (kind IN
        ('REGISTRATION','CONSULTATION','LAB_TEST','MEDICINE','PROCEDURE','RADIOLOGY','WARD')),
    -- REGISTRATION may have NULL service_uid; all other kinds require it.
    CONSTRAINT ck_service_prices_service_uid CHECK
        (kind = 'REGISTRATION' OR service_uid IS NOT NULL),
    CONSTRAINT ck_service_prices_amount_nonneg CHECK (amount >= 0)
);
-- UNIQUE over (plan_uid, kind, service_uid, currency). NULLs collide under PG (NULL != NULL),
-- so a partial-unique pair is required to enforce the spec's uniqueness for cash + registration rows:
CREATE UNIQUE INDEX uq_service_prices_plan_kind_svc_cur
    ON service_prices (COALESCE(plan_uid,''), kind, COALESCE(service_uid,''), currency);
-- High-frequency resolve path: PriceLookup.resolve(plan,kind,service,currency) and cash fallback.
CREATE INDEX idx_service_prices_lookup ON service_prices (kind, service_uid, plan_uid);
```

**The resolve algorithm is NOT in the schema.** The ward referral-override + principal/supplementary top-up split, the per-service not-covered fallback asymmetry (consultation hard-fail; lab/rad/proc/med cash-VERIFIED for inpatients only; registration stays UNPAID), and the auto-placeholder `covered=false,amount=0` rows are application logic for backend-engineer to reproduce exactly. `LabTestPlanPrice` is **DEAD — no table created** (DEVIATION D8). `min_amount/max_amount/currency` are net-new and must NOT drive behaviour.

### 2.5 Supporting tables

```sql
-- md_document_type: prefix registry. NET-NEW (no legacy document_type table) — DEVIATION D9.
CREATE TABLE md_document_types (
    id BIGINT GENERATED ALWAYS AS IDENTITY, uid VARCHAR(26) NOT NULL,
    kind   VARCHAR(60) NOT NULL,    -- e.g. STORE_TO_PHARMACY_TO
    prefix VARCHAR(12) NOT NULL,    -- e.g. SPTO
    <audit>,
    CONSTRAINT pk_md_document_types PRIMARY KEY (id),
    CONSTRAINT uq_md_document_types_uid  UNIQUE (uid),
    CONSTRAINT uq_md_document_types_kind UNIQUE (kind) );

-- md_currency: NET-NEW (no legacy Currency) — DEVIATION D10.
CREATE TABLE md_currencies (
    id BIGINT GENERATED ALWAYS AS IDENTITY, uid VARCHAR(26) NOT NULL,
    code VARCHAR(3) NOT NULL, name VARCHAR(80) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE, <audit>,
    CONSTRAINT pk_md_currencies PRIMARY KEY (id),
    CONSTRAINT uq_md_currencies_uid UNIQUE (uid), CONSTRAINT uq_md_currencies_code UNIQUE (code) );
CREATE UNIQUE INDEX uq_md_currencies_one_default ON md_currencies (is_default) WHERE is_default = TRUE;
```

---

## 3. DOCUMENT-NUMBER SEQUENCES — `V7__masterdata_document_sequences.sql`

`seq_usr_no` **already exists (V4:31) — do NOT recreate** (would fail on a clean DB after V4). Create the remainder, all `START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE` to match the V4 pattern:

```
seq_grn_no, seq_lpo_no, seq_pcn_no, seq_prl_no,
seq_spto_no, seq_ppto_no,        -- TO streams (SPTO/PPTO prefixes — split per spec; see D11)
seq_pgrn_no, seq_pprn_no,        -- received notes
seq_ppr_no, seq_psr_no,          -- requisition orders
seq_mrno                          -- patient medical record no
```

**DECISION FLAG (D11):** sequences themselves are a modernisation of legacy `MAX(id)+1` (pre-approved mechanism change, same as `seq_usr_no` in V4). But **legacy emits `SPT` for BOTH TO streams from one shared logic** — splitting into `seq_spto_no`/`seq_ppto_no` with distinct `SPTO`/`PPTO` prefixes is the inc-02 spec's intent and FIXES the legacy SPT collision. This is a **behavioural change** (number format changes), needs engagement-lead sign-off (spec line 86–87, 98 mandates SPTO/PPTO; legacy reality is SPT — DEVIATION D11). I implement the spec's SPTO/PPTO but flag the legacy SPT collision so data-migration-engineer maps existing `SPT*` numbers explicitly.

---

## 4. COMPANY PROFILE DELTA — `V8__masterdata_company_profile_delta.sql`

The V1 `company_profiles` table has only `name/address/phone`. Legacy CompanyProfile has ~40 fields. **Additive ALTER** (V1 is immutable). Note the V1 column is `name`, legacy is `companyName` — reconcile:

```sql
ALTER TABLE company_profiles
    ADD COLUMN contact_name VARCHAR(200),
    ADD COLUMN logo BYTEA,                              -- legacy @Lob byte[]
    ADD COLUMN tin VARCHAR(40), ADD COLUMN vrn VARCHAR(40),
    ADD COLUMN physical_address VARCHAR(400), ADD COLUMN post_code VARCHAR(40),
    ADD COLUMN post_address VARCHAR(200), ADD COLUMN telephone VARCHAR(40),
    ADD COLUMN mobile VARCHAR(40), ADD COLUMN email VARCHAR(120),
    ADD COLUMN website VARCHAR(200), ADD COLUMN fax VARCHAR(40),
    -- three bank blocks (1,2,3): bank_account_name[/2/3], bank_physical_address[/2/3],
    --   bank_post_code[/2/3], bank_post_address[/2/3], bank_name[/2/3], bank_account_no[/2/3]
    ADD COLUMN quotation_notes TEXT, ADD COLUMN sales_invoice_notes TEXT,
    ADD COLUMN registration_fee NUMERIC(19,2) NOT NULL DEFAULT 0,  -- legacy double, source of cash reg fee
    ADD COLUMN public_path VARCHAR(400),
    ADD COLUMN employee_prefix VARCHAR(12) DEFAULT 'EMP';
```

Existing V1 `name` column = legacy `companyName`. **DECISION FLAG (D12):** keep `name` (rename semantically to company name) and add `contact_name`; do not drop `address`/`phone` (V1 immutable) — treat them as the primary physical_address/telephone or map at the service layer. Flag the V1↔legacy column-name overlap for backend-engineer. `validateCompany()` always returns true in legacy (no service validation) — do not add CHECK constraints beyond NOT NULL on the audit columns.

---

## 5. SEED STRATEGY (V9+)

- **md_document_types (V9):** SPTO/PPTO per spec, plus the real legacy prefixes for the other docs (`GRN`,`LPO`,`PCN`,`PRL`,`PGRN`,`PPRN`,`PPR`,`PSR`,`MRNO`). Engagement-lead sign-off required before commit (spec line 105). Assertion: `SELECT prefix FROM md_document_types WHERE kind='STORE_TO_PHARMACY_TO'` = `'SPTO'`; no row with prefix `'SPT'`.
- **md_currencies (V10):** one row, the system default (code per engagement-lead — net-new, no legacy value to copy; flagged D10). `is_default=TRUE`.
- **company_profile delta (V11):** idempotent `UPDATE … WHERE uid='01J0SEEDCOMP00000000000001'` to set legacy field values (or leave defaults if production extract supplies them).
- **Context data seeds (V12+):** each `*InsurancePlan` legacy row → one `service_prices` row with `plan_uid` set, `covered` copied, `amount = CAST(legacy_double AS NUMERIC(19,2))`. Each service entity's cash price → one `service_prices` row with `plan_uid = NULL`, `covered = TRUE`: clinic.consultationFee→CONSULTATION (service_uid=clinic.uid), CompanyProfile.registrationFee→REGISTRATION (service_uid=NULL/'DEFAULT'), LabTestType.price→LAB_TEST, RadiologyType.price→RADIOLOGY, ProcedureType.price→PROCEDURE, Medicine.price→MEDICINE, WardType.price→WARD. **Exclude `LabTestPlanPrice` rows entirely** (dead — confirm with data-migration-engineer whether any production rows exist).
- **Count verification:** each seed file ends with a comment block stating the expected row count from the legacy MySQL extract; data-migration-engineer's reconciliation asserts `SELECT count(*)` per table = legacy `count(*)` per source table (e.g. `service_prices WHERE plan_uid IS NOT NULL` = sum of the 7 `*_insurance_plans` legacy counts; `service_prices WHERE plan_uid IS NULL` = count of priced service entities). Financial spot-check: `SUM(amount)` of migrated insurance rows = `SUM(fee_field)` of legacy `*InsurancePlan` rows (within rounding tolerance of the `double→NUMERIC(19,2)` CAST).

---

## 6. DEVIATION LIST (spec doc `02-master-data.md` vs legacy reality)

| # | Spec says | Legacy reality / decision | Action |
|---|---|---|---|
| D1 | `Clinic` + `ClinicType` enum (line 14) | No ClinicType anywhere; Clinic has no type field | **No clinic_type column.** Net-new → CR required. |
| D2 | 7 `*InsurancePlan` tables → unified `ServicePrice` (line 28) | Confirmed feasible as STORAGE; resolve logic + ward top-up split + fallback asymmetry stay in code | Build `service_prices`; preserve algorithm in app. |
| D3 | `LabTestAnalyte` + `LabReferenceRange` + `RangeFlag` enum (line 22) | Zero matches in legacy; only `LabTestTypeRange` = named string label | **No analyte/range/flag tables.** Reproduce legacy; richer model = CR. |
| D4 | `Diagnosis` ICD-friendly catalogue (line 25) | Entity is `DiagnosisType`; no ICD, no hierarchy, no price/uom | Table `diagnosis_types`, flat code/name/description. |
| D5 | `DosageForm`/`Route`/`Frequency` + `ItemCategory`/`UnitOfMeasure` tables (lines 20,26) | All free-text strings; no entities/enums | **No picklist tables.** Free-text columns; controlled vocab = CR. |
| D6 | `Bed` + `available` flag (line 16) | Two entities: `WardBed` (master) + `AdmissionBed` (occupancy). `ward_beds.no` NOT unique | Table `ward_beds`; do not collapse; no (ward,no) unique without approval. |
| D7 | `ClinicClinician` masterdata table (lines 15,94) | Affiliation owned by `iam.Clinician.clinics` M2M; no masterdata table in legacy | **Not created in masterdata.** Relocating ownership = structural change → solution-architect/engagement-lead decision. |
| D8 | (implied lab pricing) | `LabTestPlanPrice` is DEAD (no repo/service/usage) | Excluded from schema and seeds. |
| D9 | `md_document_type` + DB `SEQUENCE`s (lines 83–84) | Legacy has no document_type table; uses `MAX(id)+1` not sequences | Create `md_document_types` + sequences (mechanism modernisation, pre-approved like seq_usr_no). |
| D10 | `Currency` / `md_currency`, default `TZS` (line 30) | No Currency entity, no currency field, no TZS literal in legacy | Create `md_currencies` as net-new; currency inert in pricing; default code per engagement-lead. |
| D11 | `SPTO`/`PPTO` prefixes (lines 86–87,98) | Legacy emits `SPT` for BOTH TO streams (collision defect) | Implement SPTO/PPTO per spec (FIXES defect) → engagement-lead sign-off; data-migration maps existing `SPT*`. |
| D12 | `company_profiles` rich shape | V1 created minimal (name/address/phone); legacy has ~40 fields incl. 3 bank blocks, registration_fee, employee_prefix | Additive `V8` ALTER; reconcile V1 `name`↔legacy `companyName`, keep `address`/`phone`. |
| D13 | uid `CHAR(26)` (lines 15,28) | Directive + V1 baseline = `VARCHAR(26)` | Use `VARCHAR(26)` everywhere (CHAR would pad). |
| D14 | "177 privilege codes" (line 63) | Known miscount — 35 distinct (26 live), per inc-01 | Gate masterdata endpoints only with the 35 real codes; flag any uncovered endpoint, don't invent. |
| D15 | `ServiceKind` includes `REGISTRATION` + `WARD_DAY` (line 57) | Legacy ward charge is per-stay (`WardType.price`), not per-day; registration keyed plan-only | Use kind `WARD` (not `WARD_DAY`); REGISTRATION service_uid nullable. Confirm per-day vs per-stay with healthcare-domain-expert. |
| D16 | `minAmount`/`maxAmount` on ServicePrice (line 28) | No legacy source (no copay/cap/percentage field anywhere) | Columns kept NULLABLE + inert; must not drive behaviour. Populating = invention/CR. |

---

## 7. KEY INDEX / CONSTRAINT RATIONALE

- `uq_imc_item_medicine` — encodes legacy "one coefficient per (item,medicine) pair" rule (`ConversionCoefficientResource`); replaces app-level duplicate check.
- `ck_imc_item_qty_pos` / `ck_imc_medicine_qty_pos` — encode legacy "Zero values are not allowed" (qty>0 both sides) so `coefficient = medicine_qty/item_qty` never divides by zero.
- `uq_service_prices_plan_kind_svc_cur` (partial unique over COALESCE) — enforces the spec's `UNIQUE(plan_uid,kind,service_uid,currency)` correctly under PG NULL semantics (so duplicate cash/registration rows are caught → 409, not silent overwrite).
- `idx_service_prices_lookup (kind, service_uid, plan_uid)` — covers `PriceLookup.resolve` (highest-frequency point-of-care path) and the cash-fallback (`plan_uid IS NULL`) scan.
- `ck_service_prices_kind` — pins the 7 legacy billable kinds; rejects invented kinds.
- `uq_md_currencies_one_default` — at most one default currency.
- FK indexes (`idx_wards_ward_type`, `idx_items_suppliers_*`, `idx_insurance_plans_provider`) — support join/cascade-check performance on every declared FK.

---

## 8. RELEVANT FILE PATHS

- Existing baseline (read, immutable): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V1__schema.sql`, `V4__schema_iam_delta.sql` (contains `seq_usr_no`), `V3__seed_company_profile.sql`, `V5__backfill_admin_identity.sql`.
- Spec under reconciliation: `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\02-master-data.md`.
- New migrations to author (in order): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V6__masterdata_schema.sql`, `V7__masterdata_document_sequences.sql`, `V8__masterdata_company_profile_delta.sql`, `V9__seed_md_document_type.sql`, `V10__seed_md_currency.sql`, `V11__seed_company_profile_delta.sql`, `V12+__seed_<context>.sql`.

**Blocking decisions for engagement-lead before seed commit:** D1 (ClinicType), D3/D4/D5 (rich clinical models = CR), D7 (ClinicClinician ownership), D10 (default currency code), D11 (SPTO/PPTO vs legacy SPT), D15 (WARD vs WARD_DAY per-day pricing). **Audit DDL deliberately NOT designed here** — awaiting security-architect's PHI field classification and audit coverage requirements per role boundary.