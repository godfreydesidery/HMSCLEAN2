# Increment 02 — Master Data & Reference Seeding

> **⚠️ SUPERSEDED IN PART — read the as-built record first.** This planning doc pre-dates the legacy
> discovery and drifted on several points. The **authoritative, ratified, as-built** spec is in
> [`02-masterdata-discovery/`](02-masterdata-discovery/) — start with `00-build-spec.md`,
> `11-DECISIONS-RATIFIED.md`, and the review resolutions `15-REVIEW-RESOLUTIONS.md`. Legacy-verified
> corrections (all ratified): unified `ServicePrice` matrix for **insurance only** (cash stays on the
> catalog entity); flat legacy `LabTestTypeRange` (no analyte model); `DiagnosisType` (no ICD); free-text
> picklists/category/UoM (no lookup tables); clinic-clinician affiliation **owned by `iam`** (loose
> `clinicUids`) with the net-new CLINICIAN gate; WardType-only ward pricing; `SPTO`/`PPTO` (fixes the
> legacy `SPT` collision); `VARCHAR(26)` not `CHAR(26)`; 35-not-177 codes; catalogs **start empty**
> (no data migration — parity proven by golden-master test fixtures). The OpenAPI reference is committed
> at [`../../openapi/masterdata.yaml`](../../openapi/masterdata.yaml).
>
> **Delivery split:** the masterdata **backend** (this PR) is complete, reviewed (3-lens), and green
> (315 tests). The **Angular admin shell** (the ~18 admin screens in §DoD) is deferred to a dedicated
> follow-up increment per the engagement owner's decision.

## Goal

Deliver a fully operational `masterdata` Spring Modulith module — covering every reference catalogue the clinical and financial workflows depend on — seeded from legacy values via Flyway scripts, exposed through a versioned REST API, and verified by an Angular admin shell with RBAC-gated screens. After this increment, the system is bootstrappable from an empty database into a state where any subsequent clinical increment can start without manual admin setup.

## Scope

