# Zana HMIS Modernization — Build & Delivery Plan

> The executable roadmap for the greenfield rebuild. Governed by the 21 ADRs ([`../adr/`](../adr/)) and the [target architecture overview](../architecture/overview.md). We reproduce the exact legacy **process** on a modern stack; fidelity is proven **behaviourally** (golden-master parity), not by data reconciliation — there is **no migration**. Detailed specs live in [`./increments/`](./increments/).

## Overview

Twelve dependency-ordered **increments** (00–11), each a **vertical slice** (backend module + REST/OpenAPI + Angular screens + unit/integration/parity tests) delivered behind a single **PR** to a **PR-only `main`**, gated by the `code-reviewer` and a green pipeline (Spring Modulith boundary verify + ArchUnit, Flyway validate, Testcontainers, **golden-master parity**, SAST, dependency/image scan). No business code starts until the walking skeleton (00) proves the stack end-to-end.

## Delivery principles

1. **Vertical slices, not horizontal layers.** A slice is *done* only when a user can drive the workflow end-to-end through the UI against a real PostgreSQL 16. Counters the prior build's "data exists, no screen/endpoint" gaps (DISCH-1/2, BILL-1/2).
2. **Walking skeleton first (00).** Shared kernel (`AuditableEntity` hidden `id` + ULID `uid`, `Money`, audit, `ProblemDetail`/`ErrorCode`), Flyway `V1` + `ddl-auto=validate`, Modulith/ArchUnit gates, full green CI, one trivial slice — before any business logic.
3. **Pay-before-service is a hard gate, not a queue filter.** For CASH patients the `settled` flag is a precondition on the *transition that renders the act* (lab/radiology/procedure `accept`/`complete`, pharmacy `accept`/`hold`/`markSold`, `DischargePlan.approve`), never merely on order creation. Written only by the billing-side `SettlementDispatcher` (billing → encounter). Avoids M3/M13/M23/DIAG-2/PHARM-2.
4. **Exact-process gates per slice.** Legacy state machines, document-number formats+sequences (ADR-0009: `GRN{yyyyMMdd}-{n}`, `MRNO/{year}/{n}`, `SPTO`/`PPTO`), validations, the unified `ServicePrice` routing, and all 177 `@PreAuthorize` codes are ported verbatim. Mechanics change (`double`→`BigDecimal`, ULID `uid`, ProblemDetail); the process does not. Deliberate deviations go through change-request control.
5. **Golden-master parity is a merge gate.** Each context ships a `*ParityIT` suite driving identical seeded scenarios through captured legacy behaviour and the new system, asserting business-result equality (`double→BigDecimal` deltas whitelisted to ±0.01). Required CI check. Parity is behavioural — the legacy DB is consulted as a *specification source only*.
6. **Systematically avoid the prior M1–M25 regressions**, and carry the prior build's *good* patterns forward (the `settled` cross-module gate, unified `ServicePrice`, polymorphic `ClinicalOrder`, FEFO pessimistic-lock dispensing, event-driven after-commit denormalisation).

## Increment roadmap

Increment numbers are stable IDs. Because two dependencies run against the patient-journey reading order, the **build sequence differs from the numeric order** (see note below the table).

