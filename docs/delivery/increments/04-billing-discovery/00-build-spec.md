I have full grounding. Now I'll synthesize the authoritative build spec. This is the build contract; I'm presenting it as my final return value.

---

# INCREMENT 04 — BILLING / CASHIERING / INSURANCE: AUTHORITATIVE BUILD SPEC

**Author:** solution-architect | **Status:** Implementation contract for backend-engineer | **Package:** `com.otapp.hmis.billing` (+ `com.otapp.hmis.insurance`) | **Migrations:** V15+ | **Money:** `Money` VO / `NUMERIC(19,2)` HALF_UP, NO `double`

This spec reconciles the inc-04 spec doc against the five legacy extractions + four design outputs (healthcare-domain-expert, data-architect, security-architect, business-analyst). Where spec and legacy disagree, **legacy is source of truth** and the divergence is gated behind an engagement-lead change request (§9). PARITY = build now; [GATED:CR-xx] = build only after sign-off.

---

## 0. GROUNDING FACTS (verified against repo, not assumed)

- Next migration = **V15** (V1–V14 applied). `seq_pcn_no` exists (V13). `BILL-A` is seeded (V2:53); `CASHIER-ACCESS` is NOT seeded — INVENTED.
- Shared kernel present and to be consumed verbatim: `AuditableEntity` (BIGINT `id` no-getter + ULID `uid`), `Money` (`Money.of(BigDecimal)`, HALF_UP, scale 2), `TxAuditContext(dayUid, timestamp, actorUsername)`, `BusinessDayService.currentDay()/currentUid()`, `ErrorCode` enum (RFC7807), `AuditRecorder`/`AuditLog`.
- `PriceLookup.resolve(planUid, kind, serviceUid, currency) → ServicePriceResult(amount, covered, planUid, kind, serviceUid, currency, minAmount, maxAmount)` is **storage-tier only**; the resolve-TIME billing logic (§2) is THIS increment's job.
- `ServiceKind` = `{REGISTRATION, CONSULTATION, LAB_TEST, MEDICINE, PROCEDURE, RADIOLOGY, WARD}` (7 values; ward is per-stay).
- `Cashier` already lives in `iam.domain` (personnel extension, auto-provisioned on CASHIER role) — billing does NOT own it; cashier attribution = the logged-in `User` (`createdBy`), per legacy.
- `DocumentNumberService` does **NOT** exist yet — build it in this increment.
- Named-interface pattern: implementation package-private in `*.application`, interface + projection records in `*.lookup` (or `*.api`) annotated `@NamedInterface`.

---

## 1. ENTITY / TABLE / DTO / MAPPER PLAN

All entities extend `AuditableEntity` (inherits `id`, `uid`, `createdAt/By`, `updatedAt/By`, `version`). Lombok `@Getter @NoArgsConstructor(access=PROTECTED)`; state changes via intention-revealing domain methods (no public setters). MapStruct mappers **package-private in `billing.application`**; **no `id` in any DTO**; uid is the only external identifier; money fields map via the shared `MoneyMapper`. Cross-module refs are loose `VARCHAR(26)` uids with **NO FK** (`patient_uid`, `plan_uid`, `cashier_user_uid`); intra-module/intra-DB refs DO get real FKs.

### 1.1 Entities (package layout)

```
com.otapp.hmis.billing
├─ domain/        PatientBill, PatientInvoice, PatientInvoiceDetail,
│                 PatientPayment, PatientPaymentDetail, PatientCreditNote,
│                 Collection, CashierShift[GATED:CR-04]  + repositories + enums
├─ application/   BillingChargeService, PaymentService, CreditNoteService,
│                 CashierShiftService[GATED], CollectionReportService,
│                 SettlementDispatcher, DocumentNumberService,
│                 *Mapper (package-private), *Dto/*Request (no id)
├─ api/           @NamedInterface "api": BillingCommands, ChargeRequest, ChargeResult,
│                 SettlementView (read projection)
└─ web/           PatientBillController, PaymentController, CreditNoteController,
                  CashierShiftController[GATED], CollectionReportController, ReceiptController

com.otapp.hmis.insurance   [GATED:CR-06 — recommend DEFER out of inc-04]
```

### 1.2 Entity field map (PARITY baseline — legacy-exact)