**Bounded context:** `masterdata` (plus contributions to the `shared` kernel and the `iam` module's privilege-seed migration).

**Key aggregates and entities:**

- `CompanyProfile` — single managed row (hospital name, address, TIN/VRN, logo reference, registration-fee configuration, currency default). Mapped from legacy `company_profile`.
- `Clinic` + `ClinicType` — named organisational units (General OPD, ANC, Dental, ENT, etc.) with `ClinicType` enum; each carries `uid`, `code`, `name`, `active`.
- `ClinicClinician` — M:N affiliation table `(clinicUid CHAR(26), userUid CHAR(26))`; lives in `masterdata`, may read `iam` to assert CLINICIAN role (allowed edge per ADR-0008/ADR-0020). No cross-module FK — loose coupling by uid only.
- `Ward` + `WardType` + `WardCategory` + `Bed` — wards roll up to a type/category; beds carry bed label and `available` flag; `WardType` is the pricing anchor for inpatient daily charges.
- `Pharmacy` — named dispensing location; each has its own `StockBalance` ledger (seeded at zero; actual stock arrives via GRN/RO flows in later increments). Carries `code`, `name`, `active`.
- `Store` — central warehouse. One record normally, schema allows multiple. Carries same shape as `Pharmacy`.
- `Theatre` — operating theatre; `code`, `name`, `active`.
- `Medicine` / `Item` + `ItemCategory` + `UnitOfMeasure` — the full item catalogue. `NUMERIC(19,6)` for quantity/coefficient per ADR-0009.
- `ItemMedicineCoefficient` — cross-unit conversion coefficients; `coefficient NUMERIC(19,6)` (never truncated, per PROCESS.md §16.3 and ADR-0009).
- `LabTestType` + `LabTestAnalyte` + `LabReferenceRange` — structured analyte catalogue with sex/age-banded numeric bounds and `RangeFlag` enum (NONE / LOW / HIGH / CRITICAL_LOW / CRITICAL_HIGH / ABNORMAL), sourced from prior-build V60 design (good idea, carry forward).
- `RadiologyType` — type catalogue; `code`, `name`, `active`, default cash price via `ServicePrice`.
- `ProcedureType` — same shape as `RadiologyType`.
- `Diagnosis` — ICD-friendly diagnosis catalogue; `code`, `description`.
- `DosageForm` + `Route` + `Frequency` — prescription picklists.
- `InsuranceProvider` + `InsurancePlan` — payer master; plan carries membership/card scheme metadata.
- `ServicePrice` — the unified pricing matrix `(planUid CHAR(26) NULLABLE, kind ServiceKind, serviceUid CHAR(26), currency VARCHAR(3), amount NUMERIC(19,2), covered BOOLEAN, minAmount NUMERIC(19,2), maxAmount NUMERIC(19,2))`; `planUid = NULL` is the cash row. Replaces the legacy six parallel `*InsurancePlan` tables (PROCESS.md §14 "Insurance management"; ADR-0011 sourcing note). `PriceLookup.resolve(patientPlanUid, kind, serviceUid, currency)` encapsulates cash-fallback.
- `Supplier` — vendor master; contact, tax, address. Required before any LPO/GRN in later increments.
- `Currency` — `md_currency` with `isDefault` flag; system default TZS seeded.
- `BusinessDay` aggregate lives in `shared` (already defined in ADR-0009) — its `openDay()` / `closeDay()` endpoints are surfaced here because they are administrative acts, not clinical ones.

**Key REST endpoints (all under `/api/v1/`):**

- `GET/POST /masterdata/company-profile` — read and update the single company record.
- `GET /masterdata/clinics`, `POST /masterdata/clinics`, `GET /masterdata/clinics/uid/{uid}`, `PUT /masterdata/clinics/uid/{uid}` — clinic CRUD.
- `GET /masterdata/clinics/uid/{uid}/clinicians`, `POST /masterdata/clinics/uid/{uid}/clinicians`, `DELETE /masterdata/clinics/uid/{uid}/clinicians/{userUid}` — affiliation management (scoped lookup is primary per ADR-0020; unscoped `GET /masterdata/clinicians/by-role/CLINICIAN` is admin-only).
- `GET/POST /masterdata/wards`, `GET/POST /masterdata/ward-types`, `GET/POST /masterdata/beds`.
- `GET/POST /masterdata/pharmacies`, `GET/POST /masterdata/stores`, `GET/POST /masterdata/theatres`.
- `GET/POST /masterdata/medicines`, `GET/POST /masterdata/item-categories`, `GET/POST /masterdata/units`.
- `GET/POST /masterdata/medicines/uid/{uid}/coefficients` — unit conversion CRUD.
- `GET/POST /masterdata/lab-test-types`, `GET /masterdata/lab-test-types/uid/{uid}/analytes`, `POST /masterdata/lab-test-types/uid/{uid}/analytes`.
- `GET/POST /masterdata/radiology-types`, `GET/POST /masterdata/procedure-types`.
- `GET/POST /masterdata/diagnoses`.
- `GET/POST /masterdata/dosage-forms`, `GET/POST /masterdata/routes`, `GET/POST /masterdata/frequencies`.
- `GET/POST /masterdata/insurance-providers`, `GET/POST /masterdata/insurance-providers/uid/{uid}/plans`.
- `GET/POST /masterdata/service-prices` — upsert a single pricing row; `GET /masterdata/service-prices?planUid=&kind=&serviceUid=` for lookup.
- `GET/POST /masterdata/suppliers`.
- `GET/POST /masterdata/currencies`.
- `POST /shared/business-days/open`, `POST /shared/business-days/close`, `GET /shared/business-days/current`.

**Process flows from PROCESS.md §14 implemented:**
- "Medical units" — clinics, wards, theatres, pharmacies, stores seeded and manageable.
- "Inventory masterdata" — medicines, units, conversion coefficients.
- "Medical operations" — lab test types with analyte ranges, radiology types, procedure types, diagnoses, dosage picklists.
- "Stakeholders" — suppliers, insurance providers and plans.
- "Insurance management" — `ServicePrice` matrix covering all six billable kinds (REGISTRATION, CONSULTATION, LAB, RADIOLOGY, PROCEDURE, MEDICINE, WARD_DAY).
- "Pricing" — per-clinic consultation fees, per-medicine prices, per-ward-type day rates, all as `ServicePrice` rows.

## Dependencies

- **Increment 00 (Walking Skeleton)** — must be complete. The `AuditableEntity` base class (hidden `id` BIGINT + `uid` CHAR(26)), the `shared` kernel (`Money`, `TxAuditContext`, `BusinessDay`), Flyway baseline, `ApplicationModules.verify()` gate, CI pipeline, and the Angular shell consuming the OpenAPI client must all exist before any `masterdata` entities, migrations, or screens are built.
- **Increment 01 (IAM / Users & RBAC)** — must be complete. `ClinicClinician.userUid` cross-references a user in `iam`; the `@PreAuthorize` gates on every masterdata endpoint reference the 177 privilege codes that Increment 01's `V__seed_privileges.sql` must have already inserted. The `masterdata → iam` allowed-dependency edge (for role assertion during affiliation write) requires the `iam.api` module to be published first.

## Exact-process fidelity targets

**Pricing matrix — cash-fallback logic (PROCESS.md §16.4):**
- `PriceLookup.resolve(planUid, kind, serviceUid, currency)` must return the plan-specific price row when one exists; otherwise fall back to the cash row (`planUid = NULL`); if neither exists, throw `ServicePriceNotFoundException` → RFC 7807 `ProblemDetail` with `type = "urn:hmis:error:service-price-not-found"` (ADR-0009 error-code pattern). Golden-master assertion: for every seeded service, `resolve(null, kind, serviceUid, "TZS")` returns the correct legacy cash amount rounded to `NUMERIC(19,2)` (`CAST(legacy_double AS DECIMAL(19,2))` per ADR-0011).
- Insurance-specific price takes strict precedence; the test scenario must drive both branches for each of the seven `ServiceKind` values.

**Unit-conversion coefficient math (PROCESS.md §16.3, ADR-0009 §3):**
- `ItemMedicineCoefficient.coefficient` stored as `NUMERIC(19,6)`. Golden-master assertion: a coefficient of exactly 1/3 seeded from the legacy must survive round-trip (read-back value must equal `0.333333` — six dp, no truncation to four). A pharmacy issue of quantity 3 at coefficient 0.333333 must produce a store decrement of exactly `1.000000` (not `0.999999` or `1.000001`). This directly tests the ADR-0009 §4 parity definition.

**Clinic-clinician affiliation gate (PROCESS.md §14, prior-attempt R1–R4):**
- `POST /masterdata/clinics/uid/{uid}/clinicians` must reject a `userUid` whose user does not hold the CLINICIAN role in `iam`; response is `403` with `ErrorCode.CLINICIAN_ROLE_REQUIRED`. The same check must fire whether the call comes from an admin or a system seeder. This gate, not just the data shape, is under golden-master coverage.

**ServicePrice uniqueness (PROCESS.md §14 "Insurance management"):**
- The combination `(planUid, kind, serviceUid, currency)` is `UNIQUE` in DDL. A duplicate upsert returns `409 CONFLICT` with `ErrorCode.DUPLICATE_SERVICE_PRICE`, not a silent overwrite or a 500. Golden-master test creates one row, attempts a duplicate POST, asserts 409.

**CompanyProfile single-row invariant (PROCESS.md §14 "Company profile"):**
- `POST /masterdata/company-profile` inserts if absent; `PUT /masterdata/company-profile` updates the single row. A second `POST` must return `409 CONFLICT`. Seeded via `V{n}__seed_company_profile.sql`; the seed script uses `INSERT ... ON CONFLICT DO NOTHING`.

**Document-number sequences (ADR-0009 §5):**
- Although document-bearing numbers (GRN, LPO, etc.) are not generated in this increment, all `SEQUENCE` objects defined in ADR-0009 (`seq_grn_no`, `seq_lpo_no`, `seq_pcn_no`, `seq_prl_no`, `seq_pprn_no`, `seq_psr_no`, `seq_ppr_no`, `seq_sto_no`, `seq_ptp_no`, `seq_pgrn_no`, `seq_mrno`, `seq_usr_no`) must be created in the DDL migration delivered by this increment so that the sequences start at 1 on a fresh deployment. Assertion: `SELECT last_value FROM seq_grn_no` returns `1` after migration, `0` before first use.

**SPTO / PPTO prefix (ADR-0009 §6):**
- The document-type enum seeded here must map `STORE_TO_PHARMACY_TO → "SPTO"` and `PHARMACY_TO_PHARMACY_TO → "PPTO"`. No row carrying prefix `"SPT"` may exist in the `md_document_type` seed table. Assertion: `SELECT prefix FROM md_document_type WHERE kind = 'STORE_TO_PHARMACY_TO'` returns `'SPTO'`.

**Privilege-code completeness (ADR-0011 §2):**
- After the seed migrations for this increment are applied, the privilege-code diff script (ADR-0011 implementation note) must show zero missing rows: every `@PreAuthorize` value in `masterdata` controllers must have a corresponding row in `iam_privilege`. This is a CI gate, not a manual check.

## Prior-attempt pitfalls to avoid

- **R1–R4 — Clinician-clinic affiliation absent at initial launch.** The prior build launched without `ClinicClinician`; booking offered all CLINICIAN-role users against all clinics. This increment must deliver the `md_clinic_clinician` table, the affiliation API, and the `book()` gate from day one (V56 equivalent, but designed here, not retrofitted later).
- **ADMIT-3 — Ward price per-ward vs per-type.** The prior build priced by ward type uniformly; the gap audit noted some wards needed per-ward overrides. `ServicePrice` rows in this increment must support a `serviceUid` pointing to either a `WardType.uid` (type-level price) or a `Ward.uid` (ward-level override), with type-level as the fallback. Document the resolution order in `PriceLookup.resolve()`.
- **PHARM-1 — No select-working-pharmacy session scoping.** The `Pharmacy` masterdata returned by `GET /masterdata/pharmacies` must include all fields needed for the Angular frontend to implement its pharmacy-selector context. The endpoint contract must be stable before the pharmacy-dispensing increment (Increment 05) because the `pharmacyUid` header/session context is set at this level. Do not defer this to the pharmacy increment.
- **DIAG-1 / M16 — Lab analyte and reference-range model must be complete now.** The prior build lost the specimen-custody audit trail partly because the analyte model was shallow. The `LabTestAnalyte` + `LabReferenceRange` (sex/age-banded, with `RangeFlag`) must be seeded in full in this increment, not added piecemeal later.
- **ADR-0009 — SPT collision.** The prior build (legacy and early rewrite both) had `StoreToPharmacyTO` and `PharmacyToPharmacyTO` sharing the `SPT` prefix. The `md_document_type` seed script must assign `SPTO` and `PPTO` as specified. Any copy-paste from legacy seed values must be audited against this.
- **M3 / BILL-6 — ServicePrice must cover REGISTRATION kind.** The prior build split registration and consultation fees into separate payment flows (BILL-6). In this increment, `ServicePrice(planUid=null, kind=REGISTRATION, serviceUid="DEFAULT")` must be seeded so that the registration-fee invoice path (built in Increment 03) has a valid price row from day one.

## Lead & supporting agents

- **Lead:** backend-engineer, data-architect
- **Supporting:** engagement-lead, healthcare-domain-expert, solution-architect, legacy-analyst, ux-ui-designer, frontend-engineer, qa-test-engineer, code-reviewer
- **Reviewers / sign-off:** security-architect (RBAC gates on admin endpoints), data-architect (insurance pricing seed values sign-off per ADR-0011), engagement-lead (SPTO/PPTO product-owner sign-off gate before prefix seed migration is committed)

## Definition of Done

- [ ] All DDL migrations for the `masterdata` module (`V{n}__masterdata_schema.sql`) applied cleanly by Flyway against a fresh `postgres:16-alpine` Testcontainers instance; `spring.jpa.hibernate.ddl-auto=validate` passes with no missing-column or type-mismatch errors.
- [ ] All document-number `SEQUENCE` objects created (`seq_grn_no` through `seq_usr_no`); start-value assertion test green.
- [ ] All Flyway seed migrations (`V{n+1}__seed_clinic_types_and_clinics.sql` through `V{n+11}__seed_dosage_picklists.sql`, plus company-profile, currencies, and insurance-pricing rows) applied; record counts match source legacy extract counts verified by data-architect.
- [ ] Privilege-code diff CI check is green: zero `@PreAuthorize` codes in `masterdata` controllers are absent from `iam_privilege` seed rows.
- [ ] `ApplicationModules.verify()` and all ArchUnit rules pass; no `masterdata` internal type crosses module boundary; `masterdata → iam` dependency is in the allowed list and no reverse edge (`iam → masterdata`) exists.
- [ ] `PriceLookup.resolve()` unit tests cover: plan-specific hit, cash-fallback, missing-both throws `ServicePriceNotFoundException`, all seven `ServiceKind` values, and the REGISTRATION kind.
- [ ] `ItemMedicineCoefficient` coefficient round-trip test: 1/3 seeded value reads back as `0.333333`; quantity math test (3 units × 0.333333 = 1.000000) passes.
- [ ] `ClinicClinician` affiliation: integration test asserts `POST .../clinicians` with a non-CLINICIAN-role user returns `403` with correct `ErrorCode`.
- [ ] `ServicePrice` uniqueness: integration test asserts duplicate POST returns `409 CONFLICT`.
- [ ] Golden-master parity tests (JUnit 5 + Testcontainers + legacy snapshot) green for: cash-price lookup for each of seven `ServiceKind` values; insurance-plan-specific price lookup for at least one plan per kind; clinician-affiliation gate enforcement.
- [ ] All `masterdata` REST endpoints documented in the OpenAPI spec (`springdoc`); spec regenerated and committed; Angular OpenAPI client regenerated.
- [ ] Angular admin screens delivered as standalone components using Angular 18 signals: CompanyProfile view/edit, Clinic list + detail + clinician-affiliation management, Ward/Bed list, Pharmacy list, Store list, Theatre list, Medicine list + coefficient table, Lab Test Type list + analyte detail, Radiology Type list, Procedure Type list, Diagnosis list, Dosage/Route/Frequency picklist management, Insurance Provider + Plan list, ServicePrice matrix grid, Supplier list, Currency list, BusinessDay open/close widget.
- [ ] Every admin endpoint is `@PreAuthorize`-gated with the appropriate privilege code; a Testcontainers integration test verifies that a request carrying a JWT without the required privilege receives `403`.
- [ ] Audit events emitted on every create/update (using `TxAuditContext` passed from the application layer; no direct `LocalDateTime.now()` calls inside `masterdata` internals — ArchUnit rule enforces this).
- [ ] `SPTO` and `PPTO` prefixes confirmed in `md_document_type` seed; engagement-lead sign-off recorded in the PR description before merge.
- [ ] Code-reviewer has approved the PR; no HIGH-severity findings remain open; all CI checks (build, Testcontainers, Modulith verify, Flyway validate, parity harness, SAST, image scan) are green on `main`.
