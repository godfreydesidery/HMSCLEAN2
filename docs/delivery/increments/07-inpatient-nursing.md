# Increment 07 — Inpatient & Nursing

## Goal

Deliver the complete inpatient lifecycle — from admission through ward-based nursing care to discharge/deceased/referral — including all nursing charts, medication administration, consumable stock integration, a daily ward-charge accrual job, and a two-approver discharge gate, so that clinical and nursing staff can manage an inpatient stay end-to-end against the live stack with full financial accountability for CASH patients.

## Scope

**Bounded contexts:** `inpatient` (primary), `billing` (ward-day accrual, bills-cleared gate), `inventory`/`pharmacy` (consumable stock decrement), `iam` (RBAC), `shared` (TxAuditContext, Money).

**Key aggregates and entities:**

- `Admission` — states: `ADMITTED → DISCHARGED / DECEASED / REFERRED / TRANSFERRED` (ward-to-ward does not change terminal state). Fields: `wardUid`, `bedUid`, `admittedAt`, `dischargedAt`, `billsCleared` (denormalised flag owned by billing dispatcher per ADR-0008 rule 4), `businessDayId`.
- `WardBed` — `FREE / OCCUPIED / RESERVED / OUT_OF_SERVICE`; claimed on admit, updated on ward-to-ward transfer, released on any terminal transition.
- `WardTransfer` — immutable record of every ward-to-ward move within the same admission (prior ward, new ward, prior bed, new bed, reason, transferredAt).
- `DischargePlan` — `kind ∈ {DISCHARGE, DECEASED, REFERRAL}`, states `PENDING → APPROVED`; `approvedBy` must differ from `createdBy` (second-approver gate, M17). DISCHARGE kind requires history, investigation, management, recommendations. DECEASED kind requires `timeOfDeath`, `causeOfDeath`. REFERRAL kind requires `referralFacilityUid` (FK to `ExternalMedicalProvider` masterdata, not free-text — gap DISCH-5).
- `AdmissionVitalsEntry` — immutable, append-only series: BP systolic/diastolic, temperature, pulse, respiration, SpO2; recorded per shift/round.
- `NursingCarePlanItem` — per-problem goal + intervention + evaluation; states `ACTIVE → RESOLVED / CANCELLED`.
- `NursingProgressNote` — per-shift narrative; `kind ∈ {DOCTOR, NURSING, OBSERVATION, HANDOVER}`.
- `DressingChartEntry` — immutable; `WoundStatus` enum (CLEAN, HEALING, GRANULATING, SLOUGHY, INFECTED, NECROTIC, DEHISCED) + dressing applied.
- `MedicationAdministration` (MAR) — per-administered dose: `prescriptionLineUid`, `administeredAt`, `doseGiven`, `routeUid`, `administeredByUsername`, `patientResponse`; links back to `Prescription` in `pharmacy` context via uid only (M15 fix).
- `FluidBalanceEntry` — intake / urine / drainage in mL per entry; shift totals derived at query time (ADMIT-1 fix).
- `CareActivityEntry` — type enum (FEEDING, REPOSITIONING, BED_BATH, BLOOD_SUGAR, OTHER) + value/notes + recordedAt (ADMIT-2 fix).
- `ConsumableIssue` — per-issue line: `consumableUid`, `qty` (NUMERIC(19,6)), `unitCost` (snapshot Money), `sourceKind ∈ {PHARMACY, STORE}`, `sourceLocationUid`; decrements `ConsumableStockBalance` pessimistically (ADR-0017 lock pattern) in the same transaction as the chart entry; charge accrued synchronously via `billing.api.recordClinicalCharge` (ADR-0008 rule 4).
- `WardDayAccrualJob` (JOB-001) — Spring `@Scheduled` + ShedLock cron `${hmis.jobs.ward-accrual.cron:0 5 0 * * *}`; idempotent via unique constraint on `(admission_uid, business_date)` with `ON CONFLICT DO NOTHING`; priced via `ServicePrice(kind=WARD, serviceUid=wardTypeUid, planUid=insurancePlanUid|null)` per ADR-0009 money/pricing rules.

**Key REST endpoints (`/api/v1/...`):**