| Entity → table | Key fields (money = `Money` embeddable / NUMERIC(19,2); qty = NUMERIC(19,6)) | Status vocab (CHECK) | Notes |
|---|---|---|---|
| **PatientBill** → `patient_bills` | `patient_uid`(loose), `bill_item`(VARCHAR60 dflt 'NA'), `description`(NOT BLANK), `kind`(ServiceKind), `qty`, `amount`, `paid`, `balance`, `status`, `payment_type`, `membership_no`, `plan_uid`(loose), `principal_bill_id`(self-FK), `supplementary_bill_id`(self-FK), `business_day_uid` | `UNPAID, VERIFIED, COVERED, PAID, NONE, CANCELED` (single-L, legacy spelling) | The atomic charge + cash gate. `kind` normalizes legacy free-text inference. Invariant `paid+balance=amount` (enforced in code). |
| **PatientInvoice** → `patient_invoices` | `no`(unique), `patient_uid`, `plan_uid`(NULL=cash), `status`, `amount_paid`(running sum, never decremented in PARITY), `business_day_uid` | PARITY: `PENDING, APPROVED`. [GATED:CR-01]: `PENDING, PARTIALLY_PAID, PAID, CANCELLED` | Insurance-claim accumulator (one PENDING per patient+plan). DROP dead `amount_allocated/amount_unallocated` (CR-14). |
| **PatientInvoiceDetail** → `patient_invoice_details` | `patient_invoice_id`(FK), `patient_bill_id`(FK, UNIQUE — de-facto NOT NULL), `description`, `qty`, `amount`, `status`(NULL\|PAID), `coverage_status`(UNPAID\|COVERED\|VERIFIED) | detail.status ∈ {PAID, NULL} | `coverage_status` = denormalized snapshot of bill coverage at attach (spec §12). `ON DELETE CASCADE` from invoice (orphanRemoval). |
| **PatientPayment** → `patient_payments` | `patient_uid`(nullable; legacy has none), `amount`(tendered total), `payment_type`, `status`, `business_day_uid`, `cashier_shift_id`(nullable FK [GATED:CR-04]) | `RECEIVED` | uid = receipt anchor (no separate receipt sequence). |
| **PatientPaymentDetail** → `patient_payment_details` | `patient_payment_id`(FK), `patient_bill_id`(FK, OneToOne), `description`, `status`, `amount`(NULLABLE — legacy has none; populated only [GATED:CR-03]) | `RECEIVED, REFUNDED` | NO `CHECK(amount>=0)` — signed refund needs negatives if CR-03 ratified. |
| **PatientCreditNote** → `patient_credit_notes` | `no`(unique, PCN…), `patient_uid`(nullable), `amount`(full bill amount), `reference`(cause label), `status`, `patient_bill_uid`(NEW nullable traceability, no FK), `business_day_uid` | `PENDING` (legacy never transitions) | Standalone refund instrument; no FK to bill/invoice in legacy. |
| **Collection** → `collections` | `patient_uid`, `patient_bill_id`(nullable FK), `amount`, `item_name`(dflt 'NA'), `payment_channel`(dflt 'Cash'), `payment_reference_no`(dflt 'NA'), `business_day_uid`, `cashier_shift_id`(nullable FK [GATED]) | none (write-once) | The REAL legacy cash-up source; attributed to `created_by` USER. |
| **CashierShift** → `cashier_shifts` [GATED:CR-04] | `cashier_user_uid`(loose), `business_day_uid`, `status`, `opened_at`, `closed_at`, per-mode snapshot cols | `OPEN, CLOSED` | Net-new. Partial-unique index: one OPEN per (cashier, day). |

### 1.3 Enums (Java, `@Enumerated(STRING)`, VARCHAR+CHECK in DB)

- `BillStatus { UNPAID, VERIFIED, COVERED, PAID, NONE, CANCELED }` — model ALL SIX (HDE §1d: `NONE` and `VERIFIED` are behaviourally load-bearing; do not collapse).
- `PaymentMode { CASH, INSURANCE }` PARITY. [GATED:CR-08] add `DEBIT_CARD, CREDIT_CARD, MOBILE`.
- `InvoiceStatus { PENDING, APPROVED }` PARITY. [GATED:CR-01] `{ PENDING, PARTIALLY_PAID, PAID, CANCELLED }`.
- `PaymentDetailStatus { RECEIVED, REFUNDED }`; `CreditNoteStatus { PENDING }`; `CoverageStatus { UNPAID, COVERED, VERIFIED }`.

### 1.4 DTOs / Requests (no `id`; uid only)
`PatientBillDto`, `PatientInvoiceDto` (+ nested `PatientInvoiceDetailDto`), `PatientPaymentDto`, `RecordPaymentRequest` (selected bill uids + tendered `MoneyDto` + mode), `CreditNoteDto`, `CreateCreditNoteRequest`, `CollectionReportRow` (itemName, amount, paymentChannel), `ReceiptDto`. Cross-module `ChargeRequest`/`ChargeResult` live in `billing.api` (§4).

---

## 2. PRICING ENGINE (§2.3) — RESOLVE-TIME ALGORITHM (PARITY, SAFETY-CRITICAL)

