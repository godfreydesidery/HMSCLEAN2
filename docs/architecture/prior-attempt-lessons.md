# Lessons from the prior modernization attempt

Source files read:
- `D:\My_Works\HMS\HMSCLEAN\PROCESS.md`
- `D:\My_Works\HMS\HMSCLEAN\PROCESS_MISMATCHES.md`
- `D:\My_Works\HMS\HMSCLEAN\GAP_AUDIT_2026-06.md`
- `D:\My_Works\HMS\HMSCLEAN\FRONTEND_GAPS.md`
- `D:\My_Works\HMS\HMSCLEAN\STAFF_CLINIC_RELATIONSHIPS_PLAN.md`
- Flyway migrations V1–V76 under `D:\My_Works\HMS\HMSCLEAN\hmis-engine-api\src\main\resources\db\migration\`
- Module layout under `D:\My_Works\HMS\HMSCLEAN\hmis-engine-api\src\main\java\com\otapp\hmis\engine\`

---

## Process mismatches to AVOID (concrete behaviours the prior build got wrong vs legacy)

These are drawn from `PROCESS_MISMATCHES.md` (M1–M25) and `GAP_AUDIT_2026-06.md`. Every item below was confirmed as a real divergence that reached QA, not a speculative gap.

**Registration → Doctor handoff (M1–M3, BILL-6)**
The prior build initially had the consultation as a separate manual screen — the receptionist had to re-search the patient and choose clinic and clinician independently. The correct legacy behaviour is that "Send to Doctor" auto-creates the PENDING consultation and fires the consultation-fee invoice in one step. The fresh build must treat `SendToDoctorEvent` (or equivalent) as a transactional unit: patient → consultation record → fee invoice, all atomically or via a tightly-coupled after-commit event.

**Payment gates: pay before service, not after (M3, M13, M23)**
The prior build had settlement checks only as queue *filters* (`settled=false` hidden items), not as hard *gates* on state transitions. Concrete violations found in QA:
- Lab/radiology/procedure `accept()` and `complete()` did not verify that the order's invoice was settled for CASH patients.
- Pharmacy `markSold()` had the gate; `accept()` and `hold()` did not.
- `DischargePlan.approve()` was callable without the admission's invoice being cleared; this allowed CASH inpatients to leave with unpaid bills.
- Ward-day charge accrual was modelled as "on-demand at discharge time" — there was no `@Scheduled` job, so a stay could be discharged with 0 ward-days billed.

**Rule to encode in the fresh build:** for CASH patients the `settled` flag must be a hard gate on the transition that renders the service (not on creating the order). Non-CASH is treated as COVERED at order time. The `settled` flag on aggregates is driven by a billing-side dispatcher crossing the module boundary in the allowed direction (billing → encounter, never reversed). Ward-day accrual must have a daily scheduled job, not a deferred compute.

**Doctor's reception queue scoping (M2, M4, PHARM-1)**
The prior build initially collapsed all work into one generic list. The legacy has ~12 role + patient-class + payment-gated queues (each "my work, paid"). The fresh build must design its query APIs for `kind` (role lens) + `patientClass` (OUTPATIENT / INPATIENT / OUTSIDER) + `settledOnly` from day one. Pharmacy additionally needs a "select working pharmacy" session scope so all worklist queries and stock ops are implicitly scoped to the chosen dispensary.

**Consultation transfer — two-phase, not immediate (OPC-1 / M in `PROCESS_MISMATCHES.md`, V74)**
The prior build's first attempt booked the new consultation immediately at transfer time, with no pending-transfer queue and no cancel/revert path. The correct behaviour (V74 in the prior build, finally delivered): the doctor raises a PENDING `ConsultationTransfer` (target clinic only, no clinician chosen yet); the source consultation flips to TRANSFERRED; reception picks it from a pending-transfers queue, chooses the receiving clinician (affiliation-gated), and books the new consultation. The initiating doctor may cancel, reverting the source to IN_PROGRESS. The fresh build should model this two-phase hand-off from the start.

**Procedure approval gate (M14)**
The prior build had no `APPROVED` state for procedure orders; any user could complete a procedure without surgeon sign-off. The correct lifecycle is REQUESTED → APPROVED (surgeon/anaesthetist) → IN_PROGRESS → COMPLETED. The `approve()` transition is a distinct endpoint and is a required pre-condition for any procedure work.

**Lab/radiology accept step (M16)**
The prior build allowed REQUESTED → COMPLETED directly, losing the specimen-custody marker. The correct lifecycle requires REQUESTED → ACCEPTED (specimen collected / study scheduled) before COMPLETED.

**Discharge must require an approved discharge plan (M17)**
`AdmissionService.discharge()` initially had no check on `DischargePlan.status`. The correct gate: `discharge()`, `markDeceased()`, and `transferOut()` all require an APPROVED matching-kind plan; `DischargePlan.approve()` is the method that routes the actual closure. A second approver (not the plan author) is the legacy requirement.

**Outpatient death and referral paths are real (DISCH-3)**
The prior build mounted closure only under `Admission`; there was no `ConsultationStatus.DECEASED` or `REFERRED`. Outpatient deaths and referrals are real production events — the legacy records these from a consultation branch. The fresh build must model `ClosureSubject` as either an admission or a consultation from the beginning (see V73 pattern).

**Deceased patient must be flagged (DISCH-4)**
When a patient dies (via any closure path), `Patient.deceased = true` must be set and `book()`/`admit()` guards must check it. The prior build left deceased patients re-bookable.

**Clinician-clinic affiliation was missing at initial launch (R1–R4)**
`Consultation.clinicianUsername` was a free string with no clinic membership check. Booking offered all CLINICIAN-role users against all clinics, so a consultant could be booked at a ward they don't work in. The correct model: M:N `ClinicClinician` table; `book()` asserts the clinician holds the role and is affiliated with the chosen clinic.

**Nursing MAR was absent (M15)**
The prior build tracked only pharmacy `dispensedAt`; the bedside medication administration record (dose, time, route, nurse, patient response) was missing. A `MedicationAdministration` (MAR) aggregate per admission is required for inpatient nursing fidelity.

**Fluid-balance and care-activity charts were missing until late (ADMIT-1, ADMIT-2)**
These were only added in V72 after the gap audit. Both are core ICU/HDU records and must be in the initial nursing chart design: `fluid_balance_entry` (intake / urine / drainage in mL) and `care_activity_entry` (feeding / repositioning / bed bath / blood-sugar readings).

**Payroll segregation of duties collapsed (M19, M24)**
`PayrollPeriod` was initially DRAFT → APPROVED → PAID without the VERIFIED checkpoint. Legacy requires DRAFT → VERIFIED (manager locks items) → APPROVED → PAID. Additionally, the initial implementation had only a gross/deductions/net snapshot with no per-component breakdown; the correct model persists a `PayrollItemLine` per component.

**Prescribing duplicate-medicine and unfinished-course alerts are required**
`PrescribingAlertService` (`PROCESS_MISMATCHES.md` audit, `PrescribingAlertService.java`) — advisory checks on "same medicine this month" and "unfinished course" — these are non-blocking but required by the legacy and must be wired at prescription creation time in the fresh build.

---

## Gaps the prior build left open

The following were confirmed open as of the 2026-06-01 audit (`GAP_AUDIT_2026-06.md`) and were in remediation or still open:

**HIGH severity — not yet closed at audit time:**
- **DIAG-2 / PHARM-2**: Unpaid CASH orders and prescriptions were visible and advanceable in the worklist; `settledOnly` defaulted to `false`. Hard fix required: OUTPATIENT/OUTSIDER cash orders must default to `settledOnly=true`; INPATIENT orders remain visible when VERIFIED (insured).
- **DISCH-1**: No closure worklist — a second approver had no screen to see PENDING discharge/referral/death plans. The `DischargePlanRepository` had no `findByStatus` query.
- **DISCH-2**: No printable closure document (Discharge Summary / Referral Letter / Death record). No PDF/print plumbing existed anywhere.
- **BILL-1**: No POS receipt after cashier payment — only an on-screen banner. No print/PDF plumbing.
- **BILL-2**: No collections / cash-up report (per-cashier, date-range). Raw data existed but no endpoint or screen.
- **ADMIT-1 / ADMIT-2**: Fluid-balance chart and care-activity chart missing (remediated in V72 / PR #36).
- **DIAG-1**: No distinct COLLECTED specimen state — specimen-receipt audit trail absent.

**MEDIUM severity (open or partial):**
- **REG-1**: Patient search had no membership/insurance-card lookup (legacy `load_patients_like_and_card`).
- **REG-2 / REG-3**: Changing patient type or payment type did not sweep/block open outsider orders and draft invoices — orphaned work-in-progress.
- **OPC-2**: Consultation sign-out did not guard on unpaid downstream bills; always auto-cancelled them.
- **OPC-3**: No doctor-request → nurse-fill vitals lifecycle; vitals recorded inline with no `EMPTY → PENDING → SUBMITTED → ARCHIVED` state machine.
- **ADMIT-2**: No daily care-activity record (feeding/positioning/bed-bath/blood-sugar).
- **DISCH-5**: Referral target was free-text; legacy used an `ExternalMedicalProvider` masterdata FK (remediated in V73 / `closure-foundation` branch).
- **BILL-3**: No patient-first inpatient till — ADMISSION scope excluded from cashier.
- **BILL-4**: No direct-pending cash queue or printable patient invoice.
- **BILL-5**: Revenue broke down by service-kind only, not by payment mode; no pharmacy-sales report.
- **PHARM-1**: No select-working-pharmacy session scoping.

**LOW severity (documented divergences):**
OPC-4 (no "my open consultations" doctor view), OPC-5 (no follow-up queue), ADMIT-3 (ward price per-ward vs per-type), ADMIT-5 (no doctor ward-round view), DISCH-6 (bed freed at approval not authoring), DISCH-7 (self-approval hard-blocked — config-gate needed for solo-clinician sites), BILL-6 (combined reg+consultation pay split into two tills), BILL-7 (per-line Collection ledger vs one Payment/invoice), PHARM-3 (no bulk dispense), PHARM-5 (no reusable retail customer master).

**Not yet designed (open for ADR decision in the fresh build):**
- Print / PDF plumbing (receipts, invoices, closure documents) — requires a defined approach (server-side PDF, browser print CSS, or both).
- Scheduled appointment booking (theatre scheduling exists for procedures; no general appointment calendar).
- Push/real-time notifications — the prior build used only Spring events for intra-process coordination; no WebSocket or external notification channel was designed.

---

## Multi-facility / clinic-store scoping reality

**Single facility, multiple named units.** The prior build and its legacy are designed for a single hospital. There is no tenant-isolation, no facility hierarchy, and no cross-facility patient transfer protocol. "Multi-facility" in this system means: multiple named clinics, multiple pharmacies, multiple wards, one central store, and multiple theatres — all under one hospital roof.

**Clinic scoping:**
- Clinics (`md_clinic`) are named organisational units (General OPD, ANC, Dental, etc.) with a `ClinicType` enum.
- Consultations are scoped to exactly one clinic + one clinician per consultation.
- Clinicians are affiliated to one or more clinics via `md_clinic_clinician` M:N (V56). Booking enforces this: `book()` asserts the clinician belongs to the target clinic.
- Nurses, pharmacists, and cashiers are facility-wide (no per-clinic assignment). Lab techs, radiographers, and theatre staff are role-scoped, not facility-scoped.
- Reception queue (`GET /encounters/consultations/reception-queue`) shows BOOKED consultations for the authenticated clinician, gated on fee-settled.

**Pharmacy scoping:**
- Multiple pharmacies (`md_pharmacy`) coexist; each has its own `StockBalance` + `StockBatch` + `StockMovement` ledger.
- Dispensing is scoped to a specific pharmacy: the prescription records both `issuePharmacyUid` (where it was filled) and `salesPharmacyUid` (where stock was pulled — may differ without a formal transfer document).
- There is no "current session pharmacy" server-side session; the front-end is expected to hold a "selected pharmacy" context that is passed on every dispensing call (gap PHARM-1 confirmed: this was not implemented in the prior build's UI).

**Store scoping:**
- A single central store is the norm; the schema allows multiple stores.
- Store staff (`md_store_staff` M:N, V57): a store keeper must be affiliated with a store to authorise issue/transfer operations from it.
- Stock flows in one direction: supplier → store (via GRN) → pharmacy (via RO/TO/RN) → patient. Direct store-to-ward consumable issue is also supported.

**Insurance scoping:**
- `ServicePrice(planUid, kind, serviceUid, currency)` is the single pricing matrix — one row per (plan, service, currency). Cash rows have `planUid = null`. The prior build deliberately collapsed the legacy's six separate `*InsurancePlan` tables into this single table. This is an approved design simplification.
- Coverage routing is per-invoice-line (`coverage_status`: COVERED / VERIFIED / UNPAID). COVERED lines are collected into `InsuranceClaim` aggregates per payer (V70), which add a submit → settle/reject lifecycle the legacy never had.

**Multi-currency:**
- `md_currency` (V59) added a managed currency list with a single system default. The service-price matrix was widened to include currency as part of the unique key, allowing a clinic to price consultations in TZS and USD simultaneously. This is absent from the legacy.

---

## Attachments / files, scheduling, notifications

**Attachments / files:**
- Implemented in V41 (`order_attachment` table): metadata row per file (`filename`, `content_type`, `size_bytes`, `storage_key`, `uploaded_by_username`, `uploaded_at`) keyed against a `ClinicalOrder` by `order_uid` + `order_kind`.
- Physical bytes live on disk under a configurable `hmis.attachments.dir` root property. Each file's `storage_key` is a relative path (`{order_uid}/{attachment_uid}/{sanitised_filename}`).
- 25 MiB per-file cap enforced in the application layer. Filename sanitised server-side.
- Endpoints: `POST /encounters/orders/uid/{uid}/attachments` (multipart), `GET /encounters/attachments/uid/{uid}/...`, `DELETE`.
- Attachments apply to any `ClinicalOrder` kind (LAB_TEST / RADIOLOGY / PROCEDURE). There is no separate attachment model for admission documents, nursing notes, or procurement documents in the prior build.
- **Gap for fresh build:** consider a generic `Attachment` aggregate keyed by `(ownerKind, ownerUid)` rather than an order-specific table, so the same plumbing covers radiology images, discharge summaries, supplier invoices, and HR documents without bespoke tables each time. Filesystem vs. object-storage backend should be an ADR decision — the prior build's filesystem approach has no fault-tolerance or horizontal-scaling story.

**Theatre scheduling:**
- V29 added `Theatre` masterdata + scheduling fields on `ClinicalOrder` (`theatreUid`, `scheduledAt`, `scheduledByUsername`) + `POST /encounters/orders/uid/{uid}/schedule`.
- Valid only for PROCEDURE-kind orders. There is no general appointment calendar, no double-booking guard, and no theatre capacity model.
- **Gap:** no scheduling for outpatient appointments (triage queue / appointment slots), no radiology study scheduling slot (only ACCEPTED status), no reminder mechanism.

**Notifications:**
- The prior build has zero out-of-process notification infrastructure. All coordination uses Spring `ApplicationEvent` internally (e.g. `PatientRegisteredEvent`, `ConsultationBookedEvent`, `ClinicalOrderRaisedEvent`) for within-JVM decoupling across module boundaries.
- There is no WebSocket, no Server-Sent Events, no email/SMS channel, no push-notification service, and no notification table in any migration.
- **Gap for fresh build:** real-time queue updates (nurse queue, reception queue, dispense worklist) require a notification mechanism — the legacy relies on manual refresh and role-queue polling. At minimum, SSE or WebSocket for "queue updated" events should be an ADR decision.

---

## Reusable good ideas (worth carrying into the fresh build)

**`settled` flag pattern for cross-module gates**
The billing module must not depend on the encounter module (Spring Modulith boundary). The prior build solved this via a denormalised boolean flag (`consultation.fee_settled`, `clinical_order.settled`, `admission.bills_cleared`) that billing-side dispatcher writes in the allowed direction (billing → encounter). The encounter module's gates read only the local flag. This pattern is clean, testable, and eliminates circular module dependencies. Carry it verbatim.

**`ServicePrice` unified pricing matrix**
A single `md_service_price(plan_uid, kind, service_uid, currency, amount, covered, min_amount, max_amount)` table replaces the legacy's six parallel `*InsurancePlan` tables. A `PriceLookup.resolve()` service encapsulates the cash-fallback logic. This is one of the clearest data-model improvements in the prior build and should be adopted as-is.

**Polymorphic `ClinicalOrder` for lab/radiology/procedure**
All three order kinds share one aggregate (`clinical_order` table with a `kind` discriminator). Order lifecycle gates (`accept`, `approve`, `complete`, `cancel`, `reject`, `hold`) are on the shared aggregate; kind-specific behaviour (operative record for procedures, result lines for lab, attachments for all) is in sibling aggregates linked by `order_uid`. This avoids three near-identical tables and three near-identical state machines.

**FEFO batch dispensing with pessimistic lock**
`StockBatchRepository.lockFefoForDispense` walks batches in expiry-ascending order (null expiry last) under a pessimistic write lock. This prevents double-decrement races without an optimistic-lock retry loop. Carry the pattern.

**`PatientClassScope` (OUTPATIENT / INPATIENT / OUTSIDER) as a query dimension**
Rather than per-role per-patient-class endpoint proliferation, the prior build added `patientClass` as a query parameter to worklist endpoints and a `ClosureSubject` enum to aggregate keys. Derive patient class at runtime: OUTSIDER from `patient.patient_type`, INPATIENT from an ADMITTED admission, OUTPATIENT otherwise.

**Two-phase consultation transfer**
The V74 two-phase model (pending transfer → reception picks up → books new consultation) correctly separates the "request" (doctor) from the "accept/assign" (reception) concerns and gives the doctor a cancel path. Design this from the start rather than retrofitting.

**Event-driven denormalisation for module decoupling**
`@TransactionalEventListener(phase = AFTER_COMMIT)` is used to seed dependent aggregates (e.g. registration-fee invoice seeded after patient registered, consultation-fee invoice seeded after consultation booked). This keeps the producing module ignorant of the consumer. The after-commit phase prevents the seed from running if the outer transaction rolls back.

**`ProviderProfile` for clinician identity**
V58 added `iam_provider_profile` (specialty, registration number, licence number, licence expiry) as an optional extension to a User. This is cleaner than the legacy's parallel `Clinician` entity. It lives in the `iam` module so it can be referenced without crossing boundaries.

**Configurable payroll components (data-driven, no statutory hard-coding)**
`PayrollComponent` (EARNING / DEDUCTION, FIXED / PERCENT / BAND) + `PayrollComponentBand` with a stateless compute endpoint. No rates in code; the hospital defines its own. A progressive BAND method handles PAYE-style bracketed deductions. Carry this design.

**Structured lab result lines with sex/age-banded reference ranges**
V60 added `md_lab_test_analyte` + `md_lab_reference_range` (sex/age-banded numeric bounds + critical thresholds) + `lab_result_line` with flag computed server-side. The flag enum (NONE / NORMAL / LOW / HIGH / CRITICAL_LOW / CRITICAL_HIGH / ABNORMAL) is deterministic. This is a significant improvement over the legacy string-based ranges.

**Insurance claim ledger as a read/aggregate layer**
V70 built `insurance_claims` as an aggregate over existing COVERED `invoice_line` rows. Building a claim never modifies patient-balance math; it only links lines to a claim record and adds a submit/settle/reject lifecycle. This layered approach avoids re-architecting billing when adding the claims module.

---

## Implications for ADRs 0003/0005/0009/0011/0014 and the new gap ADRs

**ADR-0003 (Identifiers — UUIDv7 as native PostgreSQL `uuid`)**
The prior build used ULID stored as `VARCHAR(26)` throughout all 76 migrations. The fresh build uses native `uuid` (16 bytes, index-efficient, natively comparable). This is a clean break — no compatibility shims needed since there is no data migration. Every cross-module reference in the prior build used `VARCHAR(26)` uid columns as loose coupling (no cross-module FK); the fresh build should use `uuid` columns in the same loose-coupling pattern. Constraint: the prior build's `id` is `BIGSERIAL` — the fresh build hides it (never serialised) and exposes only the `uid`. Preserve this pattern.

**ADR-0005 (REST URL convention — `/{resources}/uid/{resourceUid}`)**
The prior build followed this convention consistently and it worked. The confirmed process gaps (DISCH-1, BILL-1, BILL-2) are missing endpoints, not wrong URL shapes. Carry the convention. One refinement needed: the prior build had `GET /iam/staff/by-role/{roleName}` as an unscoped lookup that was later augmented by `GET /masterdata/clinics/uid/{clinicUid}/clinicians`. The scoped endpoint should be the primary surface; the unscoped endpoint should be admin-only.

**ADR-0009 (Error body — RFC 7807 ProblemDetail)**
The prior build uses a custom `ApiError` body (not RFC 7807). The fresh build's ADR mandates `ProblemDetail`. Key lesson from the prior build: error codes matter for the frontend. The registration-fee gate (FRONTEND_GAPS.md B5) was handled because the frontend could pattern-match on the error message (`/registration fee/i`). A structured `ErrorCode` enum in the `type` URI field of `ProblemDetail` is necessary so the frontend can react programmatically without string matching.

**ADR-0011 (Spring Modulith boundaries)**
The prior build demonstrates that the legal dependency direction (`billing → encounter`, never `encounter → billing`) holds across 76 migrations. The `settled`-flag pattern and the `SettlementDispatcher` are the concrete implementation of this boundary. The fresh build must codify the same allowed-dependency graph in a `ApplicationModules.verify()` test from day one. Confirmed illegal edges that the prior build avoided: `encounter → billing`, `iam → masterdata`, `pharmacy → encounter`. The prior build also shows that `masterdata → iam` is required (to validate clinician role during clinic-staff assignment) and should be in the allowed-dependency list.

**ADR-0014 (MapStruct for mapping)**
The prior build used hand-coded mappers throughout. The fresh build mandates MapStruct. Lesson: the prior build's mappers include denormalisation logic (e.g. resolving `username` from a `userUid` during clinician affiliation). MapStruct cannot query a repository. Pattern to follow: the mapper handles struct-to-struct projection; the service handles lookups and denormalisation before passing to the mapper. Do not put business logic in mapper `@AfterMapping` methods.

**New gap ADRs recommended:**

**ADR-NEW-A: Attachment storage strategy**
The prior build uses a local filesystem (`hmis.attachments.dir`). Decision needed for the fresh build: filesystem (simple, no HA) vs. S3-compatible object storage (scalable, HA, works in container environments). If filesystem, define the directory layout and backup strategy. If object-storage, define the SDK dependency and credential injection. Affects V41 equivalent migration design. Recommendation: abstract behind a `StoragePort` interface from day one; default to filesystem for development.

**ADR-NEW-B: Real-time / push notification strategy**
The prior build has no out-of-process notification. The work-queue model (nurse queue, reception queue, dispense worklist) is polling-based in the UI. Decision needed: Server-Sent Events vs. WebSocket for intra-hospital real-time queue updates. The prior build's `ApplicationEvent` intra-JVM model is a good start; it should be extended to a WebSocket broadcast on queue-affecting events. Flag for the fresh build: the Angular 18 frontend should use SSE or WebSocket subscription per queue, not timed polling.

**ADR-NEW-C: Print / PDF generation**
Both `BILL-1` (POS receipt) and `DISCH-2` (closure documents) are HIGH-severity open gaps. Decision needed: server-side PDF generation (e.g. iText / OpenPDF / JasperReports) vs. browser-print CSS (`@media print`). A hybrid is common: browser-print for receipts and invoices (fast, no server dependency), server-side PDF for archivable closure documents. The fresh build should define a `DocumentRenderer` service abstraction and choose the implementation early because it affects layout engineering in the Angular frontend.

**ADR-NEW-D: Reference / master-data seeding**
The prior build seeds: IAM roles + privileges (V2), clinic/ward/pharmacy/store masterdata (V3–V4), medicine and lab test types (V4), dosage / route / frequency picklists (V32), sample currencies (V59), sample analytes + reference ranges (V60), sample theatres (V29), sample wards and beds (V33). The fresh build starts empty but the same master-data is required before the system is operationally usable. Decision needed: Flyway seed scripts vs. an admin "first-run wizard" vs. exported seed data from the prior build's QA environment. The 177 `@PreAuthorize` privilege codes must be seeded as reference data on every fresh deployment — this is non-negotiable and must be in a migration, not a dev-only seeder.

**ADR-NEW-E: Scheduling / appointment calendar**
Theatre scheduling exists (V29) but is procedure-scoped. There is no general appointment calendar for outpatient slots, radiology study time slots, or follow-up bookings. Decision needed before the fresh build designs the consultation flow: is an appointment calendar in scope for V1? If yes, it affects the consultation aggregate (add `appointmentUid` FK), the masterdata (add `Slot` / `Schedule` aggregates per clinic), and the reception queue semantics. If deferred, the fresh build must not make the consultation aggregate slot-agnostic in a way that makes retrofitting impossible.
