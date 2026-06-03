## EXTRACTION 2 — Pricing & Settlement Resolve-Time Logic (legacy source of truth)

All citations are to the legacy package `com.orbix.api`, file paths under
`D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api`.
Primary file: `service/PatientServiceImpl.java`. Settlement file: `api/PatientBillResource.java`.

### EARLY-DISCOVERY FINDINGS (re-confirmed for this extraction)
- **No Hibernate Envers audit trail is effectively active.** No `@Audited` annotation exists on `PatientBill`, `PatientInvoice`, `PatientInvoiceDetail`, `PatientPaymentDetail`, `PatientCreditNote`, or any charge/coverage entity examined. Forensic tracking is manual scalar columns only (`createdBy`/`createdOn`/`createdAt`, e.g. `PatientBill.java:80-84`). Downstream agents must not assume an Envers baseline.
- **No device-fingerprint / device-binding feature** appears in any pricing/billing/settlement path. None to preserve.

---

### 1. THE TWO-STEP BUILD (cash first, then insurance override)

**Universal shape (confirmed identical for all charge kinds):** a `PatientBill` is ALWAYS constructed first at the CASH price with `status="UNPAID"`, `paid=0`, `balance=amount`, and SAVED. Only afterward, inside an `if(INSURANCE …)` block, is it re-read from the covered plan row and overwritten. There is no single helper; the pattern is copy-pasted per kind.

**On INSURANCE override (covered plan row present), every kind sets the SAME five fields:**
`amount = planRow.price` ; `paid = planRow.price` ; `balance = 0` ; `status = "COVERED"` ; `paymentType = "INSURANCE"` ; plus `insurancePlan` and `membershipNo`. Then a `PatientInvoiceDetail` is attached to a PENDING `PatientInvoice` (the insurance claim accumulator).

Per-kind cash-price source and override line numbers:

| Kind | Cash price source (step 1) | Cash bill build | Covered-row lookup | Override block |
|---|---|---|---|---|
| Registration | `CompanyProfile.getRegistrationFee()` (loop `PatientServiceImpl.java:230-233`) | `:267-288` (status UNPAID if regFee>0 else VERIFIED, `:273-277`) | `registrationInsurancePlanRepository.findByInsurancePlanAndCovered(plan,true)` `:320` | `:326-329` sets amount/paid/balance; status set COVERED later at `:390` |
| Consultation | `Clinic.getConsultationFee()` (`Clinic.java:54`) | `:459-483` (UNPAID; NONE if followUp `:467-469`) | `consultationInsurancePlanRepository.findByClinicAndInsurancePlanAndCovered(c,plan,true)` `:597` | `:602-616` (status COVERED `:605`; NONE if followUp `:606-608`) |
| Lab test | `LabTestType.getPrice()` | `:821-835` (UNPAID) | `labTestTypeInsurancePlanRepository.findByLabTestTypeAndInsurancePlanAndCovered(ltt,plan,true)` `:839` | `:842-849` (COVERED) |
| Radiology | `RadiologyType.getPrice()` | `:1063-1075` (UNPAID) | `findByRadiologyTypeAndInsurancePlanAndCovered(rt,plan,true)` `:1079` | `:1082-1089` (COVERED) |
| Procedure (order) | `ProcedureType.getPrice()` | `:1314-1326` (UNPAID) | `findByProcedureTypeAndInsurancePlanAndCovered(pr,plan,true)` `:1330` | `:1333-1340` (COVERED) |
| Medicine (Rx) | `Medicine.getPrice() * qty` | `:1533-1545` (UNPAID; **note qty multiplier** `:1534`) | `findByMedicineAndInsurancePlanAndCovered(md,plan,true)` `:1549` | `:1552-1559` (COVERED; **plan price × qty** `:1552-1553`) |
| Ward bed | `WardBed.getWard().getWardType().getPrice()` (`WardType.java:40`) | `:1753-1774` (UNPAID) | `wardTypeInsurancePlanRepository.findByInsurancePlanAndCovered(plan,true)` returns a **List** `:1799` | `:1811-1819` (COVERED) — see §3 |
| Procedure (dressing chart) | `ProcedureType.getPrice()` | `:2078-2090` (UNPAID, desc "Dressing: …") | `:2094` | `:2096-2104` (COVERED) |
| Consumable (admission) | (analogous) | `:2314` (UNPAID) | `:2323` | `:2326+` (COVERED) — VERIFIED fallback at `:2401` |

