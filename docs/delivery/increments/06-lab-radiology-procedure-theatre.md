> ⚠️ **SUPERSEDED IN PART (2026-06-04).** This planning doc is heavily drifted. The clinical
> Lab/Radiology/Procedure/Theatre **order + result + attachment + worklist loops were already
> shipped in inc-05** under legacy-accurate names, and ~80% of this doc's "new" scope is either
> already-built or **phantom** (polymorphic `ClinicalOrder`, `COMPLETED`/`CANCELLED` states,
> procedure `APPROVED`/surgeon-approval, `OperativeRecord`, per-analyte `LabResultLine` + server-side
> flag computation, `LabBatch`/`seq_lab_batch_no`, theatre scheduling, `ORDER_NOT_SETTLED` hard gate,
> "177 privilege codes", MinIO/S3/ClamAV-as-legacy-parity). The genuine remainder is a thin set of
> inc-05 closure items ("inc-06A"). **Do NOT build against this doc.** See the authoritative
> reconciliation + scope: [06-lrp-discovery/01-RECONCILIATION-AND-SCOPE.md](06-lrp-discovery/01-RECONCILIATION-AND-SCOPE.md)
> (workflow run `wf_3d82df11-0d0`). Acceptance criteria must be re-authored from the verified legacy
> extractions, never from this doc.

# Increment 06 — Laboratory, Radiology, Procedures & Theatre

## Goal

Deliver the complete clinical-order fulfilment loop — from lab/radiology/procedure order receipt through ACCEPTED and COMPLETED with results, images, and operative records — so that a doctor ordering a test can view the finalized result or procedure report within the same end-to-end session, with all payment gates, attachment storage (ADR-0015), and per-insurance-plan pricing enforced.

## Scope

**Bounded contexts:** `laboratory`, `radiology`, `procedures` (three sibling modules), plus cross-cutting use of `billing.api`, `insurance.api`, `shared` (StoragePort, Money, TxAuditContext), and the generic `Attachment` aggregate from ADR-0015. Theatre scheduling lives inside the `procedures` module as a sub-aggregate of `ClinicalOrder`.

**Key aggregates / entities:**

- `ClinicalOrder` — polymorphic aggregate with `kind` discriminator (LAB_TEST / RADIOLOGY / PROCEDURE); holds `settled` boolean written by `SettlementDispatcher` (billing → encounter direction); `theatreUid` / `scheduledAt` / `scheduledByUsername` for procedure-kind orders.
- `LabResultLine` — per-analyte result (value, unit, `ReferenceRange` flag: NORMAL / LOW / HIGH / CRITICAL_LOW / CRITICAL_HIGH / ABNORMAL) linked to `MdLabTestAnalyte`; supports long-text/narrative fields.
- `LabBatch` — groups same-type same-date orders so a lab tech can accept and result them as a single batch action.
- `RadiologyReport` — free-text radiologist report attached to a RADIOLOGY order after images are uploaded.
- `OperativeRecord` — findings, technique, instruments, complications, surgeon signature; linked to a PROCEDURE order; required before `complete()` is callable.
- `Attachment` — generic `(ownerKind, ownerUid)` keyed row in `attachment` table (ADR-0015); `StoragePort` → MinIO/S3 bytes; ClamAV scan on upload.

**Key REST endpoints (all under `/api/v1`):**

- `GET /laboratory/orders` — lab worklist with `?patientClass=&settledOnly=true` (PROCESS.md §5 queue separation).
- `POST /laboratory/orders/uid/{uid}/accept` — PENDING → ACCEPTED; CASH gate.
- `POST /laboratory/orders/uid/{uid}/complete` — ACCEPTED → COMPLETED; CASH gate.
- `POST /laboratory/orders/uid/{uid}/result-lines` — upsert per-analyte results with flag computed server-side.
- `GET /laboratory/batches` — list lab batches; `POST /laboratory/batches` — create batch grouping orders by type+date.
- `POST /laboratory/batches/uid/{uid}/accept` / `.../complete` — bulk lifecycle on batch members.
- `GET /radiology/orders`, `POST .../uid/{uid}/accept`, `POST .../uid/{uid}/complete` — mirror lifecycle.
- `POST /radiology/orders/uid/{uid}/report` — upsert radiologist free-text report.
- `GET /procedures/orders`, `POST .../uid/{uid}/approve` — PENDING → APPROVED (surgeon/anaesthetist; M14).
- `POST /procedures/orders/uid/{uid}/schedule` — assign `theatreUid` + `scheduledAt`.
- `POST /procedures/orders/uid/{uid}/complete` — APPROVED → COMPLETED; CASH gate; requires OperativeRecord.
- `POST /procedures/orders/uid/{uid}/operative-record` — create/update operative record.
- `POST /encounters/attachments` (multipart) — upload file; `GET /encounters/attachments/uid/{uid}/download` — pre-signed redirect; `DELETE /encounters/attachments/uid/{uid}`.

