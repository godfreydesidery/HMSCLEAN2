# Inc-07 Implementation Summary — Inpatient & Nursing

**Date:** 2026-06-05 · **Branch:** `feat/increment-07-inpatient-nursing`
**Status:** ✅ **BUILD-COMPLETE & GREEN** — `mvn -o verify` BUILD SUCCESS (734 tests / 800 incl. nested, 0 failures, 0 errors). One additive aggregate (CR-07-MAR) remains owner-gated and is intentionally NOT built.

The inpatient bounded context, built from an empty stub to the exact-process baseline ratified in
[03-DECISIONS-RATIFIED.md](03-DECISIONS-RATIFIED.md) + [03b-OWNER-RULINGS.md](03b-OWNER-RULINGS.md) and
specified in [05-INC07-BUILD-SPEC.md](05-INC07-BUILD-SPEC.md). This was the highest-drift planning doc
of the engagement (~21 phantom + ~25 drift behaviours in the original plan); discovery removed the
phantoms (ward-to-ward transfer, closed-loop MAR, a non-existent inc-08 stock dependency) before any
code was written. Built in verified chunks, each committed green.

---

## What was built (by chunk)

| # | Slice | Migration | Key entities / services | IT |
|---|-------|-----------|--------------------------|-----|
| P | Prerequisite cross-module seams | — | `ErrorCodes` (STALE_ENTITY / PATIENT_DECEASED / SELF_APPROVAL_FORBIDDEN / ADMISSION_BILLS_OUTSTANDING), `WardLookup`+`WardBedClaim` (PESSIMISTIC_WRITE bed lock), `PrescriptionChartPort` stub, `billing::api.admissionHasOutstandingBills` + `ChargeRequest` billItem/description | compile + ModularityTest |
| 07a-1 | Admission lifecycle | V44, V45 | `Admission`/`AdmissionBed`, `AdmissionStatus`, payment-gated activation, `AdmissionSettlementListener` (BillSettledEvent), `PatientAdmitted/DischargedEvent` | 6/6 (`AdmissionLifecycleIT`) |
| 07a-2 | Ward insurance billing + top-up (Option B) | — | `recordClinicalCharge` COVERED principal + `recordWardTopUp` seam, plan-identity activation discriminator, `ConsultationSignOut` asymmetry | 4/4 (`WardInsuranceBillingIT`) |
| 07a-3 | Dispositions + discharge gate + SoD | V46, V47 | `DischargePlan` (inpatient-owned); `ReferralPlan`/`DeceasedNote` reuse clinical via `AdmissionDispositionPort`; bills-cleared gate; CR-07-SoD (approver≠creator); CR-07-Q10 close-bed; deceased early-bed-free | 3 groups (`DispositionIT`) |
| 07b | Five nursing-chart entities + dosing-note path | V48 | `PatientNursingChart`, `CarePlan`, `ProgressNote`, `PatientDressingChart` (BILLING record), `PatientPrescriptionChart` dosing note; clinical-owned via `NursingChartPort`+`PrescriptionChartPort`; admission-IN_PROCESS gate in inpatient | 5 groups (`NursingChartIT`) |
| 07c-i | Consumable issue/delete + Q11 fixes + stock seam | V49 | Consumable issue/delete, **Q11 three latent bugs FIXED**, `CONSUMABLE_ISSUE` stock seam over inc-08 (`pharmacy::api PharmacyStockDebit`), MEDICINE pricing + "Medication"/"Consumable: <name>" literals | 5 groups (`ConsumableChartIT`) |
| 07c-ii | Ward-day accrual oracle + scheduled job | V50 | `WardAccrualService` (sweep) + **`WardAccrualOneTx`** (per-admission REQUIRES_NEW), `WardDayAccrualJob` (@Scheduled + ShedLock + `job_run_log`), `SchedulingConfig`, `WardAccrualOpsController`, `billing::api.recordWardAccrual` | 6 groups / 7 tests (`WardAccrualIT`) |
| FINAL | OpenAPI per-module slices + full-verify gate | — | `OpenApiExportIT` rewrite: transitive `$ref` schema-pruning + new `inpatient.yaml` | full verify |