Implemented in `billing.application.BillingChargeService`, invoked by `BillingCommands.recordClinicalCharge` (§4). **Consumes `masterdata::lookup.PriceLookup.resolve(...)` for plan-covered AND cash-fallback prices** (PriceLookup already does cash-fallback as its storage tier). The catalog cash price is reachable via the same `resolve(null, kind, serviceUid, currency)` call (plan_uid NULL row) — billing does NOT read catalog entities directly. The COVERED override, VERIFIED-fallback, consultation hard-fail, registration-silent, and ward top-up are billing's resolve-TIME logic layered on top.

### 2.1 Two-step build (cash-first, then insurance override) — universal shape

Cites `PatientServiceImpl.java:821-849` (lab exemplar); identical per kind.

```
recordCharge(kind, serviceUid, patientUid, planUid, paymentType, qty, isInpatient, ctx):
  currency = "TZS"
  # STEP 1 — always build at CASH first
  cashRow = priceLookup.resolve(null, kind, serviceUid, currency)   # plan_uid NULL row
  cashAmount = (kind == MEDICINE) ? cashRow.amount * qty (HALF_UP, 2dp) : cashRow.amount
  bill = new PatientBill(patient=patientUid, kind, billItem=labelFor(kind),
                         qty, amount=cashAmount, paid=0, balance=cashAmount,
                         status=UNPAID, paymentType=CASH)            # :821-835
  # REGISTRATION special: if cashAmount == 0 -> status = VERIFIED (exemption/waiver path, :276)
  if kind == REGISTRATION and cashAmount.signum()==0: bill.status = VERIFIED

  # STEP 2 — insurance override, gated on coverage attempt
  coverageAttempt = paymentType == INSURANCE || isInpatient        # :837
  if coverageAttempt:
     coveredRow = priceLookup.resolve(planUid, kind, serviceUid, currency)  # returns covered hit OR cash fallback
     if coveredRow.covered:                                         # true insurance hit
        planAmt = (kind == MEDICINE) ? coveredRow.amount * qty (HALF_UP) : coveredRow.amount
        bill.override(amount=planAmt, paid=planAmt, balance=0,
                      status=COVERED, paymentType=INSURANCE,
                      plan=planUid, membershipNo)                    # :842-849 — the SAME 5 fields every kind
        attachToInsuranceInvoice(patientUid, planUid, bill, coverage=COVERED)  # PENDING invoice per (patient,plan)
     else:
        applyNotCoveredFallback(kind, bill, isInpatient)            # §2.2 asymmetry
```

**PriceLookup vs catalog cash price (explicit):** Both tiers come from `PriceLookup`. Step 1 calls `resolve(null, …)` → the `service_prices` cash row (`plan_uid IS NULL`), which mirrors the legacy catalog `*.price` column migrated into the matrix in inc-02. Step 2 calls `resolve(planUid, …)`; `coveredRow.covered==true` is the insurance hit (plan price), `covered==false` means it fell through to cash → treat as NOT-COVERED and run §2.2. Routing keys on `covered=true` ONLY; `active` is inert (CR-15, PARITY).

### 2.2 Per-service not-covered fallback ASYMMETRY (PRESERVE ALL THREE — HDE §1b)

```
applyNotCoveredFallback(kind, bill, isInpatient):
  switch kind:
    CONSULTATION:                                                   # :599-601 HARD FAIL
       throw BusinessRule(PLAN_NOT_AVAILABLE_FOR_CLINIC,
           "Plan not available for this clinic. Please change payment method")  # verbatim; tx rolls back, no bill persists
    LAB_TEST | RADIOLOGY | PROCEDURE | MEDICINE:                    # :912-918
       if isInpatient:
          bill.status = VERIFIED                                    # cash price kept; attach to NULL-plan PENDING invoice
          attachToCashInvoice(bill, coverage=VERIFIED)
       else:
          # non-admitted insured: NEITHER branch fires -> bill stays cash UNPAID (silent)
          pass
    REGISTRATION:                                                   # :321 no-op
       pass                                                         # silent: stays UNPAID (or VERIFIED if regFee==0)
    WARD:
       see §2.3
```

Error mapping: `PLAN_NOT_AVAILABLE_FOR_CLINIC` → new `ErrorCode` (422, `urn:hmis:error:plan-not-available-for-clinic`), title carries the verbatim legacy message for parity.

### 2.3 Ward referral-override + top-up split [GATED:CR-11 — DO NOT freeze until intent vs dead-path ruled; ward billing is inc-06]

Cites `PatientServiceImpl.java:1797-1949`. Two design forms, both documented; **engagement-lead + HDE must choose** before inc-06 wires it:

