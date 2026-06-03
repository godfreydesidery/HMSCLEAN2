Now I have all the information needed. Let me compile the full coverage matrix and findings.

---

## COVERAGE MATRIX

### 1. mrNo: format + EAT year + concurrency

| Behaviour | Covered? | Where |
|---|---|---|
| Format `MRNO/{year}/{seq}` no-pad | YES | `MrNumberGeneratorTest:formats_MRNO_year_seq_unpadded` |
| Large seq, no-pad | YES | `MrNumberGeneratorTest:largeSeq_notPadded` |
| EAT year at UTC midnight boundary (2026-12-31T23:30Z → EAT year 2027) | YES | `MrNumberGeneratorTest:usesEatYear_acrossUtcMidnight` |
| 20-thread concurrency → 20 distinct MRNs | YES | `MrNumberConcurrencyIT:concurrent20Threads_produceDistinctMrNumbers` |
| MRN format regex also asserted in concurrency test | YES | `MrNumberConcurrencyIT:59` (`^MRNO/\d{4}/\d+$`) |
| seq from `seq_mrno` (not IDENTITY PK) | YES (implicit) | `nextMrNo()` native query, covered by concurrency IT |

**Area CLEAN.**

---

### 2. searchKey

| Behaviour | Covered? | Where |
|---|---|---|
| 5-field composition, case preserved | YES | `SearchKeyBuilderTest:composesFiveFields_casePreserved` |
| Whitespace collapse, blank optional fields | YES | `SearchKeyBuilderTest:collapsesWhitespace_whenOptionalFieldsBlank` |
| `null` field → literal `"null"` | YES | `SearchKeyBuilderTest:nullField_concatenatedAsLiteralNull_perLegacy` |
| `[+^]*#$%&` is NO-OP, `#$%&` preserved | YES | `SearchKeyBuilderTest:hashDollarPercentAmp_NOT_stripped_dollarIsRegexAnchor` |
| `+` → space (Sanitizer) | YES | `SearchKeyBuilderTest:plusSign_replacedWithSpace_bySanitizer` |
| searchKey contains MRN after registration (integration round-trip) | YES (partial) | `RegisterPatientIT:116` (asserts `contains(mrn)`) |
| searchKey recomputed on demographics update | NO TEST | `PatientRegistrationProcess:217-240` (code exists, no IT assertion) |

**Minor gap: searchKey recomputation on `updateDemographics` is exercised in `PatientUpdateIT:updateDemographics_200_fieldsChanged` but no assertion verifies the new searchKey reflects the updated name/phone. Low severity — the unit path is fully covered; the integration assertion is missing.**

---

### 3. register

| Behaviour | Covered? | Where |
|---|---|---|
| CASH happy path: 201 + Location + no `id` in JSON | YES | `RegisterPatientIT:registerCashPatient_201_domainObjectsCorrect` |
| Patient active=true, type=OUTPATIENT default, CASH | YES | `RegisterPatientIT:110-117` |
| MRN format pattern | YES | `RegisterPatientIT:114` |
| searchKey contains MRN | YES | `RegisterPatientIT:116` |
| CASH → insurancePlanUid null | YES | `RegisterPatientIT:117` |
| Registration ACTIVE + non-blank billUid | YES | `RegisterPatientIT:120-122` |
| Visit FIRST + PENDING | YES | `RegisterPatientIT:125-128` |
| PatientBill kind=REGISTRATION exists | YES | `RegisterPatientIT:131-133` |
| Bill uid matches Registration.patientBillUid | YES | `RegisterPatientIT:135` |
| Audit CREATE row for patient uid | YES | `RegisterPatientIT:138-142` |
| INSURANCE happy path: 201 + Registration persisted | YES | `RegisterPatientIT:registerInsurancePatient_201_registrationPersisted` |
| INSURANCE: plan+membership stored | YES | `RegisterPatientIT:171-173` |
| INSURANCE: Visit FIRST persisted | YES | `RegisterPatientIT:181-183` |
| INSURANCE no plan → 422 `missing-insurance-information` | YES | `RegisterPatientIT:registerInsurancePatient_noPlan_422` |
| INSURANCE no membership → 422 `missing-insurance-information` | YES | `RegisterPatientIT:registerInsurancePatient_noMembership_422` |
| 403 without PATIENT-CREATE/PATIENT-ALL | YES | `RegisterPatientIT:registerPatient_missingPrivilege_403` |
| 401 no token | YES | `RegisterPatientIT:registerPatient_noToken_401` |
| No `id` in response | YES | `RegisterPatientIT:registerCashPatient_responseHasNoIdField` |
| **Reg-bill status is UNPAID (non-zero CASH)** | **NO DIRECT ASSERTION** | Code comment at line 305-306 acknowledges UNPAID but no `bill.getStatus()` assertion in the test |
| **INSURANCE-covered reg bill → COVERED status + plan price used** | **NOT COVERED** | Insurance test only checks Registration is ACTIVE; bill status not asserted |
| **regFee==0 → VERIFIED status** | **NOT COVERED** | `BillingChargeService:123` is tested in `BillingChargeIT` but not via the registration REST endpoint |
| **Insurance-but-uncovered fallback (R1: silent UNPAID)** | **NOT COVERED** | No registration IT exercises the path where INSURANCE patient has no covered REGISTRATION price row |
| Audit for Registration entity (not just Patient) | NOT COVERED | `auditRecorder` is called only for Patient entity; Registration audit is not captured in the service, and no test checks it |

