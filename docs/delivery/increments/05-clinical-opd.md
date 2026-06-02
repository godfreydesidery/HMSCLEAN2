# Increment 05 — Clinical / OPD

## Goal

Deliver the complete outpatient consultation lifecycle — from the reception queue through SOAP clinical notes, diagnoses, polymorphic ClinicalOrder (lab/radiology/procedure), prescribing alerts, two-phase consultation transfer, and outpatient closure (death/referral) — so that a doctor can drive a full OPD encounter end-to-end, with CASH fee settlement as a hard gate on service delivery.

## Scope

**Bounded contexts:** `encounter` (primary), `billing` (settlement gate), `masterdata` (clinic/clinician affiliation, diagnosis catalogue), `iam` (RBAC, ProviderProfile).

**Key aggregates and entities:**

- `Consultation` — states `BOOKED → IN_PROGRESS → COMPLETED | CANCELLED | TRANSFERRED`; `feeSettled` flag (written by billing dispatcher, never by encounter directly); `followUpOfConsultationUid` (optional back-link)
- `ClinicalNote` — SOAP free-text per consultation (chief complaint, history, examination, assessment, plan)
- `GeneralExamination` — structured vitals/findings snapshot per consultation
- `ConsultationDiagnosis` — `kind = WORKING | FINAL`; FK to `Diagnosis` masterdata; multiple per consultation
- `ClinicalOrder` — polymorphic (`kind = LAB_TEST | RADIOLOGY | PROCEDURE`); states `REQUESTED → ACCEPTED → COMPLETED | CANCELLED`; for PROCEDURE an intermediate `APPROVED` state is required (surgeon sign-off, M14); `settled` flag for CASH gate
- `OrderResult` — narrative + impression + finalized flag linked 1:1 to ClinicalOrder
- `ConsultationTransfer` — states `PENDING → COMPLETED | CANCELLED`; links source consultation to target clinic (clinician chosen at accept, not at raise)
- `Prescription` — `PENDING → ACCEPTED → HELD → VERIFIED → APPROVED → SOLD | REJECTED | CANCELLED`; `payStatus = UNPAID | PAID`; FK to `Medicine`, `Dosage`, `AdministrationRoute`, `DosingFrequency`
- `ClinicClinician` — M:N affiliation; booking asserts membership
- `PrescribingAlert` — advisory value object emitted at prescription creation (duplicate medicine within rolling 30-day window; unfinished-course alert if a prior prescription for the same medicine has non-zero remaining quantity)

**Key REST endpoints (all under `/api/v1`):**

- `GET  /encounters/consultations/reception-queue` — BOOKED + fee-settled consultations for the authenticated clinician
- `POST /encounters/consultations/uid/{uid}/start` — BOOKED → IN_PROGRESS (hard gate: `feeSettled=true` for CASH)
- `POST /encounters/consultations/uid/{uid}/complete` — IN_PROGRESS → COMPLETED
- `POST /encounters/consultations/uid/{uid}/cancel`
- `POST /encounters/consultations/uid/{uid}/clinical-notes` — upsert SOAP note
- `POST /encounters/consultations/uid/{uid}/general-examination` — upsert structured vitals
- `POST /encounters/consultations/uid/{uid}/diagnoses` — add working or final diagnosis
- `DELETE /encounters/consultations/uid/{uid}/diagnoses/uid/{diagnosisUid}`
- `POST /encounters/consultations/uid/{uid}/orders` — raise ClinicalOrder (LAB_TEST | RADIOLOGY | PROCEDURE)
- `POST /encounters/orders/uid/{uid}/accept` — REQUESTED → ACCEPTED (CASH gate: `settled=true`)
- `POST /encounters/orders/uid/{uid}/approve` — REQUESTED/ACCEPTED → APPROVED (PROCEDURE only; surgeon privilege)
- `POST /encounters/orders/uid/{uid}/complete`
- `POST /encounters/orders/uid/{uid}/cancel`
- `PUT  /encounters/orders/uid/{uid}/result` — upsert OrderResult (narrative + impression + finalize flag)
- `POST /encounters/consultations/uid/{uid}/prescriptions` — create Prescription; response includes advisory `alerts[]`
- `POST /encounters/consultations/uid/{uid}/transfer` — raise PENDING ConsultationTransfer (target clinic only; no un-acted orders or PENDING prescriptions allowed on source)
- `GET  /encounters/consultations/transfers?status=PENDING` — reception pending-transfer queue
- `POST /encounters/consultations/transfers/uid/{uid}/accept` — reception books new consultation at target clinic with chosen clinician; PENDING → COMPLETED
- `POST /encounters/consultations/transfers/uid/{uid}/cancel` — initiating doctor reverts source to IN_PROGRESS
- `POST /encounters/consultations/uid/{uid}/closure` — outpatient death or referral closure (kind = DECEASED | REFERRAL; see DISCH-3); sets `Patient.deceased = true` on death