- `POST /inpatient/admissions` — admit patient (requires consultation uid, ward uid, bed uid)
- `GET /inpatient/admissions/uid/{uid}` — admission detail
- `GET /inpatient/admissions?ward={uid}&status=ADMITTED` — ward worklist
- `POST /inpatient/admissions/uid/{uid}/transfer-ward` — ward-to-ward transfer without closing admission
- `POST /inpatient/admissions/uid/{uid}/discharge-plan` — create discharge/deceased/referral plan (PENDING)
- `POST /inpatient/admissions/uid/{uid}/discharge-plan/approve` — second-approver approves; triggers admission terminal transition
- `GET /inpatient/admissions/discharge-plans?status=PENDING` — approver worklist (DISCH-1 fix)
- `POST /inpatient/admissions/uid/{uid}/vitals` — append vitals entry
- `GET /inpatient/admissions/uid/{uid}/vitals` — full vitals series
- `POST /inpatient/admissions/uid/{uid}/care-plan` — create/update nursing care plan item
- `POST /inpatient/admissions/uid/{uid}/progress-notes` — per-shift note
- `POST /inpatient/admissions/uid/{uid}/dressing-chart` — dressing entry
- `POST /inpatient/admissions/uid/{uid}/mar` — record medication administration (MAR)
- `GET /inpatient/admissions/uid/{uid}/mar` — MAR list for admission
- `POST /inpatient/admissions/uid/{uid}/fluid-balance` — fluid balance entry
- `GET /inpatient/admissions/uid/{uid}/fluid-balance` — fluid balance series
- `POST /inpatient/admissions/uid/{uid}/care-activities` — care activity entry
- `POST /inpatient/admissions/uid/{uid}/consumables` — consumable issue (decrements stock + accrues charge)
- `GET /inpatient/admissions/uid/{uid}/consumables` — consumable chart
- `POST /ops/jobs/ward-day-accrual/trigger` — manual trigger for operational recovery (ADMIN-ACCESS)

**Process flows (PROCESS.md §3.3, §4, §11.5, §15, §16.5 and §16.8):**

Admission state machine: `ADMITTED → DISCHARGED / DECEASED / REFERRED / TRANSFERRED`. Ward-to-ward transfer updates bed assignment without changing admission state. Discharge requires an APPROVED DischargePlan of matching kind; the `approve()` call on the plan (not a direct discharge call) is what closes the admission. CASH patient admission is blocked from discharge if `billsCleared = false` (the billing dispatcher sets this flag when the admission invoice balance reaches zero). Deceased note captures `timeOfDeath` + `causeOfDeath` and sets `Patient.deceased = true`, after which `book()` and `admit()` guards reject the patient (gap DISCH-4 fix).

## Dependencies

- **Increment 01 (Identity & Access)** — privilege codes (`ADMISSION-CREATE`, `ADMISSION-DISCHARGE`, `NURSING-CHART-WRITE`, `MAR-WRITE`, `DISCHARGE-PLAN-APPROVE`, etc.) seeded before this increment's `@PreAuthorize` gates can be tested end-to-end.
- **Increment 02 (Master Data & Reference Seeding)** — `Ward`, `WardType`, `WardBed`, and `ServicePrice(kind=WARD)` rows seeded; bed-assignment logic is a hard dependency for admit and ward-transfer.
- **Increment 04 (Billing, Cashiering & Insurance)** — `billing.api.recordClinicalCharge` and the `SettlementDispatcher` are called synchronously from consumable issue and the daily ward-accrual job.
- **Increment 05 (Clinical / OPD)** — admission is triggered from a consultation; the encounter context must exist.
- **Increment 08 (Pharmacy, Inventory & Procurement)** — `ConsumableStockBalance` and `ConsumableStockService.decrementForIssue` must exist before the consumable chart can issue stock atomically (overdraft refusal). **08 is therefore built before 07** (see build-plan.md → build sequence).

## Exact-process fidelity targets

1. **Admission state machine** (PROCESS.md §3.3, §15): golden-master scenario drives a patient through ADMITTED, records 3 days of vitals and ward charges, then discharges — verifying the terminal state is `DISCHARGED`, the bed is freed, and `Patient.deceased` remains false.

2. **Ward-day accrual math** (PROCESS.md §11.5, ADR-0018 JOB-001): a 3-night stay must produce exactly 3 `InvoiceLine` rows of kind `WARD`, each with `amount = WardType.price` (cash) or `ServicePrice(kind=WARD, planUid).amount` (insured); total equals `3 × wardRate`. The legacy charges one flat amount at admission; the fresh-build accrual model must produce the same total by discharge. Golden-master assertion: `sum(wardDayLines.amount)` rounded to 2dp equals `legacyWardBill` rounded to 2dp (ADR-0009 rounding-parity rule).

