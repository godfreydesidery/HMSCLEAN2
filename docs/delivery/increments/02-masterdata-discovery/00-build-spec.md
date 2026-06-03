I now have a complete and verified picture of the existing build conventions, the iam.lookup contract (which exposes `roleNames` on `UserSummary` — sufficient for the CLINICIAN assertion), the AuditableEntity base, the ErrorCode enum, the MapStruct/service/controller patterns, and the migration baseline (V1–V5, next = V6). I have everything needed to write the build contract.

One correction I will note: the `AuditableEntity` Javadoc says `uid` is `CHAR(26)` but the actual `@Column(length=26)` + V1 DDL is `VARCHAR(26)` — the spec must lock VARCHAR(26).

Here is the authoritative, implementation-ready Increment 02 build spec.

---

# INCREMENT 02 — MASTER DATA BUILD SPEC (AUTHORITATIVE BUILD CONTRACT)

Status: implementation-ready. Source of truth = legacy `com.orbix.api`. Stack baseline = HMSCLEAN2 `com.otapp.hmis`, Flyway V1–V5 applied, **next migration = V6**. All extraction findings, the data-architect schema, the security-architect gate map, the BA acceptance criteria, and the healthcare-domain-expert sign-off are folded in. Where the inc-02 spec doc (`02-master-data.md`) drifts from legacy, **the legacy reality wins and the drift is logged in the sign-off register (§8)**.

This contract is binding on `backend-engineer`, `data-architect`, `frontend-engineer`, `qa-test-engineer`. Items tagged **[GATED:CR-xx]** must NOT be built until `engagement-lead` signs off the linked change request (§8). Everything else is legacy-faithful and may proceed.

---

## 0. GLOBAL CONVENTIONS (non-negotiable, inherited from inc-00/01)

- Package root `com.otapp.hmis.masterdata`; sub-packages `domain/`, `application/`, `application/dto/`, `api/`.
- Entities extend `shared.domain.AuditableEntity` (hidden `id BIGINT GENERATED ALWAYS AS IDENTITY` + `uid VARCHAR(26)` ULID via `@PrePersist`; `created_at/updated_at/created_by/updated_by/version`). **`uid` is `VARCHAR(26)`, NOT CHAR** — the AuditableEntity Javadoc text says CHAR but `@Column(length=26)` + V1 DDL is VARCHAR; lock VARCHAR(26) everywhere (CR-02).
- Lombok generates accessors/constructors; `@NoArgsConstructor(access = PROTECTED)` + a public business constructor + `update(...)` mutator method per entity (mirror the `CompanyProfile` pattern). Lombok runs BEFORE MapStruct (annotation-processor order already configured).
- MapStruct mappers are **package-private** `@Mapper` interfaces in `application/`. **No `id` in any DTO.** DTOs are Java `record`s in `application/dto/`.
- Tables PLURAL snake_case; money `NUMERIC(19,2)`; qty/coefficient `NUMERIC(19,6)`; constraint naming `pk_/fk_/uq_/idx_/ck_`.
- `spring.jpa.hibernate.ddl-auto=validate` — Flyway owns the schema. Every entity must match its DDL exactly or boot fails.
- Errors via `shared.error` → RFC7807 `ProblemDetail` through `GlobalExceptionHandler`. New error codes added to `shared.error.ErrorCode` (§2.4, §5).
- Audit: every create/update/delete calls `shared.audit.AuditRecorder.record(entityType, uid, action)` inside the service transaction (`Propagation.MANDATORY`). `entityType` = `"masterdata.<Entity>"`. No `LocalDateTime.now()` / `Instant.now()` inside masterdata internals (ArchUnit gate) — time comes from `AuditRecorder`/auditing listener.
- Module boundary: `@ApplicationModule(allowedDependencies = "iam")` already set. masterdata may call `iam.lookup.IamLookupService` ONLY; never iam domain entities. No reverse edge.

---

## 1. ENTITY / TABLE / DTO / MAPPER PLAN (per entity)

Legend: every table implicitly carries the `AuditableEntity` column block (`id, uid, created_at, updated_at, created_by, updated_by, version`) + `uq_<t>_uid`. Only business columns are listed. DTOs are `record`s, no `id`, `uid` first. One `@Mapper` per entity (or per small cluster) → `toDto` + `toDtoList`.

### 1.1 Organizational units (P1)

| Entity / Table | Business columns (legacy-faithful) | Notes |
|---|---|---|
| `Clinic` / `clinics` | `code` UQ NOT NULL, `name` UQ NOT NULL, `description`, `consultation_fee NUMERIC(19,2) NOT NULL DEFAULT 0`, `active BOOLEAN NOT NULL DEFAULT FALSE` | **NO `clinic_type`** (legacy has no ClinicType — CR-17). Cash consultation fee stays ON the row. |
| `WardType` / `ward_types` | `code` UQ, `name` UQ, `description`, `price NUMERIC(19,2) NOT NULL DEFAULT 0`, `active` | Pricing anchor for wards. |
| `WardCategory` / `ward_categories` | `code` UQ, `name` UQ, `description`, (`active`?) | **NO price** — descriptive grouping only. |
| `Ward` / `wards` | `code` UQ, `name` UQ, `no_of_beds INTEGER NOT NULL DEFAULT 0`, `active`, `ward_category_id` FK NOT NULL, `ward_type_id` FK NOT NULL | **NO per-ward price.** FKs `@OnDelete NO_ACTION` (no cascade). Index both FKs. |
| `WardBed` / `ward_beds` | `no VARCHAR(40) NOT NULL`, `status VARCHAR(40)`, `active`, `ward_id` FK NOT NULL | **`no` NOT unique** (legacy) — do NOT add `(ward_id,no)` unique [GATED:CR-16]. Index `ward_id`. |
| `Pharmacy` / `pharmacies` | `code` UQ, `name` UQ, `description`, `location`, `category VARCHAR(80)` (free-text), `active` | |
| `Store` / `stores` | same shape as pharmacies | |
| `Theatre` / `theatres` | `code` UQ, `name` UQ, `description`, `location`, `active` | |

