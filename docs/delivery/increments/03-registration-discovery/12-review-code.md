I have enough to finalize. Let me do one final verification: confirm the `changePatientType` audit and the `change_type` flow does NOT silently drop the OUTSIDER→OUTPATIENT guard in a way the spec didn't authorize. I confirmed the build-spec defers REG-3 (NonConsultation clearance) since those entities don't exist. That's properly documented.

Now let me write the review report.

# CODE REVIEW — Increment 03 (Registration & Patient)

**Reviewer:** Tech Lead / Code Reviewer · **Branch:** feat/increment-04-billing-cashiering · **Scope:** inc-03 registration module + cross-module BILLING CR-20 + shared error additions + tests. `mvn clean verify` GREEN (427 tests). Read-only review.

---

## Summary verdict: **REQUEST-CHANGES** (one MAJOR fidelity gap; everything else clean or NIT)

The mrNo/searchKey reproduction, the CR-20 follow-up→NONE billing change, atomicity, Modulith boundary, RBAC, and the pricing-engine non-regression are all correct and well-tested. There is **one genuine in-scope behaviour drift**: the `change_payment_type` open-work guard drops its **consultation leg**, which is live in inc-03, while the build-spec's own C4 DoD requires an "open-work guard."

---

## BLOCKER
None.

---

## MAJOR

### M1 — `change_payment_type` silently drops the in-scope CONSULTATION open-work guard
**File:** `backend/src/main/java/com/otapp/hmis/registration/application/PatientRegistrationProcess.java:349-371` (esp. the deferred-stub comment at `:353-355`).

**Legacy (source of truth):** `PatientResource.java:325-357` blocks the payment-type flip when the patient has **any `Consultation` in `{PENDING, IN-PROCESS, STOPPED, HELD}`** (`:331-334`), OR a `NonConsultation` with active lab/rad/proc work (`:335-353`), OR a matching `Admission` (`:354-357`) — verbatim message `"Could not change. Patient has an ongoing medical operation s"` (note the trailing `" s"` typo, sic).

**Drift:** The new method documents the guard as a single deferred "admissions guard" no-op stub (`:353-355`) and implements only the insurance/CASH-collapse assignment. But **`Consultation` is a live aggregate in inc-03** — `sendToDoctor` creates `PENDING` consultations, and the finder `consultationRepository.existsByPatientAndStatus(patient, PENDING)` already exists and is used by `changePatientType` (`:294`). So the consultation leg is enforceable now and was dropped, not legitimately deferred. The build-spec C4 line item explicitly scopes this: *"`PATCH .../payment-type` (open-work guard + CR-03 gate). ITs for each guard"* (`00-build-spec.md:196`), and BA `10-design-ba.md:89` (AC7.1) lists the consultation block as in-scope. A patient with a PENDING consultation can today flip CASH→INSURANCE (or back), which the legacy forbids — a financially material mutation on an active encounter.

