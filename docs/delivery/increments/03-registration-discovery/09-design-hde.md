# DOMAIN REVIEW — Increment 03 Registration/Patient Flow (Clinical/Operational Validation)

Reviewing the verified legacy facts against Tanzanian healthcare operational reality. All findings below are grounded in the cited extractions; no new code behaviour is invented. Where legacy behaviour conflicts with clinical best practice, I surface it explicitly per the "exact process" rule rather than override it.

---

## 1. OUTPATIENT vs OUTSIDER distinction — CLINICALLY VALID, reproduce as-is

The legacy 4-value `type` vocabulary (`OUTPATIENT`/`OUTSIDER`/`INPATIENT`/`DECEASED`, EXTRACTION 1 §4) is operationally sound and maps to real Tanzanian facility practice:

- **OUTPATIENT** = a patient who will see a clinician (consultation flow). Correctly the only type `do_consultation` accepts (`PatientResource.java:535-537`).
- **OUTSIDER** = a walk-in who consumes services WITHOUT a doctor consultation — direct lab, radiology, or procedure orders (the `NonConsultation` path, EXTRACTION 4). This is a genuine and common Tanzanian workflow: patients referred from another facility "for investigation only" (e.g. a CBC or X-ray requested elsewhere), or self-pay clients buying a single test. The `OUTPATIENT↔OUTSIDER` toggle with active-work guards (`PatientResource.java:421-495`) is clinically correct — you must not flip a patient out of consultation while a consultation is live, nor strand paid-for orders.

**SIGN-OFF:** This distinction is clinically and operationally correct. Reproduce the 4-value vocabulary exactly. **BLOCKER on any spec that drops INPATIENT or DECEASED from `type`** — both carry safety-critical workflow meaning (admission lockout and consultation freeze respectively) and dropping them silently breaks the state machine. The DRIFT flag in EXTRACTION 1 §4 (planning doc only models OUTPATIENT/OUTSIDER) must be resolved before design-complete.

---

## 2. Next-of-kin capture — OPERATIONALLY ADEQUATE for parity, but a documented clinical gap

Legacy captures exactly one next-of-kin as three flat nullable strings (`kinFullName`, `kinRelationship`, `kinPhoneNo` — EXTRACTION 1 §2, all nullable, no constraint).

**Clinical assessment:** A single, fully-optional next-of-kin is below ideal practice for inpatient and emergency care (consent-for-surgery, deceased notification, and discharge-to-guardian all rely on a reachable kin contact). In a Tanzanian facility, next-of-kin is operationally important for: minors (guardian consent), maternity, surgical consent, and the deceased-handover workflow.

**However — per the exact-process rule, this is NOT a blocker.** Legacy makes kin optional and singular; the rebuild must do the same. I flag this as a **documented clinical gap for the edge-case register**, NOT a spec change:

- **CONFLICT (surfaced, not overridden):** Best practice wants mandatory kin for INPATIENT/DECEASED; legacy allows null. **Recommended resolution:** keep legacy nullable behaviour for parity; raise a SEPARATE change request to the engagement-lead if the facility wants kin made conditionally-required at admission. Do NOT bake a new validation into Increment 03.

---

## 3. Deceased handling — SAFETY-CRITICAL workflow correctly modelled, but reproduce the freeze deliberately

Legacy models death as `type = "DECEASED"` plus a companion `DeceasedNote` entity (PENDING→APPROVED), set via `save_deceased_note`, NOT a boolean (EXTRACTION 1 §5, EXTRACTION 3 §d). The clinically important behaviours:

1. **Deceased patient cannot be sent to doctor** — but only as an *incidental* side-effect of the OUTPATIENT-only check (`PatientResource.java:535`), with NO explicit deceased guard, and `change_type` cannot move a DECEASED patient back to OUTPATIENT (it falls through to "Patient type could not be changed." `:501-503`). So once DECEASED, the patient is frozen out of clinical workflow. **This freeze is clinically correct and must be preserved** — you must never be able to book a consultation for a deceased patient.

2. **Deceased summary requires all bills cleared** — `getDeceasedSummary` blocks if related invoices are not cleared ("Could not get deceased summary. Patient have uncleared bills." `PatientResource.java:5857-5877`). Operationally this is the bill-reconciliation-before-body-release practice common in Tanzanian facilities. Preserve exactly.

**CLINICAL CONFLICT to surface (not a blocker for parity):** The legacy freeze is *implicit* (rides on the OUTPATIENT check), not an explicit "patient is deceased, refuse" guard. From a patient-safety/clarity standpoint an explicit guard is preferable, but adding one is NET-NEW. **Recommended resolution:** reproduce the implicit freeze exactly for Increment 03; if the rebuild team wants an explicit DECEASED guard on send-to-doctor for clarity/auditability, that is an approved-change-request item for engagement-lead — flag it, do not silently add it. The planning doc's invented `deceased` boolean + `PATCH .../deceased` + `PATIENT_DECEASED` send-guard (EXTRACTION 5 §7.3, ADVERSARIAL §10) are all DRIFT and must NOT be reproduced.

