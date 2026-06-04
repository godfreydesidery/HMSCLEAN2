# Inc-05 Clinical — 3-Lens Review Resolutions

**Status:** RESOLVED 2026-06-04. `mvn clean verify` GREEN: **647 tests, 0 failures**.
Review reports: [16-review-reports.md](16-review-reports.md) (raw: [15-review-3lens-raw.json](15-review-3lens-raw.json)).
Engagement-owner ruling: **extend billing::api + apply all in-scope fixes** (not re-scope as deferral).

## Verdicts
- code-reviewer: **REQUEST_CHANGES** → resolved (all F1-F11 fixed; F12 documented).
- qa-test-engineer: **REQUEST_CHANGES** → resolved (QA-01/03/04/07/08/09 fixed; QA-02/05/06 = follow-up coverage, below).
- security-architect: **APPROVE_WITH_GAPS** → SEC-01 fixed; SEC-02/03/04 documented (audit-classification CR).

## Production-code fixes applied (all GREEN)

| Finding | Fix |
|---|---|
| **F1** open/open-follow-up verbatim drift | `ConsultationLifecycleService`: restored `"Could not open. Not a pending consultation."` and `"Could not open. This is not a follow up consultation"`. |
| **F2** dup-drug message | `PrescriptionService` (both guards): `"Duplicate drug is not allowed. Consider editing qty"` verbatim. |
| **F3** transfer guard + messages | `ConsultationTransferService`: WIRED the no-PENDING-child-orders guard (lab/radiology/procedure PENDING, prescription NOT-GIVEN) with the 4 verbatim messages; fixed `"Can not transfer, the patient already have a pending transfer"` and `"Can not transfer to the same clinic"`. |
| **F4** referral-save 4-way gate | `ClosureService`: split into per-type checks throwing the 4 verbatim `"Could not save. Patient have uncleared <type> bill(s)"` messages in legacy order. |
| **F5** deceased gate scope + invoice-APPROVED | `ClosureService.approveDeceased`: added `consultation.isSettled()` to the gate; calls `billingCommands.approveInvoicesForBills(...)` to reproduce the invoice-APPROVED side-effect. |
| **F6** bill cancel/refund/credit-note | **Extended `billing::api`** with `cancelCharge(billUid, reference, ctx)` + `approveInvoicesForBills(...)` (delegating to the existing inc-04 `CreditNoteService.cancel` + `PatientInvoice.approve`). Wired into `ConsultationLifecycleService.cancel`/`free` and `PrescriptionService.delete`. |
| **F7** free reg-no identity check | **DEFERRED with rationale** (no module cycle): the reg-no→patientUid resolution needs a registration read, but `registration → clinical::api` already exists, so a `clinical → registration` call would cycle. The verbatim messages + a TODO referencing the cycle constraint are in place; a no-cycle mechanism (frontend passes both, or an event/query interface) is a follow-up. |
| **F8** issueMedicine guards | `Prescription.issue()`: guard order/messages aligned with legacy (`"Could not issue medicine. Prescription is not a pending prescription"`, `"Invalid issue value"` for under-issue, `"You can only issue the prescribed qty"` for the all-or-nothing). |
| **F9** transfer-completion message | `ConsultationTransferService`: reproduces `"Can not send to the specified clinic. Patient has been transfered to <clinicName> clinic. Please send the patient to the specified clinic"` — resolves the clinic NAME via masterdata::lookup (no uid leak). |
| **F10** lab attachment messages | `LabTestService`: `"Can not add more than 5 attachments"` + `"Can only attach for collected tests"` verbatim. |
| **F11** stale Javadoc | `Consultation.java` + V33-V36 headers corrected (FK columns DROPPED, settlement seam IMPLEMENTED). |
| **SEC-01** Patient-DECEASED mutation unaudited | `PatientClosureListener`: both handlers now `auditRecorder.record("registration.Patient", uid, UPDATE, actor)`; the events carry `actorUsername` so the audit is attributed to the real approver (not SYSTEM). Verified end-to-end by `ClinicalAuditIT`. |

## Test fixes applied (all GREEN)
- **QA-01** `ConsultationLifecycleIT.seedConsultationWithStatus`: TRANSFERED now seeds `open()`+`markTransferred()` (was IN_PROCESS); the free_transfered test genuinely exercises TRANSFERED→SIGNED_OUT.
- **QA-03** `ConsultationTransferIT`: `rebook_toCorrectDestination_completesTransfer` + `rebook_toWrongClinic_422VerbatimMessage` (the source is freed to SIGNED_OUT first per the legacy precondition; exercises the transfer-completion seam end-to-end).
- **QA-04** `ConsultationTransferIT`: the one-pending-transfer test asserts exact 422 + verbatim detail (was `is4xxClientError`).
- **QA-07** the 4 `seedPrice` helpers assert `isCreated()` (was `is2xxSuccessful`, which masked 409s).
- **QA-08** `ConsultationLifecycleIT`: openFollowUp CASH-unsettled → 422 pay-before-service.
- **QA-09** new `ClinicalAuditIT`: open → `clinical.Consultation` UPDATE audit; deceased approval → `registration.Patient` UPDATE audit (the SEC-01 verification).
- Test-fixture corrections surfaced by the wiring: the cancel/free/deceased tests now seed a REAL `PatientBill` (the new `cancelCharge`/`approveInvoicesForBills` calls require a real bill); affected message assertions updated to the verbatim strings.

## Documented (not fixed — deferred/limitations)
- **F12 / CR-INC05-09** — the deceased (UNPAID|VERIFIED) vs referral (UNPAID-only) bill-gate asymmetry is structurally preserved as two separate gate methods, but the single local `settled` bit cannot represent "VERIFIED-but-unpaid". The distinction is unobservable until a billing-status read is added. Accepted as a CR-register limitation (HDE + engagement-lead).
- **SEC-02** — no PHI READ-access log on bulk/by-patient clinical reads. Folds into the deferred **CR-INC05-03 audit-classification CR** (the auth-only model makes read-logging the load-bearing compensating control).
- **SEC-03** — audit actor source consistency (jwt.getSubject vs SecurityContext name); LOW/latent; standardise in the audit-classification CR.
- **SEC-04** — the deferred attachment byte-download endpoint MUST gate on parent status==VERIFIED server-side (binding acceptance criterion; no inc-05 change — the metadata-list endpoint leaks only filenames).
- **F7** — the free reg-no identity check (module-cycle constraint, above).

## Test-coverage follow-up (additive; not blocking — code is correct + green)
The following QA coverage additions remain (the production code they cover is complete and green;
these strengthen the suite and should land in a follow-up test-coverage pass):
- **QA-02** — `SettlementSeamIT`: add the Radiology / Procedure / Prescription legs of `ConsultationSettlementListener` (the Consultation + LabTest legs are covered; the listener flips all five).
- **QA-05** — `ClosureIT`: `approveReferral` bill-gate test (the save-gate is covered; the approve-gate re-runs the same check).
- **QA-06** — `LabTestIT`/`RadiologyIT`/`ProcedureIT`: assert a VERIFIED order is ABSENT from the worklist (+ lab COLLECTED present).