**OUT OF SCOPE for masterdata inc-02:** `AdmissionBed` (inpatient/billing transaction — later increment); clinic↔clinician and store↔storeperson M2M join tables (owned by iam — see §5.2, CR-08). DTOs: `ClinicDto`, `WardTypeDto`, `WardCategoryDto`, `WardDto` (embeds `wardTypeUid`, `wardCategoryUid` strings, not nested entities), `WardBedDto` (embeds `wardUid`), `PharmacyDto`, `StoreDto`, `TheatreDto`.

### 1.2 Inventory catalog (P2)

| Entity / Table | Business columns | Notes |
|---|---|---|
| `Medicine` / `medicines` | `code` UQ, `name` UQ, `description`, `type VARCHAR(80) NOT NULL` (free-text "ORAL,ETC"), `price NUMERIC(19,2) NOT NULL DEFAULT 0`, `uom VARCHAR(40)`, `category VARCHAR(80) NOT NULL DEFAULT 'MEDICINE'`, `active` | **Cash price stays on the row** (dispensing path reads it). category/type/uom free-text — NO lookup tables [GATED:CR-07]. |
| `Item` / `items` | `code` UQ, `barcode`, `name` UQ, `short_name VARCHAR(120)` UQ, `common_name`, `vat NUMERIC(19,2) NOT NULL DEFAULT 0`, `uom VARCHAR(40)`, `pack_size NUMERIC(19,6) NOT NULL DEFAULT 1`, `category VARCHAR(80)`, `cost_price_vat_incl NUMERIC(19,2) NOT NULL DEFAULT 0`, `selling_price_vat_incl NUMERIC(19,2) NOT NULL DEFAULT 0`, `active BOOLEAN NOT NULL DEFAULT TRUE`, `ingredients TEXT` | `active` default **TRUE** (legacy). `short_name` UQ (legacy `@Column(unique=true)`). |
| `ItemMedicineCoefficient` / `item_medicine_coefficients` | `coefficient NUMERIC(19,6) NOT NULL DEFAULT 0`, `item_qty NUMERIC(19,6) NOT NULL DEFAULT 0`, `medicine_qty NUMERIC(19,6) NOT NULL DEFAULT 0`, `item_id` FK NOT NULL, `medicine_id` FK NOT NULL | `uq_imc_item_medicine UNIQUE(item_id, medicine_id)`; `ck_imc_item_qty_pos CHECK(item_qty>0)`, `ck_imc_medicine_qty_pos CHECK(medicine_qty>0)`. `coefficient = medicine_qty/item_qty` computed in service (§5.3). Do NOT make `item_id` globally unique (preserve `findAllByMedicine` listing). |
| `Supplier` / `suppliers` | `code` UQ, `name` UQ, `contact_name NOT NULL`, `active BOOLEAN NOT NULL DEFAULT TRUE`, `tin`, `vrn`, `terms_of_contract TEXT`, address block (`physical_address`, `post_code`, `post_address`, `telephone`, `mobile`, `email`, `fax`), bank block (`bank_account_name`, `bank_physical_address`, `bank_post_code`, `bank_post_address`, `bank_name`, `bank_account_no`) | `active` default TRUE (legacy). |
| `ItemSupplier` / `items_suppliers` | `item_id` FK NOT NULL, `supplier_id` FK NOT NULL, `cost_price_vat_incl NUMERIC(19,2) NOT NULL DEFAULT 0`, `cost_price_vat_excl NUMERIC(19,2) NOT NULL DEFAULT 0`, `active` | **NO audit columns in legacy** — but in HMSCLEAN2 it still extends `AuditableEntity` (uid + audit cols are a target-side invariant; preserve the legacy *behaviour* asymmetry only as a note, not by omitting audit cols — flag asymmetry to data-architect, default = include audit cols for consistency). |
| `SupplierItemPrice` / `supplier_item_prices` | `price NUMERIC(19,2) NOT NULL DEFAULT 0`, `terms TEXT`, `active`, `supplier_id` FK NOT NULL, `item_id` FK NOT NULL | `SupplierItemPriceList` is a non-persistent DTO (no table) — model as a response record `SupplierItemPriceListDto(SupplierDto supplier, List<SupplierItemPriceDto> prices)`. |

ItemSupplier vs SupplierItemPrice overlap is a known legacy redundancy — preserve both; do not unify (flag to business-analyst, no build action). DTOs: `MedicineDto`, `ItemDto`, `ItemMedicineCoefficientDto` (embeds `itemUid`, `medicineUid`, `coefficient`, `itemQty`, `medicineQty`), `SupplierDto`, `ItemSupplierDto`, `SupplierItemPriceDto`, `SupplierItemPriceListDto`.

### 1.3 Clinical catalog (P3) — LEGACY MODEL ONLY