| ID | Increment | Bounded context(s) | Depends on | Milestone | Lead |
|----|-----------|--------------------|------------|-----------|------|
| [00](./increments/00-walking-skeleton-shared-kernel.md) | Walking Skeleton & Shared Kernel | platform / shared kernel | — | M0 | solution-architect |
| [01](./increments/01-identity-access.md) | Identity & Access (IAM) | Identity/Access | 00 | M1 | backend-engineer |
| [02](./increments/02-master-data.md) | Master Data & Reference Seeding | Masterdata | 00, 01 | M1 | data-architect |
| [03](./increments/03-registration-patient.md) | Registration & Patient | Registration/Patient | 00, 01, 02, **04** | M2 | backend-engineer |
| [04](./increments/04-billing-cashiering-core.md) | Billing, Cashiering **& Insurance** | Billing & Cashiering, **Insurance/Claims** | 00, 01, 02 | M2 | backend-engineer |
| [05](./increments/05-clinical-opd.md) | Clinical / OPD | Clinical/OPD | 00, 01, 02, 03, 04 | M2 | backend-engineer |
| [06](./increments/06-lab-radiology-procedure-theatre.md) | Laboratory, Radiology, Procedures & Theatre | Lab, Radiology, Procedures/Theatre | 00–02, 04, 05 | M3 | backend-engineer |
| [07](./increments/07-inpatient-nursing.md) | Inpatient & Nursing | Inpatient/Nursing | 00–02, 04, 05, **08** | M5 | backend-engineer |
| [08](./increments/08-pharmacy-inventory-procurement.md) | Pharmacy, Inventory & Procurement | Pharmacy, Inventory/Procurement | 00–02, 04, 05 | M4 | backend-engineer |
| [09](./increments/09-hr-payroll-assets.md) | HR, Payroll & Assets | HR/Payroll, Assets | 00, 01, 02 | M6 | backend-engineer |
| [10](./increments/10-reporting-management.md) | Reporting & Management | Reporting | 03–09 | M6 | backend-engineer |
| [11](./increments/11-hardening-cutover.md) | Hardening, Non-Functional & Cutover | cross-cutting | all | M7 | devops-engineer |

**Recommended build sequence (topological):** `00 → 01 → 02 → 04 → 03 → 05 → 06 → 08 → 07 → 09 → 10 → 11`.
Two edges invert the numeric order, by design:
- **04 (Billing) before 03 (Registration):** registration raises the registration-fee invoice synchronously via `billing.api.recordClinicalCharge()` (ADR-0008 rule 4), so the billing module must exist first.
- **08 (Pharmacy/Inventory) before 07 (Inpatient):** the inpatient consumable chart decrements pharmacy/store stock atomically (`ConsumableStockService.decrementForIssue`), so the stock ledger from 08 must exist first.

## Milestones

| # | Milestone | Increments | Goal / exit criteria |
|---|-----------|------------|----------------------|
| M0 | Walking skeleton | 00 | Stack proven end-to-end; CI green; Modulith/ArchUnit/Flyway gates active. |
| M1 | Secure foundation + data | 01, 02 | Auth + 177 privilege codes enforced; all reference/master data seeded via Flyway. |
| M2 | First billable OPD happy-path | 04, 03, 05 | Register → consult → order → bill → pay, end-to-end, parity-green, pay-before-service enforced. |
| M3 | Ancillary services | 06 | Lab/radiology/procedure/theatre lifecycles at parity (incl. approval & accept gates). |
| M4 | Inventory & pharmacy | 08 | Stock (FEFO/batches), dispensing, procurement (LPO→GRN, 3-way match), RO/TO/RN transfers, SPTO/PPTO numbering. |
| M5 | Inpatient | 07 | Admission, full nursing charts (incl. MAR + fluid-balance + care-activity), daily ward accrual, gated discharge. |
| M6 | Back-office & reporting | 09, 10 | HR/payroll (with VERIFIED gate); the 29 reports + dashboard at golden-master parity. |
| M7 | Hardening & cutover | 11 | Facility scoping, observability, i18n, load/security/accessibility, IaC, go-live + rollback. |

## RACI

`R` = responsible (lead). `C` = consulted/supporting. **Standing across all increments:** `engagement-lead` (A, accountable), `code-reviewer` (merge gate), `qa-test-engineer` (parity suites), `security-architect` (PHI/RBAC review).