**Process states (PROCESS.md §5, §6, §7, §15):**

- Lab: PENDING → ACCEPTED → COMPLETED (/ CANCELLED).
- Radiology: PENDING → ACCEPTED → COMPLETED (/ CANCELLED).
- Procedure: PENDING → APPROVED (surgeon) → COMPLETED (/ CANCELLED). The ACCEPTED intermediate from the prior attempt is not present for procedures; only Lab and Radiology require ACCEPTED.

**Pricing:** `ServicePrice(planUid, kind, serviceUid, currency)` unified matrix from `shared`; `PriceLookup.resolve()` applies plan-specific price first, falls back to cash price. Per-insurance-plan pricing for LAB_TEST, RADIOLOGY, and PROCEDURE kinds. Price is locked at order-raise time (charge via `billing.api.recordClinicalCharge()`), not re-computed at accept/complete.

## Dependencies

- **Increment 01 (Identity & Access)** — all 177 `@PreAuthorize` privilege codes and JWT claims; `@PreAuthorize("hasAuthority('LAB_TEST-CREATE')")` et al. on every endpoint.
- **Increment 02 (Master Data & Reference Seeding)** — `LabTestType`, lab analytes/reference ranges, `RadiologyType`, `ProcedureType`, `Theatre` seed data; the `ServicePrice` matrix for plan-based pricing.
- **Increment 04 (Billing, Cashiering & Insurance)** — the `settled` flag and `SettlementDispatcher` that gate accept/complete on payment for CASH patients.
- **Increment 05 (Clinical / OPD)** — `ClinicalOrder` records are created by the doctor in 05 (status PENDING on raise); this increment consumes and advances them and must not duplicate the order-creation endpoint.

## Exact-process fidelity targets

1. **CASH payment gate on `accept()` and `complete()` — not a filter (M3, M16, DIAG-2).** For CASH (`paymentType == CASH`) patients: `accept()` on a lab/radiology order must assert `clinicalOrder.settled == true`; `approve()` and `complete()` on a procedure must assert `settled == true`. Violation → HTTP 409 `ProblemDetail` with `type=urn:hmis:error:ORDER_NOT_SETTLED`. Non-CASH orders are treated as COVERED at order time and pass the gate unconditionally. The `settled` flag is **written by `SettlementDispatcher`** in `billing` → `laboratory`/`radiology`/`procedures` direction only; these modules never call billing to read settlement status.

2. **Mandatory ACCEPTED step for lab and radiology (M16, DIAG-1).** PENDING → COMPLETED direct transition is forbidden at the service layer. `LabOrderService.complete()` and `RadiologyOrderService.complete()` must assert `order.status == ACCEPTED` or throw `InvalidOrderStateException` (HTTP 409). This preserves the specimen-custody audit trail: `acceptedAt`, `acceptedByUsername` are stamped at `accept()`.

3. **Mandatory surgeon APPROVED step for procedures (M14).** `ProcedureOrderService.complete()` asserts `order.status == APPROVED`. `approve()` is a distinct endpoint requiring `PROCEDURE_APPROVE` privilege; the approver must be a `SURGEON` or `ANAESTHETIST`-role user (validated against `User.roles` via `iam.api`). Self-approval is permitted only when `hmis.procedure.allow-self-approve=true` (config-gate for solo-clinician sites; default false).