| Entity / Table | Business columns | Notes |
|---|---|---|
| `LabTestType` / `lab_test_types` | `code` UQ, `name` UQ, `description`, `price NUMERIC(19,2) NOT NULL DEFAULT 0`, `uom VARCHAR(40)`, `active` | |
| `LabTestTypeRange` / `lab_test_type_ranges` | `name VARCHAR(200) NOT NULL DEFAULT ''`, `lab_test_type_id` FK NOT NULL `ON DELETE CASCADE` (legacy orphanRemoval) | **A named string label only.** NO low/high/min/max/unit/sex/age/flag. |
| `RadiologyType` / `radiology_types` | `code` UQ, `name` UQ, `description`, `price NUMERIC(19,2)`, `uom`, `active` | |
| `ProcedureType` / `procedure_types` | same shape as radiology_types | |
| `DiagnosisType` / `diagnosis_types` | `code` UQ, `name` UQ, `description`, `active` | **Entity is `DiagnosisType`, NOT "Diagnosis".** NO price/uom, NO ICD, NO hierarchy [GATED:CR-06]. |

**NOT created** (no legacy source; all [GATED]): `lab_test_analytes`, `lab_reference_ranges`, `range_flag` enum [CR-05]; `dosage_forms`, `routes`, `frequencies` [CR-07]; `item_categories`, `units_of_measure` [CR-07]; ICD `diagnoses` [CR-06]. Dosage/route/frequency are free-text columns on `Prescription` in a later pharmacy increment, NOT masterdata picklists. DTOs: `LabTestTypeDto`, `LabTestTypeRangeDto` (embeds `labTestTypeUid` + `name`), `RadiologyTypeDto`, `ProcedureTypeDto`, `DiagnosisTypeDto`.

**LabTestType edit quirk (AC-9.4):** reproduce legacy exactly — on update, `code` is immutable; reproduce, document in mapper/service Javadoc. Do NOT implement `PUT /lab_test_types/update_by_code` (dead legacy endpoint, AC-9.5).

### 1.4 Insurance master + ServicePrice (P4) — see §2.

| Entity / Table | Business columns | Notes |
|---|---|---|
| `InsuranceProvider` / `insurance_providers` | `code` UQ, `name` UQ, `address`, `telephone`, `email`, `fax`, `website`, `active` | NO membership/card scheme on provider (legacy). |
| `InsurancePlan` / `insurance_plans` | `code` UQ, `name` UQ, `description`, `active`, `insurance_provider_id` FK NOT NULL | NO copay/coverage/card fields on plan (membership_no lives on Patient, later increment). Resolved by NAME at point of care — name/code uniqueness load-bearing. |
| `ServicePrice` / `service_prices` | §2 | Replaces the 7 live `*InsurancePlan` tables [GATED:CR-04]. |

`LabTestPlanPrice` is DEAD — **no table, no entity, no seed** (CR-04 sub-item). DTOs: `InsuranceProviderDto`, `InsurancePlanDto` (embeds `insuranceProviderUid`), `ServicePriceDto`.

### 1.5 Stakeholders / system (P5)

| Entity / Table | Action | Notes |
|---|---|---|
| `CompanyProfile` / `company_profiles` | **ALTER existing table** (V8 delta — §4) to add the full legacy field set | V1 has only `name/address/phone`. Add: `contact_name`, `logo BYTEA`, `tin`, `vrn`, address block, **3 bank blocks**, `quotation_notes TEXT`, `sales_invoice_notes TEXT`, `registration_fee NUMERIC(19,2) NOT NULL DEFAULT 0`, `public_path`, `employee_prefix VARCHAR(12) DEFAULT 'EMP'`. Reconcile V1 `name`↔legacy `companyName`, keep `address`/`phone` (V1 immutable). Expand `CompanyProfileDto` accordingly. |
| `MdDocumentType` / `md_document_types` | NEW [GATED:CR-09] | `kind VARCHAR(60)` UQ, `prefix VARCHAR(12) NOT NULL`. |
| `MdCurrency` / `md_currencies` | NEW [GATED:CR-07/CR-10-currency] | `code VARCHAR(3)` UQ, `name`, `is_default BOOLEAN NOT NULL DEFAULT FALSE`; partial unique `WHERE is_default=TRUE`. |
| `BusinessDay` (shared) | EXISTS (inc-00 `business_days`) | Surface open/close/current admin endpoints (§5). Map legacy misspelled `bussinessDate`→`business_date` in migration only. |

---

## 2. UNIFIED ServicePrice MATRIX + PriceLookup.resolve ALGORITHM

### 2.1 Table (DDL in V6)

```sql
CREATE TABLE service_prices (
    id BIGINT GENERATED ALWAYS AS IDENTITY, uid VARCHAR(26) NOT NULL,
    plan_uid    VARCHAR(26),                          -- NULL = cash row (loose uid ref to insurance_plans.uid)
    kind        VARCHAR(20) NOT NULL,
    service_uid VARCHAR(26),                           -- Clinic.uid|WardType.uid|LabTestType.uid|...; NULL for REGISTRATION
    currency    VARCHAR(3)  NOT NULL DEFAULT 'TZS',    -- NET-NEW, inert
    amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    covered     BOOLEAN NOT NULL DEFAULT FALSE,        -- cash rows = TRUE by convention
    min_amount  NUMERIC(19,2),                         -- NET-NEW, NULLABLE, inert [CR-11]
    max_amount  NUMERIC(19,2),                         -- NET-NEW, NULLABLE, inert [CR-11]
    active      BOOLEAN NOT NULL DEFAULT TRUE,         -- inert in resolve; kept for fidelity
    <audit>,
    CONSTRAINT pk_service_prices PRIMARY KEY (id),
    CONSTRAINT uq_service_prices_uid UNIQUE (uid),
    CONSTRAINT ck_service_prices_kind CHECK (kind IN
        ('REGISTRATION','CONSULTATION','LAB_TEST','MEDICINE','PROCEDURE','RADIOLOGY','WARD')),
    CONSTRAINT ck_service_prices_service_uid CHECK (kind = 'REGISTRATION' OR service_uid IS NOT NULL),
    CONSTRAINT ck_service_prices_amount_nonneg CHECK (amount >= 0)
);
CREATE UNIQUE INDEX uq_service_prices_plan_kind_svc_cur
    ON service_prices (COALESCE(plan_uid,''), kind, COALESCE(service_uid,''), currency);
CREATE INDEX idx_service_prices_lookup ON service_prices (kind, service_uid, plan_uid);
```