| Increment | R (lead) | C (supporting) |
|---|---|---|
| 00 Skeleton | solution-architect | backend-engineer, devops-engineer |
| 01 IAM | backend-engineer | security-architect |
| 02 Master data | data-architect | backend-engineer, healthcare-domain-expert |
| 03 Registration | backend-engineer | frontend-engineer, business-analyst |
| 04 Billing & Insurance | backend-engineer | healthcare-domain-expert, integration-engineer |
| 05 Clinical/OPD | backend-engineer | frontend-engineer, healthcare-domain-expert |
| 06 Lab/Rad/Proc/Theatre | backend-engineer | frontend-engineer |
| 07 Inpatient/Nursing | backend-engineer | frontend-engineer, healthcare-domain-expert |
| 08 Pharmacy/Inventory | backend-engineer | healthcare-domain-expert |
| 09 HR/Payroll/Assets | backend-engineer | business-analyst |
| 10 Reporting | backend-engineer | qa-test-engineer |
| 11 Hardening/Cutover | devops-engineer | security-architect, qa-test-engineer, engagement-lead |

## Exact-process governance

- **Parity without migrated data.** For each context, `qa-test-engineer` captures legacy outputs for fixed seeded scenarios (from the legacy app/DB used purely as a *specification oracle*), and the `*ParityIT` suite asserts the new system reproduces them. Money compares at scale 2 (`round(legacy,2) == round(new,2)`).
- **Per-entity money oracle.** `double → BigDecimal` is pre-approved; any trailing-digit delta is documented, not a defect.
- **Change-request control.** Any deliberate deviation from observed legacy behaviour requires a written, approved change request before implementation (e.g. the `"roles"`-claim refresh defect fix, ADR-0006).
- **Pricing resolution (single source).** `PriceLookup.resolve(plan, kind, service)` returns the plan-specific `ServicePrice`, else the cash price. The **missing-both** case is, per legacy (PROCESS.md §16.4), *configurable* (deny vs default); the build adopts a single default — **throw `ServicePriceNotFoundException` → ProblemDetail `urn:hmis:error:service-price-not-found`**, with a per-deployment flag to fall back to a 0/default charge. `legacy-analyst` confirms the production default before increment 02/04 parity fixtures are frozen; both increments state this identical behaviour.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Pharmacy/inventory (08) complexity (FEFO, batches, RO/TO/RN, coefficients, concurrency) | Largest increment; may split 08a (dispensing/stock) + 08b (store/procurement/transfers); pessimistic locks per ADR-0017. |
| Cross-context transactions (legacy `PatientServiceImpl` spans contexts) | Application-service orchestration + after-commit events (ADR-0008/0014); idempotent, compensating where needed. |
| Golden-master fixtures without migrated data | Author from the legacy app/DB as a specification oracle; `qa-test-engineer` owns the fixture library from M2. |
| 29 reports scope (10) | Treated as its own milestone; one `*ParityIT` per report; caching per ADR-0016; perf tested at 3-yr scale. |
| Build order ≠ numeric order (04<03, 08<07) | Explicit topological sequence above; dependency table is canonical; reviewers verify against it. |
| Insurance/claims ownership | Delivered in increment 04 (InsuranceClaim submit→settle→reject ledger), before reporting (10) consumes it. |
| Privilege-code drift breaks 177 gates | Codes seeded verbatim via Flyway; authorization parity suite asserts all 177 code→endpoint mappings (ADR-0006). |
| Scheduled jobs (ward accrual, payroll) single-execution in cluster | ShedLock/Quartz per ADR-0018; idempotent, audited. |

## Definition of Done

**Per increment:** vertical slice works end-to-end against the live stack; golden-master/parity tests green; RBAC enforced (relevant `@PreAuthorize` codes); audit events emitted; OpenAPI contract updated; Modulith/ArchUnit boundaries clean; `code-reviewer` approved; merged via PR.

**Engagement:** all 14 bounded contexts delivered at behavioural parity; the 29 reports reconcile to legacy; security + PHI sign-off; observability live; facility scoping enforced; staged cutover with a rehearsed rollback; stakeholder acceptance recorded.

## Next action

Start **Increment 00 — Walking Skeleton & Shared Kernel**: scaffold the Spring Modulith project, the shared kernel (base entity with ULID `uid`, `Money`, `ProblemDetail`), Flyway `V1`, the privilege seed, Docker Compose, and the CI pipeline with the Modulith/ArchUnit gates — then one trivial vertical slice to prove the stack.