4. **Operative record required before `complete()` (PROCESS.md §7, step 4).** `ProcedureOrderService.complete()` asserts that an `OperativeRecord` exists for the order uid (non-null, non-empty `technique` field). HTTP 422 `ProblemDetail` with `type=urn:hmis:error:OPERATIVE_RECORD_MISSING` otherwise.

5. **Theatre scheduling only on PROCEDURE kind.** Calling `schedule` on a LAB_TEST or RADIOLOGY order returns HTTP 400. No double-booking guard in this increment (documented gap, deferred to scheduling ADR); `scheduledAt` is a free timestamp.

6. **Lab result line flags computed server-side.** `LabResultService.upsertLines()` resolves the applicable `MdReferenceRange` by `(analyteUid, sexEnum, ageAtOrderDate)` and computes `flag` deterministically: `CRITICAL_LOW` if `value < criticalLow`, `LOW` if `value < low`, `HIGH` if `value > high`, `CRITICAL_HIGH` if `value > criticalHigh`, `NORMAL` otherwise. The flag is **never accepted from the client**; client sends only raw value. Golden-master assertion: given the same analyte, reference-range seed, and raw value, the flag enum must match.

7. **Lab batch grouping (PROCESS.md §5, step 5).** A `LabBatch` groups orders by `(labTestTypeUid, orderDate)`. `LabBatchService.accept()` calls `accept()` on each member order atomically in one `@Transactional` boundary (CASH gate fires per order). `LabBatchService.complete()` does the same. Partial-batch failure (one order's gate fails) rolls back the entire batch accept.

8. **Pricing math golden-master (ADR-0009).** For an insured plan with a plan-specific price for LAB_TEST `uid=X`: `ChargeRequest.amount = ServicePrice.resolve(planUid, LAB_TEST, X)`. For CASH: `amount = ServicePrice.resolve(null, LAB_TEST, X)`. Parity assertion: `round(new_amount, 2) == round(legacy_double_price, 2)`. Verified by a parameterized test seeded with the legacy `LabTestTypeInsurancePlan` reference rows loaded via Flyway.

9. **Attachment guard: no delete after COMPLETED (ADR-0015).** `AttachmentService.delete()` asserts the owning order is not in COMPLETED or CANCELLED status. HTTP 409 `ProblemDetail` with `type=urn:hmis:error:ATTACHMENT_DELETE_LOCKED`. This reproduces the legacy constraint (PROCESS.md §5 implied; `PatientResource.java` lines 6021–6023 confirmed).

10. **Document numbers for any batch documents** use `seq_lab_batch_no` PostgreSQL sequence producing format `LABB{yyyyMMdd}-{seq}` (new sequence added to the ADR-0009 prefix table — requires engagement-lead sign-off before Flyway migration is committed).

11. **Worklist queue scoping (M2, DIAG-2).** All three worklist `GET` endpoints default `settledOnly=true` for OUTPATIENT and OUTSIDER patient classes. INPATIENT orders appear when the admission invoice is VERIFIED (insured) or PAID (cash). `patientClass` is derived at runtime (OUTSIDER if `patient.patientType==OUTSIDER`, INPATIENT if open ADMITTED admission exists, else OUTPATIENT).

## Prior-attempt pitfalls to avoid

- **M16 / DIAG-1** — allowing PENDING → COMPLETED directly on lab and radiology orders, losing the specimen-receipt audit trail. Encode the ACCEPTED gate as an `InvalidOrderStateException` in the service layer, not as a UI-only guard.
- **M14** — missing APPROVED state for procedures. The `approve()` endpoint must exist and be required before `complete()`.
- **M3 / M13 / DIAG-2** — settlement check as a worklist filter only (`settledOnly=false` default). Encode as a hard precondition on `accept()` and `complete()` state transitions, not as a query parameter default.
- **DISCH-2 / BILL-1 gap pattern** — do not leave the attachment download endpoint as a stub. The `GET .../download` endpoint must return either streamed bytes (< 1 MiB) or an HTTP 302 pre-signed S3 redirect in this increment; a missing endpoint is not acceptable at merge time.
- **Prior-build `order_attachment` narrow scope** — do not create a `lab_test_attachment` or `radiology_attachment` table. Use the ADR-0015 generic `attachment` table with `(ownerKind=CLINICAL_ORDER, ownerUid)` from the start.
- **OPC-2 / billing atomicity (ADR-0008 rule 4)** — the charge (via `billing.api.recordClinicalCharge()`) is raised at order-creation time (increment 05); do not re-raise a charge at `accept()` or `complete()`. The `settled` flag is the only billing-side signal this increment reads.
- **ArchUnit / Modulith boundary** — `laboratory`, `radiology`, `procedures` modules must not import each other's `@Entity` classes. Shared lifecycle logic lives in `shared` or is duplicated deliberately per module. `ApplicationModules.verify()` must remain green.

## Lead & supporting agents

- **Lead:** backend-engineer, frontend-engineer
- **Supporting:** engagement-lead, solution-architect, data-architect, legacy-analyst, healthcare-domain-expert, security-architect, ux-ui-designer, qa-test-engineer, code-reviewer, devops-engineer

## Definition of Done

- [ ] All three module packages (`laboratory`, `radiology`, `procedures`) pass `ApplicationModules.verify()` and ArchUnit boundary rules with no new violations; no `@Entity` crosses module boundaries.
- [ ] Flyway migrations applied cleanly on a fresh PostgreSQL 16 schema: `clinical_order` table with `kind` discriminator, `lab_result_line`, `lab_batch` + `lab_batch_member`, `operative_record`, `attachment` (generic, ADR-0015 schema), new sequences including `seq_lab_batch_no`.
- [ ] Golden-master parity tests green: (a) for each lab test type seeded from legacy reference data, `PriceLookup.resolve()` for cash and each insurance plan matches `round(legacy_double, 2)`; (b) `LabResultService` flag computation matches legacy flag strings for all seeded analyte/range/value combinations; (c) concurrent lab batch accept produces unique, gap-free `LABB{yyyyMMdd}-N` document numbers.
- [ ] CASH payment gate integration test: `accept()` on a PENDING CASH order with `settled=false` returns HTTP 409 with `type=urn:hmis:error:ORDER_NOT_SETTLED`; same gate fires on `complete()` for CASH; INSURANCE order bypasses the gate.
- [ ] State-machine guard integration tests: PENDING → COMPLETED (skipping ACCEPTED) on lab/radiology returns HTTP 409; PENDING → COMPLETED (skipping APPROVED) on procedure returns HTTP 409; `complete()` on procedure with no `OperativeRecord` returns HTTP 422.
- [ ] Procedure approval gate: `approve()` by a non-SURGEON/ANAESTHETIST user returns HTTP 403; `approve()` by a valid role with `settled=true` (CASH) advances to APPROVED.
- [ ] Attachment lifecycle end-to-end: upload PDF (< 50 MiB, `application/pdf`) via multipart, ClamAV scan CLEAN, metadata row in `attachment` table with opaque `storage_key` (never in response body), `GET .../download` for file < 1 MiB returns streamed bytes with correct `Content-Type`; for file > 1 MiB returns HTTP 302 pre-signed URL; `DELETE` on a COMPLETED-order attachment returns HTTP 409.
- [ ] RBAC: all endpoints carry `@PreAuthorize` with the correct privilege code from the 177-code set; a user without `LAB_TEST-VIEW` receives HTTP 403 on the lab worklist endpoint.
- [ ] Audit events: `LabOrderAccepted`, `LabOrderCompleted`, `RadiologyOrderAccepted`, `RadiologyOrderCompleted`, `ProcedureOrderApproved`, `ProcedureOrderCompleted` events emitted via Spring Modulith `@ApplicationModuleListener` (non-blocking, after-commit); `TxAuditContext.dayId` and `timestamp` are identical across all sub-entities written in the same transaction.
- [ ] Angular screens functional against a live Testcontainers stack: lab worklist (OUTPATIENT/INPATIENT/OUTSIDER tabs), result-entry form with analyte flag display, radiology worklist with report editor and attachment upload/preview, procedure worklist with approve action and operative-record form, theatre schedule view (read-only in this increment beyond scheduling an order).
- [ ] OpenAPI spec regenerated and committed; all new endpoints documented with correct request/response schemas and `ProblemDetail` error responses.
- [ ] Code-reviewer approval on the PR; no HIGH-severity findings from the `code-review` skill unresolved at merge.