`ServiceKind` enum (domain): `REGISTRATION, CONSULTATION, LAB_TEST, MEDICINE, PROCEDURE, RADIOLOGY, WARD`. **Use `WARD` not `WARD_DAY`** — legacy ward charge is per-stay, not per-day (CR-04/D15). Domain expert flag F1 stands; reproduce per-stay.

### 2.2 PriceLookup.resolve — the contract

`PriceLookup` is a public application service in `masterdata.application` exposed cross-module via a `masterdata.lookup` read interface (so billing/clinical increments consume it without touching masterdata internals). Signature:

```
ServicePriceResult resolve(String planUid, ServiceKind kind, String serviceUid, String currency)
```

**Storage-tier resolution order (this method is ONLY the row lookup; the legacy resolve-time business logic in §2.3 lives in the consuming billing code, NOT here):**

1. If `planUid != null`: query covered insurance row — `(plan_uid=planUid, kind, COALESCE(service_uid,'')=COALESCE(serviceUid,''), currency, covered=TRUE)`. If found → return it (insurance hit).
2. Cash fallback — query `(plan_uid IS NULL, kind, service_uid match, currency)`. If found → return cash row.
3. If neither → throw `ServicePriceNotFoundException` → RFC7807 `type = "urn:hmis:error:service-price-not-found"`, 422.

**Per-ward override vs per-ward-type resolution order (ADMIT-3 / CR-12):** legacy has WardType-level pricing ONLY. The spec's per-Ward override is [GATED:CR-12] and net-new. Default build (CR-12 not approved): `resolve(planUid, WARD, wardTypeUid, currency)` only. **If CR-12 is approved**, resolution order becomes: (a) try `service_uid = wardUid` (ward-level override); (b) fall back to `service_uid = wardTypeUid` (type-level); (c) cash; (d) throw. Until CR-12 sign-off, build WardType-only.

**Critical: `PriceLookup.resolve` is a storage primitive, NOT the legacy pricing engine.** The legacy point-of-care behaviours below are NOT expressible as a row lookup and are reproduced in the BILLING increment's application code (consuming `PriceLookup`), per the domain-expert blockers B1/B2.

### 2.3 Legacy resolve-time logic that must be reproduced IN CODE (billing increment, documented here as the contract `PriceLookup` must support)

Confirmed by healthcare-domain-expert against `PatientServiceImpl`:

1. **Two-step:** always build the bill at CASH first; if INSURANCE, override with the covered plan row. Covered override sets `amount=paid=planPrice, balance=0, status="COVERED"`.
2. **Per-service not-covered fallback asymmetry (B2 — must NOT be normalized):**
   - CONSULTATION: HARD FAIL → `InvalidOperationException("Plan not available for this clinic. Please change payment method")` (no cash fallback).
   - LAB/RADIOLOGY/PROCEDURE/MEDICINE: cash fallback with status `VERIFIED` **only when patient is inpatient**; non-admitted → initial cash `UNPAID` bill remains.
   - REGISTRATION: silent — cash bill stays `UNPAID`, no exception.
3. **WARD referral-override + top-up split (B1 — must NOT be dropped):** load ALL covered ward-type rows for the plan, pick highest `price`, short-circuit on a row whose plan == patient's plan (referral override); if eligible plan differs AND `wardType.price - eligiblePlan.price > 0`, emit a SECOND `PatientBill` for the difference (`status=UNPAID`, billItem `"Bed"`, description `"Ward Bed / Room (Top up)"`, principal/supplementary linkage, attached to a cash invoice). This is the ONLY co-pay mechanism in the entire system.
4. **`covered=false` placeholder rows behave as cash** (resolve queries `covered=TRUE` only). `active` is inert. `min_amount/max_amount/currency` are inert net-new and MUST NOT drive behaviour.

**Domain-expert verdict:** the matrix faithfully preserves legacy pricing for all 7 kinds **as a storage model** IF AND ONLY IF: `service_uid` nullable for REGISTRATION and = Clinic.uid for CONSULTATION; `min/max/currency` inert; and the §2.3 algorithm (ward top-up + per-service fallback asymmetry) is reproduced in the billing increment's code. Sign-off is conditional on the spec documenting §2.3 as preserved code-level behaviour — **this section IS that documentation.** Nothing must be added to legacy pricing; nothing may be silently dropped.

---

## 3. EXACT-PROCESS GATE MAP (real legacy codes only — the 35)

