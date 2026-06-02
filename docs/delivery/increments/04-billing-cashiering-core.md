# Increment 04 — Billing, Cashiering & Insurance

## Goal

Deliver a fully working billing and cashiering vertical slice so that a cashier can receive a patient, view all outstanding charges by invoice, accept payment with automatic per-line allocation, issue a POS receipt, write off with a credit note, manage a cashier shift (OPEN→CLOSED), and run an end-of-day collections reconciliation report — making the pay-before-service hard gate available for all downstream clinical increments to consume.

## Scope

**Bounded context:** `billing` (primary) and `insurance` (claims); `shared` kernel (ServicePrice, DocumentNumberService, BusinessDay).

**Key aggregates and entities:**
- `PatientInvoice` — PENDING → PARTIALLY_PAID → PAID / CANCELLED; holds N `PatientInvoiceDetail` lines each carrying a `Money amount`, `coverage_status` (UNPAID / COVERED / VERIFIED), and a back-reference to the originating charge kind (REGISTRATION / CONSULTATION / LAB / RADIOLOGY / PROCEDURE / PRESCRIPTION / WARD_DAY).
- `PatientBill` — per-charge line (UNPAID → PAID); written atomically in the caller's transaction via `billing.api.BillingCommands.recordClinicalCharge(ChargeRequest, TxAuditContext)`.
- `PatientPayment` — one record per cashier payment event; holds N `PatientPaymentDetail` rows allocating the collected amount to specific invoice lines. Payment modes: CASH, INSURANCE, DEBIT_CARD, CREDIT_CARD, MOBILE (mirrors PROCESS.md §11.3 and §15 PaymentType enum).
- `PatientCreditNote` — write-off (partial or full) against one invoice; numbered `PCN{yyyyMMdd}-{seq}` via `seq_pcn_no` (ADR-0009).
- `CashierShift` — OPEN → CLOSED; one shift per cashier per business day; `closeShift()` computes collected totals and locks the shift for reconciliation.
- `ServicePrice` — `(plan_uid nullable, kind, service_uid, currency, amount NUMERIC(19,2))` pricing matrix; `PriceLookup.resolve(patientUid, kind, serviceUid)` applies plan-specific price with cash fallback (PROCESS.md §14 pricing / insurance management).
- `settled` flag on `patient_invoice` — boolean written by `SettlementDispatcher` after a PAID/PARTIALLY_PAID transition; crossing the boundary in the **billing → encounter** direction only (ADR-0008 §4 and §6).
- `InsuranceClaim` (`insurance` context) — the claim ledger for insured patients: SUBMITTED → SETTLED / REJECTED, aggregating the covered invoice lines for a payer, with claim-reference numbering and reconciliation against payer remittance (PROCESS.md §11.4 insurance routing; carried forward from prior-build V70). Eligibility (`covered` on `ServicePrice`) gates routing a line to the plan vs. cash. Increment 10 (Reporting) consumes this aggregate (open-claims dashboard), so it must exist here, before 10.

**Key REST endpoints (all prefixed `/api/v1`):**
- `GET  /billing/invoices?patientUid={uid}&status=PENDING` — cashier queue
- `GET  /billing/invoices/uid/{uid}` — invoice detail with line breakdown
- `POST /billing/invoices/uid/{uid}/payments` — record a payment; body carries mode, amount, optional per-line allocations
- `GET  /billing/invoices/uid/{uid}/receipt` — printable POS receipt (HTML/print CSS; ADR-NEW-C browser-print approach)
- `POST /billing/credit-notes` — create credit note (write-off)
- `GET  /billing/credit-notes/uid/{uid}` — credit note detail
- `POST /billing/shifts` — open cashier shift
- `PUT  /billing/shifts/uid/{uid}/close` — close shift; triggers EOD reconciliation snapshot
- `GET  /billing/shifts/uid/{uid}/collections` — EOD collections report for a closed shift (per-mode breakdown: cash collected vs. billed)
- `GET  /billing/service-prices` — list; `POST /billing/service-prices` — create/update price row (admin only)