**MOH/regulatory note on cause-of-death:** Tanzanian facilities are obliged to document cause of death for civil-registration/vital-statistics reporting (the death notification feeding RITA / MOH HMIS, ICD-10 coded). I cannot confirm from the extractions whether `DeceasedNote` captures an ICD-10-codable cause-of-death field. **ACTION for legacy-analyst:** extract the `DeceasedNote` entity field list (cause of death, certifying clinician, date/time of death) so I can validate against MOH death-notification requirements. If the field exists in legacy, reproduce it; if the rebuild needs to ADD ICD-10 cause-of-death capture for MOH compliance, that is a flagged change request, not a silent addition.

---

## 4. Registration-fee + consultation-fee split — OPERATIONALLY CORRECT, this is real Tanzanian practice

The two-charge model is clinically/operationally accurate and must be preserved:

- **Registration fee** — a one-time (or per-episode) facility entry/file fee, created unconditionally at registration as a `PatientBill` with `billItem="Registration"`, sourced from `CompanyProfile.registrationFee` (EXTRACTION 2 §c). This is the standard Tanzanian OPD "card/registration fee."
- **Consultation fee** — a per-encounter clinician fee, created atomically with the consultation, `billItem="Consultation"`, sourced from `clinic.getConsultationFee()` (EXTRACTION 3 §a step 4), waived (status `"NONE"`) for follow-up visits (`PatientServiceImpl.java:467-469`).

**The follow-up waiver is clinically correct and important** — Tanzanian facilities routinely do not re-charge consultation for a return visit within an episode (the follow-up window). The `status="NONE"` on follow-up consultation bills (and the open-gate accepting `"NONE"`, `PatientResource.java:914`) correctly implements "no payable consultation fee for a follow-up." **BLOCKER on any spec that drops the `"NONE"` follow-up state** — losing it would force patients to pay twice for one episode, a real financial-harm regression.

**Insurance registration-fee handling** (EXTRACTION 2 §c insurance override): the legacy fall-throughs are clinically concerning and must be flagged:

- **Edge case A** — INSURANCE patient but no `RegistrationInsurancePlan` with `covered=true`: bill stays UNPAID at the cash `CompanyProfile` rate with no claim created. Operationally this means an insured patient is silently charged a cash registration fee with no insurance claim. **This is a real billing-correctness hazard** (insured patient billed as cash). Per exact-process, reproduce the behaviour — but **add to the edge-case register as a known billing gap** for the billing/claims context, and confirm with business-analyst whether this silent fall-through is intended facility policy or a latent bug.
- **Edge case B** — INSURANCE but `regFee==0`: insurance block skipped entirely, bill stays `"VERIFIED"`, no claim. Lower risk (zero-value), but document it.

---

## 5. NHIF / insurance patient requirements — plan + membership ENFORCEMENT is correct; coverage scoping needs the claims-context review

Legacy correctly requires, for `paymentType="INSURANCE"`: a resolvable `insurancePlan` (by name) AND a non-empty `membershipNo`, else `MissingInformationException("Membership number required")` (EXTRACTION 1 §6, EXTRACTION 2 §b). **This is the correct minimum for NHIF parity** — an NHIF claim cannot be submitted without the scheme/plan and the member card number. Preserve both validations exactly.

**Per-service coverage scoping** (the core of NHIF compliance) is present in legacy as *separate* per-service plan tables:
- `RegistrationInsurancePlan` (registration fee per plan, `covered` flag) — EXTRACTION 2 §c.
- `ConsultationInsurancePlan` (consultation fee per clinic+plan, `covered` flag), with the correct hard error when a clinic is not covered: "Plan not available for this clinic. Please change payment method" (`PatientServiceImpl.java:599-601`) — EXTRACTION 3 §a step 9.

**SIGN-OFF (Registration scope only):** the plan+membership capture and the per-service coverage gate are NHIF-correct for the registration/consultation slice. **This is NOT a full NHIF compliance sign-off** — the complete per-service pricing matrix (lab, radiology, procedures, medicine, ward) and claim-submission field completeness live in the Insurance/Claims and Billing contexts and require their own dedicated review (my DoD deliverable: NHIF compliance checklist). 