**Fix (precise):** In `changePaymentType`, before the assignment block, add the consultation leg of the open-work guard:
```java
if (consultationRepository.existsByPatientAndStatus(patient, ConsultationStatus.PENDING)) {
    throw new InvalidPatientOperationException(
        "Could not change. Patient has an ongoing medical operation s"); // verbatim, sic
}
```
Keep the `NonConsultation`/`Admission` legs as documented DEFERRED stubs (those entities don't exist until inc-05/06) — but the comment at `:353-355` must be corrected to say only those legs are deferred, and the consultation leg must be enforced + covered by an IT (mirroring `PatientUpdateIT.changePatientType_blockedByActiveConsultation_422`). Use the exact legacy string including the trailing `" s"` so QA golden-master matching holds. If the engagement-lead prefers to formally defer the consultation leg too, that requires an explicit CR (this is a billing/clinical-state guard) — it cannot be dropped on the existing ratification, which only defers admissions/non-consultation.

---

## MINOR

### m1 — REGISTRATION zero-fee + covered-plan: pre-existing engine edge (out of inc-03 scope, log only)
**File:** `backend/src/main/java/com/otapp/hmis/billing/application/BillingChargeService.java:122-156`.
Legacy gates the registration insurance override on `regFee > 0` (`PatientServiceImpl.java:312`). The engine attempts the insurance override regardless of a zero cash amount; for `kind=REGISTRATION` with `cashAmount==0` AND a covered plan row, it would `overrideWithInsurance` to the plan price (COVERED + claim) where legacy stays VERIFIED with no claim. This is **pre-existing inc-04 billing behaviour**; CR-20 did not touch it (the follow-up short-circuit returns before reaching this code). Not blocking for inc-03 — flag to the billing-increment owner / legacy-analyst to confirm whether the `regFee>0` gate must be reproduced on the REGISTRATION override path. No registration call seeds a zero covered registration price, so it is unreachable in inc-03 flows.

---

## NIT

- **n1 — `package-info.java:29-30` is stale.** It says the `registration :: api` named interface is exposed "in a later chunk (C6/C7)"; CR-21/ADR-0008-R1 require it for inc-05 PENDING-consultation reads, and inc-03 is now complete with no `@NamedInterface("api")` in the registration package. Not a defect (inc-05 hasn't landed), but the doc should state it is deferred to inc-05 rather than "C6/C7" of inc-03. File: `backend/src/main/java/com/otapp/hmis/registration/package-info.java`.
- **n2 — `PatientMapper.toDto(patient)` single-arg used in `register()`** (`PatientRegistrationProcess.java:189`) returns `lastVisitAt=null`. Correct for inc-03 parity (legacy `doRegister` returns the patient with no last-visit enrichment), but note the just-created FIRST visit's timestamp is intentionally omitted — acceptable; documented behaviour. No change needed.
- **n3 — `Consultation.visit` is nullable in schema (`V19:268`, entity `:64-66`)** but `sendToDoctor` always sets it. Fine for the stub; tighten to `NOT NULL` if inc-05 confirms invariant. Non-blocking.

---

## Fidelity / correctness checks PASSED (explicitly verified clean)

1. **mrNo** — `MrNumberGenerator.java:46-51`: `MRNO/{EAT-year}/{nextval seq_mrno}`, atomic (`PatientRepository.java:42-43` native `nextval`), no pad, no per-year reset. EAT-year pin (`:32`). Tests pin format/no-pad/EAT-boundary/large-seq (`MrNumberGeneratorTest`) + 20-thread distinctness (`MrNumberConcurrencyIT`). Matches legacy `PatientServiceImpl.java:250` format with the ratified seq decoupling (CR-02). ✔
2. **searchKey byte-verbatim** — `SearchKeyBuilder.java:43-50` reproduces `createSearchKey` (`PatientServiceImpl.java:739-744`) then `Sanitizer.sanitizeString` (`Sanitizer.java:11-17`) in order: 5-field `+`-concat, case PRESERVED, `null`→`"null"`, `'+'`→space, and the `[+^]*#$%&` no-op (the `$` anchor). `SearchKeyBuilderTest` pins all four quirks including `Jo#$%&hn` NOT stripped and `null` literal. ✔
3. **change_type guards** — OUTPATIENT→OUTSIDER blocked on PENDING consultation, verbatim "Can not change patient type, the patient has an active consultation." (`PatientRegistrationProcess.java:296-298` vs legacy `:424`); INPATIENT blocked verbatim (`:306-307` vs `:500`); DECEASED/catch-all verbatim (`:310` vs `:502`); OUTSIDER→OUTPATIENT NonConsultation-clearance correctly DEFERRED as a documented stub (`:301-303`, REG-3; those entities don't exist until inc-05). INSURANCE register guard + CASH plan-collapse correct. ✔
4. **sendToDoctor guard order + followUp→NONE** — order matches legacy resource+service: admission (DEFERRED stub, CR-19, documented `:452`) → OUTPATIENT (`:430-433` vs legacy `:535-537`) → clinician affiliation (replaces legacy active-clinician `:427`) → PENDING-consultation block verbatim message (`:446-449` vs legacy `:449`). TRANSFERED/IN-PROCESS deferred & documented. ✔
5. **reg-fee gate correctly ABSENT** — `sendToDoctor` is UNGATED on payment (CR-01 rejected); no registration-fee block. ✔
6. **CR-20 billing change** — follow-up short-circuit (`BillingChargeService.java:96-104`) creates a `Money.zero()` bill, `markNoCharge()` → status NONE, returns BEFORE any price resolution/override/invoice. This correctly reproduces the legacy NET behaviour where NONE wins for follow-ups on BOTH cash and insurance paths (legacy forces NONE at `:468` AND again at `:607` overriding COVERED). `markNoCharge()` (`PatientBill.java:251-257`) zeroes amount/paid/balance and asserts the invariant. **Non-followUp path byte-identical** — confirmed by the intact golden-master suite (`BillingChargeIT`: all 11 cases pass with the trailing `false` followUp arg; COVERED override, medicine ×qty HALF_UP, consultation hard-fail, inpatient VERIFIED, outpatient UNPAID, REGISTRATION silent/zero-VERIFIED, accumulator). Invariant `paid+balance==amount` and insurance override untouched. ✔
7. **Atomicity** — `sendToDoctor` single `@Transactional` (`:423`); `recordClinicalCharge` is `Propagation.REQUIRED`/`MANDATORY` (`BillingCommandsImpl.java:44`, `BillingChargeService.java:85`); charge called BEFORE Visit+Consultation persist so a charge failure leaves no orphans. `SendToDoctorIT.sendToDoctor_chargeFails_rollsBackEntirely` asserts zero consultation/SUBSEQUENT-visit/CONSULTATION-bill rows. ✔
8. **Modulith/layering** — `package-info.java:40-41` allows only `shared, billing::api, masterdata::lookup, iam::lookup`. The `@NamedInterface("api")` on `PaymentMode/BillStatus/CoverageStatus` (`billing.domain`) merges into the same `"api"` interface declared at `billing/api/package-info.java:13`, so referencing them from registration is legal (correct fix vs leaking `billing.domain`). `ApplicationModules.verify()` GREEN. No `@Transactional` on `PatientController`; no `{id}` routes (only `/uid/{uid}`); no `id` in DTOs (tests assert `$.id` doesn't exist); MapStruct `injectionStrategy=CONSTRUCTOR`, package-private, no repo injection. ✔
9. **RBAC** — live codes only: register `PATIENT-ALL/CREATE` (`PatientController.java:107`), update/type/payment `PATIENT-ALL/UPDATE` (`:133,151,170`; payment-type is the CR-03 FIX gate), send-to-doctor `PATIENT-ALL/CREATE/UPDATE` (`:189` vs legacy `:509`), reads ungated (CR-04). 401/403 ITs present. No invented codes. ✔
10. **Null-safety / JPA** — `insurancePlanUid`/`membershipNo` normalised on CASH (`PatientRegistrationProcess.java:106-110`); membership `null`-vs-`""` handled into ChargeRequest (`:166`); search `LOWER(...) LIKE` tolerates null fields. `ConsultationMapper` reads `patient.uid` within-tx (patient eagerly available). last-visit uses indexed `findFirstByPatientOrderByCreatedAtDesc` (CR-08). Search N+1 (per-row last-visit enrichment in `PatientQueryService.toDtoWithLastVisit`) is acceptable per page size and noted in spec. Audit calls (`auditRecorder.record`) are in-tx. ✔

---

**Required before APPROVE:** fix **M1** (enforce the consultation leg of the `change_payment_type` open-work guard with the verbatim legacy message + an IT), or obtain an explicit engagement-lead CR formally deferring it. Address m1/n1 at the billing-increment owner's discretion (out of inc-03 scope). Re-run `mvn clean verify`.

**PHI/security clearance:** No PHI leak introduced. No audit entries removed (CREATE/UPDATE audit calls present and in-tx for Patient and Consultation). No privilege granularity reduced (CR-03 adds a gate). The M1 gap is a business-state guard, not a PHI/security regression.