**Process flows implemented (PROCESS.md §3.1, §3.2, §16.9):**

- §3.1 full four-state consultation lifecycle
- §3.2 all inner-consultation artefacts (notes, exam, diagnoses, orders, prescriptions, follow-up flag, transfer)
- §16.9 two-phase consultation transfer
- §3.3 outpatient death/referral as consultation closure paths (DISCH-3)

**Role + patient-class worklists (PROCESS.md §3, lessons M2/M4/M8):**

- Doctor reception queue: `kind=CONSULTATION, patientClass=OUTPATIENT, settledOnly=true`
- Lab/radiology/procedure order worklists: `kind`, `patientClass`, `settledOnly=true` for CASH

## Dependencies

- **Increment 02 (IAM)** — JWT auth, 177 privilege codes, `ProviderProfile`, `ClinicClinician` affiliation table seeded; `booking()` asserts clinician belongs to the target clinic (lessons R1–R4)
- **Increment 03 (Masterdata)** — `Clinic`, `Diagnosis`, `LabTestType`, `RadiologyType`, `ProcedureType`, `Medicine`, `Dosage`, `AdministrationRoute`, `DosingFrequency` all present; ServicePrice matrix for CONSULTATION, LAB_TEST, RADIOLOGY, PROCEDURE kinds
- **Increment 04 (Registration/Billing)** — `Patient`, `PatientType`, `PaymentType`, `feeSettled` flag pattern, `SettlementDispatcher`, `Invoice` and `InvoiceLineKind`; consultation-fee invoice seeded by `ConsultationBookedEvent` listener; CASH gate infrastructure already exercised at registration

## Exact-process fidelity targets

**Consultation fee gate (PROCESS.md §2, §11; lessons M3, DIAG-2):**
`ConsultationService.start()` must assert `consultation.feeSettled == true` for CASH patients before the BOOKED → IN_PROGRESS transition. This is a hard exception (`CONSULTATION_FEE_UNPAID`, `ErrorCode` in ProblemDetail `type` URI), not a worklist filter. Non-CASH patients are treated as `COVERED` and the flag is set to `true` by the `SettlementDispatcher` at booking time.

**Order settlement gate (M3, M13, DIAG-2):**
`ClinicalOrderService.accept()` must assert `order.settled == true` for CASH patients. The `settled` flag is flipped by the billing `SettlementDispatcher` on the `ClinicalOrderRaisedEvent` for non-CASH, and by a cashier payment event for CASH. An `accept()` on an unsettled CASH order throws `ORDER_FEE_UNPAID`.

**Procedure approval gate (M14):**
PROCEDURE orders require an explicit `approve()` transition (surgeon/anaesthetist privilege `PROCEDURE_ORDER_APPROVE`) before any operative record can be attached and before `complete()` is callable. The state machine must enforce `REQUESTED | ACCEPTED → APPROVED → COMPLETED`; skipping approval throws `PROCEDURE_NOT_APPROVED`.

**Lab/radiology accept step (M16):**
`REQUESTED → ACCEPTED` is a mandatory step; `complete()` is not callable on a `REQUESTED` order. This preserves the specimen-custody audit trail.