**Process states/flows from PROCESS.md §11:**
- Charge accrual at clinical-event time (§11.1); invoice lifecycle PENDING → PARTIALLY_PAID → PAID / CANCELLED (§11.2); payment with per-line allocation (§11.3); insurance routing / plan-price override (§11.4); credit note write-off (§11.7); refund via signed `PatientPaymentDetail` amount (§11.8 — no separate Refund entity); collections reconciliation at end of day (§11.6).

## Dependencies

- **Increment 00 (Walking Skeleton & Shared Kernel)** — `AuditableEntity`, ULID `uid`, `Money`, `TxAuditContext`, `DocumentNumberService` with `seq_pcn_no`, `BusinessDay`/`BusinessDayService`, Flyway baseline, `ApplicationModules.verify()` gate, CI pipeline. All directly consumed by the billing module.
- **Increment 01 (Identity & Access)** — the `iam` module for `@PreAuthorize` enforcement (`BILL-A`, `CASHIER-ACCESS`, `ADMIN-ACCESS`, and the billing-specific codes seeded in V2).
- **Increment 02 (Master Data & Reference Seeding)** — the seeded `ServicePrice` matrix (cash + plan rows), `InsuranceProvider`/`InsurancePlan`, `Currency`, `CompanyProfile`. Billing cannot price a charge without a loaded pricing matrix.

This increment is **built before 03 (Registration) and 05 (Clinical/OPD)**, which are *downstream consumers*: registration's registration-fee invoice and clinical's consultation-fee invoice both call `billing.api.recordClinicalCharge()` synchronously in one transaction (ADR-0008 §4). Build order: `00 → 01 → 02 → 04 → 03 → 05`.

## Exact-process fidelity targets

**Invoice lifecycle (PROCESS.md §11.2, §15 PatientInvoice states):**
- A `PatientInvoice` starts PENDING (first `PatientBill` line appended). It transitions to PARTIALLY_PAID when `totalPaid < totalAmount` and any payment exists. It transitions to PAID when `totalPaid >= totalAmount`. CANCELLED is the only write-off terminal state (set by credit note covering the full balance). No other terminal state exists in the legacy.
- Golden-master assertion: create an invoice of 10,000 TZS; pay 6,000 → status must be PARTIALLY_PAID, `balance = 4,000.00`; pay 4,000 more → status must be PAID, `balance = 0.00`. Values must round to 2 dp (ADR-0009 §4 parity definition).

**Pricing / insurance math (PROCESS.md §11.4, §14, ADR-0009 §2):**
- `PriceLookup.resolve(patientUid, kind, serviceUid)` returns `ServicePrice.amount` for the patient's active insurance plan if a row exists for `(planUid, kind, serviceUid)`; else falls back to the cash price row `(planUid = null, kind, serviceUid)`. If **neither** a plan-specific nor a cash row exists, the default is to **throw `ServicePriceNotFoundException` → ProblemDetail `urn:hmis:error:service-price-not-found`**; a per-deployment flag may instead apply a 0/default charge. Per PROCESS.md §16.4 this is configurable (deny vs default); `legacy-analyst` confirms the production default before parity fixtures are frozen. This is the **same** behaviour stated in increment 02 (see build-plan.md → Exact-process governance).
- Golden-master: seed a cash price of 5,000 and a plan-X override of 3,500 for LAB kind. Invoice a plan-X patient → assert line amount = `3,500.00 TZS`. Invoice a CASH patient → assert `5,000.00 TZS`. All arithmetic via `BigDecimal`, `RoundingMode.HALF_UP` at persistence (ADR-0009 §2).

**Document numbering (ADR-0009 §5, legacy-findings.md §C):**
- Credit note: `PCN{yyyyMMdd}-{nextval(seq_pcn_no)}` e.g. `PCN20260602-1`. Generated in one atomic step (nextval then insert); no double-save. Date reflects EAT (`Africa/Dar_es_Salaam`) calendar date.
- Receipt number (for the POS receipt printout): use `PatientPayment.uid` as the receipt reference; no separate document sequence is defined for receipts in the legacy — the payment record uid is the receipt anchor.
- Golden-master: concurrent credit-note creation by two cashiers must produce two distinct `PCN…` values (Testcontainers concurrency test, ADR-0017 §3).

