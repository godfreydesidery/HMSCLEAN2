I have everything I need. The extraction findings, spec doc, current build state, and gate-code reality are all confirmed. Here is my deliverable.

---

# INCREMENT 02 — BA/PRODUCT-OWNER DELIVERABLE

Three parts: (1) testable acceptance criteria, (2) change-request register, (3) scope/chunking recommendation. Every criterion is verifiable by qa-test-engineer without legacy source. Items marked **[CR-PENDING]** must NOT be built until the linked change request is signed off by engagement-lead at the phase gate.

---

## PART 1 — ACCEPTANCE CRITERIA (Given/When/Then)

Convention: tables PLURAL snake_case; `uid VARCHAR(26)` (NOT CHAR — see CR-02); money `NUMERIC(19,2)`; qty/coefficient `NUMERIC(19,6)`; RFC7807 ProblemDetail; Flyway V6+. Data-classification noted where PHI/PII-adjacent.

### AC-1 — ServicePrice cash-fallback resolve, per kind (SAFETY-CRITICAL: financial)

The unified `service_prices` matrix is conditionally approved as a STORAGE model only (CR-04). The RESOLVE ALGORITHM must reproduce legacy `PatientServiceImpl` two-step exactly. Acceptance criteria are written against legacy-confirmed behaviour; the per-kind asymmetry is load-bearing.

- **AC-1.1 CONSULTATION cash** — Given a clinic with cash row `(planUid=NULL, kind=CONSULTATION, serviceUid=<clinicUid>, amount=X)`; When `resolve(NULL, CONSULTATION, clinicUid)`; Then returns `X` (= legacy `Clinic.consultationFee` CAST to NUMERIC(19,2)).
- **AC-1.2 CONSULTATION insurance hit** — Given a covered row `(planUid=P, kind=CONSULTATION, serviceUid=<clinicUid>, covered=true, amount=Y)`; When `resolve(P, CONSULTATION, clinicUid)`; Then returns `Y`.
- **AC-1.3 CONSULTATION insurance MISS = HARD FAIL** — Given NO covered row for `(P, clinicUid)`; When an INSURANCE consultation is priced; Then the operation throws and surfaces RFC7807 ProblemDetail with the legacy message text `"Plan not available for this clinic. Please change payment method"`. There must be NO cash fallback for consultation insurance. (legacy PatientServiceImpl:599-601)
- **AC-1.4 REGISTRATION cash** — Given cash row `(planUid=NULL, kind=REGISTRATION, serviceUid=NULL, amount=R)` sourced from `CompanyProfile.registrationFee`; When `resolve(NULL, REGISTRATION, NULL)`; Then returns `R`. serviceUid MUST be nullable for REGISTRATION (plan-only keyed in legacy).
- **AC-1.5 REGISTRATION fee-zero status** — Given `R = 0`; When a cash registration bill is created (Increment 03 consumer, asserted here at price level); Then status resolves `VERIFIED`. Given `R > 0`; Then status resolves `UNPAID`. (legacy PatientServiceImpl:273-277)
- **AC-1.6 REGISTRATION insurance miss = SILENT UNPAID** — Given INSURANCE patient, `R>0`, and NO covered `RegistrationInsurancePlan` row; When priced; Then the cash bill stays `UNPAID` with NO exception thrown (contrast AC-1.3). (legacy PatientServiceImpl:321 block skipped)
- **AC-1.7 LAB/RADIOLOGY/PROCEDURE/MEDICINE cash** — For each kind, Given cash row on the service entity; When `resolve(NULL, kind, serviceUid)`; Then returns the entity `price` (× qty for MEDICINE) CAST to NUMERIC(19,2).
- **AC-1.8 LAB/RAD/PROC/MED insurance MISS fallback asymmetry** — Given INSURANCE, no covered row, AND patient is INPATIENT; Then bill falls back to CASH price with status `VERIFIED`, attached to a null-plan invoice. Given the same but patient is NOT admitted; Then the initial cash `UNPAID` bill remains unchanged (no override, no exception). (legacy lab example :912-918)
- **AC-1.9 covered=false placeholder behaves as cash** — Given a persisted row `(planUid=P, covered=false, amount=0)`; When `resolve(P, kind, serviceUid)`; Then the covered lookup misses and behaviour follows the per-kind miss rule (it does NOT return 0). Resolution queries `covered=true` only. (legacy InsurancePlanResource:179-192)
- **AC-1.10 `active` is inert** — Given any row with `active=false`; Then resolve behaviour is UNCHANGED (legacy never consults `active`). qa asserts toggling `active` does not change any resolve result.
- **AC-1.11 minAmount/maxAmount/currency are inert** — Given any rows; Then `minAmount`, `maxAmount`, and `currency` MUST NOT alter any resolve output. Net-new columns carry no behaviour (CR-04, CR-11). A test must prove changing them changes nothing.