**Two items for the claims-context review (flagged, not resolved here):**
1. The unguarded `.get()` on plan-by-name resolution (`PatientResource.java:297`) throws a raw `NoSuchElementException` for an unknown plan name instead of a clean domain error — a robustness gap when a cashier mistypes/selects an unconfigured plan. Reproduce behaviour for parity, flag for hardening.
2. The `PENDING`→`APPROVED` `PatientInvoice` claim lifecycle (EXTRACTION 3 §a step 8) is the NHIF claim build — its completeness against NHIF submission fields must be validated in the claims context.

---

## 6. Cash-before-doctor gate — CLINICALLY SAFE AS LEGACY IMPLEMENTS IT; do NOT add the inc-04-style blanket gate

This is the most important clinical-safety finding. The verified facts (EXTRACTION 3 §b, ADVERSARIAL Claim 2) establish:

- **There is NO registration-fee block on send-to-doctor.** `do_consultation` gates ONLY on: no active admission, type=OUTPATIENT, clinician active, no existing pending/active consultation. Registration-bill status is never read.
- The pay-before-service gate is on the **consultation `PatientBill`**, enforced at **doctor-open time** (`open_consultation` → "Could not open. Payment not verified." `PatientResource.java:884-897`), with a soft UI queue filter upstream. Follow-up open additionally accepts `"NONE"`.

**Clinical-safety verdict:** The legacy design is the SAFER design and is clinically defensible:

1. **Sending to the doctor is free of any payment gate** — a patient can always be placed in the doctor's queue. This is correct: triage/queueing must never be blocked by a billing state.
2. **The payment check sits at doctor-open**, on the *consultation* charge only, which mirrors the standard Tanzanian cash-OPD flow (pay the consultation fee at the cashier, then the doctor opens you). It does NOT gate on the registration fee, so a registration-fee accounting hiccup never blocks clinical care.

**BLOCKER-level guidance against the inc-04 blanket-gate pattern:** Any Increment 03 proposal to **block send-to-doctor on an unpaid registration fee** is (a) NET-NEW drift from legacy (ADVERSARIAL Claim 2 confirms it does not exist) AND (b) a **patient-safety hazard** — it would prevent a patient from reaching a clinician because of a non-clinical, one-time facility fee. This is exactly the inc-04 blanket-gate concern. **Do not reproduce it. Do not introduce it.**

### Recommended scope for any net-new gate

If a payment gate is wanted in Increment 03, the ONLY clinically-safe, parity-aligned scope is:

- **Gate target:** the **consultation `PatientBill`** (NOT the registration bill, NOT a blanket patient-level gate).
- **Gate point:** **doctor-open** (`open_consultation` equivalent), NOT send-to-doctor / `do_consultation`. Send-to-doctor must remain ungated so queueing is never blocked.
- **Accepted statuses:** `PAID` or `COVERED` (and `NONE` for follow-up) — exactly the legacy open-gate set.
- **Emergency carve-out (flag for change-request):** legacy has no explicit emergency override at open. Standard Tanzanian/MOH emergency-care obligation is that life-threatening emergencies must not be denied care for inability to pay. Legacy does not implement this carve-out, so it is NET-NEW — flag to engagement-lead as a candidate change request for the emergency-admission edge case, do NOT silently add it.

This maps cleanly onto the existing inc-04 `SettlementPolicy` (scoped pay-before-service) applied to the **clinical/consultation charge**, NOT a blanket per-patient or registration-fee gate.

---

## 7. Edge-case register additions (Registration/Patient context)

For the annotated edge-case register (my DoD deliverable), Increment 03 must cover at minimum:

| # | Edge case | Legacy behaviour (cite) | Clinical/operational risk | Disposition |
|---|---|---|---|---|
| R1 | Insured patient, no covered RegistrationInsurancePlan | Billed cash rate, no claim, bill UNPAID (`PatientServiceImpl.java:312-321`) | Insured patient silently cash-charged | Reproduce; flag billing gap to BA |
| R2 | Insured patient, regFee==0 | Insurance block skipped, bill VERIFIED (`:312`) | Low (zero value) | Reproduce; document |
| R3 | Follow-up consultation | Consultation bill status `"NONE"`, no charge (`:467-469`) | Dropping it = double-charge harm | BLOCKER if dropped |
| R4 | Deceased patient send-to-doctor attempt | Implicitly blocked by OUTPATIENT-only check; cannot revert type | Must never book deceased | Reproduce freeze exactly |
| R5 | Deceased summary with uncleared bills | Blocked (`:5857-5877`) | Body-release/bill reconciliation | Reproduce exactly |
| R6 | Send-to-doctor with active admission | Blocked (`:531-534`) | Prevents double clinical episode | Reproduce exactly |
| R7 | Repeated same-day send-to-doctor | Creates multiple SUBSEQUENT Visit rows, no dedup (EXTRACTION 4) | Visit-count/encounter inflation in reports | Reproduce; flag reporting impact to BA |
| R8 | Emergency admission without registration | NOT handled in legacy registration path | MOH emergency-care obligation | NET-NEW — flag to engagement-lead, do not add |
| R9 | Unknown insurance plan name on register | Raw NoSuchElementException (`PatientResource.java:297`) | Cashier UX / unclean error | Reproduce; flag hardening |