**Pay-before-service hard gate (PROCESS.md §11, lessons brief M3/M13/M23):**
- `SettlementDispatcher` writes `patient_invoice.settled = true` (and/or the per-owning-entity flag, e.g. `clinical_order.settled`) immediately when invoice status transitions to PAID. It does NOT write this from an async event — it runs in the same transaction that committed the final payment (billing → encounter direction, ADR-0008 §6).
- Clinical modules (lab, radiology, procedure, pharmacy) must read only the local `settled` flag; they must never call into `billing.api` to check. This is verifiable by the `ApplicationModules.verify()` gate from day one.
- Golden-master: attempt `accept()` on a CASH patient's lab order whose invoice is PENDING → expect `422 Unprocessable Entity` with `ErrorCode = PAY_BEFORE_SERVICE`. Confirm the gate passes once the invoice is PAID.

**Cashier shift and end-of-day collections reconciliation (PROCESS.md §11.6):**
- A `CashierShift` must be OPEN before any payment can be recorded. If no shift is open, the payment endpoint returns `409 Conflict`, `ErrorCode = NO_OPEN_SHIFT`.
- `closeShift()` snapshots per-payment-mode totals (`cash_collected`, `mobile_collected`, `card_collected`, `insurance_collected`) and per-invoice-kind revenue breakdown into the shift record. The collections report endpoint projects this snapshot; it does not re-aggregate live data after close (audit immutability).
- Golden-master: open shift → record 3 cash payments and 2 mobile payments → close shift → assert collections report matches the sum of payments by mode to 2 dp.

**Refund via signed amounts (PROCESS.md §11.8):**
- There is no `Refund` entity. Overpayment is reversed by posting a `PatientPaymentDetail` with a negative `amount` on the same or subsequent payment. The invoice's `totalPaid` recomputes correctly. Assertion: pay 12,000 on a 10,000 invoice → post −2,000 refund detail → `totalPaid = 10,000.00`, status = PAID, balance = 0.

## Prior-attempt pitfalls to avoid

- **M3 / M13 / M23 / DIAG-2 / PHARM-2 — settlement as filter, not gate.** The prior build allowed CASH patients' orders to appear in worklists with `settled=false`. This increment must encode `settled` as a hard precondition on every downstream `accept()` transition, not a UI filter. The `SettlementDispatcher` pattern (billing writes the flag, clinical reads it) must be in place and tested before Increment 03 (consultation) and Increment 05 (lab/radiology/procedures) are built.
- **BILL-1 — no POS receipt.** The prior build showed only an on-screen banner after payment. This increment must deliver a printable POS receipt from day one (HTML with `@media print` CSS; endpoint `GET /billing/invoices/uid/{uid}/receipt`). The receipt must surface invoice number (uid reference), patient MR number, payment mode, amount, cashier name, and shift date.
- **BILL-2 — no collections / cash-up report.** The prior build had raw data but no endpoint or screen. The EOD collections endpoint and Angular screen are in-scope for this increment, not deferred.
- **BILL-3 / BILL-4 — inpatient till and direct-pending cash queue.** The cashier queue must be scoped: OUTPATIENT/OUTSIDER CASH patients show PENDING invoices by default; INPATIENT patients are surfaced on a separate inpatient-payment screen. The `GET /billing/invoices` query must accept `patientClass` (OUTPATIENT / INPATIENT / OUTSIDER) and `paymentType` (CASH / INSURANCE / …) as filter parameters.
- **Legacy-findings.md §C — `MAX(id)+1` race for PCN numbers.** The fresh build uses `seq_pcn_no` via `DocumentNumberService.next(PCN)` — one atomic nextval call, no double-save.
- **M23 — ward-day accrual must not be deferred to discharge.** Although the `WardDayAccrualJob` (JOB-001, ADR-0018) is the inpatient-billing scheduler, its output — `PatientBill` lines — flows through the same `BillingCommands.recordClinicalCharge` pipeline. The billing module must support batch accrual insertion correctly so Increment 06 (inpatient) can wire the scheduler without structural change.

## Lead & supporting agents

