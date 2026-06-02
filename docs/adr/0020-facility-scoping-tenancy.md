# ADR-0020: Facility scoping & tenancy model
- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

## Context
The legacy (`com.orbix.api`) and the prior rewrite are both designed for a **single hospital**. The legacy `CompanyProfile` is a single-row entity (company name, TIN/VRN, logo, banks, `registrationFee`, prefixes) with **no** branch, facility, or tenant column. A grep for `branch|facility|tenant` across the entire legacy `domain` package returns **zero** matches. `Clinic`, `Pharmacy`, `Store`, and `Ward` each carry only `code`/`name`/`active` plus audit columns — **none has a parent-facility FK**. They are flat, named organisational units under one company roof.

The real scoping the legacy enforces is **intra-facility**, via two mechanisms:
1. **Hard scoping FKs on transactions.** `Consultation` has non-null `clinic_id` + `clinician_id` (clinician `updatable=true`, i.e. reassignable). Pharmacy stock is per-pharmacy: `PharmacyMedicine`, `PharmacyStockCard`, `PharmacySaleOrder`, and every transfer document (`StoreToPharmacyTO/RN`, `PharmacyToPharmacy*`, `PharmacyToStoreRO`) carry a `pharmacy_id`. `Prescription` records `issue_pharmacy_id`. `Visit.type` is INPATIENT/OUTPATIENT; `Ward` rolls up to `WardCategory`/`WardType`, again with no facility FK.
2. **Staff↔unit affiliation M:Ns.** Only two exist: `Clinician ⇄ Clinic` (a clinician works at one or more clinics; booking offers/accepts only that clinic's clinicians) and `StorePerson ⇄ Store` (a store keeper must be affiliated to authorise issue/transfer). Nurses, pharmacists, and cashiers are **facility-wide**; lab techs, radiographers, and theatre staff are **role-scoped**, not unit-affiliated. The prior attempt independently verified this exact split (`STAFF_CLINIC_RELATIONSHIPS_PLAN.md`, R1–R6) and restored both M:Ns as `md_clinic_clinician` (V56) and `md_store_staff` (V57).

The brief asks us to decide single- vs multi-tenant and define the data-scoping model without inventing tenancy the legacy lacks.

## Decision
**Single facility, multiple named internal units. NO multi-tenant / multi-hospital isolation.** We model exactly the intra-facility scoping the legacy enforces:

1. **One `CompanyProfile`** (single managed row, seeded master-data). No facility/branch/tenant entity, no tenant discriminator column on any table, no row-level tenant filter.
2. **Named units as flat masterdata:** `Clinic`, `Pharmacy`, `Store`, `Ward`, `Theatre` — each with its own `uid`, no parent-facility FK.
3. **Scope is carried on transactions, not on a tenant axis.** Consultations reference a `clinicUid`; pharmacy stock ledgers (`StockBalance`/`StockBatch`/`StockMovement`) and prescriptions reference a `pharmacyUid`; store ops reference a `storeUid`.
4. **Two staff↔unit affiliations only:** `ClinicClinician(clinicUid, userUid)` and `StoreStaff(storeUid, userUid)`, loosely coupled by uid (no cross-module FK), living in `masterdata` (which may depend on `iam` to assert the role). All other roles stay facility-wide or role-scoped — we do **not** add per-unit affiliation for nurses/pharmacists/cashiers/lab/radiology/theatre.
5. **Scoping enforced as a hard gate, not a filter.** `book()`/`transfer()` assert clinician∈clinic; store issue asserts keeper∈store; pharmacy dispensing/stock ops are scoped to a **selected working pharmacy** (session/header context, fixing prior gap PHARM-1). Lab/radiology/procedure worklists remain role-scoped.

## Considered alternatives
- **True multi-tenant (tenant_id discriminator + row-level security).** Rejected: the legacy has no tenant concept and no cross-facility patient/transfer protocol. Adding it would violate "do not invent tenancy the legacy lacks," inflate every key and index, and complicate the 177 `@PreAuthorize` semantics for zero in-scope benefit.
- **Database/schema-per-facility.** Rejected for the same reason; also breaks the single golden-master behavioural baseline.
- **Facility hierarchy (Facility → Clinic/Store/Pharmacy FK).** Rejected: legacy units are flat. A nullable `facilityUid` "for the future" is speculative coupling; defer until a real multi-hospital requirement exists.
- **Per-prescription `salesPharmacy` distinct from `issuePharmacy`.** The legacy has only `issue_pharmacy_id`; the prior build added a second `salesPharmacyUid`. We keep **issue pharmacy** as the canonical scope and record the dispensing pharmacy only where a transfer document already models it — no new field absent legacy precedent.

## Consequences
- Simplest correct model; no tenant plumbing to build, test, or secure. Indexes stay lean.
- Pharmacy/store cross-unit movement remains an explicit **document** (TO/RO/RN/GRN), preserving the legacy supplier→store→pharmacy→patient flow and per-pharmacy ledgers.
- A "selected working pharmacy" context must exist in the API contract and Angular frontend from day one (passed per dispensing/stock call), closing PHARM-1.
- If multi-hospital is ever required, it is a deliberate future ADR (new `Facility` aggregate + scope column), not a silent default.

## Exact-process impact
Process is preserved exactly: consultation booking is clinic+clinician-scoped with affiliation gating and reassignment; pharmacy stock and dispensing are pharmacy-scoped; store issue is store-affiliation-gated; reports aggregate over one facility. **RBAC × scope:** the privilege check (`@PreAuthorize`) answers "may this role act," and the scope gate answers "at this unit" — both must pass, as in the legacy. **Day workflow × scope:** the business `Day` (open/close) is **facility-wide**, not per-unit — a single open day gates all `createdOn` stamping across every clinic/pharmacy/store, matching `DayService.getDayId()` returning the single current open day. No reference/master-data is migrated; clinics, pharmacies, stores, wards, ward types/categories, and the single `CompanyProfile` are **seeded** (flag for ADR-NEW-D).

## Implementation notes
- No tenant column anywhere; no Hibernate tenant filter. Scope columns are plain `uuid` (`clinicUid`/`pharmacyUid`/`storeUid`), loosely coupled, no cross-module FK (per ADR-0003/0011).
- `ClinicClinician` and `StoreStaff` live in `masterdata`; `masterdata → iam` (allowed) for the role assertion. `encounter → masterdata + iam` for booking gates. Keep these edges green in `ApplicationModules.verify()`.
- Working-pharmacy context: a request-scoped `pharmacyUid` (header or session selection) injected into pharmacy worklist/stock services; reject stock ops without it.
- Scoped lookups are primary (`GET /masterdata/clinics/uid/{clinicUid}/clinicians`); the unscoped `by-role` lookup is admin-only.
- Lab/radiology/theatre stay role-scoped — do **not** add affiliation tables for them (verified faithful to legacy).