Only 4 distinct live codes appear in masterdata controllers: `ADMIN-ACCESS` (dominant), `MEDICINE_STOCK-UPDATE`, `ITEM_STOCK-UPDATE`, `SUPPLIER_PRICE_LIST-ALL`. All exist in the seeded 35. **No invented codes.** `@PreAuthorize("hasAnyAuthority('<code>')")` on every mutation; reads require a valid JWT but carry NO role gate (reproduce legacy: masterdata GETs are ungated-by-role).

| Endpoint group (mutation) | Gate | Status |
|---|---|---|
| Clinic / Ward / WardType / WardCategory / WardBed write | `ADMIN-ACCESS` | exact |
| Pharmacy write | `ADMIN-ACCESS` | exact |
| Pharmacy stock update | `MEDICINE_STOCK-UPDATE` | exact |
| Store write / item-register update | `ADMIN-ACCESS` | exact |
| Store stock update | `ITEM_STOCK-UPDATE` | exact |
| Theatre write | `ADMIN-ACCESS` | exact |
| Medicine write + (de)activate | `ADMIN-ACCESS` | exact |
| LabTestType / LabTestTypeRange / RadiologyType / ProcedureType / DiagnosisType write | `ADMIN-ACCESS` | exact |
| InsuranceProvider / InsurancePlan write | `ADMIN-ACCESS` | exact |
| All 7 per-service plan-price write/delete (now ServicePrice upsert/delete) | `ADMIN-ACCESS` | exact |
| SupplierItemPrice write/delete/save_or_update | `SUPPLIER_PRICE_LIST-ALL` | exact |
| CompanyProfile write + logo | `ADMIN-ACCESS` | exact |
| **Item write** | `ADMIN-ACCESS` | **DEVIATION-1** [GATED:CR-15] — legacy gate commented out, referenced DEAD `PROCUREMENT-ACCESS`. |
| **ItemMedicineCoefficient write** | `ADMIN-ACCESS` | **DEVIATION-2** [GATED:CR-15] — legacy commented out, DEAD `ROLE-CREATE`. |
| **Supplier write** | `ADMIN-ACCESS` | **DEVIATION-3** [GATED:CR-15] — legacy `ADMIN-ACCESS` commented out; re-enable. |
| **ServicePrice upsert/delete (the 14 ungated InsurancePlan coverage/price mutations)** | `ADMIN-ACCESS` | **DEVIATION-4** [GATED:CR-15] — legacy UNGATED; tightening to ADMIN-ACCESS (matches sibling per-service resources). |

DEVIATIONS 1–4 are security tightenings (ungated→gated) recommended by security-architect; gate with the existing live `ADMIN-ACCESS`, do not invent codes, do not reproduce ungated. CI privilege-diff gate: every `@PreAuthorize` value in masterdata controllers must exist in the seeded 35-code set (AC-8.4).

---

## 4. FLYWAY V6+ MIGRATION & SEED PLAN

DDL is one transactional file per concern; seeds one file per context for reviewable diffs and per-file reconciliation. **All seeds use `INSERT ... ON CONFLICT (uid) DO NOTHING` (idempotent).** Data-migration-engineer extracts legacy counts/values from the deployed legacy MySQL; each seed file ends with an expected-row-count comment that reconciliation asserts.

| File | Contents | Gate |
|---|---|---|
| `V6__masterdata_schema.sql` | All masterdata DDL: org units, inventory catalog, clinical catalog (legacy model), insurance master + `service_prices`, supporting tables. Pure DDL. | — |
| `V7__masterdata_document_sequences.sql` | New sequences (NOT `seq_usr_no` — exists in V4): `seq_grn_no, seq_lpo_no, seq_pcn_no, seq_prl_no, seq_spto_no, seq_ppto_no, seq_pgrn_no, seq_pprn_no, seq_ppr_no, seq_psr_no, seq_mrno`. `START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE`. | [GATED:CR-09] |
| `V8__masterdata_company_profile_delta.sql` | Additive ALTER on `company_profiles` (full legacy field set, §1.5). | — |
| `V9__seed_md_document_type.sql` | Prefix seed: `SPTO`/`PPTO` per spec + real legacy prefixes `GRN,LPO,PCN,PRL,PGRN,PPRN,PPR,PSR,MRNO`. | [GATED:CR-09/CR-10] |
| `V10__seed_md_currency.sql` | One default currency row, `is_default=TRUE` (code per engagement-lead). | [GATED:CR-07-currency] |
| `V11__seed_company_profile_delta.sql` | Idempotent UPDATE backfilling legacy CompanyProfile field values onto the seeded row. | — |
| `V12__seed_org_units.sql` | clinics, ward_types, ward_categories, wards, ward_beds, pharmacies, stores, theatres. | — |
| `V13__seed_inventory.sql` | suppliers, items, medicines, item_medicine_coefficients, items_suppliers, supplier_item_prices. | — |
| `V14__seed_clinical_catalog.sql` | lab_test_types + lab_test_type_ranges, radiology_types, procedure_types, diagnosis_types. | — |
| `V15__seed_insurance.sql` | insurance_providers, insurance_plans. | — |
| `V16__seed_service_prices.sql` | Cash mirror rows (plan_uid NULL) from each service entity's price + the 7 `*InsurancePlan` legacy tables → plan-keyed rows. **Exclude `LabTestPlanPrice`.** | [GATED:CR-04 + insurance seed-value sign-off] |