- **Form A (reproduce legacy exactly):** selection query returns only the patient's own plan rows → `eligiblePlan.plan == patient.plan` always → the top-up guard (`:1880` `eligiblePlan.plan != patient.plan`) is **unreachable** → no co-pay ever emitted. Faithful but the only co-pay mechanism never fires (BLOCKER-CLINICAL-1: uncollected revenue).
- **Form B (documented intent — RECOMMENDED):** load covered ward-type rows; pick eligible covered tier; if `wardType.cashPrice − eligiblePlan.price > 0`, emit a SECOND `PatientBill` (amount=balance=difference, paid=0, status=UNPAID, billItem="Bed", description="Ward Bed / Room (Top up)"), bidirectionally self-linked via `principal_bill_id`/`supplementary_bill_id`, attached to a separate CASH invoice. The covered bill = COVERED at eligiblePlan.price.

The self-link columns + invoice plumbing are built now (schema is ready); the SELECTION + guard logic is deferred to inc-06 pending CR-11. Admission/bed activation transition split (`:1950-1963`) is inc-06 scope.

---

## 3. INVOICE / PAYMENT LIFECYCLE + BALANCE MATH + REFUND + CREDIT NOTE

### 3.1 Invoice lifecycle

**PARITY (build now):** header status ∈ `{PENDING, APPROVED}`. PENDING at creation; APPROVED batch-applied to a patient's prior PENDING invoices at the start of the next charge tx (`:586-590`) — a claim-batch close, NOT a payment event. Settlement tracked per-detail (`detail.status=PAID`) + running `amount_paid` sum (`PatientBillResource.java:341-349`). On cancel, delete the detail; delete the parent invoice **only when zero details remain** (CR-10 FIX — do not reproduce the `j=j++` always-delete bug; HDE BLOCKER-3: faithful reproduction corrupts multi-line claims).

**[GATED:CR-01]:** richer `{PENDING, PARTIALLY_PAID, PAID, CANCELLED}` lifecycle with header recompute. Only ratify jointly with CR-06 (insurance claim digitization) — do not bolt PARTIALLY_PAID on in isolation (HDE §4a, BA CR-04-01).

### 3.2 Payment recording + balance math (BigDecimal HALF_UP)

**PARITY (build now):** full-bill-only, per-bill (NOT per-invoice-line, NOT partial).
```
recordPayment(billUids[], tenderedTotal, mode, ctx):
  [GATED:CR-04] require an OPEN CashierShift for ctx.actor else 409 NO_OPEN_SHIFT
  payment = new PatientPayment(amount=tenderedTotal, status=RECEIVED, paymentType=mode, day=ctx.day)
  running = ZERO
  for billUid in billUids:
     bill = load(billUid) or throw NOT_FOUND
     if bill.status not in {UNPAID, VERIFIED}:
        throw BusinessRule(BILL_NOT_PAYABLE,
            "One or more bills have been paid/covered/canceled...")   # :295-296
     bill.markPaid()  # paid=amount, balance=0, status=PAID          # :305-307
     new PatientPaymentDetail(bill, payment, status=RECEIVED)         # no amount in PARITY
     new Collection(patient, bill, amount=bill.amount, itemName=bill.billItem,
                    paymentChannel="Cash", paymentReferenceNo="NA", day=ctx.day, createdBy=ctx.actor)  # :327-337
     if bill on insurance invoice: detail.status=PAID; invoice.amountPaid += detail.amount  # :341-349
     running = running.add(bill.amount)
     applyPaymentSideEffects(bill)   # admissions->IN-PROCESS+bed OCCUPIED; consult->SIGNED-OUT; pharmacy SO->APPROVED  :352-387
  # EXACT-TENDER guard (CR-12): replace legacy double != with compareTo on scaled NUMERIC(19,2)
  if tenderedTotal.compareTo(running) != 0:
     throw BusinessRule(PAYMENT_AMOUNT_MISMATCH, "...Insufficient payment")  # :389-391
  [GATED:CR-05] SettlementDispatcher.dispatch(paidBills)   # §4.2
```
No overpayment/change handling in PARITY. [GATED:CR-02] partial payment + per-line allocation adds the `amount` column to PatientPaymentDetail. [GATED:CR-03] signed-amount refund detail.

### 3.3 Refund + credit note

**PARITY (build now):** the credit note is the refund instrument (HDE §4b — auditable, NHIF-presentable; signed-detail is NOT legacy and is invisible to payer). On cancel of a PAID charge: create `PatientCreditNote(amount=bill.amount FULL, status=PENDING, reference=causeLabel, no=DocumentNumberService.next(PCN))`. Cancel behaviour — **standardize on the consultation soft-flag pattern for ALL kinds (CR-13 FIX, HDE BLOCKER-CLINICAL-4)**: set `bill.status=CANCELED`, flip the `PatientPaymentDetail.status=REFUNDED` (do NOT hard-delete bill/payment-detail — hard-delete erases PHI/audit evidence of an ordered+charged+refunded service, a regulatory defect). Fix the procedure-cancel PCN omission (CR-13/4d). `amount_paid` recompute-on-refund is [GATED:CR-03b] (legacy never decrements; reproducing = knowingly-wrong totals).