3. **Second-approver discharge gate** (M17, PROCESS.md §3.3): `DischargePlan.approve()` must reject if `approvedBy == createdBy` (same user). Golden-master: a scenario where the plan author attempts self-approval must receive `422 Unprocessable Entity` with `ErrorCode = SELF_APPROVAL_FORBIDDEN`; a different user's approval succeeds and transitions admission to DISCHARGED.

4. **CASH discharge blocked by unpaid invoice** (M3/M23, PROCESS.md §11.5): `DischargePlan.approve()` must reject if `admission.billsCleared = false` for a CASH patient. Golden-master: attempt approval with open invoice → `409` (`ErrorCode = ADMISSION_BILLS_OUTSTANDING`); after payment settles the invoice, the billing dispatcher flips `bills_cleared`, and approval succeeds.

5. **Consumable stock decrement** (PROCESS.md §4, ADR-0017): concurrent issue of the last unit of a consumable must result in one success and one `409` (`STOCK_INSUFFICIENT`) — Testcontainers concurrency test with two parallel requests.

6. **MAR completeness** (M15): each `MedicationAdministration` row must reference a `Prescription` line uid in the pharmacy context (uid-only cross-module reference per ADR-0008). Golden-master: administer a drug from the admission's active prescriptions and verify the MAR row is retrievable with the correct prescription line uid and administered-by username.

7. **Fluid-balance and care-activity charts** (ADMIT-1, ADMIT-2): golden-master verifies that intake, urine, and drainage entries are persisted and retrievable; care-activity entries of type BLOOD_SUGAR with a numeric value round-trip correctly.

8. **Referral facility as masterdata FK** (DISCH-5): a `REFERRAL` DischargePlan must accept only a `referralFacilityUid` pointing to a seeded `ExternalMedicalProvider`; free-text referral facility is rejected with `400`.

9. **Document date in EAT timezone** (ADR-0009 §7): admission timestamps and ward-day charge `businessDate` must reflect `Africa/Dar_es_Salaam` local calendar date, not UTC.

10. **Deceased patient guard** (DISCH-4): after a `DECEASED` DischargePlan is approved and `Patient.deceased = true` is set, attempting to book a new consultation or admit the same patient must return `422` (`ErrorCode = PATIENT_DECEASED`).

## Prior-attempt pitfalls to avoid

- **M17 (missing second-approver gate):** the prior build's `DischargePlan.approve()` had no check that `approvedBy != createdBy`. Enforce this as a service-layer precondition, not just a UI hint.
- **M23 / accrual defect (ADR-0018):** the prior attempt had no ShedLock on `AdmissionAccrualJob`, allowing double-charge under multi-instance deployment. This increment must add ShedLock from the first commit of the job (ShedLock dependency + `shedlock` Flyway migration must precede the job registration).
- **M15 (MAR absent):** the prior build tracked only pharmacy `dispensedAt`. A `MedicationAdministration` aggregate is mandatory; it must not be deferred to a follow-up increment.
- **ADMIT-1 / ADMIT-2 (fluid-balance and care-activity charts added late in V72):** both charts must be designed and delivered in this increment, not retrofitted.
- **DISCH-1 (no approver worklist):** `GET /inpatient/admissions/discharge-plans?status=PENDING` must be implemented as a pageable, role-scoped endpoint so the second approver has a screen from day one.
- **DISCH-4 (deceased patient re-bookable):** `Patient.deceased` flag must be set atomically on DECEASED plan approval; the `admit()` and `book()` guards in the `inpatient` and `clinical` modules must read this flag from `PatientRef` in the `shared` kernel.
- **DISCH-5 (referral facility free-text):** `ExternalMedicalProvider` masterdata must be seeded and the FK enforced in the `DischargePlan` aggregate before the referral flow is marked done.
- **M3 / pay-before-service hard gate:** the discharge approval gate on `billsCleared` must be a hard precondition on `DischargePlan.approve()`, not a UI filter. The `billing → inpatient` dispatcher (writing `bills_cleared`) is the only allowed cross-module write (ADR-0008 rule 4 / ADR-0011).
- **ADR-0017 (pessimistic lock on consumable stock):** consumable decrement must use `@Lock(PESSIMISTIC_WRITE)` on `ConsumableStockBalance`; optimistic-only would allow overdraft under concurrent nursing chart entries.
- **ADR-0008 rule 4 (bill creation must stay in-tx):** the consumable charge accrual must call `billing.api.recordClinicalCharge` synchronously in the same transaction as the `ConsumableIssue` row insert — never via an async event.