**Legacy data to extract for seeds (data-migration-engineer):**
- All rows + counts from: `clinics, ward_types, ward_categories, wards, ward_beds, pharmacies, stores, theatres, items, medicines, item_medicine_coefficients, suppliers, items_suppliers, supplier_item_prices, lab_test_types, lab_test_type_ranges, radiology_types, procedure_types, diagnosis_types, insurance_providers, insurance_plans`.
- The 7 `*_insurance_plans` tables → `service_prices` (plan_uid set, covered copied, `amount = CAST(legacy_double AS NUMERIC(19,2))`). Confirm whether `lab_test_plan_prices` has any production rows (default: exclude).
- Cash prices: `clinics.consultation_fee`→CONSULTATION (service_uid=clinic.uid); `company_profile.registration_fee`→REGISTRATION (service_uid NULL); `lab_test_types.price`→LAB_TEST; `radiology_types.price`→RADIOLOGY; `procedure_types.price`→PROCEDURE; `medicines.price`→MEDICINE; `ward_types.price`→WARD. Each as plan_uid NULL, covered=TRUE.
- Actual physical names of the legacy clinic↔clinician and store↔storeperson join tables (Hibernate-default-derived — read from deployed DDL, don't assume) — only relevant if CR-08 relocates affiliation.
- Existing `SPT`-prefixed document numbers (both TO streams) — for the SPTO/PPTO mapping note (CR-10).

**Reconciliation (AC-12, NOT deferred):** per-table source count == target count; `service_prices WHERE plan_uid IS NOT NULL` == sum of the 7 legacy `*_insurance_plans` counts; `SUM(amount)` parity within double→NUMERIC rounding tolerance; signed off by data-architect.

---

## 5. KEY DECISIONS LOCKED

### 5.1 Lab model — REPRODUCE LEGACY
Build `LabTestTypeRange` as a named-string-label list per `LabTestType`. NO analyte/reference-range/RangeFlag tables. Result entry (later increment) stores free-text `range`/`level`(Low|Medium|High)/`unit`/`result`. Richer analyte model is the highest-value Phase-2 clinical enhancement but requires [CR-05]. (Domain-expert F3; BA AC-9.1.)

### 5.2 ClinicClinician — affiliation ownership stays in IAM (default); masterdata table NOT created
Legacy affiliation is owned EXCLUSIVELY by `iam.Clinician.clinics` @ManyToMany; there is NO masterdata `clinic_clinician` table in legacy, and the affiliation **gate** (reject non-CLINICIAN-role users) was a prior-build GAP (R1–R4), i.e. net-new. **Do NOT create `md_clinic_clinician` in inc-02** until [CR-08] resolves ownership. The cross-module edge masterdata→iam (`IamLookupService`) is ratified and present; when affiliation is built, the CLINICIAN-role assertion uses `iamLookupService.findUser(userUid).map(UserSummary::roleNames).filter(r -> r.contains("CLINICIAN"))` — `UserSummary` already exposes `roleNames`. Affiliation endpoints (`POST/DELETE .../clinics/uid/{uid}/clinicians`) and the gate (`ErrorCode.CLINICIAN_ROLE_REQUIRED`, new) are [GATED:CR-08], built in sub-phase P4b only after ownership is decided.

### 5.3 ItemMedicineCoefficient math
Service computes `coefficient = medicineQty / itemQty` on save (BigDecimal, scale 6, `RoundingMode.HALF_UP` — data-architect to confirm rounding matches legacy double arithmetic for bit-comparable transfer qtys). Reject `itemQty<=0 || medicineQty<=0` → `"Zero values are not allowed"` (VALIDATION 400). Reject duplicate `(item,medicine)` → `"Coefficient already exist"` (CONFLICT 409, also enforced by `uq_imc_item_medicine`). Conversion `pharmacyQty = storeQty * coefficient`. The RN-path conversion inconsistency (commented out in legacy) is OUT OF SCOPE for inc-02 [CR-13, deferred to inc-05].

### 5.4 Document prefixes + sequences
[GATED:CR-09] adopt DB sequences (mechanism modernization, like `seq_usr_no` in V4) replacing legacy `MAX(id)+1`. [GATED:CR-10] adopt `SPTO`/`PPTO` (FIXES the legacy `SPT`-for-both collision defect). No document numbers are generated in inc-02, so both are deferrable with zero functional cost if not yet signed. Real legacy prefixes for reference: `GRN, LPO, PCN, PRL, PGRN, PPRN, PPR, PSR, MRNO` (+ legacy `SPT` for both TO streams).

### 5.5 Audit events
Every masterdata create/update/delete records via `AuditRecorder.record("masterdata.<Entity>", uid, AuditAction.CREATE|UPDATE|DELETE)` in the service transaction. This is the new tamper-evident append-only audit (inc-00 `audit_logs`), NOT a Hibernate Envers port — legacy has the Envers dependency but ZERO `@Audited` usage and no active audit tables (confirmed across all 5 extractions). **Do NOT add `@Audited` or any Envers config.** No device-fingerprint (phantom feature, confirmed absent). [CR-03 covers the net-new audit scope — pre-blessed by the engagement principle.]

### 5.6 Cross-module masterdata→iam edge
Already ratified (`@ApplicationModule(allowedDependencies = "iam")`). masterdata consumes `iam.lookup.IamLookupService` only. `ApplicationModules.verify()` + ArchUnit must stay green: no masterdata internal type crosses the boundary; no reverse `iam→masterdata` edge.

### 5.7 Test plan (qa-test-engineer)

- **Pricing parity (AC-1):** for each of 7 kinds, golden-master `resolve(null, kind, serviceUid, "TZS")` == legacy cash amount `CAST(double AS DECIMAL(19,2))`; insurance hit == plan row; consultation miss → hard-fail message; lab/rad/proc/med inpatient cash-VERIFIED fallback vs non-admitted UNPAID-stays; registration miss → silent UNPAID; covered=false behaves as cash; `active`/`min`/`max`/`currency` provably inert.
- **Ward (AC-2):** highest-covered selection, same-plan short-circuit, top-up principal/supplementary split, no-eligible→UNPAID. (Resolve primitive tested in inc-02; full bill split tested in billing increment but contract asserted here.)
- **Coefficient math (AC-3):** 1/3 → stored & read-back `0.333333` (no truncation to 4dp); `3 × 0.333333 = 1.000000` exact; zero-guard 400; duplicate 409; missing-coefficient NotFound.
- **Affiliation gate (AC-4) [GATED:CR-08]:** non-CLINICIAN userUid → 403 `CLINICIAN_ROLE_REQUIRED`; CLINICIAN → 201; same check for admin and seeder.
- **Uniqueness (AC-5):** duplicate `service_prices (plan_uid,kind,service_uid,currency)` → 409 (not 500, not silent), correctly handling NULL plan_uid (cash) and NULL service_uid (registration) via the COALESCE partial-unique index.
- **Sequence start (AC-7) [GATED:CR-09]:** each new sequence's first `nextval` returns 1; `SPTO`/`PPTO` prefix assertions; no `SPT` row in `md_document_types`.
- **Gate coverage (AC-8):** every mutation 403 without the required code, proceeds with it; privilege-diff CI gate green against the 35.
- **Reconciliation (AC-12):** per-table source==target counts; financial sum parity.
- **Module hygiene:** `ApplicationModules.verify()` + ArchUnit (no id exposure, no `now()` in masterdata internals, no cross-boundary entity leak) + Flyway `validate` against `postgres:16-alpine` Testcontainers.

---

## 6. RECOMMENDED BUILD CHUNKING (each independently `mvn verify`-able)

Ordered so unblocked legacy-faithful work proceeds while HIGH-impact CRs clear the gate. Each sub-phase ships DDL (its slice of V6 or a follow-on V#), entities, repos, mappers, services, controllers, tests — all green before the next.

- **P1 — Org units (no CR blockers).** Clinic (no ClinicType), Ward/WardType/WardCategory/WardBed, Pharmacy, Store, Theatre. Largest faithful chunk; unblocks downstream fastest. Gate writes with `ADMIN-ACCESS`/`MEDICINE_STOCK-UPDATE`/`ITEM_STOCK-UPDATE`.
- **P2 — Inventory catalog (no CR blockers; DEVIATION-1/2/3 gates default to ADMIN-ACCESS).** Item, Medicine, ItemMedicineCoefficient (AC-3 tests), Supplier, ItemSupplier, SupplierItemPrice. Free-text category/uom.
- **P3 — Clinical catalog (faithful; CR-05/CR-06 shape decisions).** LabTestType + LabTestTypeRange (named-string), RadiologyType, ProcedureType, DiagnosisType. Build legacy shape by default; richer models behind CRs.
- **P4 — Pricing/insurance [BLOCKED:CR-04, CR-11, CR-12].** InsuranceProvider, InsurancePlan, `service_prices` + `PriceLookup.resolve` (storage tier), insurance + cash-mirror seeds, reconciliation. Riskiest — do NOT start until CR-04 (storage shape) + CR-12 (ward semantics) signed.
  - **P4b — Affiliation + access [BLOCKED:CR-08, CR-15].** Clinic-clinician affiliation (location per CR-08) + net-new CLINICIAN gate; finalize the 4 gate deviations.
- **P5 — Stakeholders/system.** CompanyProfile delta (V8) + backfill; `md_document_types`/`md_currencies` + sequences ONLY if CR-09/CR-10 approved (else defer, zero functional cost); BusinessDay open/close/current admin endpoints.
- **P6 — Angular admin shell.** Standalone signal-based screens for all delivered entities + ServicePrice matrix grid + BusinessDay widget; regenerate OpenAPI client.

**Sequencing for engagement-lead:** batch-clear the low/expected CRs first (CR-01/02/03/11/14/16/17/18), then prioritize CR-04 + CR-12 (unblock P4) and CR-08 (unblock P4b). CR-05/06/07 default-to-faithful so P1–P3 proceed at risk-zero.

---

## 7. CONSOLIDATED DEVIATION / SIGN-OFF REGISTER (engagement-lead gates)

The three named engagement-lead gates are **CR-04 (ServicePrice consolidation), CR-16-seed (insurance seed values), CR-10 (SPTO/PPTO)** plus the full register below. Status `OPEN` = blocks the linked sub-phase; nothing OPEN may be built.

| CR | Spec says | Legacy reality / decision | Owner | Impact | Status |
|----|-----------|---------------------------|-------|--------|--------|
| CR-01 | "177 privilege codes" | 35 distinct (26 live). Doc correction. | engagement-lead | low | OPEN |
| CR-02 | `uid CHAR(26)` | `VARCHAR(26)` (directive + baseline). | data-architect | low | OPEN |
| CR-03 | New audit events per create/update | Net-new tamper-evident audit (no Envers, no `@Audited`). Pre-blessed by engagement principle. | security-architect | net-new | OPEN |
| **CR-04** | Unified `ServicePrice` replaces 7 tables | STORAGE replacement sound IF serviceUid nullable (REGISTRATION) / =Clinic (CONSULTATION), cash stays on catalog entity, §2.3 reproduced in code, LabTestPlanPrice excluded. | data-architect / engagement-lead | **HIGH** | OPEN |
| CR-05 | Analyte + ReferenceRange + RangeFlag | Does NOT exist in legacy. Default = reproduce flat. Richer = enhancement. | healthcare-domain-expert | HIGH | OPEN |
| CR-06 | "Diagnosis", ICD-friendly | Entity is `DiagnosisType`; no ICD/hierarchy. Rename; ICD = CR. | healthcare-domain-expert | med | OPEN |
| CR-07 | ItemCategory/UoM/DosageForm/Route/Frequency/Currency entities | All free-text; no Currency concept. Default = free-text + implicit single currency. | data-architect / HDE | med | OPEN |
| **CR-08** | `ClinicClinician` in masterdata | Owned by iam `Clinician.clinics`; no masterdata table in legacy; gate is net-new (R1–R4 gap). | solution-architect / engagement-lead | **HIGH** | OPEN |
| CR-09 | DB SEQUENCEs + `md_document_type` | Legacy = `MAX(id)+1`, no document_type table. Mechanism modernization (deferrable — no doc numbers in inc-02). | engagement-lead | med | OPEN |
| **CR-10** | `SPTO`/`PPTO` | Legacy emits `SPT` for BOTH (collision defect). Fix recommended; tell data-migration. | engagement-lead | med | OPEN |
| CR-11 | min/maxAmount + currency | No legacy source. Keep inert/nullable; must not drive behaviour. | data-architect | low | OPEN |
| CR-12 | Per-ward override | Legacy = WardType-only. Per-ward = enhancement; reproduce referral/top-up algorithm. Confirm referral semantics (F1). | HDE / engagement-lead | HIGH | OPEN |
| CR-13 | (implicit "apply coefficient") | RN paths skip conversion in legacy. Out of scope inc-02; resolve before inc-05. | HDE | deferred | OPEN |
| CR-14 | Company-profile second POST = 409 | Legacy silently deleteAll()+keep-one, zero validation. 409 = improvement (recommend approve). | engagement-lead | low | OPEN |
| CR-15 | Every admin endpoint gated | Legacy Item/Coefficient/Supplier writes + 14 InsurancePlan mutations effectively UNGATED. Gate all with `ADMIN-ACCESS` (DEVIATIONS 1–4); no new codes. | security-architect | med | OPEN |
| CR-16 | WardBed.no unique within ward | Not unique in legacy. Tightening = CR. | data-architect | low | OPEN |
| CR-16-seed | Insurance pricing seed values | Migrate 7 `*InsurancePlan` → `service_prices`; data-architect signs values + counts. | data-architect / engagement-lead | HIGH | OPEN |
| CR-17 | `ClinicType` enum | No ClinicType in legacy. Net-new = CR. | HDE / engagement-lead | low | OPEN |
| CR-18 | REGISTRATION serviceUid="DEFAULT" | Registration is plan-only keyed; use NULL serviceUid, not magic string. | data-architect | low | OPEN |

**Domain-expert blockers folded into CR-04 (B1/B2/B3):** the inc-02 spec / build contract MUST document (and §2.3 does) that the ward referral+top-up split and the per-service not-covered fallback asymmetry are reproduced in CODE, and that serviceUid is nullable (REGISTRATION) / =Clinic (CONSULTATION) with min/max/currency inert. With §2.3 present, the domain-expert's withheld sign-off condition is satisfied at the spec level; final sign-off occurs when CR-04 is ratified.

---

## RELEVANT FILE PATHS (all absolute)

- Build contract source spec (under reconciliation): `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\02-master-data.md`
- Migration dir (next = V6): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\` (V1–V5 immutable)
- Patterns to mirror: `...\backend\src\main\java\com\otapp\hmis\masterdata\domain\CompanyProfile.java`, `...\masterdata\application\CompanyProfileMapper.java`, `...\masterdata\application\CompanyProfileService.java`, `...\masterdata\api\CompanyProfileController.java`, `...\masterdata\application\dto\CompanyProfileDto.java`
- Base/shared: `...\shared\domain\AuditableEntity.java`, `...\shared\audit\AuditRecorder.java`, `...\shared\error\ErrorCode.java`
- Cross-module edge: `...\iam\lookup\IamLookupService.java`, `...\iam\lookup\UserSummary.java` (exposes `roleNames` — sufficient for CLINICIAN assertion), `...\iam\application\IamLookupServiceImpl.java`
- Module config: `...\masterdata\package-info.java` (`allowedDependencies = "iam"`)
- Legacy source of truth (READ-ONLY): `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\` — key: `service\PatientServiceImpl.java`, `api\InsurancePlanResource.java`, `api\ConversionCoefficientResource.java`, `accessories\Formater.java`, `domain\*InsurancePlan.java`, `domain\LabTestTypeRange.java`

**New `ErrorCode` constants to add (shared.error):** `SERVICE_PRICE_NOT_FOUND` (`urn:hmis:error:service-price-not-found`, 422), `DUPLICATE_SERVICE_PRICE` (CONFLICT 409), `CLINICIAN_ROLE_REQUIRED` (FORBIDDEN 403) [GATED:CR-08]. `ServicePriceNotFoundException` extends `HmisException`.

**Build gate for backend-engineer:** P1–P3 may proceed immediately (legacy-faithful, no blocking CR). P4/P4b are BLOCKED on CR-04+CR-12 / CR-08+CR-15. P5 sequences/document-types BLOCKED on CR-09/CR-10 (deferrable). Route all CR sign-off requests through `engagement-lead`.