Plan-row coverage fields: each `*InsurancePlan` entity carries `price` (or `consultationFee`/`registrationFee`), a `boolean active`, and a `boolean covered`; lookups always filter `covered = true` (e.g. `ConsultationInsurancePlan.java:42-44`, `WardTypeInsurancePlan.java:42-44`). The `active` flag is NOT consulted by any resolve-time query — only `covered` gates pricing. (Flag for confirmation.)

**Money type:** every amount/paid/balance is Java `double` (`PatientBill.java:47-53`). Per directive these become `BigDecimal NUMERIC(19,2) HALF_UP` in the rebuild; medicine override must compute `planPrice.multiply(qty)` with HALF_UP, replacing legacy `double` multiply at `:1552-1553`.

---

### 2. PER-SERVICE NOT-COVERED FALLBACK ASYMMETRY

The override block is entered when **`patient.getPaymentType().equals("INSURANCE") || a.isPresent()`** (admission present) for lab/radiology/procedure/medicine (`:837`, `:1077`, `:1328`, `:1547`, `:2092`). Inside, behaviour when NO covered plan row is found differs by kind:

- **CONSULTATION — HARD FAIL, no cash fallback.** `PatientServiceImpl.java:599-601`:
  `if(!consultationPricePlan.isPresent()){ throw new InvalidOperationException("Plan not available for this clinic. Please change payment method"); }`
  The previously-saved UNPAID cash consultation bill is left orphaned in DB (the transaction rolls back on throw, so net effect is the whole `doConsultation` fails). Only reachable when `paymentType==INSURANCE` (guard `:594`).

- **LAB / RADIOLOGY / PROCEDURE / MEDICINE — cash fallback GATED ON ADMISSION (inpatient).** When the covered row is absent, an `else if(a.isPresent() == true)` branch fires and sets the bill to cash price with **`status="VERIFIED"`** and a cash (insurancePlan=null) invoice:
  - Lab `:912-918` (VERIFIED `:917`)
  - Radiology `:1152-1158` (VERIFIED `:1157`)
  - Procedure (order) `:1403-1409` (VERIFIED `:1408`)
  - Medicine `:1622-1628` (VERIFIED `:1627`)
  - Procedure (dressing) `:2172`; Consumable `:2401`
  If the patient is INSURANCE **but NOT admitted** (`a` empty) and no covered row exists, **neither inner branch executes** — the bill keeps its step-1 values: cash price, `status="UNPAID"`, `paymentType="CASH"`, `insurancePlan=null` (`:821-835` etc.). So a non-admitted insured patient silently falls back to a CASH-payable bill. (This is the asymmetry: VERIFIED for inpatients, UNPAID for outpatients.)

- **REGISTRATION — silent cash, no exception.** The insurance branch (`:312-403`) only acts `if(plan.isPresent())` (`:321`). If no covered registration plan row exists, nothing happens and the bill stays at the step-1 cash state: UNPAID (regFee>0) or VERIFIED (regFee==0) per `:273-277`. No throw, no VERIFIED-on-fallback.

**Meaning of VERIFIED vs UNPAID at creation:** VERIFIED = an admitted (inpatient) charge with no insurance coverage that is *acknowledged/approved to proceed* and is still cash-collectable later (cashier treats UNPAID and VERIFIED identically as collectable, `:295-307`). UNPAID = outpatient/registration cash charge awaiting collection.

---

### 3. WARD REFERRAL-OVERRIDE + TOP-UP SPLIT (the only co-pay mechanism)

