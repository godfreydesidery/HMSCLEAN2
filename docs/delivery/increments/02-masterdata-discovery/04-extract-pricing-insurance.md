## EXTRACTION 4 — Legacy Pricing & Insurance Model (with file:line citations)

Legacy root: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api`. Controllers are under `api/` (NOT `resource/`). All money fields are Java `double` in legacy.

### Early-discovery findings (mandatory in audit/auth-touching specs)
- **Audit trail:** No `@Audited` annotation appears on any pricing/insurance entity examined (none of `InsuranceProvider`, `InsurancePlan`, the 7 `*InsurancePlan` tables, `LabTestPlanPrice`, `LabTestType`, `Medicine`, etc. carry it). Consistent with the known engagement finding: *"No Hibernate Envers audit trail is effectively active in the legacy system."* Each pricing entity instead carries home-grown forensic fields: `createdBy` (`created_by_user_id`), `createdOn` (`created_on_day_id`), `createdAt` (`LocalDateTime`).
- **Device-fingerprint / device-binding:** Not present in any pricing/insurance code path. *"No device-fingerprint or device-binding feature exists in the legacy system."*

---

### 1. Provider & Plan headers

**InsuranceProvider** (`domain/InsuranceProvider.java:37`, table `insurance_providers`): `id`; `code` (`@NotBlank`, unique, `:42-43`); `name` (`@NotBlank`, unique, `:44-46`); `address`, `telephone`, `email`, `fax`, `website` (all plain `String`, nullable, `:47-51`); `active boolean = false` (`:52`); forensic trio (`:54-58`). NO membership/card-scheme metadata lives on the provider.

**InsurancePlan** (`domain/InsurancePlan.java:37`, table `insurance_plans`): `id`; `code` (`@NotBlank`, unique, `:41-43`); `name` (`@NotBlank`, unique, `:44-46`); `description` (`:47`); `active boolean = false` (`:48`); `@ManyToOne InsuranceProvider insuranceProvider` EAGER, `optional=false`, FK `insurance_provider_id` NOT NULL (`:50-53`); forensic trio (`:55-59`). NO card/membership-number scheme, NO copay/coverage fields on the plan itself — membership number lives on `Patient` (set at registration, `PatientResource.java:299-301`).

Note: plans are looked up by `name` at point of care (`PatientResource.java:297,360,384`; `PatientServiceImpl.java:530`), not by id — `code`/`name` uniqueness is load-bearing.

---

### 2. The seven per-service plan-price tables (all confirmed `double`, all keyed by plan + service)

Common shape: `id`; service-fee field (`double`, `@NotNull`); `active boolean = false`; `covered boolean = false`; `@ManyToOne InsurancePlan` (`optional=false`, FK `insurance_plan_id` NOT NULL); `@ManyToOne` to the service entity (`optional=false`, NOT NULL); forensic trio.

| Entity | Table | Fee field | Service FK |
|---|---|---|---|
| `ConsultationInsurancePlan` (`domain/ConsultationInsurancePlan.java:37`) | `consultation_insurance_plans` | `consultationFee` (`:42`) | `Clinic clinic` FK `clinic_id` (`:51-54`) — keyed by **Clinic**, not a ConsultationType |
| `RegistrationInsurancePlan` (`domain/RegistrationInsurancePlan.java:37`) | `registration_insurance_plans` | `registrationFee` (`:42`) | **none** — keyed by plan ONLY (`:46-49`) |
| `LabTestTypeInsurancePlan` (`domain/LabTestTypeInsurancePlan.java:37`) | `lab_test_type_insurance_plans` | `price` (`:42`) | `LabTestType` FK `lab_test_type_id` (`:51-54`) |
| `MedicineInsurancePlan` (`domain/MedicineInsurancePlan.java:37`) | `medicine_insurance_plans` | `price` (`:42`) | `Medicine` FK `medicine_id` (`:51-54`) |
| `ProcedureTypeInsurancePlan` (`domain/ProcedureTypeInsurancePlan.java:37`) | `procedure_type_insurance_plans` | `price` (`:42`) | `ProcedureType` FK `procedure_type_id` (`:51-54`) |
| `RadiologyTypeInsurancePlan` (`domain/RadiologyTypeInsurancePlan.java:37`) | `radiology_type_insurance_plans` | `price` (`:42`) | `RadiologyType` FK `radiology_type_id` (`:51-54`) |
| `WardTypeInsurancePlan` (`domain/WardTypeInsurancePlan.java:37`) | `ward_type_insurance_plans` | `price` (`:42`) | `WardType` FK `ward_type_id` (`:51-54`) |

**`LabTestPlanPrice` is a DEAD/UNUSED table.** `domain/LabTestPlanPrice.java:34`, table `lab_test_plan_prices`, field `labTestFee double = 0` (`:39`), with NULLABLE plan and labTestType FKs (`optional=true`, `:41-49`) and NO `covered` field. A whole-tree grep for `LabTestPlanPrice` returns ONLY its own class definition — there is no `LabTestPlanPriceRepository`, no service, and no resolve-logic reference. It must NOT be modelled as live lab pricing; the active lab table is `LabTestTypeInsurancePlan`. (Recommend confirming whether to migrate its data at all — flagged in decisions.)

---

### 3. THE RESOLVE LOGIC (the critical part)

All point-of-care pricing is centralised in `service/PatientServiceImpl.java`. The universal pattern is **two-step**: (1) always build the bill at the CASH price first; (2) if INSURANCE, look up the covered plan row and OVERRIDE.

**Cash price source = a `double` field on the service entity / master record (NOT a null-plan row):**
- Registration: `CompanyProfile.registrationFee` (`domain/CompanyProfile.java:77`, `double = 0`), read via a loop over all profiles keeping the last value (`PatientServiceImpl.java:230-233`).
- Consultation: `Clinic.consultationFee` (`domain/Clinic.java:54`), used at `PatientServiceImpl.java:460,462`.
- Lab test: `LabTestType.price` (`domain/LabTestType.java:56`), used at `:822,824`.
- Radiology: `RadiologyType.price` (`domain/RadiologyType.java:50`), at `:1064,1066`.
- Procedure: `ProcedureType.price` (`domain/ProcedureType.java:50`), at `:1315,1317`.
- Medicine (prescription/dressing/sale): `Medicine.price` × qty (`domain/Medicine.java:52`), at `:1534,1536`, `:2308-2310`, `:3422-3424`.
- Ward bed: `wb.getWard().getWardType().getPrice()` (`:1754,1756`).

**Insurance override (covered) path — uniform for consultation/lab/radiology/procedure/medicine:**
`if (patient.paymentType == "INSURANCE")` → `findByXAndInsurancePlanAndCovered(service, plan, true)`. If a row is present, OVERRIDE: `bill.setAmount(planPrice); bill.setPaid(planPrice); bill.setBalance(0); bill.setStatus("COVERED"); bill.setPaymentType("INSURANCE"); bill.setInsurancePlan(...); bill.setMembershipNo(...)` and attach a `PatientInvoiceDetail` to a PENDING `PatientInvoice` for that plan. Canonical examples:
- Consultation `:597-616` (lookup `findByClinicAndInsurancePlanAndCovered`, `ConsultationInsurancePlanRepository.java:40`).
- Lab `:839-849` (`findByLabTestTypeAndInsurancePlanAndCovered`, `LabTestTypeInsurancePlanRepository.java:43`).
- Radiology `:1082-1083`; Procedure `:1333-1334`; Medicine `:1552-1553`.

So under insurance the covered claim is recorded as fully paid (`balance=0`, status `COVERED`) against the insurer's invoice — there is NO patient-side residual EXCEPT the ward top-up (below).

**Insurance NOT-covered / no-row fallback — DIFFERS BY SERVICE (asymmetry to preserve):**
- **Consultation:** HARD FAIL. If no covered row → `throw new InvalidOperationException("Plan not available for this clinic. Please change payment method")` (`:599-601`). No cash fallback.
- **Lab / radiology / procedure / medicine:** if no covered row AND the patient is an inpatient (`a.isPresent()`), the bill falls back to CASH price with status `VERIFIED` and is attached to a null-plan (cash) invoice (lab example `:912-918`). For non-admitted insurance patients with no covered row, the initially-created `UNPAID` cash bill simply remains (no override, no exception).
- **Registration:** if INSURANCE and `regFee > 0` but no covered `RegistrationInsurancePlan` row → the cash regBill silently stays `UNPAID` (the `if(plan.isPresent())` block at `:321` is skipped; no exception).

**Registration fee resolution** (`PatientServiceImpl.doRegister`, `:227-422`): cash `regFee` from CompanyProfile; bill status `UNPAID` if `regFee>0` else `VERIFIED` (`:273-277`); insurance override via `registrationInsurancePlanRepository.findByInsurancePlanAndCovered(plan, true)` (`:320`, `RegistrationInsurancePlanRepository.java:30`) — keyed by PLAN ONLY (no service FK), so at most one covered registration row per plan is expected.

**WARD pricing — the unique, hardest case (referral override + top-up co-pay):** `PatientServiceImpl.java:1795-1966`. NOT a single keyed lookup. It loads a LIST: `wardTypeInsurancePlanRepository.findByInsurancePlanAndCovered(plan, true)` (`:1799`, `WardTypeInsurancePlanRepository.java:40`) and selects an `eligiblePlan` by iterating (`:1801-1809`): keep the row with the highest `price`, BUT if any row's `insurancePlan.id == patient's plan.id`, short-circuit (`break`) and use THAT row. This is a **referral-plan override**: a patient on plan A admitted to a ward priced under a different (referral) plan B can be covered up to B's highest covered ward price.
- If `eligiblePlan != null`: ward bill set COVERED at `eligiblePlan.price` (`:1812-1819`).
- **Supplementary / top-up split** (`:1880-1949`): if the eligible plan is from a DIFFERENT plan than the patient's (`eligiblePlan.insurancePlan.id != patient.plan.id`) AND `wardType.price - eligiblePlan.price > 0`, a SECOND `PatientBill` is created for the difference — status `UNPAID`, billItem "Bed", description "Ward Bed / Room (Top up)", linked via `setPrincipalPatientBill(wardBedBill)` and `wardBedBill.setSupplementaryPatientBill(...)` (`:1881-1897`), and attached to a NULL-plan (cash) invoice so the patient pays the residual in cash. This principal/supplementary linkage is the ONLY place a partial-coverage co-pay is expressed in the legacy.
- If `eligiblePlan == null`: no-op else-branch (`:1964-1966`, commented-out throw) — ward bed stays UNPAID at cash price.

