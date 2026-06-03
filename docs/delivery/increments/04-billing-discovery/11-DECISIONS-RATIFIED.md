# Increment 04 (Billing) — Ratified Decisions

**Status:** RATIFIED by the engagement owner (user) 2026-06-03. Binding over the build spec
([00-build-spec.md](00-build-spec.md)). Legacy = source of truth; the spec drifted heavily (billing is
largely a redesign) so most "modern" billing features are net-new and gated below.

## The four sign-off decisions
| CR | Decision |
|---|---|
| **CR-01/02 Payment model** | **Faithful core now; partial-payments deferred.** Build the legacy full-payment-only core: `PatientInvoice {PENDING, APPROVED}`, full-bill payment (exact tender via `BigDecimal.compareTo==0`), per-detail `status=PAID` + running `amount_paid`, credit-note refunds. NO partial payment, NO per-line allocation, NO balance tracking, NO PARTIALLY_PAID/PAID/CANCELLED header lifecycle in this increment. The richer lifecycle is additive later via a separate CR. |
| **CR-05 Pay-before-service gate** | **Build it, SCOPED.** Net-new hard gate (legacy had only a UI filter — prior-build defect). `SettlementDispatcher` writes a `settled` flag in the SAME tx as the PAID transition (billing→encounter direction only); `accept()` raises 422 `PAY_BEFORE_SERVICE`. **Scope: CASH outpatient/outsider only**; inpatient (settles at discharge) + emergency EXEMPT; COVERED auto-passes. (Blanket gate rejected — patient-safety hazard.) In inc-04 the flag lives on the billing entities + a stub test proves the mechanism; clinical-side wiring is inc-05/06. |
| **CR-04 CashierShift** | **Read-time collections report only; defer shifts.** Ship the legacy-faithful read-time EOD collections report (per-mode/per-cashier `SUM GROUP BY`). DEFER the net-new `CashierShift` OPEN/CLOSED, `NO_OPEN_SHIFT` 409, and frozen snapshot. **Payment is NOT gated on an open shift.** |
| **CR-06 InsuranceClaim** | **Defer the ledger.** Build the PENDING-invoice covered-line accumulator now (faithful, part of the pricing engine). DEFER the net-new SUBMITTED/SETTLED/REJECTED claim ledger + numbering + remittance to a dedicated increment before inc-10 (Reporting). |

## Applied defaults (faithful/clear — recorded, not asked)
- **Refund = legacy credit-note instrument** (reject the spec's signed-amount `PatientPaymentDetail`; legacy has no amount column and credit-notes are NHIF-presentable). CR-03 rejected.
- **Fix two clinically-harmful legacy bugs** (NOT exact-process — reproducing data corruption/PHI loss is indefensible): CR-10 the `j=j++` always-delete-parent-invoice bug → delete parent only when zero details remain; CR-13 standardize the soft-flag cancel (set `bill.status=CANCELED` + `PatientPaymentDetail.status=REFUNDED`, do NOT hard-delete paid+cancelled charges) across ALL kinds + add the omitted procedure-cancel credit note.
- **CR-14** drop dead `amount_allocated`/`amount_unallocated`; `patient_invoice_details.patient_bill` NOT NULL.
- **CR-09 DocumentNumberService** — `PCN{EAT-yyyyMMdd}-{nextval(seq_pcn_no)}`, one atomic nextval+insert (fixes legacy MAX(id)+1 race; format-preserving; EAT `Africa/Dar_es_Salaam`). Per-document-TYPE keying.
- **CR-12** exact-tender via `BigDecimal.compareTo==0` on scaled NUMERIC(19,2) (replaces legacy `double ==`).
- **CR-15** pricing routes on `covered=true` only; `active` inert (legacy-faithful).
- **CR-08** PaymentMode = `{CASH, INSURANCE}` only (legacy live values); DEBIT/CREDIT/MOBILE deferred.
- **CR-11 Ward top-up** — build the self-link schema plumbing (`principal_bill_id`/`supplementary_bill_id`) now; DEFER the selection+guard logic to inc-06 (the only co-pay path is unreachable in legacy — resolve intent vs dead-path then). Ward charge logic itself is inc-06.
- **Gates** (35 codes only): `BILL-A` for billing endpoints (view/pay/credit-note/collections); `ADMIN-ACCESS` for service-price + cashier master + day-close; **`CASHIER-ACCESS` is INVENTED → map to `ADMIN-ACCESS`**. `recordClinicalCharge` is an in-process module API (trusted caller, NOT `@PreAuthorize`-gated).
- **Consultation hard-fail** message verbatim: "Plan not available for this clinic. Please change payment method" (→ 422 `urn:hmis:error:plan-not-available-for-clinic`).

## Build chunking (each independently `clean verify`-able; Flyway V15+)
- **P1 — Billing core + PRICING engine §2** (no CR blockers): domain (PatientBill, PatientInvoice, PatientInvoiceDetail, PatientPayment, PatientPaymentDetail, Collection) + enums + repos + `BillingChargeService` (two-step cash→insurance override; fallback asymmetry: consultation hard-fail / lab-rad-proc-med inpatient-VERIFIED / registration-silent; medicine qty×price HALF_UP; ward top-up plumbing only) + `BillingCommands.recordClinicalCharge` (REQUIRED propagation, TxAuditContext) + PaymentService (full-payment PARITY, exact-tender, Collection write, side-effects) + PENDING-invoice accumulator. Consumes `masterdata::lookup` PriceLookup + `iam::lookup`. **Flyway V15–V17.**
- **P2 — DocumentNumberService + credit notes + refund** (CR-09/CR-13): `next(type)` on `seq_pcn_no`; credit-note on cancel; standardized soft-flag refund; fix CR-10/CR-13 bugs.
- **P3 — EOD collections report + POS receipt** (PARITY + BILL-1 hardening). No CashierShift.
- **P4 — settled flag + scoped pay-before-service gate** (CR-05): SettlementDispatcher (same-tx, billing→encounter), scoped 422; stub test. Flyway V18.

Test plan = build-spec §7.2 (golden-master pricing/§2.3 fallback/exact-tender/PCN-concurrency/scoped-gate/EOD). Modulith: billing exposes `billing.api` only; consumes masterdata::lookup + iam::lookup; no reverse edges; no async.