**Cross-module seams added (no cycles; `ApplicationModules.verify()` green):**
- `inpatient → billing::api`: `admissionHasOutstandingBills` (discharge gate scans lab/rad/pharmacy bills inside billing), `recordWardTopUp`, `recordWardAccrual`, extended `ChargeRequest` (billItem + description, CR-07-Q13).
- `inpatient → masterdata::lookup`: `WardLookup` (+`WardBedClaim` PESSIMISTIC_WRITE bed lock, ADR-0017), `DressingLookup`, `ConsumableLookup`.
- `inpatient → pharmacy::api`: `PharmacyStockDebit` (CONSUMABLE_ISSUE stock seam, CR-07-consumable-stock).
- Clinical-owned reuse: `PrescriptionChartPort` / `NursingChartPort` / `AdmissionDispositionPort` (referral + deceased dispositions reuse clinical's `ClosureService`).
- Activation via existing `shared.event.BillSettledEvent` (billing→inpatient, no compile edge) — `AdmissionSettlementListener` matches the admit-time ward-bed bill uid.
- `registration::lookup PatientStatusLookup`; `PatientAdmitted/DischargedEvent` + extended `PatientClosureListener`.

---

## Exact-process fidelity — what the build reproduces verbatim

- **Admission:** free-text status PENDING / IN-PROCESS / STOPPED / HELD / SIGNED-OUT (NOT a typed ADMITTED→… machine); payment-gated activation; guard order not-found → DECEASED → already-admitted → claimBed; deceased admit blocked with verbatim `PATIENT_DECEASED`.
- **Ward charge:** per-stay flat charge re-accrued per rolling-24h window — `(1 + N) × WardType.price`. Accrual closes the OPENED `AdmissionBed`, opens a new one (`openedAt = now`), and writes a new VERIFIED (cash) / COVERED+top-up (insurance, Option B) ward bill with billItem `"Bed"` / desc `"Ward Bed / Room"` (UpdatePatient.java:258-340). De-dup branch closes extra OPENED beds and skips.
- **Discharge gate:** hard, recompute-live (no `billsCleared` flag), across all admissions + all three dispositions, blocking on UNPAID+VERIFIED bills.
- **Dispositions:** three separate tables (discharge / referral / deceased); deceased requires summary + cause-of-death (422 BUSINESS_RULE, verbatim message incl. the legacy `outpatioent` typo branch).
- **Nursing charts:** five entities (fluid-balance / care-activity are COLUMNS, not entities); dressing chart is a BILLING record (kind=PROCEDURE); prescription chart dosing note is free-text. No edit path.
- **Consumable issue:** billing-driven; Q11 three latent bugs reproduced-then-fixed (qty source, credit-note ref, parent-invoice cascade-delete only when empty).

## Deliberate deltas (all labelled, none silent)

- `double → BigDecimal` (ADR-0009, pre-approved); ULID uid + hidden id (ADR-0014).
- **Pessimistic bed lock + 409 STALE_ENTITY** (CR-07-Q3, ADR-0017 — supersedes inc-08 `@Version`-only for contended aggregates).
- **Scheduled cron replaces the legacy 5-minute polling Thread** (CR-07-Q2, ADR-0018): `@Scheduled(0 5 0 * * *)` + ShedLock + append-only `job_run_log`. Owner accepted the midnight-vs-rolling-24h timing variance; the golden-master anchors the *amount* to the legacy elapsed-24h chained total.
- **CONSUMABLE_ISSUE touches stock** (CR-07-consumable-stock) — legacy consumable issue was billing-only; the new seam adds the `pharmacy::api` debit/reversal edge.
- Per-entity APPROVE-suffixed disposition privileges + SELF_APPROVAL_FORBIDDEN (CR-07-SoD) — legacy approver was always = creator.
- Ward-insurance pricing: legacy max-price loop is dead code / top-up unreachable / ward-type-agnostic defect → owner chose **Option B** (ward-type-keyed pricing, real top-up). See [06-AMBIGUITY-WARD-INSURANCE-PRICE.md](06-AMBIGUITY-WARD-INSURANCE-PRICE.md).
- **07c-ii fix (this session):** the per-admission accrual was a `@Transactional(REQUIRES_NEW)` method called via self-invocation, which Spring's proxy bypasses — writes ran in the read-only sweep tx → `UnexpectedRollbackException`. Extracted into a separate `WardAccrualOneTx` bean so the propagation boundary is honoured (real per-admission fault isolation, ADR-0018 §6). Verified by `WardAccrualIT` 7/7.

## Parked as CRs (NOT built — held behind owner sign-off)

- **CR-07-MAR** (additive closed-loop MedicationAdministration / MAR aggregate) — owner approved into scope but blocked on the route/administration **masterdata + PHI/audit decision** (data-architect + security-architect). The only inc-07 scope item not yet built.
- **CR-INC07-Q7** (referral FK — loose uid stands) · **CR-07-ward-transfer / Q8** (ward-to-ward transfer — excluded; no legacy basis).

## OpenAPI (FINAL chunk)

`OpenApiExportIT` now prunes `components.schemas` to each slice's transitive `$ref` closure and emits a
new **`inpatient.yaml`** (covers `/api/v1/inpatient/**` + the ops trigger `/api/v1/ops/jobs/ward-accrual/trigger`).
This also fixed a pre-existing leak where the global schema set was copied into every slice — e.g.
`pharmacy.yaml` shrank 82 KB → 12 KB and `inventory.yaml` 96 KB → 26 KB once cross-module DTOs
(AdmissionRequest, DischargePlan…) were pruned. Dangling-ref check: 0 unresolved `$ref` in any slice.

## Migrations
V44 (admission lifecycle) · V45 (`patient_bill.admission_uid`) · V46 (discharge plans) · V47 (disposition
APPROVE privileges, 35→38) · V48 (nursing charts + dressings masterdata) · V49 (consumable charts) ·
V50 (ShedLock + `job_run_log`). All additive; apply clean V1→V50 on PostgreSQL 16.

## Verification
`mvn -o verify` GREEN — 734 tests (800 incl. nested), 0 failures, 0 errors. ModularityTest green
(no new cycle). Per-flow inpatient ITs (43 total): `AdmissionLifecycleIT` 6, `WardInsuranceBillingIT` 4,
`DispositionIT` (3 groups), `NursingChartIT` (5 groups), `ConsumableChartIT` (5 groups),
`WardAccrualIT` 7. `OpenApiExportIT` regenerates pharmacy / inventory / inpatient slices.