**Pharmacy sale order detail** (`savePharmacySaleOrderDetail`, `:3418-3442`): charges CASH ONLY (`Medicine.price × qty`, `:3422`); there is NO insurance branch in this method (contrast with the prescription/dressing medicine paths that DO branch). Flagged as an ambiguity.

**Setup-time auto-creation of placeholder rows:** `InsurancePlanResource.getLabTestTypePrices` (`api/InsurancePlanResource.java:161-204`) shows the cash-vs-insurance UI contract: when `insurancePlanId == 0` the UI shows the entity cash `price` (`:170-171`); when `> 0` it returns the `*InsurancePlan` row, auto-creating a placeholder `covered=false, price=0` row if none exists (`:179-192`). Same pattern for consultation/medicine/procedure/radiology/ward in the same file (`:309,440,571,701,957`). Therefore a `covered=false` row is a real persisted row meaning "configured but NOT covered"; resolution always uses the `...AndCovered(true)` query, so `covered=false` rows never override and behave as cash.

---

### 4. Does the unified `ServicePrice` matrix faithfully replace the 7 tables?

**Mostly yes, with required extensions.** Mapping cash = `(planUid IS NULL)` is sound IF the migration copies each service entity's `price`/`consultationFee`/`registrationFee` into a planUid-NULL `ServicePrice` row per service, and each `*InsurancePlan` row becomes a planUid-NOT-NULL row. `kind` distinguishes CONSULTATION/REGISTRATION/LAB_TEST/MEDICINE/PROCEDURE/RADIOLOGY/WARD. `covered` maps directly to the legacy `covered` boolean.

