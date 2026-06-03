All three critical pricing paths are confirmed exactly as extracted: consultation hard-fail (line 600), lab inpatient cash-fallback with status VERIFIED (lines 912-918), and the ward referral-override + supplementary top-up bill split (lines 1799-1949). I have what I need for the clinical validation report.

---

# CLINICAL VALIDATION REPORT — Pricing/Insurance, Lab Model, Clinical Catalog
## Healthcare/HMIS Domain Expert sign-off (Increment 02 master data)

Verified against legacy `PatientServiceImpl.java` (lines 594-616, 822-918, 1795-1966) and `LabTest.java`. All findings ground in the legacy source of truth.

---

## Q1 — Does a UNIFIED ServicePrice matrix faithfully preserve legacy CASH + INSURANCE pricing across all kinds?

**VERDICT: CONDITIONAL PASS as a STORAGE model. BLOCKER if treated as a resolution model.** The matrix can hold the price rows, but five legacy pricing behaviours are **resolve-time application logic that is NOT expressible as a row lookup** and will be silently LOST if the team assumes "the matrix replaces the 7 tables" end-to-end. From a clinical/operational standpoint these are not cosmetic — they determine what the patient pays at the point of care and what gets claimed against NHIF.

### Pricing nuances the matrix MUST capture (storage) — per kind

| Kind | Cash source (legacy) | Insurance source | serviceUid references | Notes |
|---|---|---|---|---|
| REGISTRATION | `CompanyProfile.registrationFee` | `RegistrationInsurancePlan` keyed by **plan only** | **NULL** (no service key) | serviceUid MUST be nullable for this kind |
| CONSULTATION | `Clinic.consultationFee` | `ConsultationInsurancePlan` keyed by **(clinic, plan)** | **Clinic** (not a consultation service) | serviceUid = Clinic id |
| LAB | `LabTestType.price` | `LabTestTypeInsurancePlan` | LabTestType | — |
| RADIOLOGY | `RadiologyType.price` | `RadiologyTypeInsurancePlan` | RadiologyType | — |
| PROCEDURE | `ProcedureType.price` | `ProcedureTypeInsurancePlan` | ProcedureType | — |
| MEDICINE | `Medicine.price` × qty | `MedicineInsurancePlan` | Medicine | qty applied at billing, not in matrix |
| WARD_DAY | `WardType.price` (via WardBed→Ward→WardType) | `WardTypeInsurancePlan` keyed by **(wardType, plan)** | WardType | per-TYPE only; NEVER per-Ward, NEVER per-WardCategory |

### Nuances that would be LOST by consolidation — flag each as a MUST-PRESERVE-IN-CODE requirement (not in the matrix)

1. **Co-pay is NOT a percentage, cap, min, or max — it is the ward principal/supplementary bill SPLIT.** Confirmed at lines 1880-1897: when the eligible (referral) plan differs from the patient's plan AND `wardType.price − eligiblePlan.price > 0`, a SECOND `PatientBill` is created (`status=UNPAID`, billItem "Bed", description "Ward Bed / Room (Top up)") linked via `principalPatientBill`/`supplementaryPatientBill` and attached to a **null-plan (cash) invoice**. This is the ONLY co-pay mechanism in the entire legacy system. **`minAmount`/`maxAmount` in the proposed matrix have NO legacy source** — populating them would be invention. **BLOCKER if the principal/supplementary PatientBill linkage is dropped:** the patient's cash residual on inpatient stays would silently vanish, and the NHIF claim amount (`eligiblePlan.price`) would be conflated with the full ward charge. This is a billing-correctness/safety issue.

2. **Ward eligibility is a LIST-BASED algorithm with a referral short-circuit, not a keyed lookup.** Confirmed lines 1799-1809: load ALL covered ward-type rows for the patient's plan, iterate keeping the **highest price**, BUT short-circuit (`break`) on the first row whose `insurancePlan.id == patient.plan.id`. The matrix can store the per-(plan, wardType) rows, but this selection algorithm MUST be reproduced in code. **CLINICAL FLAG (referral semantics):** the "highest-priced covered ward across all of the plan's covered ward types" rule is operationally unusual. In Tanzanian practice an NHIF-referred patient is typically entitled to the ward grade authorised on the referral/pre-authorisation, not automatically the most expensive covered grade. I cannot confirm from code whether this is intended NHIF referral policy or a legacy quirk. **Recommendation:** reproduce exactly per "exact process," but escalate to engagement-lead/NHIF-compliance for confirmation — do not silently "improve" it, and do not silently keep it without flagging.