- **Lead:** backend-engineer (module design, API, domain logic, Flyway sequences)
- **Supporting:** healthcare-domain-expert (settlement gate semantics, insurance pricing logic, shift/collections reconciliation process), solution-architect (ADR-0008/0009/0017 enforcement, module boundary review), data-architect (ServicePrice schema, `PatientInvoiceDetail` coverage_status column design), security-architect (RBAC: billing privilege codes seeded in V2, `@PreAuthorize` on all endpoints), ux-ui-designer (cashier queue screen, POS receipt print layout, EOD collections screen, credit-note dialog), frontend-engineer (Angular standalone + signals billing screens, OpenAPI-generated client), qa-test-engineer (golden-master parity suite: pricing math, invoice status transitions, settlement gate, concurrent PCN numbering, EOD reconciliation), code-reviewer (PR gate)
- **Informed:** engagement-lead, business-analyst, legacy-analyst (confirm `Collection` entity semantics and refund-via-signed-amount pattern), devops-engineer (ShedLock table Flyway migration needed before Increment 06 ward-day accrual)

## Definition of Done

- [ ] `PatientInvoice` lifecycle (PENDING → PARTIALLY_PAID → PAID / CANCELLED) enforced in domain aggregate; no direct status setter callable from outside billing module.
- [ ] `BillingCommands.recordClinicalCharge` participates in caller's transaction (`REQUIRED` propagation); no `REQUIRES_NEW`; no async event boundary.
- [ ] `SettlementDispatcher` writes `settled = true` in the same transaction as PAID transition; `ApplicationModules.verify()` confirms billing → encounter is the only allowed direction for this write.
- [ ] `PriceLookup.resolve()` returns plan-specific price with cash fallback; unit-tested for plan-match, cash-fallback, and missing-both (throws `ServicePriceNotFoundException` by default; the 0/default fallback flag is covered) — consistent with increment 02.
- [ ] All monetary values persisted as `NUMERIC(19,2)`, `RoundingMode.HALF_UP`; no `double` anywhere in the billing module.
- [ ] PCN document number: `PCN{yyyyMMdd}-{seq}` generated by `seq_pcn_no`; concurrent-creation Testcontainers test produces unique numbers.
- [ ] `CashierShift` OPEN/CLOSED lifecycle enforced; payment endpoint returns `409 NO_OPEN_SHIFT` if no open shift exists.
- [ ] EOD collections report (`GET /billing/shifts/uid/{uid}/collections`) returns per-mode totals matching sum of payments in Testcontainers integration test.
- [ ] POS receipt endpoint (`GET /billing/invoices/uid/{uid}/receipt`) returns printable HTML with patient MR, invoice reference, mode, amount, cashier, and shift date; rendered by Angular `@media print` layout.
- [ ] Pay-before-service hard gate: golden-master test asserts `422 PAY_BEFORE_SERVICE` on `accept()` of a CASH patient's lab order (stub) with an unpaid invoice; clears once invoice is PAID.
- [ ] Cashier queue (`GET /billing/invoices`) supports `patientClass` and `paymentType` filters; OUTPATIENT/OUTSIDER CASH defaults return only PENDING invoices; INPATIENT scope separated.
- [ ] All billing endpoints protected by correct privilege codes (`BILL-A`, `CASHIER-ACCESS`, etc.) matching the V2 seed migration; `@PreAuthorize` verified by authorization parity test.
- [ ] RFC 7807 `ProblemDetail` returned for all error paths (`PAY_BEFORE_SERVICE`, `NO_OPEN_SHIFT`, `STALE_ENTITY`); Angular error handler reacts programmatically via `ErrorCode`, not string matching.
- [ ] Audit events emitted on invoice status change and payment recording; `businessDayId` stamped via `TxAuditContext` on every write.
- [ ] OpenAPI spec updated; Angular OpenAPI-generated client regenerated and consumed by billing screens.
- [ ] All Testcontainers integration tests green; `ApplicationModules.verify()` green; ArchUnit rules green (no billing entity imported outside billing module, no encounter → billing call).
- [ ] Code-reviewer PR approval obtained; no HIGH-severity findings unresolved.