**[GATED:CR-01] CANCELLED write-off:** full-write-off → invoice CANCELLED only exists with the richer lifecycle; not in PARITY.

---

## 4. CROSS-MODULE API + SETTLEMENT GATE + DOCUMENT NUMBERING

### 4.1 `BillingCommands.recordClinicalCharge` (named interface `billing.api`)

```java
@NamedInterface("api")  // billing/api/package-info.java
public interface BillingCommands {
    ChargeResult recordClinicalCharge(ChargeRequest req, TxAuditContext ctx);
}
// ChargeRequest(patientUid, planUid, kind, serviceUid, qty, paymentType, inpatient) — uids only, no id
// ChargeResult(billUid, status, amount: MoneyDto, coverage)
```
- **Propagation REQUIRED** — runs in the caller's (Registration/Clinical) transaction. **No `@Async`, no `REQUIRES_NEW`** (atomic with the encounter). 
- **NOT a REST endpoint, NOT `@PreAuthorize`-gated** (security-architect): authz is enforced once at the caller's REST edge; the in-process Modulith call is trusted. Implementation package-private in `billing.application`.
- `createdBy` taken from `ctx.actorUsername()`, `business_day_uid` from `ctx.dayUid()` — preserves legacy attribution; threaded explicitly (no ThreadLocal, per `TxAuditContext` contract).
- Modulith verify must prove no module imports `billing.domain`; only `billing.api` + read projections are visible. Billing consumes `masterdata::lookup` + `iam::lookup`; **no reverse edges**.

### 4.2 Settled / pay-before-service gate [GATED:CR-05 — NET-NEW hardening, scoped]

Legacy has NO `settled` field and NO hard gate (Ext 2 §5 — UI filter only). If ratified:
- `SettlementDispatcher` (in billing) writes `settled=true` onto the downstream encounter/order **local** projection in the SAME tx as the PAID transition — **billing → encounter direction only** (ADR-0008 §6), no async, no reverse edge.
- Downstream clinical modules read ONLY their local `settled` flag; they NEVER call `billing.api` to check (enforced by `ApplicationModules.verify()` + ArchUnit).
- Gate raises `PAY_BEFORE_SERVICE` (422) on `accept()`. **MUST be scoped (HDE BLOCKER-CLINICAL-2):** ON for CASH outpatient/outsider only; OFF for inpatient (VERIFIED/UNPAID settle at discharge — blocking is a patient-safety hazard); COVERED auto-passes; emergency/unregistered bypass. A blanket gate must NOT be approved as written.
- `settled`/`settled_at` columns ship in V18 IF ratified; clinical-side local flags are inc-05/06 migrations.

### 4.3 `DocumentNumberService` (build now — PARITY format, concurrency-fixed)

```java
public interface DocumentNumberService { String next(DocumentType type); }
// PCN: "PCN" + EAT-yyyyMMdd + "-" + nextval(seq_pcn_no)   e.g. PCN20260603-7
```
- One atomic `nextval(seq_pcn_no)` + insert (no `Math.random()` placeholder, no MAX(id)+1, no double-save). Fixes the legacy collision race (CR-09); **format preserved**, so non-behavioural.
- **Key counters per document TYPE, not per prefix string** (CR-09 SPT-collision note).
- Date component = **EAT `Africa/Dar_es_Salaam`** per spec — this is a deliberate, small UTC→EAT deviation vs legacy (legacy emits UTC date in the number for 00:00–03:00 EAT events); flag in register, recommend approve.
- No zero-padding on the seq (legacy has none) unless data-architect rules otherwise.

---

## 5. CASHIER SHIFT + EOD COLLECTIONS + POS RECEIPT + GATE MAP

### 5.1 EOD collections report (PARITY — build regardless)
Read-time aggregation over `collections`, NOT a persisted snapshot (Ext 3 §4):
- General: `SUM(amount) GROUP BY (item_name, payment_channel)` over `collections` joined to `users`, date range `from.atStartOfDay() .. to.atStartOfDay().plusDays(1)` (inclusive of `to`). Aggregates across ALL users (not grouped by user despite selecting user).
- Per-cashier: same + `WHERE users.nickname = :nickname` (keyed on USER, not the `cashiers` table).
- SUM over BigDecimal NUMERIC(19,2) for bit-identical totals. Because `payment_channel` is always 'Cash' in legacy data, rows collapse to one channel (multi-mode = [GATED:CR-08]).
- Per-kind detail reports: FIX the boxed-`Long ==` id bug to `.equals()` (CR-04 EPIC4, latent legacy bug).