3. **Per-service NOT-COVERED fallback asymmetry — confirmed and load-bearing.** This is genuinely divergent behaviour by service and MUST be reproduced per service:
   - **CONSULTATION:** HARD FAIL — `throw InvalidOperationException("Plan not available for this clinic. Please change payment method")` (line 600). No cash fallback. Clinically defensible: a clinic must have an agreed consultation tariff with the insurer before the encounter proceeds on insurance.
   - **LAB / RADIOLOGY / PROCEDURE / MEDICINE:** fall back to CASH price with status `VERIFIED` **only when the patient is an inpatient** (`a.isPresent()`, confirmed lab path lines 912-918). For non-admitted insurance patients with no covered row, the initial `UNPAID` cash bill simply remains. This inpatient-only auto-cash-fallback is a real operational rule (you cannot stop an admitted patient's diagnostics for a missing tariff row) and must NOT be generalised to outpatients.
   - **REGISTRATION:** silently leaves the cash bill `UNPAID` (no exception, no override).
   - **BLOCKER if any of these three divergent behaviours is normalised into a single uniform fallback** — that would change what happens to a real patient at the desk.

4. **`covered=false` placeholder rows are functionally cash and are auto-created by setup endpoints.** Resolution always queries `...AndCovered(true)`; a `covered=false, price=0` row never overrides. The matrix `covered` boolean maps directly. Migration must preserve these (or synthesise on demand) — confirm with data-migration-engineer. The matrix `active` boolean is NEVER consulted in resolve (only `covered`); document it as currently inert if kept.

5. **`currency` has no legacy source.** All amounts are bare `double`; no currency concept exists anywhere. A `currency` column is net-new — default it, and it must NOT drive any resolve behaviour. Single-currency (TZS implicit) is the exact-process position.

6. **`LabTestPlanPrice` is DEAD — exclude it.** No repository/service/resolve reference. The live lab table is `LabTestTypeInsurancePlan`. Do not model `lab_test_plan_prices` as live pricing.

7. **Pharmacy sale order is CASH-ONLY (no insurance branch)** at `savePharmacySaleOrderDetail` (lines 3418-3442), unlike the prescription/dressing medicine paths which DO apply `MedicineInsurancePlan`. **CLINICAL FLAG:** OTC/counter pharmacy sales being cash-only is common practice (NHIF generally reimburses prescribed, not counter-sale, medicines), but the asymmetry vs. the prescription path is sharp. Confirm with business-analyst whether OTC is intentionally cash-only or coverage is applied in a later step. Preserve cash-only per exact-process pending that confirmation.

**Bottom line for Q1:** the unified matrix is an acceptable STORAGE consolidation IF and ONLY IF (a) `serviceUid` is nullable (REGISTRATION) and references Clinic for CONSULTATION; (b) `minAmount`/`maxAmount`/`currency` are acknowledged as inert net-new columns that must not drive behaviour; (c) the ward list-based referral-override + principal/supplementary top-up split, and (d) the per-service not-covered fallback asymmetry are reproduced EXACTLY in application code. Sign-off is **WITHHELD** until the spec explicitly documents (c) and (d) as preserved code-level behaviour rather than implying the matrix subsumes them.

---

## Q2 — Lab model: is legacy `LabTestTypeRange` clinically sufficient, or is the richer analyte model warranted?

**RECOMMENDATION: REPRODUCE LEGACY. Do NOT adopt the analyte/reference-range/RangeFlag model in Increment 02.** Confirmed from `LabTest.java`: results are flat free-text Strings — `result`, `report`, `description`, `range` (DB col `rrange`), `level`, `unit`, `status` — with **no FK from a result to a `LabTestTypeRange` row** and no numeric bounds anywhere. `LabTestTypeRange` is merely a named string label scoped to one `LabTestType` (a picklist source). The "Low/Medium/High" level is a hard-coded 3-value UI string, manually chosen, never computed.

**Clinical assessment of the trade-off:**

- The richer model (`LabTestAnalyte` + `LabReferenceRange` + `RangeFlag` with `CRITICAL_LOW/CRITICAL_HIGH/ABNORMAL`, sex/age banding, numeric bounds) is genuinely superior clinical practice. Sex- and age-banded numeric reference ranges with automated abnormal/critical flagging are the standard of care for laboratory information systems and materially improve patient safety (e.g., catching a critical potassium or haemoglobin value). A panel test (e.g., FBC, U&E, LFT) clinically comprises MULTIPLE analytes each with its own range — which legacy cannot represent at all (one `LabTest` row = one free-text result blob per `LabTestType`).

- **However**, this is a NEW CLINICAL CAPABILITY, not a faithful reproduction. The terms appear ONLY in HMSCLEAN2 design docs — zero matches in `com.orbix.api`. Adopting it now would: (a) violate the exact-process mandate; (b) change the result-entry workflow clinicians currently use (manual range/level picklist → structured analyte entry with auto-flagging); (c) require a data-migration strategy for legacy free-text `range`/`level`/`result` values that have no structured equivalent; and (d) expand QA scope significantly.

- **There is no clinical SAFETY REGRESSION in reproducing legacy** — the legacy system never had automated flagging, so reproducing it preserves the current (manual) safety posture rather than degrading it. The richer model is an enhancement, not a fix for a safety gap I can point to in the legacy behaviour.

**My recommendation to engagement-lead:** reproduce the legacy `LabTestTypeRange` (named string labels) + free-text `range`/`level`(Low|Medium|High)/`unit`/`result` on each `LabTest` for Increment 02. Log the analyte/reference-range/critical-flag model as a **strongly-recommended Phase-2 clinical enhancement** with its own change request — it is the single highest-value clinical safety improvement available in the lab context, but it must go through CR governance with a migration plan, not be smuggled in as "the V60 design." The "Low/Medium/High" three-value set should be confirmed as the authoritative legacy domain set for the reproduction (it is the only set the legacy UI offers).

---

## Q3 — Clinical-catalog correctness issues + recommendations

### 3a. Diagnosis coding — `DiagnosisType` has NO ICD support. CLINICAL/REGULATORY FLAG (not a blocker for inc-02 storage, but escalate).

Confirmed: `DiagnosisType` is a flat `code`/`name`/`description`/`active` catalogue with **no ICD-10 code field, no ICD version, no hierarchy/category** — zero ICD matches repo-wide. `LabTest` optionally references a `DiagnosisType` (line 60-63).

- **Regulatory reality (Tanzania/MOH/NHIF):** ICD-10 coding of diagnoses is a recognised expectation for MOH morbidity/mortality reporting (DHIS2/HMIS reporting) and increasingly for NHIF claim adjudication. A flat free-naming diagnosis catalogue does not natively support ICD-10-coded morbidity returns or coded claim lines. I will not make a hard regulatory pronouncement on the exact current NHIF claim requirement without a verifiable source — **flag for legal/compliance escalation** before treating ICD-10 as mandatory.
- **For Increment 02 (exact-process):** preserve `DiagnosisType` as-is, and **use the legacy name `DiagnosisType`, NOT the spec's "Diagnosis"** (the spec naming is wrong). Do not add ICD columns silently.
- **Recommendation:** raise ICD-10 support as a high-priority candidate CR for a later increment. If/when approved, the clean modelling is an ICD-10 code + version on `DiagnosisType` (or a linked ICD reference table), preserving the existing `code`/`name` so legacy diagnoses migrate without loss. This is the second-highest-value clinical enhancement after the lab analyte model.

### 3b. Dosage form / Route / Frequency — free-text, no controlled vocabulary. MEDICATION-SAFETY FLAG.

Confirmed: `Prescription.dosage/frequency/route/days` are free-text Strings; `PatientPrescriptionChart` carries its own free-text `dosage`. No `Dosage`/`Route`/`Frequency` entity or enum exists.

- **Clinical concern:** uncontrolled free-text route/frequency is a known medication-error vector (ambiguous abbreviations, inconsistent frequency notation). Controlled picklists (route, frequency, dosage form) are standard medication-safety practice.
- **For inc-02:** reproduce free-text exactly (exact-process). Introducing controlled picklists is an enhancement requiring a CR.
- **Recommendation:** flag controlled prescription picklists as a medication-safety CR candidate. **Note for completeness — outside the inc-02 master-data scope but safety-critical:** I did not find an allergy/contraindication check in these paths. Allergy checking is a hard safety requirement and must be validated when the Pharmacy/Clinical-OPD context specs are reviewed; if legacy genuinely lacks it, that is a safety gap to log (legacy absence does not make it safe — it makes it a documented limitation to escalate, not silently reproduce-and-forget).

### 3c. Radiology/Procedure typing — clinically adequate as masterdata; no issue.

`RadiologyType` and `ProcedureType` are clean `code`/`name`/`description`/`price`/`uom`/`active` masterdata, identical in shape to `LabTestType` minus ranges. No clinical correctness issue with the catalogue structure itself. Two quirks to carry as documented exact-behaviour (not blockers): `@NotNull` on a primitive `double price` is a no-op (price effectively optional, defaults 0.0 — confirm intended min/required-price rule), and the `LabTestType.save` edit branch never updates `code` and re-derives name/code from the persisted row (reproduce as quirky exact behaviour unless a CR fixes it).

### 3d. Dead/broken legacy artefacts — do NOT reproduce.

- Angular `PUT /lab_test_types/update_by_code` has no backend mapping — broken bulk-update; do not re-implement.
- `LabTestPlanPrice` table — dead; exclude from the live pricing model.

---

## SUMMARY OF BLOCKERS vs. FLAGS

**BLOCKERS (spec cannot reach design-complete until resolved in writing):**
- B1. ServicePrice spec must explicitly document that ward referral-override + principal/supplementary top-up split (PatientServiceImpl 1799-1949) is reproduced in CODE, not subsumed by the matrix.
- B2. ServicePrice spec must explicitly document the per-service not-covered fallback asymmetry (consultation hard-fail / lab-rad-proc-med inpatient-only cash-VERIFIED / registration silent UNPAID) as preserved code-level behaviour.
- B3. `serviceUid` MUST be nullable (REGISTRATION) and reference Clinic for CONSULTATION; `minAmount`/`maxAmount`/`currency` MUST be marked inert net-new (no legacy source, must not drive behaviour).

**FLAGS for escalation (engagement-lead / NHIF-compliance), not inc-02 blockers:**
- F1. Ward "highest-priced covered grade" referral semantics — confirm against NHIF referral/pre-auth policy (reproduce meanwhile).
- F2. Pharmacy OTC sale cash-only vs. prescription-path insurance — confirm intent (business-analyst).
- F3. Lab analyte/reference-range/critical-flag model — recommended Phase-2 clinical enhancement via CR (reproduce flat legacy now).
- F4. ICD-10 diagnosis coding — recommended CR; escalate to legal/compliance whether currently mandatory for NHIF/MOH; use `DiagnosisType` naming, not "Diagnosis".
- F5. Controlled prescription picklists (route/frequency/dosage) — medication-safety CR candidate; verify allergy-checking when Pharmacy/OPD specs are reviewed.

**Sign-off status for Increment 02 master-data pricing/lab/catalog: WITHHELD pending written resolution of B1-B3.** Lab model and clinical catalog: APPROVED to reproduce legacy as specified, with F3/F4/F5 logged as enhancement CRs.

Relevant legacy source paths (read-only, source of truth):
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\service\PatientServiceImpl.java` (consultation 594-616; lab fallback 912-918; ward referral/top-up 1795-1966)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\domain\LabTest.java` (flat free-text result model, no range FK)