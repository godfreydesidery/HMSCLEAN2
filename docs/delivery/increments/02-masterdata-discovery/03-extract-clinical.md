# EXTRACTION 3 — Clinical Catalog (Lab / Radiology / Procedure / Diagnosis / Prescription picklists)

Legacy source root: `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api` (package `com.orbix.api`). Angular: `D:\My_Works\HMS\ZANAHMIS-2-feature\zana-hmis\src\app`.

## Early-discovery findings (carry into any audit/auth spec)
- **No Hibernate Envers audit trail is effectively active in the legacy system.** No `@Audited` annotation appears on any clinical-catalog entity inspected (`LabTestType`, `LabTestTypeRange`, `RadiologyType`, `ProcedureType`, `DiagnosisType`, `LabTest`, `Prescription`, `PatientPrescriptionChart`). These entities carry only manual audit columns (`created_by_user_id`, `created_on_day_id`, `created_at`). Downstream agents must not assume an Envers baseline.
- **No device-fingerprint / device-binding feature** appears in any clinical-catalog code path examined. (Full confirmation belongs to the IAM/security extraction; nothing in this extraction contradicts the "phantom feature" finding.)

## CRITICAL FINDING — the spec's "LabTestAnalyte + LabReferenceRange + RangeFlag" model does NOT exist in legacy

The strings `Analyte`, `ReferenceRange`, and `RangeFlag` (and the enum values `CRITICAL_LOW`/`CRITICAL_HIGH`/`ABNORMAL`) appear **nowhere** in the legacy backend or Angular app. A repo-wide grep across `com.orbix.api` returned **zero** matches. The only place these terms exist is inside HMSCLEAN2 design docs (`docs/delivery/increments/02-master-data.md`, `06-lab-radiology-procedure-theatre.md`, `docs/architecture/*`, `docs/adr/*`). The "prior-build V60 design" is therefore a **forward-looking proposal, not extracted legacy behaviour.** It must be treated as a candidate enhancement requiring an explicit `engagement-lead` change request, not as "exact process."

### What legacy `LabTestTypeRange` actually provides (the REAL lab model)

`LabTestTypeRange.java:37-54` — the entity has exactly these business fields:
- `id` (Long, IDENTITY) — `LabTestTypeRange.java:38-40`
- `name` (String, `@NotBlank`, defaults to `""`) — `LabTestTypeRange.java:41-42`
- `labTestType` (`@ManyToOne`, FK `lab_test_type_id`, non-null) — `LabTestTypeRange.java:44-47`
- plus manual audit columns — `LabTestTypeRange.java:49-53`

There is **no** `low`, `high`, `min`, `max`, `unit`, `sex`, `gender`, `ageFrom`, `ageTo`, `flag`, or `abnormalMarker` field. A `LabTestTypeRange` is nothing more than a **named string label scoped to one `LabTestType`** — a dropdown picklist source. There are **no sex bands, no age bands, no numeric bounds, and no abnormal/flag classification logic anywhere.**

`LabTestType.java:43-71` owns the ranges via `@OneToMany ... fetch = EAGER, orphanRemoval = true` (`LabTestType.java:60-64`). `LabTestType` itself has: `code` (unique, `@NotBlank`), `name` (unique, `@NotBlank`), `description`, `price` (primitive `double`, `@NotNull`), `uom` (String), `active` (boolean, default false). Note `@NotNull` on a primitive `double` is a no-op (price always present).

### How ranges/levels are consumed at result-entry time (free-text strings, no derivation)

The result is recorded on the separate `LabTest` entity, NOT on `LabTestTypeRange`. `LabTest.java:46-55` stores results as **flat free-text Strings**: `result` (String), `report` (String, len 10000), `description`, `range` (DB column `rrange`, String — `LabTest.java:51-52`), `level` (String), `unit` (String), `status` (String). There is **no FK from a `LabTest` result to a `LabTestTypeRange` row** — `range` holds the *text* of the chosen range name, copied by value.

Angular result form (`zana-hmis/src/app/pages/laboratory/lab-test/lab-test.component.html`):
- **Range** is a `<select>` whose `<option>`s iterate `labTest.labTestType.labTestTypeRanges` and bind `{{range.name}}` (the string) into `labTest.range` — `lab-test.component.html:52-56`. So the range stored is the **picklist string**, chosen manually.
- **Level** is a `<select>` with **three hard-coded string options: `Low`, `Medium`, `High`** (plus a blank) bound into `labTest.level` — `lab-test.component.html:59-65`. This is the entire "abnormal flag" concept in legacy: a manually chosen 3-value string. It is NOT computed, NOT an enum, and does NOT match the spec's RangeFlag set (NONE/LOW/HIGH/CRITICAL_LOW/CRITICAL_HIGH/ABNORMAL).
- `result` is a free-text `<textarea>` (editable only while status = `COLLECTED`) — `lab-test.component.html:47-49`; `unit` is a free-text input — `lab-test.component.html:69`.
- The component POSTs only `{id, result, range, level, unit}` to the patient lab-test endpoints (`lab-test.component.ts:137-143`, `277-283`). No min/max or flag is ever sent.

`LabTestTypeRangeServiceImpl.save` does a self-assign no-op (`labTestTypeRange.setName(labTestTypeRange.getName())`, `LabTestTypeRangeServiceImpl.java:42`) then stamps audit fields — no validation/derivation. `LabTestTypeRangeResource` exposes list-by-type, get-by-id, get-all-names (`getNames()` JPQL `SELECT l.name FROM LabTestTypeRange l`, `LabTestTypeRangeRepository.java:21-22`), delete, and save. The Angular create form sends only `{name, labTestType:{id}, active}` — `lab-test-type-range.component.ts:65-69`.