---

## 8. PHI/PII guidance note (Registration/Patient context)

**Protected data in this context:** patient `no` (MRN), `searchKey` (contains name+phone — itself PHI), full name, `dateOfBirth`, `phoneNo`, `address`, `email`, `nationalId`, `passportNo`, `membershipNo` (NHIF card), next-of-kin fields, and `insurancePlan` linkage. The `searchKey` is especially sensitive because it concatenates name + phone into a single searchable string (EXTRACTION 1 §8).

**Minimum-necessary concerns for the security-architect:**
- **All read/search endpoints are UN-GATED beyond authentication** (EXTRACTION 5 §4): `get_all_search_keys` returns EVERY patient's name+phone composite as a flat list with no paging (EXTRACTION 4 §a). This is a bulk-PHI-exposure surface. Per exact-process, reproduce the behaviour, but I flag it to the security-architect as a **PHI minimum-necessary concern** — a read privilege (legacy has none) may be warranted as a change request. Do NOT invent `PATIENT-VIEW` for Increment 03 parity, but record the gap.
- **No update audit stamp** (EXTRACTION 1 §2): legacy records creation only (`createdBy/createdOn/createdAt`), never who/when updated demographics or changed payment type. For PHI auditability this is a genuine gap — demographic and payment-type changes are exactly the events an audit trail should capture. **CONFLICT surfaced:** exact-process says no update stamp; PHI good-practice wants one. Recommended resolution: reproduce no-stamp for parity, flag to engagement-lead + security-architect as a candidate audit change request.
- **No Envers, no device-binding** — confirmed absent (ADVERSARIAL phantom-feature banner). Do not assume an audit baseline exists.

**Required audit events (for the audit change-request, if approved):** patient register, demographic update, payment-type change, type change (incl. DECEASED transition), consultation create/cancel/free, deceased-note create/approve. Note `change_payment_type` ships UNGATED in legacy (`PatientResource.java:311`) — a PHI/financial-sensitive operation with no privilege check; flag to security-architect.

---

## SIGN-OFF SUMMARY

| Item | Verdict |
|---|---|
| OUTPATIENT/OUTSIDER distinction | SIGNED OFF — reproduce 4-value vocabulary; BLOCKER if INPATIENT/DECEASED dropped |
| Next-of-kin (single, optional) | SIGNED OFF for parity; clinical gap documented (not a blocker) |
| Deceased flag + freeze + bill-clear-before-summary | SIGNED OFF — reproduce implicit freeze; ICD-10 cause-of-death capture needs `DeceasedNote` field extraction |
| Registration-fee + consultation-fee split + follow-up NONE waiver | SIGNED OFF — BLOCKER if `"NONE"` follow-up state dropped |
| NHIF plan+membership requirement | SIGNED OFF (registration scope only); full NHIF checklist deferred to claims context |
| Cash-before-doctor gate | SIGNED OFF on LEGACY design (consultation-bill gate at open). BLOCKER against any registration-fee or blanket send-to-doctor gate — patient-safety hazard |

**Net-new gate recommendation:** the only clinically-safe gate is on the **consultation `PatientBill`** (`PAID`/`COVERED`/`NONE`-for-follow-up) at **doctor-open**, never on the registration fee and never at send-to-doctor. Maps onto inc-04 `SettlementPolicy` applied to the clinical/consultation charge only.

**Open items requiring action before design-complete:**
1. **legacy-analyst:** extract `DeceasedNote` field list (cause-of-death / certifying clinician / time-of-death) for MOH death-notification + ICD-10 validation.
2. **business-analyst:** confirm whether the insured-but-uncovered-registration silent cash fall-through (R1) is intended policy or latent bug; confirm same-day duplicate-Visit reporting impact (R7).
3. **engagement-lead:** rule on (a) emergency-admission-without-registration (R8, NET-NEW), (b) explicit DECEASED send-guard (NET-NEW), (c) update-audit-stamp change request.
4. **security-architect:** PHI minimum-necessary on ungated bulk-search reads + ungated `change_payment_type` + no-update-audit-stamp.

**Relevant files (absolute):**
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/PatientServiceImpl.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Patient.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/DeceasedNote.java` (field list needs extraction)
- `D:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/03-registration-patient.md` (DRIFT source — must be reconciled)