### 5.2 CashierShift + persisted EOD snapshot + NO_OPEN_SHIFT [GATED:CR-04 — NET-NEW]
No shift exists in legacy (Ext 3 §3); `Cashier` is iam personnel; payment never gated on open shift. If ratified: `CashierShift OPEN→CLOSED`, one OPEN per (cashier, day) via partial-unique index; payment with no open shift → 409 `NO_OPEN_SHIFT`; `closeShift()` freezes per-mode totals; report projects the snapshot, never re-aggregates. V19 ships the table + deferred FKs IF ratified.

### 5.3 POS receipt (NET-NEW, BILL-1 hardening) [PHI]
`GET /api/v1/billing/invoices/uid/{uid}/receipt` (or payment-uid anchored) → printable HTML (`@media print`): invoice reference (uid), patient MR number, payment mode, amount, cashier (logged-in user) name, business-day date. Receipt anchor = `PatientPayment.uid` (no separate receipt sequence in legacy).

### 5.4 RBAC gate map (only the 35 live codes; preserve legacy gates verbatim)

| Endpoint group | Recommended gate | Class |
|---|---|---|
| View invoices / cashier queue (`GET /billing/invoices…`) | `BILL-A` | NET-NEW (legacy ungated) |
| Record payment (`POST …/payments`) | `BILL-A` | RESTORE (legacy gate commented out) |
| POS receipt / view credit note | `BILL-A` | NET-NEW |
| Create credit note (billing endpoint) | `BILL-A`; preserve `PATIENT-*` where PCN is a side-effect of a clinical cancel | DEV-2 |
| EOD collections / cash-up report | `BILL-A` | NET-NEW (legacy ungated) |
| Cashier master-data save (`/cashiers/save`) | `ADMIN-ACCESS` | PRESERVE |
| Service-price / plan-price save | `ADMIN-ACCESS` | PRESERVE |
| Day close (`/days/end_day`) | `ADMIN-ACCESS, DAY-ACCESS` | PRESERVE |
| Cashier shift open/close [GATED:CR-04] | `ADMIN-ACCESS` | INVENTED |
| Insurance claim transitions [GATED:CR-06] | `BILL-A`/`ADMIN-ACCESS` | INVENTED |

- **USE `BILL-A`** (seeded V2:53). **`CASHIER-ACCESS` is INVENTED → do NOT use**; map any cashier gate to `ADMIN-ACCESS` (or `CASHIER_SERVICE-ACCESS` only if among the live 35 — security-architect to confirm). All transactional-surface gates are net-new hardening tied to CR-05; flag, do not present as parity. Authorization-parity test asserts every `@PreAuthorize` code is in the 35.

---

## 6. INSURANCECLAIM DECISION

**No `InsuranceClaim` entity exists in legacy** (Ext 5 §A — INVENTED). The de-facto claim = a PENDING `PatientInvoice` per (patient, plan), terminal-PENDING, COVERED bills write no Collection.

**Decision: BUILD the accumulator (PARITY) NOW; DEFER the SUBMITTED/SETTLED/REJECTED ledger out of inc-04 [GATED:CR-06].**
- PARITY (in §2 already): PENDING `PatientInvoice` per (patient, plan) accumulating COVERED detail lines. No submit/settle/reject, no claim numbering, no remittance.
- The settlement ledger (HDE §3 supports building it — it digitizes a real offline NHIF process) is net-new with no money-critical coupling to the cashiering core; inc-10 (Reporting) is the real consumer. Recommend a dedicated increment before inc-10, not inc-04. `insurance_claims`/`insurance_claim_lines` + `seq_claim_no` ship in V20 ONLY if CR-06 is ratified.

---

## 7. FLYWAY V15+ PLAN + TEST PLAN + MODULITH BOUNDARIES

### 7.1 Migrations (one concern per file; additive only; V1–V14 untouched)
| File | Content | Gate |
|---|---|---|
| **V15__billing_core.sql** | patient_bills, patient_invoices, patient_invoice_details, patient_payments, patient_payment_details (legacy-faithful core; drop dead alloc fields; fix detail nullability) | CORE |
| **V16__billing_credit_notes.sql** | patient_credit_notes (reuse `seq_pcn_no`) | CORE |
| **V17__billing_collections.sql** | collections (real reconciliation source) | CORE |
| **V18__billing_settlement_flag.sql** | `settled`/`settled_at` on patient_invoices + patient_bills | IF CR-05 |
| **V19__billing_cashier_shifts.sql** | cashier_shifts + deferred FKs on payments/collections | IF CR-04 |
| **V20__insurance_claims.sql** | insurance_claims + insurance_claim_lines + `seq_claim_no` | IF CR-06 |

Use data-architect's ratified DDL (named constraints `pk_/fk_/uq_/ck_/idx_`; loose-uid no-FK cross-module; partial indexes for collectable-queue and cash-pending). `ddl-auto=validate`. Every change reversible; data-migration-engineer reconciliation contract: row counts + `SUM(patient_invoice_details.amount)`, `SUM(collections.amount)`, `SUM(patient_payments.amount)`, and every COVERED bill has a detail. Dead `amount_allocated/amount_unallocated` NOT migrated.