**Gaps here (see prioritized section below).**

---

### 4. flips: patient-type + payment-type

| Behaviour | Covered? | Where |
|---|---|---|
| OUTPATIENT → OUTSIDER success | YES | `PatientUpdateIT:changePatientType_outpatientToOutsider_200` |
| OUTPATIENT → OUTSIDER blocked by active PENDING consultation | YES | `PatientUpdateIT:changePatientType_blockedByActiveConsultation_422` |
| INPATIENT → any: blocked 422 | YES | `PatientUpdateIT:changePatientType_inpatient_422` |
| **OUTSIDER → OUTPATIENT success** | **NOT COVERED** | No test for the reverse flip; only one direction is tested |
| **DECEASED → any: blocked 422** | **NOT COVERED** | No test for DECEASED guard (`PatientRegistrationProcess:310`) |
| CASH → INSURANCE missing plan+membership → 422 | YES | `PatientUpdateIT:changePaymentType_cashToInsurance_requiresPlanAndMembership` (first sub-case) |
| CASH → INSURANCE with plan+membership → 200 | YES | `PatientUpdateIT:changePaymentType_cashToInsurance_requiresPlanAndMembership` (second sub-case) |
| INSURANCE → CASH collapse (plan nulled) | YES | `PatientUpdateIT:changePaymentType_insuranceToCash_collapses` |
| PUT update → 403 without PATIENT-UPDATE | YES | `PatientUpdateIT:update_returns403_withoutPatientUpdate` |
| PATCH patient-type → 401 no token | YES | `PatientUpdateIT:changePatientType_returns401_whenNoToken` |
| PUT update → 404 unknown uid | YES | `PatientUpdateIT:update_unknownUid_404` |
| **change_payment_type RBAC (CR-03): requires PATIENT-ALL/PATIENT-UPDATE** | **NOT COVERED** | No 403 test for PATCH /payment-type endpoint specifically |

---

### 5. search

| Behaviour | Covered? | Where |
|---|---|---|
| Search by name substring | YES | `PatientSearchIT:search_byNameSubstring_findsPatient` |
| Search by MRN (no) | YES | `PatientSearchIT:search_byMrNo_findsPatient` |
| Search by membershipNo (REG-1) | YES | `PatientSearchIT:search_byMembershipNo_findsPatient_reg1` |
| Pagination (totalElements, totalPages, page, size) | YES | `PatientSearchIT:search_paginated` |
| GET by uid + lastVisitAt populated | YES | `PatientSearchIT:getByUid_200_withLastVisit` |
| last-visit endpoint | YES | `PatientSearchIT:lastVisit_200` |
| Reads authenticated-only, no privilege needed (CR-04) | YES | `PatientSearchIT:reads_areAuthenticatedOnly_ungated` |
| 401 no token on reads | YES | `PatientSearchIT:reads_401_whenNoToken` |
| 404 unknown uid | YES | `PatientSearchIT:getByUid_unknown_404` |
| **lastVisitAt ordering: most-recent of multiple visits returned** | **WEAK** | `PatientSearchIT:getByUid_200_withLastVisit` only asserts `lastVisitAt` is non-empty after FIRST visit; no test creates a SUBSEQUENT visit then asserts the later timestamp wins |