### AC-2 — WARD pricing: referral-override + top-up split (SAFETY-CRITICAL: financial) [DEPENDS ON CR-04, CR-12]

Ward is NOT a single keyed lookup. The resolve algorithm and PatientBill principal/supplementary linkage must be reproduced in code.

- **AC-2.1 Cash ward** — Given a bed whose `WardBed→Ward→WardType` has cash price `W`; When priced cash; Then ward bill `amount=balance=W`, status `UNPAID`. There is NO per-Ward and NO per-WardCategory price (CR-12).
- **AC-2.2 Eligible-plan selection (highest covered)** — Given INSURANCE plan P and a LIST of covered ward-type rows; When selecting; Then the row with the HIGHEST `price` is chosen as eligible, UNLESS a row's plan id equals the patient's plan id, in which case that row short-circuits selection (referral override). (legacy PatientServiceImpl:1801-1809)
- **AC-2.3 Covered ward bill** — Given an eligible plan; Then ward bill set COVERED at `eligiblePlan.price`, `balance=0`. (legacy :1812-1819)
- **AC-2.4 Top-up supplementary split** — Given the eligible plan is a DIFFERENT plan than the patient's AND `wardType.price - eligiblePlan.price > 0`; Then a SECOND PatientBill is created for the difference with status `UNPAID`, billItem `"Bed"`, description `"Ward Bed / Room (Top up)"`, linked via principal/supplementary references, attached to a null-plan (cash) invoice. (legacy :1881-1897)
- **AC-2.5 No eligible plan** — Given `eligiblePlan == null`; Then ward bed stays `UNPAID` at cash price (no exception; legacy throw is commented out). (legacy :1964-1966)
- **AC-2.6** The referral semantics (selecting highest-priced covered ward across all of a plan's covered ward types) require healthcare-domain-expert confirmation that this is intended, not a quirk — query open (CR-12). qa criteria reproduce legacy exactly pending that confirmation.

### AC-3 — ItemMedicineCoefficient round-trip (SAFETY-CRITICAL: clinical dosing/stock)

- **AC-3.1 Derivation** — Given `itemQty=3, medicineQty=1`; When saved; Then `coefficient = medicineQty/itemQty = 0.333333` stored as NUMERIC(19,6) and reads back EXACTLY `0.333333` (six dp, no truncation to four). (legacy ConversionCoefficientResource:95,101)
- **AC-3.2 Zero guard** — Given `itemQty=0` OR `medicineQty=0`; When saved; Then rejected with message `"Zero values are not allowed"` (RFC7807). (legacy :87-89)
- **AC-3.3 Uniqueness** — Given an existing coefficient for `(item, medicine)`; When a second is saved with a different uid; Then rejected with `"Coefficient already exist"` semantics → 409 CONFLICT. (legacy :83-86)
- **AC-3.4 Forward conversion parity** — Given `coefficient=0.333333`; When a transfer of store qty `3` is converted; Then `pharmacySKUQty = 3 × 0.333333 = 1.000000` exactly (not 0.999999 / 1.000001). qa must assert against rounding to scale 6. (legacy InternalOrderResource:595; StoreToPharmacyTOServiceImpl:424)
- **AC-3.5 Missing-coefficient guard** — Given no coefficient for `(item, medicine)`; When a TO conversion is attempted; Then `"Item to medicine conversion factor does not exist"` (NotFound). (legacy StoreToPharmacyTOServiceImpl:417-420)
- **AC-3.6 Inconsistent-application is OUT OF SCOPE for inc-02** — The RN paths and second internal-order path COMMENT OUT conversion (raw qty). This is consumed in later increments; flagged as query (CR-13). Inc-02 delivers only catalog CRUD + the coefficient entity; do NOT resolve the RN-vs-TO inconsistency here.

### AC-4 — Clinic-clinician affiliation gate [DEPENDS ON CR-08]

DATA-CLASSIFICATION: links a clinician (staff PII) to a clinic. Use anonymized userUid in tests.

- **AC-4.1 Role gate** — Given a `userUid` whose user does NOT hold CLINICIAN role in iam; When `POST .../clinics/uid/{uid}/clinicians`; Then `403` with a stable ErrorCode for "clinician role required". The check fires for admin callers AND seeders alike. (spec R1-R4; gate behaviour is net-new — legacy had no affiliation table)
- **AC-4.2 Happy path** — Given a CLINICIAN-role user; When affiliated; Then `201`/`200` and the affiliation is queryable.
- **AC-4.3 Ownership is a structural decision** — Legacy owns affiliation EXCLUSIVELY on iam `Clinician.clinics` @ManyToMany; there is NO masterdata `clinic_clinician` table in legacy. Whether to keep ownership in iam (legacy-faithful) or introduce a masterdata table is CR-08. AC-4.1/4.2 are written against the masterdata-table proposal but MUST NOT be implemented until CR-08 is signed off. If CR-08 resolves to "keep in iam", these ACs move to the iam module and the masterdata endpoints are dropped.

### AC-5 — ServicePrice uniqueness

- **AC-5.1** — Given an existing row `(planUid, kind, serviceUid, currency)`; When a duplicate is POSTed; Then `409 CONFLICT` (not silent overwrite, not 500), RFC7807 ProblemDetail. UNIQUE constraint enforced in DDL. NOTE: the uniqueness KEY differs from spec because consultation is keyed by Clinic and registration has serviceUid NULL — the constraint must permit NULL serviceUid (REGISTRATION) and NULL planUid (cash) (CR-04).

### AC-6 — CompanyProfile single-row invariant

- **AC-6.1 Update single row** — Given exactly one company profile row; When `PUT`; Then the single row updates.
- **AC-6.2 Second POST conflict** — Given a row exists; When a second `POST` is issued; Then `409 CONFLICT`.
  - **LEGACY-REALITY FLAG (CR-14):** legacy is NOT a 409 system. `CompanyProfileServiceImpl.saveCompanyProfile` SILENTLY `deleteAll()` then keeps one row; `validateCompany()` ALWAYS returns true (zero service validation). The 409 invariant is a behavioural CHANGE (improvement) and must be logged as CR-14 before being built. Pending sign-off, the legacy-faithful behaviour is "last write wins, single row enforced by delete-all."
- **AC-6.3 Fields** — Inc-00 stub has only `uid/name/address/phone`. The full legacy field set (companyName, contactName, logo @Lob, tin, vrn, address block, THREE bank-account blocks, quotationNotes, salesInvoiceNotes, registrationFee NUMERIC(19,2), publicPath, employeePrefix default "EMP") must be added in V6+. Migration must map legacy single-table `company_profile` (singular) to the inc-00 `company_profiles` (plural) shape.

### AC-7 — Document-number sequences / SPTO/PPTO [BOTH CR-PENDING — CR-09, CR-10]

These ACs are written for the spec's proposal but are GATED.

- **AC-7.1 [CR-09]** — Spec wants DB `SEQUENCE` objects starting at 1. LEGACY uses application-level `MAX(id)+1`, NOT sequences, NOT a `document_type` table. Adopting sequences is a data-model change requiring CR-09 sign-off (analogous to the double→BigDecimal pre-approval but NOT yet approved). Until signed off, do NOT create sequence objects or a `md_document_type` table. No document numbers are generated in inc-02 regardless, so this is deferrable with no functional cost.
- **AC-7.2 [CR-10]** — Spec wants prefixes `SPTO`/`PPTO`. LEGACY emits the SAME literal `"SPT"` for BOTH StoreToPharmacyTO and PharmacyToPharmacyTO (a real defect: streams can collide, distinguishable only by table). Splitting to SPTO/PPTO is a defect-fix CR (CR-10). Existing production data carries `SPT` for both — data-migration-engineer must be told. Until CR-10 sign-off, neither prefix is seeded.
- **AC-7.3 Other legacy prefixes (reference, for when sequences are approved):** GRN, LPO, PCN, PRL, received-notes `PGRN` (store→pharm) and `PPRN` (pharm→pharm), RO `PPR` (pharm→pharm) and `PSR` (pharm→store), USR (`USR-` + formatSix), Employee (`employeePrefix`+`/`+id), Patient (`MRNO/`+year+`/`+id). qa must cover empty-table edge (`MAX(null)+1` NPE swallowed → first doc `...-1`) and save-then-renumber entities where embedded number ≠ final id.

### AC-8 — @PreAuthorize 403 coverage (SAFETY-CRITICAL: access control)

- **AC-8.1** — For EVERY masterdata write endpoint, Given a JWT lacking the required privilege; Then `403`. Given a JWT with it; Then the operation proceeds.
- **AC-8.2 Gate codes constrained to the 35 legacy codes** — `@PreAuthorize` may use ONLY the 35 distinct legacy codes seeded in V2. Spec's "177 privilege codes" is a known miscount (CR-01) = annotation sites, not distinct codes.
- **AC-8.3 Legacy catalog writes were effectively UNGATED** — `ItemResource:187` (items/save) and `ConversionCoefficientResource:70` have their `@PreAuthorize` COMMENTED OUT, referencing DEAD codes (PROCUREMENT-ACCESS, ROLE-CREATE). `medicines/save` uses LIVE `ADMIN-ACCESS`. Choosing a real gate for catalog/coefficient writes is a SECURITY DECISION, not like-for-like reproduction → flag for security-architect (CR-15). Do NOT invent new codes; if no legacy code matches a masterdata endpoint, flag for a decision rather than minting a code. Per guardrail, RBAC must not be silently reduced.
- **AC-8.4 Privilege-diff CI gate** — Every `@PreAuthorize` value in masterdata controllers must exist in the seeded 35-code set; CI fails otherwise. (Spec's "zero missing vs iam_privilege" check, recast against the real 35.)

### AC-9 — Catalog-shape parity (LabTestType, RadiologyType, ProcedureType, DiagnosisType, picklists) [CR-05, CR-06, CR-07]

- **AC-9.1 LabTestTypeRange is a named string label only** — Given a LabTestType; Then its ranges are a per-type list of NAMED STRING LABELS (`name` only, scoped to one LabTestType). There are NO numeric bounds, NO sex/age bands, NO computed flags. Result entry stores free-text `range` (picklist string copied by value), `level` (hard-coded UI picklist `Low|Medium|High`), `unit`, `result`. The spec's `LabTestAnalyte`/`LabReferenceRange`/`RangeFlag` model does NOT exist in legacy (zero matches) → CR-05. Inc-02 reproduces legacy unless CR-05 approves the richer model.
- **AC-9.2 DiagnosisType (not "Diagnosis")** — entity is `DiagnosisType` with `code/name/description/active` only. NO ICD code, NO hierarchy, NO category, NO price/uom. Spec's "Diagnosis" + "ICD-friendly" is drift → CR-06. Target naming must be `DiagnosisType`.
- **AC-9.3 Picklists are free-text** — Dosage/Route/Frequency are free-text Strings on Prescription, NOT entities/enums. Spec's `DosageForm`/`Route`/`Frequency` entities are an enhancement → CR-07. Inc-02 does NOT introduce lookup tables unless CR-07 approves.
- **AC-9.4 LabTestType edit quirk** — on update, `code` cannot be changed and name/code are re-derived from the persisted row (LabTestTypeServiceImpl:47-48,62). Reproduce as exact legacy behaviour OR fix-via-CR; document either way.
- **AC-9.5 No bulk update endpoint** — Angular `PUT /lab_test_types/update_by_code` has no backend mapping (dead). Do NOT implement.

### AC-10 — Category / UOM / cash-price placement parity

- **AC-10.1** — `Item.category`, `Item.uom`, `Medicine.category` (default "MEDICINE"), `Medicine.uom`, `Medicine.type` are FREE-TEXT Strings — NO `ItemCategory`/`UnitOfMeasure` entity/enum in legacy. Spec's `ItemCategory`+`UnitOfMeasure` tables are an enhancement → CR-07. Reproduce free-text unless approved.
- **AC-10.2 Cash price stays on the catalog entity** — `Medicine.price`, `Item.costPriceVatIncl`/`sellingPriceVatIncl` are columns on the entity. The ServicePrice matrix is for INSURANCE-plan pricing + a migrated cash mirror row; the cash/default price must REMAIN a column on the catalog entity, else medicine cash dispensing breaks (CR-04 nuance). qa asserts cash medicine price is readable from the catalog row.
- **AC-10.3 Item vs Medicine separation** — two separate entities/tables joined only by ItemMedicineCoefficient; preserve the two-table split (no unification without CR).

### AC-11 — BusinessDay open/close (shared kernel)

- **AC-11.1** — `bussinessDate` (legacy typo preserved in mapping) LocalDate UNIQUE, one row per date; `status` default `STARTED`; open = `endedAt IS NULL`; close sets `endedAt`. Migration must map the legacy misspelled column name explicitly. Forensic stamps reference Day id.

### AC-12 — Data-continuity / reconciliation (do NOT defer)

- **AC-12.1** — For each migrated catalog (clinics, wards, ward_types, ward_categories, pharmacies, stores, theatres, items, medicines, coefficients, lab_test_types + ranges, radiology_types, procedure_types, diagnosis_types, suppliers, insurance_providers, insurance_plans, all 7 *InsurancePlan → service_prices rows), source row count == target row count, reconciled and signed off by data-architect.
- **AC-12.2** — `LabTestPlanPrice` is DEAD (no repo/service/usage). Migration must confirm whether any production rows exist; default is EXCLUDE (do not model as live lab pricing). Decision logged (CR-04 sub-item).
- **AC-12.3** — Legacy `double` money → `NUMERIC(19,2)` via `CAST(legacy_double AS DECIMAL(19,2))`; qty/coefficient → `NUMERIC(19,6)`. Rounding rules documented; data-architect confirms scale-6 sufficiency for the `medicineQty/itemQty` division.
- **AC-12.4** — Join-table physical names (clinic↔clinician, store↔storeperson) are NOT in legacy source (Hibernate-default-derived). Read actual names from deployed legacy DDL before writing migration; do NOT assume.

---

## PART 2 — CHANGE-REQUEST REGISTER

Approval authority: **engagement-lead** at the phase gate. My role is to maintain and impact-assess. Status `OPEN` = not yet approved → blocks the linked build. Nothing marked OPEN may be implemented.

| CR | SPEC SAYS | LEGACY REALITY | RECOMMENDED DECISION | SIGN-OFF OWNER | STATUS |
|----|-----------|----------------|----------------------|----------------|--------|
| **CR-01** | "177 privilege codes" | 35 distinct codes (26 live gates; 9 dead). "177" = annotation sites. | Correct spec to 35; gates use ONLY the 35. Doc-only correction. | engagement-lead | OPEN (low impact) |
| **CR-02** | `uid CHAR(26)` | Directive + inc-00 reality = `VARCHAR(26)`. | Use VARCHAR(26). Doc/DDL correction. | data-architect / engagement-lead | OPEN (low impact) |
| **CR-03** | New audit events on every create/update via TxAuditContext; ArchUnit no-`now()` rule | Legacy has manual forensic triplet (createdBy/createdOn/createdAt); NO Envers, NO @Audited. New tamper-evident audit is net-new (engagement principle). | Approve as NET-NEW (pre-blessed by engagement principle); confirm scope = forensic triplet + new audit, NOT Envers port. | security-architect / engagement-lead | OPEN (net-new, expected) |
| **CR-04** | Unified `ServicePrice(planUid,kind,serviceUid,currency,amount,covered,minAmount,maxAmount)` REPLACES 7 *InsurancePlan tables | Storage replacement is sound IF: serviceUid nullable (REGISTRATION), CONSULTATION.serviceUid=Clinic, cash price stays on catalog entity, resolve algorithm + ward principal/supplementary preserved in CODE, LabTestPlanPrice excluded as dead. | Approve as STORAGE model with the 6 documented constraints. Resolve logic is code, not row-lookup. | data-architect / engagement-lead | OPEN (HIGH impact) |
| **CR-05** | `LabTestAnalyte`+`LabReferenceRange`+`RangeFlag` (sex/age bands, numeric bounds, 6-value flag enum) "carry forward" | Does NOT exist in legacy (zero matches). Legacy = named string ranges + free-text range/`level`(Low\|Medium\|High)/unit on each result. | Default = reproduce legacy. Richer model = approved ENHANCEMENT only via this CR; if approved, label clearly as net-new clinical capability. | healthcare-domain-expert / engagement-lead | OPEN (HIGH impact) |
| **CR-06** | "Diagnosis", ICD-friendly | Entity is `DiagnosisType`; NO ICD, NO hierarchy, NO category. | Rename to `DiagnosisType`; ICD coding = enhancement requiring this CR. | healthcare-domain-expert / engagement-lead | OPEN (med impact) |
| **CR-07** | `ItemCategory`, `UnitOfMeasure`, `DosageForm`, `Route`, `Frequency`, `Currency` entities | All are free-text Strings; NO Currency concept anywhere (single implicit currency). | Default = preserve free-text + implicit single currency. Reference tables/enums + explicit Currency = enhancement via this CR. | data-architect / healthcare-domain-expert / engagement-lead | OPEN (med impact) |
| **CR-08** | `ClinicClinician` table in `masterdata` (uid-only edge to iam) | Affiliation owned EXCLUSIVELY by iam `Clinician.clinics` @ManyToMany; NO masterdata table in legacy. Note: legacy had NO affiliation gate at all (R1-R4 was the GAP). | Decide ownership: keep in iam (legacy-faithful) vs masterdata table (structural relocation). The affiliation GATE itself is net-new (legacy lacked it) — also flag as new scope. | solution-architect / engagement-lead | OPEN (HIGH impact) |
| **CR-09** | DB `SEQUENCE` objects + `md_document_type` table; sequences start at 1 | Legacy = application `MAX(id)+1`, NO sequences, NO document_type table. | Approve sequence adoption as data-model-only change (process-equivalent) OR reproduce MAX(id)+1. No doc numbers generated in inc-02 → defer either way. | engagement-lead | OPEN (med impact, deferrable) |
| **CR-10** | Prefixes `SPTO` / `PPTO`; no `SPT` row allowed | Legacy emits SAME `"SPT"` for BOTH TO streams (collision defect). | Approve defect-fix to SPTO/PPTO (recommended) OR reproduce SPT-for-both. Tell data-migration-engineer existing data is `SPT`. | engagement-lead | OPEN (med impact) |
| **CR-11** | `minAmount`/`maxAmount` columns; `currency VARCHAR(3)` | NO min/max/copay/cap/currency anywhere in legacy. Co-pay = ward principal/supplementary split only. | Keep columns inert (nullable, never read) OR drop. Populating them = invention. Currency defaults, drives no behaviour. | data-architect / engagement-lead | OPEN (low impact) |
| **CR-12** | "Ward-level override" `serviceUid → Ward.uid OR WardType.uid`, type-level fallback (ADMIT-3) | Legacy ward pricing is WardType ONLY; NO per-Ward, NO per-WardCategory price. Co-pay = referral-override + top-up split. | Per-Ward override = enhancement via this CR. Default = WardType-only + reproduce referral/top-up algorithm. Confirm referral semantics aren't a quirk. | healthcare-domain-expert / engagement-lead | OPEN (HIGH impact) |
| **CR-13** | (n/a — implicit "apply coefficient") | Coefficient applied in TO + one internal-order path; COMMENTED OUT in RN paths + second internal-order path (raw qty). | Confirm whether RN path intentionally skips conversion (RN qty already in pharmacy units) or is a latent bug. Out of scope for inc-02 build; resolve before inc-05. | healthcare-domain-expert / engagement-lead | OPEN (deferrable to inc-05) |
| **CR-14** | `POST` company-profile second time = 409; PUT updates | Legacy SILENTLY deleteAll()+keep-one; `validateCompany()` always true (zero validation). | 409 + validation = improvement via this CR. Default = last-write-wins single-row. Recommend approving 409 (safer). | engagement-lead | OPEN (low impact) |
| **CR-15** | Every admin endpoint @PreAuthorize-gated | Legacy catalog/coefficient writes effectively UNGATED (commented-out gates referencing DEAD codes); medicines/save uses live ADMIN-ACCESS. | Choose a REAL gate (likely ADMIN-ACCESS) per endpoint; do NOT invent codes. Tightening ungated→gated is a security improvement — log it, don't silently reduce. | security-architect / engagement-lead | OPEN (med impact) |
| **CR-16** | WardBed.no implied unique within ward | `WardBed.no` is NOT DB-unique in legacy. | Adding `(ward_id, no)` unique = behavioural tightening via this CR. | data-architect / engagement-lead | OPEN (low impact) |
| **CR-17** | `ClinicType` enum on Clinic | NO ClinicType enum/entity; Clinic has no type field. | Adding clinic type = net-new via this CR; do not invent silently. | healthcare-domain-expert / engagement-lead | OPEN (low impact) |
| **CR-18** | `ServicePrice.serviceUid="DEFAULT"` for REGISTRATION (M3/BILL-6) | Registration is plan-only keyed, NO service FK; serviceUid should be NULL not a magic "DEFAULT" string. | Use NULL serviceUid for REGISTRATION (matches legacy keying); avoid magic-string. | data-architect / engagement-lead | OPEN (low impact) |

**NEW-SCOPE / no-legacy-behaviour items** (acceptance criteria MUST be labelled net-new, not legacy-reproduction): CR-03 (audit), CR-05 (analyte model if approved), CR-06 (ICD), CR-07 (reference tables + currency), CR-08 (affiliation gate + masterdata ownership), CR-12 (per-ward override), CR-11 (min/max/currency). No Insurance/Claims claim-submission scope appears in inc-02; if any is later proposed it must be flagged NEW SCOPE per M22 (legacy has no claims workflow).

---

## PART 3 — SCOPE / CHUNKING RECOMMENDATION

**Recommendation: CHUNK into 5 ordered sub-phases. Do NOT deliver as one.**

Rationale: inc-02 as specified is ~25 entities + a unified pricing matrix with non-trivial resolve logic + ~12 seed migrations + a full Angular admin shell + 7+ HIGH-impact open CRs. Delivering monolithically forces engineering to either block on all CRs at once or guess — both violate the engagement guardrails. Chunking lets the unblocked, legacy-faithful work proceed while HIGH-impact CRs (CR-04/05/08/12) clear the phase gate, and keeps each PR reviewable. Critically, chunking sequences CR sign-off so backend/frontend never build on unapproved deviations.

**Sub-phase 02a — Plain organizational + inventory catalogs (NO open CR blockers).**
Clinic (no ClinicType), Ward/WardType/WardCategory, WardBed, AdmissionBed split, Pharmacy, Store, Theatre, Item, Medicine, ItemMedicineCoefficient (with AC-3 round-trip tests), Supplier/ItemSupplier/SupplierItemPrice. Free-text category/uom (pending CR-07). Full CompanyProfile field expansion (CR-14 covers the 409 nuance — ship last-write-wins if CR-14 not yet signed). DDL V6+, MapStruct mappers, @PreAuthorize using ADMIN-ACCESS pending CR-15. This is the largest faithful chunk and unblocks downstream increments fastest.

**Sub-phase 02b — Clinical catalogs (faithful) [needs CR-05, CR-06 decisions before final shape].**
LabTestType + LabTestTypeRange (named-string model unless CR-05 flips it), RadiologyType, ProcedureType, DiagnosisType (NOT "Diagnosis"). Build the legacy-faithful shape by default; hold the analyte-model alternative behind CR-05. Picklists stay free-text pending CR-07.

**Sub-phase 02c — Pricing matrix + resolve [BLOCKED on CR-04, CR-11, CR-12, CR-18].**
`service_prices` storage model + migration of 7 *InsurancePlan tables + cash mirror rows; `PriceLookup.resolve` reproducing AC-1 per-kind asymmetry; ward referral/top-up (AC-2) in code; LabTestPlanPrice excluded. This is the riskiest chunk — must NOT start until CR-04 (storage shape) and CR-12 (ward semantics) are signed. Cash price stays on catalog entities (AC-10.2).

**Sub-phase 02d — Affiliation + access decisions [BLOCKED on CR-08, CR-15].**
Clinic-clinician affiliation (location TBD by CR-08: iam vs masterdata) + the net-new role gate (AC-4). Finalize @PreAuthorize gate codes per CR-15. Do not build the masterdata ClinicClinician table until CR-08 resolves ownership.

**Sub-phase 02e — Seeds, sequences, Angular admin shell, reconciliation.**
Seed migrations + count reconciliation (AC-12); sequences/document-type ONLY if CR-09/CR-10 approved (else defer with zero functional cost — no doc numbers in inc-02); Angular standalone signal-based admin screens for all delivered entities; BusinessDay open/close widget (AC-11). Data-continuity (AC-12) is delivered IN this increment, not deferred.

**Sequencing note for engagement-lead:** the phase gate should clear CR-01/02/03/11/14/16/17/18 (low/expected) early as a batch, then prioritize CR-04 and CR-12 (block 02c) and CR-08 (blocks 02d). CR-05/06/07 affect shape but default-to-faithful, so 02a/02b can proceed at risk-zero by building the legacy shape and treating the richer models as additive follow-ons if approved.

---

**Relevant file paths (all absolute):**
- Spec under reconciliation: `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\02-master-data.md`
- Migration directory (NEXT = V6+): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\` (existing V1–V5)
- Legacy gate-code seed (the real 35): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V2__seed_iam.sql`
- CompanyProfile stub to expand: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V3__seed_company_profile.sql`
- Legacy source of truth (READ-ONLY): `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\` (key: `service\PatientServiceImpl.java`, `api\InsurancePlanResource.java`, `api\ConversionCoefficientResource.java`, `accessories\Formater.java`, `domain\*InsurancePlan.java`)