### 7.2 Test plan (golden-master parity; Testcontainers + PostgreSQL 16)
1. **Pricing two-step (§2.1):** cash→COVERED override per kind; medicine `planPrice×qty` HALF_UP; plan-X LAB 3500 vs cash 5000.
2. **Fallback asymmetry (§2.2):** consultation HARD-FAIL verbatim message + tx rollback (no bill persists); lab/rad/proc/med inpatient→VERIFIED, outpatient→UNPAID; registration silent; regFee==0→VERIFIED.
3. **PriceLookup resolve:** plan-match / cash-fallback / missing-both→422 SERVICE_PRICE_NOT_FOUND.
4. **Payment (PARITY):** payable-status gate (BILL_NOT_PAYABLE), exact-tender `compareTo==0` (PAYMENT_AMOUNT_MISMATCH), Collection write, side-effects.
5. **Balance math [GATED:CR-01]:** 10000 → pay 6000 → PARTIALLY_PAID bal 4000 → pay 4000 → PAID bal 0 (2dp HALF_UP) — only after CR-01.
6. **Credit note / refund:** PCN format + concurrency (two cashiers, zero unique violations); cancel soft-flag pattern; full-amount PENDING note.
7. **Settlement gate [GATED:CR-05]:** 422 PAY_BEFORE_SERVICE on CASH outpatient lab accept; clears on PAID; inpatient/emergency NOT blocked; clinical reads local flag only.
8. **EOD collections (PARITY):** SUM GROUP BY (item_name, payment_channel) over date range; per-cashier by nickname; BigDecimal totals.
9. **Cashier shift [GATED:CR-04]:** NO_OPEN_SHIFT 409; close snapshot matches per-mode sums.
10. **Modulith:** `ApplicationModules.verify()` green; ArchUnit — no billing entity imported outside billing, no encounter→billing call, no `id` in DTOs, no `double` in billing.

### 7.3 Module boundaries
billing **exposes** `billing.api` (BillingCommands + read projections) only; **consumes** `masterdata::lookup` (PriceLookup) + `iam::lookup`. Downstream Registration(03)/Clinical(05) call `recordClinicalCharge` in their own tx. SettlementDispatcher writes billing→encounter only. No reverse edges; no async.

---

## 8. RECOMMENDED BUILD CHUNKING (each independently `mvn verify`-able; migrations from V15)

- **P1 — Core skeleton + PARITY payment** (no CR blockers): 6 core entities + Collection; bill/invoice/payment-detail enums (PARITY); full-bill-only payment, exact-tender guard (CR-12), Collection write, side-effects. **Flyway V15–V17.** Ships value, zero CR dependency.
- **P2 — Pricing engine §2 + BillingCommands.recordClinicalCharge** (no CR blockers; CR-15 minor): two-step build, COVERED override per kind, fallback asymmetry, medicine qty×price HALF_UP, PENDING-invoice-per-(patient,plan) accumulator. REQUIRED propagation. Exposes `billing.api` for inc-03/05. Includes CR-06a PARITY accumulator.
- **P3 — DocumentNumberService + credit notes + PARITY refund** (CR-09, CR-13): `next(type)` on `seq_pcn_no`, per-type keying, EAT date, concurrency test; credit-note creation on cancel; standardized soft-flag refund. Signed-amount only IF CR-03.
- **P4 — EOD collections (PARITY) + POS receipt (NET-NEW)** build regardless; **CashierShift + NO_OPEN_SHIFT + persisted snapshot + multi-mode** only after CR-04 + CR-08 (V19).
- **P5 — Settled flag + pay-before-service gate** [GATED:CR-05, V18]: SettlementDispatcher (same-tx, billing→encounter), scoped 422 PAY_BEFORE_SERVICE. Must land before inc-03/05 wire clinical accept().
- **P6 — InsuranceClaim ledger** [GATED:CR-06, V20]: recommend DEFER to a dedicated increment before inc-10.

RBAC applied incrementally per endpoint as each phase lands (35 codes only). CR-10 (invoice-delete fix) folds into P1/P3; CR-14 (model cleanup) into P1.

---

## 9. CONSOLIDATED DEVIATION / SIGN-OFF REGISTER (engagement-lead gates)