Method `doAdmission` (`PatientServiceImpl.java:1700-2021`). After building the cash ward bed bill (`:1753-1774`) and flipping the patient to INPATIENT (`:1785`), the insurance branch runs (`:1795`):

**Eligible-plan selection (`:1797-1809`):**
```
List<WardTypeInsurancePlan> wardTypePricePlans =
    wardTypeInsurancePlanRepository.findByInsurancePlanAndCovered(p.getInsurancePlan(), true);   // :1799
double eligiblePrice = 0;
for(plan : wardTypePricePlans){
   if(plan.getPrice() > eligiblePrice || plan.getInsurancePlan().getId() == p.getInsurancePlan().getId()){  // :1802
       eligiblePrice = plan.getPrice(); eligiblePlan = plan;
       if(plan.getInsurancePlan().getId() == p.getInsurancePlan().getId()) break;   // :1805-1807 referral short-circuit
   }
}
```
Logic: iterate all covered ward-type rows for the patient's plan; keep the **highest-priced** row; but if a row's plan id equals the patient's own plan id, take it and **break immediately** (the "referral override" — the patient's own plan wins regardless of price). NOTE the query already filters `findByInsurancePlanAndCovered(p.getInsurancePlan(),...)`, so every returned row's plan IS the patient's plan; the `||`/`==` short-circuit therefore effectively breaks on the FIRST returned row, and the "pick highest" loop is largely dead under this query. **Flag as a latent code smell / ambiguity** — the highest-price intent and the query scope conflict.

**Insurance override of the ward bill (`:1811-1819`):** if `eligiblePlan != null`, set `amount=paid=eligiblePlan.price`, `balance=0`, `paymentType=INSURANCE`, `status="COVERED"`, attach plan+membership; save. Then attach a `PatientInvoiceDetail` (desc "Ward Bed / Room") to a PENDING insurance invoice (`:1821-1871`).

**TOP-UP second bill (`:1880-1949`) — the only co-pay in the system:**
```
if(eligiblePlan.getInsurancePlan().getId() != p.getInsurancePlan().getId()
   && (wb.getWard().getWardType().getPrice() - eligiblePlan.getPrice() > 0)){   // :1880
   PatientBill supplementaryWardBedBill = new PatientBill();
   amount = balance = wardType.price - eligiblePlan.price;   // :1883-1885
   paid = 0; status="UNPAID";                                 // :1884,1886
   billItem="Bed"; description="Ward Bed / Room (Top up)";    // :1887-1888
   supplementaryWardBedBill.setPrincipalPatientBill(wardBedBill);   // :1889  (child -> parent)
   save;
   wardBedBill.setSupplementaryPatientBill(supplementaryWardBedBill);  // :1896 (parent -> child)
   save;
}
```
The top-up is added to a **separate CASH invoice** (`insurancePlan=null`, `:1899-1948`). Linkage is bidirectional via `PatientBill.principalPatientBill` and `PatientBill.supplementaryPatientBill` (`PatientBill.java:65-73`).

**Critical guard contradiction:** the top-up condition at `:1880` requires `eligiblePlan.plan.id != patient.plan.id`, but the selection loop only ever sets `eligiblePlan` to a row whose plan IS the patient's plan (query scope + short-circuit). Under the current query, `:1880` can essentially never be true → **the top-up branch appears unreachable in practice.** This is a high-priority ambiguity: the intended design (referral to a higher ward tier covered by a *different* plan, patient tops up the difference) is not realised by the code path. Downstream must decide: reproduce the (effectively-dead) co-pay exactly, or treat the documented intent as the spec. DO NOT silently "fix".

**Admission/bed state-transition asymmetry (`:1950-1963`):** the admission is set to `IN-PROCESS` and the bed to `OCCUPIED` ONLY in the `else` branch (no top-up created), along with signing out consultations. In the top-up branch (`:1880-1949`) admission stays `PENDING` / bed stays `WAITING`. For pure-CASH admissions (`:1967-2018`) admission also stays PENDING. Admission→IN-PROCESS + bed→OCCUPIED is otherwise driven later by the cashier on payment (`PatientBillResource.java:352-365`). Flag this dual path.