## Lead & supporting agents

- **Lead:** backend-engineer, frontend-engineer
- **Supporting:** engagement-lead, healthcare-domain-expert (nursing chart field validation, MAR clinical rules), legacy-analyst (confirm ward-to-ward transfer records required fields; confirm second-approver privilege code names), solution-architect (ADR-0008/0017/0018 compliance review), data-architect (ward-day accrual idempotency constraint, consumable stock lock ordering), security-architect (RBAC for MAR and discharge-plan-approve privileges), ux-ui-designer (nurse inpatient worklist, discharge plan approval screen, MAR timeline), qa-test-engineer (golden-master scenarios, concurrency test), devops-engineer (ShedLock job monitoring, `job_run_log` alerting), code-reviewer (gates every PR to main)

## Definition of Done

- [ ] `Admission` aggregate persists with all state transitions; ward/bed assignment claimed and released correctly; `WardTransfer` history persists on ward-to-ward transfer without closing the admission.
- [ ] `DischargePlan` (DISCHARGE / DECEASED / REFERRAL kinds) transitions `PENDING → APPROVED`; self-approval returns `422 SELF_APPROVAL_FORBIDDEN`; DECEASED plan sets `Patient.deceased = true`; REFERRAL plan requires a valid `ExternalMedicalProvider` uid.
- [ ] CASH admission blocked from discharge approval when `billsCleared = false`; flag is set by the billing dispatcher after invoice balance reaches zero (not by the inpatient module directly).
- [ ] All six nursing chart aggregates (`AdmissionVitalsEntry`, `NursingCarePlanItem`, `NursingProgressNote`, `DressingChartEntry`, `FluidBalanceEntry`, `CareActivityEntry`) persisted, retrieved, and immutable where specified.
- [ ] `MedicationAdministration` (MAR) aggregate created, linked to prescription line uid, and retrievable per admission.
- [ ] `ConsumableIssue` decrements `ConsumableStockBalance` under `PESSIMISTIC_WRITE` lock in the same transaction as charge accrual; overdraft returns `409 STOCK_INSUFFICIENT`.
- [ ] `WardDayAccrualJob` (JOB-001) runs with ShedLock; `shedlock` and `job_run_log` tables created by Flyway migration; manual trigger endpoint (`POST /ops/jobs/ward-day-accrual/trigger`) works; idempotency constraint prevents double-accrual on same `(admission_uid, business_date)`.
- [ ] Ward-day price resolves via `ServicePrice(kind=WARD, serviceUid=wardTypeUid, planUid)` with cash fallback; 3-night golden-master scenario produces correct total.
- [ ] Approver worklist (`GET /inpatient/admissions/discharge-plans?status=PENDING`) returns only PENDING plans, pageable, gated by `DISCHARGE_PLAN_APPROVE` privilege.
- [ ] All RBAC `@PreAuthorize` privilege codes for this increment are seeded in the Flyway reference-data migration and exercised in security integration tests (at least one "allowed" and one "denied" case per endpoint group).
- [ ] Audit events emitted for every state transition (admitted, ward-transferred, plan-created, plan-approved, discharged/deceased/referred) and captured in `audit_event`.
- [ ] OpenAPI spec updated; springdoc generates correct schemas for all new request/response DTOs.
- [ ] Behavioural golden-master parity tests green: 3-night stay accrual, second-approver gate, CASH discharge block, deceased guard, consumable concurrency, MAR round-trip.
- [ ] Angular standalone screens delivered: nurse inpatient worklist, admission detail with tabbed chart views (vitals, care plan, progress notes, dressing, fluid balance, care activities, MAR, consumables), discharge plan authoring and approval screens.
- [ ] Testcontainers integration test covers concurrent consumable issue (two parallel last-unit requests → exactly one 200, one 409).
- [ ] `ApplicationModules.verify()` and ArchUnit gates pass with no new illegal cross-module edges.
- [ ] Code-reviewer has approved the PR; no HIGH-severity findings unresolved.