**Decision for the firm:** reproduce-legacy = a per-`LabTestType` list of named string labels + a free-text `range`/`level`(Low|Medium|High)/`unit` on each result, with NO numeric reference ranges and NO sex/age banding. The richer model would be a behavioural *addition* (new clinical capability), not a faithful reproduction. This needs an explicit change-request decision.

## RadiologyType (`RadiologyType.java:38-60`)
Fields: `id`; `code` (unique, `@NotBlank`); `name` (unique, `@NotBlank`); `description` (String); `price` (`double`, `@NotNull` — no-op on primitive); `uom` (String); `active` (boolean, default false); manual audit columns. Identical master-data shape to `LabTestType` minus the ranges collection. Table `radiology_types`. Save gated by `@PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")` — `RadiologyTypeResource.java:73-74`.

## ProcedureType (`ProcedureType.java:38-59`)
Fields: `id`; `code` (unique, `@NotBlank`); `name` (unique, `@NotBlank`); `description`; `price` (`double`, `@NotNull`); `uom`; `active` (default false); manual audit columns. Table `procedure_types`. Save gated `ADMIN-ACCESS` — `ProcedureTypeResource.java:77-78`.

## DiagnosisType (`DiagnosisType.java:37-55`) — the diagnosis catalogue
Fields: `id`; `code` (unique, `@NotBlank`); `name` (unique, `@NotBlank`); `description` (String); `active` (default false); manual audit columns. Table `diagnosis_types`. **No `price`/`uom`** (differs from the other three). **No ICD code, no ICD version, no parent/child hierarchy, no category** — an ICD search across `com.orbix.api` returned zero matches. The catalogue entity is `DiagnosisType` (NOT `Diagnosis`); the spec's "Diagnosis" naming is wrong, consistent with prior memory. Angular form sends only `{id, code, name, description, active}` — `diagnosis-type.component.ts:72-78`. Save gated `ADMIN-ACCESS` — `DiagnosisTypeResource.java:73-74`. `LabTest` may reference an optional `DiagnosisType` (`LabTest.java:60-63`).

## Prescription picklists — Dosage form / Route / Frequency
These are **free-text Strings on the `Prescription` entity, NOT entities and NOT enums.** `Prescription.java:42-45`: `dosage` (String), `frequency` (String), `route` (String), `days` (String). Also `qty` (`double`, `@NotNull`), `issued` (double, default 0), `balance` (double), `status` (String), `instructions` (String), `paymentType`/`membershipNo` (Strings). No `Dosage`, `Frequency`, or `Route` domain class exists (glob returned none). `PatientPrescriptionChart.java:38` independently carries its own free-text `dosage` String for administration charting (plus `output`, `remark`). There is no controlled vocabulary, lookup table, or enum constraining these values anywhere in legacy.

**Decision for the firm:** if the modern build wants controlled picklists for dosage form/route/frequency, that is an *addition* to legacy (which is free-text), requiring an explicit change request — not exact process.

## Cross-reference index (legacy artefact -> target bounded context: Master Data / Clinical Catalog)
- `LabTestType` / `lab_test_types` -> `LabTestTypeResource`, `LabTestTypeServiceImpl`, `LabTestTypeRepository`
- `LabTestTypeRange` / `lab_test_type_ranges` -> `LabTestTypeRangeResource`, `LabTestTypeRangeServiceImpl`, `LabTestTypeRangeRepository`
- `RadiologyType` / `radiology_types` -> `RadiologyTypeResource`
- `ProcedureType` / `procedure_types` -> `ProcedureTypeResource`
- `DiagnosisType` / `diagnosis_types` -> `DiagnosisTypeResource`
- `Prescription.dosage|frequency|route` (free-text) -> Pharmacy/Prescription context (out of master-data scope, referenced here for picklist provenance)

## Ambiguities discovered
1. **Dead/broken legacy endpoint:** Angular `updateLabTestTypes()` issues `PUT /lab_test_types/update_by_code` (`lab-test-type.component.ts:458`) but `LabTestTypeResource.java` defines **no** `update_by_code` mapping (grep returned no matches). The Excel bulk-update path is non-functional in legacy. Do not reproduce this broken call; flag whether bulk update was ever intended.
2. **`LabTestType.save` update branch swallows name/code edits:** in `LabTestTypeServiceImpl.save` the update branch re-derives `code`/`name` from the *already-persisted* `testType` (`LabTestTypeServiceImpl.java:47-48`) rather than from the incoming payload, then later sets `name` from the payload (`:62`) but never `code`. Net effect: on edit, `code` cannot be changed and name handling is inconsistent. Document as the exact (quirky) legacy behaviour.
3. **`@NotNull` on primitive `double price`** (`LabTestType.java:55-56`, `RadiologyType.java:49-50`, `ProcedureType.java:49-50`) is a no-op — price is never actually required at validation and defaults to 0.0. Confirm intended minimum/required-price rule with domain expert.
4. **`Level` picklist values** are hard-coded UI strings `Low|Medium|High` (`lab-test.component.html:62-64`) with no backend constraint; the backend `level` column accepts any string. Confirm whether these three values are the authoritative domain set.