---

### 4. coverage_status / bill status — enumerated legacy values

`PatientBill.status` is a free-text String (`PatientBill.java:55`), not an enum. Observed values and meaning at resolve/settlement time:
- **UNPAID** — cash charge awaiting cashier collection (step-1 default for every kind; outpatient insured-no-cover fallback).
- **VERIFIED** — inpatient (admission-present) cash charge with no insurance coverage; treated as collectable, equivalent to UNPAID for the cashier (`PatientBillResource.java:295-307`). Also the registration bill when `regFee == 0` (`:276`).
- **COVERED** — fully met by insurance plan row (`paid=amount`, `balance=0`); routed to a claim invoice, NOT cash-collected.
- **PAID** — set by the cashier on collection (`PatientBillResource.java:176, 227, 307`).
- **NONE** — consultation follow-up bill, no charge (`:466-469, 605-608`).
- **RECEIVED** — used on `PatientPayment`/`PatientPaymentDetail.status`, not on the bill (`:170, 283, 314`).
Cashier collection accepts only `UNPAID` or `VERIFIED`; anything else throws `"One or more bills have been paid/covered/canceled…"` (`:295-296`).
The build-memory triple `UNPAID / COVERED / VERIFIED` is the resolve-time subset; **PAID and NONE must also be modelled** to be exact.

---

### 5. THE 'SETTLED' / PAY-BEFORE-SERVICE CONCEPT — IS IT A HARD GATE?

**Finding: there is NO `settled` field and NO hard pay-before-service precondition anywhere in the legacy.**
- Grep across all of `com.orbix.api` for `settled|isSettled|setSettled|paymentStatus|payBeforeService`: **zero matches.**
- No method in `PatientServiceImpl.java` (where lab tests, radiology, procedures, prescriptions, dressings are created and where results/dispensing logic lives) checks a bill's status/balance before rendering. Grep for `getPatientBill().getStatus()` / `equals("PAID")` in `PatientServiceImpl.java`: **zero matches.** Same for `PharmacyServiceImpl.java`: **zero matches.**
- The only places bill status is read in the service/clinical path are: (a) the cashier **collection** endpoint, which refuses to re-pay a non-UNPAID/VERIFIED bill (`PatientBillResource.java:295-296`) and flips bills to PAID, downstream `PatientInvoiceDetail`→PAID and invoice `amountPaid` accumulation (`:341-350`), and triggers admission→IN-PROCESS/bed→OCCUPIED (`:352-365`); and (b) **read-only list endpoints** `get_lab_test_bills` / `get_procedure_bills` / `get_prescription_bills` / `get_radiology_bills` / `get_inpatient_bills` which simply *filter to UNPAID bills* to populate the cashier screen (`PatientBillResource.java:415, 442, 470, 497, 532-588`). These are projections, not gates.

**Conclusion for inc-04:** the legacy enforces pay-before-service only as a **UI filter / workflow convention**, not as a code precondition on order accept/complete or on lab-result entry or pharmacy dispensing. Clinical staff are not technically blocked by an unpaid bill. Therefore the inc-04 "hard gate via settled flag" is **NET-NEW HARDENING, not exact-process.** It requires an explicit engagement-lead change request before implementation; reproducing legacy faithfully means NO hard block at service time.

---

### Cross-references for the rebuild (billing module, package `com.otapp.hmis`)
- Map cash-first/override two-step onto `PriceLookup.resolve(...)` + `BillingCommands.recordClinicalCharge`; resolve currently returns storage-tier only — the COVERED-override, VERIFIED-inpatient-fallback, consultation hard-fail, registration-silent, and ward top-up are the resolve-TIME logic to add in billing (deferred from inc-02).
- Status enum to model: `UNPAID, VERIFIED, COVERED, PAID, NONE` (+ payment `RECEIVED`).
- Ward top-up = `principalPatientBill`/`supplementaryPatientBill` self-link; only co-pay mechanism.