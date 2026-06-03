# Increment 02 (Master Data) — Ratified Decisions

**Status:** RATIFIED by the engagement owner (user) on 2026-06-03. Binding over the build spec
([00-build-spec.md](00-build-spec.md)) where applicable. Unblocks the gated sub-phases.

## The four sign-off decisions
| CR | Decision |
|---|---|
| **CR-04 Pricing** | **Unified `ServicePrice` matrix for INSURANCE pricing; CASH price stays as a column on each catalog entity.** Reproduce the legacy resolve-time logic (ward referral + top-up co-pay split; per-service not-covered fallback asymmetry; consultation hard-fail) in the BILLING increment's code, consuming `PriceLookup.resolve` (storage tier). `min/max/currency` inert-nullable. `LabTestPlanPrice` excluded. (build-spec §2 is the contract.) |
| **CR-05/06/07 Catalog** | **Reproduce legacy exactly.** Flat string `LabTestTypeRange` (no analyte/numeric-bounds/flags); `DiagnosisType` with no ICD/hierarchy; dosage/route/frequency + item category/UoM stay free-text strings (no lookup tables); no `Currency` entity beyond the system `md_currencies` config row. Richer models are deferred enhancements (future CRs). |
| **CR-08 Affiliation** | **Build clinic–clinician affiliation + the net-new CLINICIAN-role gate NOW (P4b); ownership stays in `iam`** (legacy `Clinician.clinics` M:N). No masterdata `clinic_clinician` table. The CLINICIAN assertion uses `iam.lookup.IamLookupService` (`UserSummary.roleNames`). New `ErrorCode.CLINICIAN_ROLE_REQUIRED` (403). |
| **CR-12 Ward pricing** | **WardType-only** (legacy-faithful). No per-ward price column/override. `ServicePrice` for WARD keyed by `WardType.uid`. |

## Minor-CR defaults applied (legacy-faithful / recommended)
CR-01 (35 codes, not 177) · CR-02 (`VARCHAR(26)`) · CR-03 (new tamper-evident audit via `AuditRecorder`; NO Envers/`@Audited`) · CR-09 (DB sequences replacing `MAX(id)+1`) · **CR-10 (fix legacy `SPT` collision → `SPTO`/`PPTO`)** · CR-11 (`min/max/currency` inert-nullable) · CR-14 (company-profile 2nd POST → 409) · CR-15 (gate the legacy's ungated catalog/coefficient/supplier/insurance-price writes with the existing live `ADMIN-ACCESS`; no invented codes) · CR-16 (`WardBed.no` NOT unique — faithful) · CR-17 (no `ClinicType`) · CR-18 (REGISTRATION keyed by NULL `service_uid`, not a magic "DEFAULT" string).

## Seed scope (from the ratified "NO production-data migration")
- **Catalogs start EMPTY** (clinics, wards, medicines, items, lab/radiology/procedure types, diagnoses, insurance providers/plans, suppliers, service_prices) — admin-managed via the UI; no legacy data dump / ETL (consistent with the ratified no-migration decision; ADR-0011 re-scoped).
- **System/config seeds only:** `md_currencies` (TZS default), `md_document_types` (`SPTO`/`PPTO` + real legacy prefixes), document-number sequences (start 1), and the company-profile single row (already seeded V3; V8 delta adds columns, defaulted).
- **Parity proven by golden-master LOGIC tests** with test-scoped fixtures of known values (PriceLookup cash-fallback per kind; coefficient 1/3 → 0.333333 and 3×0.333333 = 1.000000; affiliation gate; ServicePrice uniqueness 409) — NOT against a legacy row-count extract.

## Build order (chunked, each independently `mvn verify`-able)
P1 org-units → P2 inventory → P3 clinical catalog (all legacy-faithful) → P4 pricing/insurance (ServicePrice + PriceLookup) → P4b affiliation+gate → P5 stakeholders/system (company-profile delta, document-types, currencies, sequences, BusinessDay endpoints) → P6 Angular admin shell + OpenAPI regen.