**Two-phase transfer fidelity (OPC-1, PROCESS.md §16.9):**
The source consultation must carry no REQUESTED ClinicalOrders and no PENDING Prescriptions before `transfer()` is callable, or the transfer is refused (`CONSULTATION_HAS_OPEN_WORK`). Source transitions to `TRANSFERRED` on raise; reverts to `IN_PROGRESS` on cancel (optimistic-lock, ADR-0017 pattern). Reception `accept()` is affiliation-gated: the chosen clinician must be in `ClinicClinician` for the target clinic.

**Prescribing alerts (prior-attempt `PrescribingAlertService`):**
Two advisory (non-blocking) checks at prescription creation: (a) same `medicineUid` in a SOLD or IN_PROGRESS prescription for this patient within the preceding 30 days → `DUPLICATE_MEDICINE` alert; (b) a prior prescription for the same medicine where `remainingQty > 0` and `status ∉ {CANCELLED, REJECTED}` → `UNFINISHED_COURSE` alert. Alerts are returned in the `POST /prescriptions` response as `alerts[]`; they do not prevent saving.

**Outpatient death/referral (DISCH-3, PROCESS.md §3.3):**
Consultation `closure()` with `kind = DECEASED` must set `Patient.deceased = true` via a cross-module event (encounter publishes `PatientDeceasedEvent`; patient module listens). Subsequent `book()` calls on a deceased patient throw `PATIENT_IS_DECEASED`.

**Follow-up billing (PROCESS.md §3.2):**
When `followUpOfConsultationUid` is provided on `start()`, the consultation-fee invoice is seeded at zero (`amount = 0`) by the billing listener. The follow-up link is validated: the referenced consultation must belong to the same patient and be in COMPLETED state.

**ADR-0009 numbering:**
Consultation number format: `CONS{yyyyMMdd}-{seq_cons_no}` (new sequence `seq_cons_no` START 1). ClinicalOrder number: `ORD{yyyyMMdd}-{seq_ord_no}`. These sequences are registered in the `DocumentType` enum alongside GRN, LPO, etc.

**Worklist scoping (M2, M4, M8):**
`GET /encounters/consultations/reception-queue` returns only BOOKED + `feeSettled=true` rows for the authenticated clinician username. Order worklists (`GET /encounters/orders?kind=LAB_TEST&patientClass=OUTPATIENT&settledOnly=true`) enforce the scoping from day one; `settledOnly` defaults to `true` for OUTPATIENT/OUTSIDER, `false` for INPATIENT (insured/ward billing).

## Prior-attempt pitfalls to avoid

| Code | What went wrong | What this increment must do instead |
|---|---|---|
| M3, M13, DIAG-2 | Settlement checks were worklist filters, not transition gates | `start()` and `accept()` throw hard exceptions for unsettled CASH; `settled` is never passed as a query-only param |
| M14 | No APPROVED state for procedures; anyone could complete | Procedure state machine enforces APPROVED pre-condition; `approve()` is a distinct endpoint gated by `PROCEDURE_ORDER_APPROVE` privilege |
| M16 | REQUESTED → COMPLETED skip allowed; no specimen-custody marker | `accept()` is a required intermediate step; `complete()` on a REQUESTED order throws `ORDER_NOT_ACCEPTED` |
| OPC-1 | Transfer booked the new consultation immediately with no cancel path | Two-phase hand-off modelled from the start; `ConsultationTransfer` aggregate; optimistic lock on accept/cancel race |
| DISCH-3 | Outpatient death/referral mounted only under Admission | `ConsultationClosure` models both closure subjects; `Patient.deceased` set via event |
| DISCH-4 | Deceased patients remained bookable | `book()` checks `Patient.deceased`; throws `PATIENT_IS_DECEASED` |
| R1–R4 | Clinician-clinic affiliation missing; all clinicians shown against all clinics | `ClinicClinician` M:N enforced on booking and on transfer accept |
| OPC-2 | Sign-out (complete) did not guard on unpaid downstream bills | `complete()` may warn (advisory) but must not silently auto-cancel unpaid orders; the downstream order/prescription lifecycle owns settlement |
| BILL-6 | Reception-queue showed unsettled consultations | Reception queue hard-filters `feeSettled=true` |