---

### 6. send-to-doctor

| Behaviour | Covered? | Where |
|---|---|---|
| CASH success: Consultation PENDING, CONSULTATION bill, SUBSEQUENT visit | YES | `SendToDoctorIT:sendToDoctor_cash_201_createsConsultationFeeBillAndVisit` |
| 201 + ConsultationDto (not null — CR-06) | YES | `SendToDoctorIT:88` |
| SUBSEQUENT visit added (2 visits total after send) | YES | `SendToDoctorIT:98-100` |
| followUp=true → NONE bill status | YES | `SendToDoctorIT:sendToDoctor_followUp_createsNoneBill` (asserts `BillStatus.NONE`) |
| followUp=true → no CONSULTATION price needed | YES | `SendToDoctorIT:113` (no price seeded, still 201) |
| not-OUTPATIENT → 422 | YES | `SendToDoctorIT:sendToDoctor_notOutpatient_422` (flips to OUTSIDER first) |
| clinician not affiliated → 422 | YES | `SendToDoctorIT:sendToDoctor_clinicianNotAffiliated_422` |
| duplicate PENDING consultation → 422 | YES | `SendToDoctorIT:sendToDoctor_existingPendingConsultation_422` |
| atomicity rollback: charge fails → no consultation, no SUBSEQUENT visit, no CONSULTATION bill | YES | `SendToDoctorIT:sendToDoctor_chargeFails_rollsBackEntirely` |
| 403 without privilege | YES | `SendToDoctorIT:sendToDoctor_403_withoutPatientPrivilege` |
| 401 no token | YES | `SendToDoctorIT:sendToDoctor_401_noToken` |
| **followUp NONE bill: amount is zero** | **PARTIAL** | `SendToDoctorIT:sendToDoctor_followUp_createsNoneBill` asserts status==NONE but never asserts `bill.amountValue() == 0` |
| **INSURANCE patient send-to-doctor: plan covers clinic → COVERED bill** | **NOT COVERED** | All send-to-doctor tests use CASH patients; no IT exercises the INSURANCE path through sendToDoctor |
| **INSURANCE patient send-to-doctor: plan does NOT cover clinic → 422 `PLAN_NOT_AVAILABLE_FOR_CLINIC`** | **NOT COVERED** | `BillingChargeIT` covers the engine path but no registration-level IT drives it end-to-end |
| Atomicity convincingness: proves consultation+visit+bill all-or-nothing? | **PARTIAL** | Test proves zero consultation rows and zero SUBSEQUENT visits and zero CONSULTATION bills on charge failure. It does NOT prove what happens if the Visit persist fails after the charge succeeds (Visit is persisted before Consultation at line 476-477, charge is first at line 460) — but this ordering makes the test valid: charge fails first, everything rolls back. The rollback proof is adequate for the charge-fail scenario. The Visit-persist-fail scenario is not tested but is covered by the single `@Transactional` boundary. |
| **Audit for Consultation CREATE** | **NOT ASSERTED IN TESTS** | `PatientRegistrationProcess:493` emits a Consultation audit record but no test in `SendToDoctorIT` reads `auditLogRepository` to verify it |

---

### 7. Ratified behaviours with no test; flaky risk