| CR | Topic | Legacy reality | Recommendation | Sign-off |
|---|---|---|---|---|
| **CR-01** | Invoice lifecycle PARTIALLY_PAID/PAID/CANCELLED | header only {PENDING, APPROVED}, delete-on-cancel | Approve jointly with CR-06 only | engagement-lead + HDE + data-architect |
| **CR-02** | Partial payment + per-line allocation | full-bill-only, no allocation, no `amount` col | Approve as net-new (adds column) | engagement-lead + data-architect |
| **CR-03** | Signed/negative refund detail | impossible (no amount col); refund=REFUNDED flag + PCN | Reject signed-detail; keep credit-note instrument | engagement-lead + HDE |
| **CR-04** | CashierShift + NO_OPEN_SHIFT + EOD snapshot | no shift; payment never gated; report is read-time | Net-new process change; ratify explicitly | engagement-lead + HDE |
| **CR-05** | Pay-before-service HARD gate | no settled field; UI filter only | Approve SCOPED (CASH-OP only; exclude inpatient/emergency) | engagement-lead + HDE |
| **CR-06** | InsuranceClaim SUBMITTED/SETTLED/REJECTED ledger | absent; claim=PENDING invoice | DEFER out of inc-04; build before inc-10 | engagement-lead + HDE |
| **CR-08** | Multi-mode PaymentType | only CASH+INSURANCE live; channel hardcoded 'Cash' | Approve only if client takes card/mobile today | engagement-lead + HDE |
| **CR-09** | DocumentNumberService (seq + EAT date) | MAX(id)+1, UTC date | Approve seq (format-preserving); flag UTC→EAT | engagement-lead + data-architect |
| **CR-10** | Invoice always-delete `j=j++` bug | always deletes parent on any detail removal | FIX (delete only when zero details) — clinically harmful | engagement-lead + HDE + qa |
| **CR-11** | Ward top-up dead path vs intent | guard unreachable → no co-pay ever | Implement intent (Form B); resolve before inc-06 | engagement-lead + HDE |
| **CR-12** | BigDecimal equality semantics | `double ==` | `compareTo==0` on scaled NUMERIC(19,2) | data-architect (pre-approved) |
| **CR-13** | Divergent cancel + procedure-PCN omission | consultation soft-flag vs lab/rad hard-delete; one PCN commented | Standardize on soft-flag+PCN for all; no hard-delete of PHI charges | engagement-lead + HDE + qa |
| **CR-14** | Dead fields + mapping cleanup | amountAllocated/Unallocated never written | Drop; set detail.patient_bill NOT NULL | data-architect + data-migration |
| **CR-15** | `active` flag at resolve time | only `covered` consulted | Reproduce (covered-only); active-gating is a CR | engagement-lead + HDE |
| **DEV-1** | `BILL-A` vs `BILL-ALL`/`BILL-CREATE` | seed uses `BILL-A` | USE `BILL-A` (confirmed seeded V2:53) | security-architect |
| **DEV-2** | `CASHIER-ACCESS` invented | not seeded; real = `CASHIER_SERVICE-ACCESS` | Map to `ADMIN-ACCESS` | security-architect |

**Four engagement-lead decision-blocking gates** before billing is design-complete: §2.3 ward top-up fidelity (CR-11), pay-before-service gate scope (CR-05), InsuranceClaim scope (CR-06), CashierShift scope (CR-04). The three spec golden masters (invoice balance math, signed-amount refund, cashier-shift/EOD snapshot) are ALL net-new and must NOT be frozen as parity until their CRs sign off. **P1–P3 (PARITY core + pricing + numbering/refund) carry zero CR risk and should start immediately**, unblocking inc-03/05.

---

### Relevant files (absolute)
- Spec: `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\04-billing-cashiering-core.md`
- Target module (empty): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\billing\package-info.java`
- Shared kernel consumed: `...\shared\domain\Money.java`, `...\shared\domain\TxAuditContext.java`, `...\shared\domain\AuditableEntity.java`, `...\shared\domain\BusinessDayService.java`, `...\shared\error\ErrorCode.java`
- Pricing contract consumed: `...\masterdata\lookup\PriceLookup.java`, `...\masterdata\lookup\ServicePriceResult.java`, `...\masterdata\lookup\ServiceKind.java`, `...\masterdata\application\PriceLookupImpl.java`
- IAM contract pattern: `...\iam\lookup\IamLookupService.java`, `...\iam\lookup\package-info.java`, `...\iam\domain\Cashier.java`
- Seeds/sequences: `...\db\migration\V13__masterdata_document_sequences.sql` (seq_pcn_no), `...\db\migration\V2__seed_iam.sql:53` (BILL-A)
- Proposed new migrations: `...\db\migration\V15__billing_core.sql`, `V16__billing_credit_notes.sql`, `V17__billing_collections.sql`, `V18__billing_settlement_flag.sql` [CR-05], `V19__billing_cashier_shifts.sql` [CR-04], `V20__insurance_claims.sql` [CR-06]
- Legacy source of truth (read-only): `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\service\PatientServiceImpl.java`, `...\api\PatientBillResource.java`, `...\api\PatientResource.java`, `...\service\PatientCreditNoteServiceImpl.java`, `...\repositories\CollectionRepository.java`, `...\accessories\Formater.java`, `...\security\Object_.java`