## Lead and supporting agents

- **Lead:** backend-engineer, frontend-engineer
- **Supporting:** engagement-lead, healthcare-domain-expert, solution-architect, ux-ui-designer, qa-test-engineer, code-reviewer
- **Consulted:** business-analyst (process edge cases), security-architect (RBAC privilege codes), data-architect (sequence and index design)

## Definition of Done

- [ ] `Consultation` state machine (BOOKED → IN_PROGRESS → COMPLETED | CANCELLED | TRANSFERRED) is implemented and covered by unit tests; `start()` hard-gates on `feeSettled` for CASH patients with `CONSULTATION_FEE_UNPAID` ProblemDetail
- [ ] `ClinicalNote` (SOAP), `GeneralExamination`, and `ConsultationDiagnosis` (WORKING/FINAL) CRUD endpoints operational; data survives a restart against a real PostgreSQL 16 (Testcontainers)
- [ ] `ClinicalOrder` polymorphic aggregate covers LAB_TEST, RADIOLOGY, PROCEDURE kinds; `accept()` enforces settlement gate; PROCEDURE `approve()` enforces privilege gate; M16 accept-before-complete enforced
- [ ] `OrderResult` upsert wired to each ClinicalOrder kind; narrative + impression + finalize flag persisted
- [ ] `Prescription` full state machine implemented; `PrescribingAlertService` returns `DUPLICATE_MEDICINE` and `UNFINISHED_COURSE` advisory alerts on create; alerts appear in API response and in the Angular prescribing form
- [ ] Two-phase `ConsultationTransfer` implemented: raise (source has no open work → TRANSFERRED), reception accept (affiliation-gated clinician → new BOOKED consultation), doctor cancel (→ IN_PROGRESS); optimistic-lock race resolved first-writer-wins (ADR-0017)
- [ ] Outpatient closure (`kind = DECEASED | REFERRAL`) implemented; `Patient.deceased = true` set via event on DECEASED; `book()` blocks on deceased patient
- [ ] `seq_cons_no` and `seq_ord_no` PostgreSQL sequences added in Flyway migration; `CONS{yyyyMMdd}-{seq}` and `ORD{yyyyMMdd}-{seq}` formats confirmed by a concurrent-creation integration test (two parallel creates produce unique, non-duplicate numbers, per ADR-0017)
- [ ] Reception queue (`/encounters/consultations/reception-queue`) returns only BOOKED + `feeSettled=true` for the authenticated clinician
- [ ] Order worklists scope by `kind`, `patientClass`, `settledOnly` (defaults correct per patient class)
- [ ] Golden-master / parity test: drive a complete OPD scenario (register → fee-pay → book → start → SOAP note → exam → working dx → lab order → accept order → result → final dx → prescribe → alerts verified → complete) through legacy and new system; compare consultation status, diagnosis codes, order counts, prescription alerts, and invoice amounts to 2 dp (ADR-0009 rounding parity)
- [ ] All 177 `@PreAuthorize` codes relevant to this increment enforced and covered by a Spring Security test slice (e.g. `CONSULTATION_START`, `ORDER_RAISE`, `PROCEDURE_ORDER_APPROVE`, `PRESCRIPTION_CREATE`)
- [ ] `ApplicationModules.verify()` passes; no illegal cross-module edges introduced (encounter must not import billing; `settled` flag written only by billing dispatcher)
- [ ] OpenAPI 3 contract updated; Angular `typescript-angular` generated client regenerated; no drift detected in CI drift-gate
- [ ] All new endpoints emit structured audit events (`TxAuditContext`) captured in the audit log (ADR-0007)
- [ ] PR reviewed and approved by code-reviewer; no HIGH-severity findings unresolved