| Behaviour | Status |
|---|---|
| **`NoDayOpenException` → 422 on POST /patients when no day is open** | **NOT COVERED** — the `ensureDayOpen()` helper silently opens a day; no test deliberately leaves the day closed and asserts 422 |
| **Audit trail for Visit mutations** | **NOT COVERED** — `PatientRegistrationProcess` emits no Visit audit events; none are asserted in tests. Per build-spec §7: "every Patient/Registration/Visit mutation emits exactly one `audit_log` row". Registration entity also gets no audit call |
| **Audit trail for Registration entity** | **NOT COVERED** — no `auditRecorder.record` for Registration in the service; no test verifies it |
| **registrationFee==0 → VERIFIED bill status via the registration endpoint** | **NOT COVERED** — `BillingChargeIT` tests this in the engine; no IT seeds a zero REGISTRATION price and asserts the bill's status via the full registration flow |
| **Insurance-but-uncovered fallback at registration (R1 parity): INSURANCE patient with no covered REGISTRATION price → silent UNPAID** | **NOT COVERED** |
| **`OUTSIDER → OUTPATIENT` flip** | **NOT COVERED** — only one direction is tested |
| **DECEASED guard on patient-type flip** | **NOT COVERED** |
| **CR-03 RBAC: PATCH /payment-type requires PATIENT-UPDATE/PATIENT-ALL** | **NOT COVERED** |
| **INPATIENT guard on send-to-doctor (CR-19 deferred stub)** | **UNVERIFIABLE as deferred** — documented no-op, acceptable |
| **Flaky risk: `ensureRegistrationCashPrice()` shared singleton** | **LOW** — suppresses 409 correctly; the try/catch or `anyOf(200,201,409)` pattern handles idempotency. However, the `seedPrice()` helper in `RegisterPatientIT` does NOT suppress 409 with a try/catch for the INSURANCE plan price seed (line 155); it uses `anyOf(201,409)` which is safe. Low actual flake risk |
| **Unique discriminators in search tests** | CLEAN — `uniqueToken()` uses `System.nanoTime()` hex, providing sufficient isolation on the shared container |
| **`PatientUpdateIT.changePatientType_blockedByActiveConsultation_422`: does NOT commit the consultation** | **POTENTIAL ISSUE** — the test class lacks `@Transactional`; the consultation is saved with `consultationRepository.save()` inside the test method (line 101). Without an explicit `save()` flush + commit, the seed may not be visible when the endpoint's new transaction starts. The test calls `save()` (not `saveAndFlush()`) with no explicit flush — if the test method has no transaction boundary, the save happens in an auto-commit path through the Spring Data repository. In a `@SpringBootTest(MOCK)` context without `@Transactional` on the test, each `save()` is committed immediately. This is likely correct, but the use of raw `save()` without `flush()` is fragile — the consultation save may not be flushed to the DB before the MockMvc call in the same test. **This is a genuine flaky test risk.** |

---

## PRIORITIZED GAPS (severity: CRITICAL / HIGH / MEDIUM / LOW)

**CRITICAL**

1. **Audit trail for Registration and Visit entities never emitted, never tested** (`PatientRegistrationProcess` lines 184-188 only audit Patient; no `auditRecorder.record` exists for Registration or Visit). Build-spec §7 mandates "every Patient/Registration/Visit mutation emits exactly one `audit_log` row". The omission is in both implementation and tests simultaneously — this is a safety-critical regression against the security-architect's audit requirement. File: `PatientRegistrationProcess.java:184-188` (audit after step 8 covers only Patient). Missing: audit calls after step 6 (Registration persist) and step 7 (Visit persist), plus corresponding assertions in `RegisterPatientIT`.

2. **Atomicity partial-risk: `changePatientType_blockedByActiveConsultation_422` uses `consultationRepository.save()` without `saveAndFlush()`** in a non-`@Transactional` test. If the Hibernate session is not flushed before the MockMvc call triggers a new transaction, the test guard may see no consultation and incorrectly return 200 instead of 422, causing a false-negative (the guard appears to work when it does not). File: `PatientUpdateIT.java:101`. Fix: change to `consultationRepository.saveAndFlush()`.

**HIGH**

3. **NONE-bill amount not asserted as zero** (`SendToDoctorIT:sendToDoctor_followUp_createsNoneBill` asserts `BillStatus.NONE` but not `amountValue() == BigDecimal.ZERO`). The build-spec is explicit: follow-up must produce a zero-charge bill. The status check alone does not prove the invariant holds for amount/paid/balance. File: `SendToDoctorIT.java:121-125`. Missing assertion: `bills.stream().filter(CONSULTATION + NONE).findFirst().map(b -> b.amountValue()).orElseThrow() == 0`.

4. **INSURANCE happy path through send-to-doctor never tested**. `BillingChargeIT` covers the engine, but no registration-level IT sends an INSURANCE patient to a doctor with a covered plan (expected: COVERED bill + invoice detail). The `PLAN_NOT_AVAILABLE_FOR_CLINIC` 422 path through the send-to-doctor endpoint is also untested at the registration layer. Files: `SendToDoctorIT.java` has no insurance patient; `BillingChargeIT.java` covers only the engine.