**Nuances the flat matrix as proposed does NOT capture (must be preserved):**
1. **`minAmount`/`maxAmount` have NO legacy source.** A grep across all `domain/` pricing entities found NO min/max/copay/percentage/deductible/cap field. Co-pay is NOT a percentage or cap — it is the ward principal/supplementary bill split. Leaving these columns nullable-and-unused is faithful; populating them is invention.
2. **No `currency`.** No currency field exists anywhere in legacy pricing; all amounts are plain `double`. A `currency` column is a NEW concept with no legacy value to migrate (default it; do not derive behaviour from it).
3. **Consultation is keyed by `Clinic`, not a consultation service** — `serviceUid` for CONSULTATION rows must reference the Clinic. Registration rows have NO service key at all (plan-only) — `serviceUid` must be nullable for kind=REGISTRATION.
4. **Ward eligibility is list-based with a referral-override + top-up split, not a single keyed row.** The matrix can STORE the per-(plan,wardType) rows, but the *resolve algorithm* (`PatientServiceImpl.java:1799-1966`) — pick highest covered ward across the plan's covered rows, short-circuit on same-plan match, then emit a principal+supplementary bill pair when covered < cash — is application logic that must be reproduced exactly; it is NOT expressible as a row lookup.
5. **Service-specific not-covered fallback asymmetry** (consultation hard-fails; lab/rad/proc/med fall back to cash-VERIFIED only for inpatients; registration stays UNPAID) is resolve-logic that lives outside the matrix and must be re-implemented per the citations above.
6. **`active` boolean** exists on every row but is NEVER consulted in resolve (only `covered` is queried). If the matrix drops `active`, behaviour is unchanged; if kept, document it as currently inert.

**Conclusion:** The unified `ServicePrice(planUid nullable, kind, serviceUid, currency, amount, covered, minAmount, maxAmount)` CAN replace the 7 live tables as a STORAGE model with cash = planUid-NULL, provided: serviceUid is nullable (registration), CONSULTATION.serviceUid = Clinic, `minAmount`/`maxAmount`/`currency` are acknowledged as net-new (no legacy data, must not drive behaviour), and the ward referral/top-up algorithm plus per-service not-covered fallbacks are preserved in code. `LabTestPlanPrice` should be excluded as dead.