5. **Reg-bill status not asserted** — `RegisterPatientIT:registerCashPatient_201_domainObjectsCorrect` verifies kind=REGISTRATION and uid match but never calls `bills.get(0).getStatus()`. A broken billing step that produces COVERED/PAID instead of UNPAID would pass all tests. File: `RegisterPatientIT.java:131-135`. Missing: assert `bills.get(0).getStatus() == BillStatus.UNPAID`.

6. **INSURANCE-covered registration bill status (COVERED) and insurance-but-uncovered silent-UNPAID (R1 parity) never tested at the registration endpoint level**. `RegisterPatientIT:registerInsurancePatient_201_registrationPersisted` only checks that Registration is ACTIVE; the bill status (expected COVERED) is not asserted. The R1 fall-through (INSURANCE but no covered price → silent UNPAID) has no test at all. Files: `RegisterPatientIT.java:150-184`.

**MEDIUM**

7. **OUTSIDER → OUTPATIENT flip has no test**. `PatientUpdateIT` tests only OUTPATIENT→OUTSIDER. The reverse direction `PatientRegistrationProcess:303` (`case OUTSIDER -> patient.changeType(req.targetType())`) is live code with no coverage. File: `PatientUpdateIT.java` (absent).

8. **DECEASED guard on patient-type flip not tested**. `PatientRegistrationProcess:310` catches DECEASED (default case) and throws 422, but no test exercises it. File: `PatientUpdateIT.java` (absent).

9. **CR-03 RBAC on PATCH /payment-type not tested**. `PatientController:170` has `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")` but `PatientUpdateIT` has no 403 test for the payment-type endpoint. `update_returns403_withoutPatientUpdate` only covers PUT, not PATCH /payment-type. File: `PatientUpdateIT.java:169-195` (absent).

10. **`NoDayOpenException` → 422 path not tested in any registration IT**. Every test silently opens a day in `ensureDayOpen()`. The 422 response when no day is open is never verified end-to-end for POST /patients.

11. **`lastVisitAt` ordering not proven**: `PatientSearchIT:getByUid_200_withLastVisit` asserts the field is non-empty but does not prove the most-recent visit timestamp is returned when multiple visits exist. A correct result when only one visit exists does not test the ORDER BY. File: `PatientSearchIT.java:111-120`.

12. **searchKey recomputed on demographics update not asserted**. `PatientUpdateIT:updateDemographics_200_fieldsChanged` changes the first name but never reads `patient.getSearchKey()` to verify it was recomputed. File: `PatientUpdateIT.java:59-78`.

**LOW**

13. **Audit for Consultation CREATE not asserted in SendToDoctorIT**. `PatientRegistrationProcess:493` emits it, but no test reads `auditLogRepository` for the consultation uid. The Patient audit test in `RegisterPatientIT` sets a good pattern but it is not replicated for Consultation. File: `SendToDoctorIT.java` (absent).

14. **Registration entity audit emission is architecturally absent** — covered under CRITICAL #1 above but also worth noting: even if a test were written, it would fail because the service does not emit the event. This is both an implementation and a test gap.

---

## VERDICT: SUFFICIENT-WITH-GAPS

The test suite is well-structured and covers the primary happy paths, the principal guard conditions (OUTPATIENT guard, affiliation gate, duplicate-consultation gate, atomicity rollback), and the key parity items (EAT year, searchKey verbatim regex, NONE bill, MRN concurrency). The singleton Testcontainer pattern is sound and the unique-discriminator discipline avoids cross-test contamination.

However, the following gaps make the suite insufficient for a formal release sign-off:

- The audit trail for Registration and Visit entities is simultaneously absent from the implementation and untested — a hard failure against the security-architect's audit requirement (§7 of the build-spec mandates it).
- The NONE-bill zero-amount invariant is not asserted.
- The INSURANCE path through send-to-doctor (covered and not-covered) is not tested at the registration level.
- The COVERED/UNPAID bill status on registration is not asserted in any IT.
- The `save()` without `saveAndFlush()` in `PatientUpdateIT:101` is a concrete flaky-test risk for the active-consultation guard.

**Release is blocked** pending (in priority order): CRITICAL #1 (implement + test audit for Registration/Visit), CRITICAL #2 (saveAndFlush fix), HIGH #3-6, and MEDIUM #7-9. Items #10-14 are recommended before production sign-off but do not individually block the increment if the CRITICAL and HIGH items are resolved.