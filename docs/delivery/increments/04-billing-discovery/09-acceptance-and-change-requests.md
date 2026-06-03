Confirmed: `BILL-A` exists in the seed (V2/V4), `CASHIER-ACCESS` does NOT (consistent with extraction §5 — it's invented; real legacy constant is `CASHIER_SERVICE-ACCESS`), `seq_pcn_no` exists (V13), next migration is V15. I now have everything needed.

---

# INCREMENT 04 — BA DELIVERABLES (Billing / Cashiering / Insurance)

The spec (`docs/delivery/increments/04-billing-cashiering-core.md`) and the five legacy extractions DISAGREE on most money-critical behaviour. Per "modern design, exact process", **every spec assertion that is not in legacy is a deviation requiring an `engagement-lead`-approved change request BEFORE acceptance criteria can be written as parity.** I have split acceptance criteria into two classes: **PARITY** (reproduces verified legacy behaviour) and **NEW-SCOPE** (net-new hardening; AC only valid once the CR is approved — drafted conditionally and labelled).

CRITICAL: Sections 1, 4, 6 of the spec's golden masters (richer invoice lifecycle + balance math, refund-via-signed-amount, cashier-shift + EOD snapshot) are ALL net-new. The legacy has no partial payment, no signed-amount refund, and no shift. Do NOT freeze those golden masters as "parity" — they are CR-gated.

---

## PART A — CHANGE-REQUEST REGISTER

Format: ID | SPEC SAYS | LEGACY REALITY (cited) | RECOMMENDED DECISION | SIGN-OFF OWNER. All approval authority rests with `engagement-lead`; BA maintains and impact-assesses only.

**CR-04-01 — Invoice lifecycle PENDING→PARTIALLY_PAID→PAID/CANCELLED**
- SPEC: §11.2 four-state lifecycle with balance math; CANCELLED on full write-off.
- LEGACY: header only ever {PENDING, APPROVED}; APPROVED batch-applied at start of next charge tx (`PatientServiceImpl.java:586-590, :1789-1793`), NOT a payment event. Never PAID/PARTIALLY_PAID/CANCELLED. On cancel the detail is deleted and the parent invoice physically deleted (`PatientResource.java:659-673`). No recompute of header status from detail totals; settlement tracked per-detail (`detail.status="PAID"`) + running `amountPaid` sum. PatientInvoice is the INSURANCE-CLAIM accumulator (one PENDING invoice per patient+plan), created only for covered/inpatient bills — NOT for plain cash OPD.
- IMPACT: HIGH. The entire spec §1 golden master (10000 → pay 6000 → PARTIALLY_PAID bal 4000 → pay 4000 → PAID) tests behaviour that does not exist in legacy. Also conflates two legacy concepts: the cash-collection gate (which lives on `PatientBill.status`, not the invoice) and the insurance-claim accumulator (`PatientInvoice`).
- RECOMMENDED: APPROVE as net-new modernization — but redefine the aggregate. The richer lifecycle/balance math is a sound modern design IF engagement-lead ratifies it as a deliberate process change. Decide whether "invoice" in the new model = the cash-collectable unit (new) or the legacy claim-accumulator (parity). Do NOT silently ship the spec lifecycle.
- SIGN-OFF: engagement-lead. Inputs: healthcare-domain-expert (clinical impact of treating invoice as cash unit), data-architect (migration of legacy PENDING/APPROVED invoices + per-detail status to the new header status).

**CR-04-02 — Partial payment + per-line allocation**
- SPEC: §11.3 payment with automatic per-line allocation; partial payment drives PARTIALLY_PAID.
- LEGACY: NO partial payment. Every bill paid in full (`paid=amount; balance=0; status="PAID"`, `PatientBillResource.java:305-307`). `PatientPaymentDetail` has NO amount column — value is implicit = linked bill amount. Tendered total must EXACTLY equal sum of selected bills or `InvalidOperationException("...Insufficient payment/ amount mismatch")` (`:389-391`).
- IMPACT: HIGH. Per-line allocation and partial payment are net-new. Requires adding an `amount` column to PatientPaymentDetail (legacy has none).
- RECOMMENDED: APPROVE as net-new (modern cashiering needs partial payment), but flag the data-model addition. Parity fallback if rejected: full-bill-only payment with exact-total guard.
- SIGN-OFF: engagement-lead + data-architect.

**CR-04-03 — Refund via signed/negative PatientPaymentDetail amount**
- SPEC: §11.8 overpayment reversed by a negative-amount PatientPaymentDetail; totalPaid recomputes; (pay 12000 on 10000 → −2000 → totalPaid 10000 PAID).
- LEGACY: IMPOSSIBLE as written — `PatientPaymentDetail` has no amount field (`PatientPaymentDetail.java:37-58`). Real refund = flip detail `status="REFUNDED"` (consultation, `PatientResource.java:638`) OR hard-delete detail+bill (lab/radiology/procedure/prescription/sale), PLUS create a positive-amount `PatientCreditNote` (status PENDING) as the only durable refund record. `PatientPayment.amount` and `PatientInvoice.amountPaid` are NEVER decremented on refund/cancel (totals can become overstated — `PatientBillResource.java:347` increments only).
- IMPACT: HIGH + money-critical. Two incompatible refund models. The spec also implies overpayment is allowed (legacy forbids it via the exact-total guard, CR-04-02).
- RECOMMENDED: This is two CRs in one. (a) Refund mechanism: choose signed-detail (net-new, clean) vs legacy credit-note pattern. (b) totalPaid recompute-on-refund: legacy does NOT recompute → reproducing exactly reproduces a latent inconsistency bug. RECOMMEND approving recompute as a fix-under-CR (overstated-paid is almost certainly an unintended legacy bug, but ledger correctness is safety-critical — needs healthcare-domain-expert + engagement-lead sign-off, NOT a silent fix).
- SIGN-OFF: engagement-lead + healthcare-domain-expert + data-architect.

**CR-04-04 — CashierShift OPEN→CLOSED + NO_OPEN_SHIFT 409 + EOD snapshot**
- SPEC: §11.6 CashierShift OPEN→CLOSED, one per cashier per business day; payment blocked with 409 NO_OPEN_SHIFT if none open; closeShift snapshots per-mode totals; report projects the frozen snapshot.
- LEGACY: NO shift entity. Full grep `shift|openShift|closeShift|cashUp|reconcil` = zero matches. `Cashier` is iam personnel master-data, auto-provisioned on role (`UserServiceImpl.java:243-266`), NEVER consulted at payment time. The only temporal primitive is the global `Day` (STARTED→ENDED, `DayRepository.java:17-18`), system-wide not per-cashier, and `endDay()` does NO snapshot. NO payment gate on shift OR day — `Day` is used only to stamp `createdOn` (`PatientBillResource.java:210,254,335`). EOD reconciliation is READ-TIME only: `SUM(amount) GROUP BY itemName, paymentChannel` over `collections` (`CollectionRepository.java:21-59`).
- IMPACT: HIGH process change. NO_OPEN_SHIFT 409 hard-blocks a workflow that legacy never blocked. Persisted EOD snapshot is net-new (legacy computes on demand).
- RECOMMENDED: APPROVE as net-new controls IF engagement-lead wants tighter cash governance — but this is a genuine PROCESS change (cashiers cannot collect without opening a shift), so it needs explicit sign-off. Parity alternative: drop the shift gate; reproduce read-time SUM report keyed on User (not Cashier), date range `from.atStartOfDay()..to.atStartOfDay().plusDays(1)`, GROUP BY (itemName, paymentChannel).
- SIGN-OFF: engagement-lead + healthcare-domain-expert.

**CR-04-05 — Pay-before-service HARD gate (settled flag, 422 PAY_BEFORE_SERVICE)**
- SPEC: §11 + M3/M13/M23: `SettlementDispatcher` writes `settled=true` on PAID; clinical `accept()` hard-blocked with 422 PAY_BEFORE_SERVICE until paid.
- LEGACY: NO `settled` field anywhere (grep `settled|isSettled|paymentStatus|payBeforeService` = zero). NO bill-status/balance check before result entry or dispensing in `PatientServiceImpl` or `PharmacyServiceImpl`. Pay-before-service is enforced ONLY as a UI filter (`get_*_bills` filter to UNPAID, `PatientBillResource.java:415-588`) plus a re-pay guard (`:295-296`). Clinical staff are NOT technically blocked.
- IMPACT: HIGH. This is the headline net-new hardening and explicitly a "prior-attempt pitfall" the spec wants fixed — but it CHANGES legacy behaviour (legacy allowed unpaid service).
- RECOMMENDED: APPROVE as net-new safety hardening (the prior-build "filter not gate" was a real gap), but it MUST be logged as a deliberate deviation, not presented as exact-process. The SettlementDispatcher direction (billing→encounter, same tx, no async) and clinical-reads-local-flag-only rule are sound and align with ADR-0008.
- SIGN-OFF: engagement-lead. Input: healthcare-domain-expert (confirm no clinical-safety regression from blocking unpaid emergency care — likely needs an emergency-override path, itself net-new).

**CR-04-06 — InsuranceClaim ledger SUBMITTED→SETTLED/REJECTED**
- SPEC: §11.4 InsuranceClaim aggregate with claim numbering + payer-remittance reconciliation, "carried forward from prior-build V70"; Increment 10 consumes it.
- LEGACY: ABSENT. No Claim/InsuranceClaim entity/repo/service/resource (glob `**/*laim*.java` = no files). All `[Cc]laim` hits are JWT claims or code comments. The de-facto claim = a PENDING `PatientInvoice` per (patient, insurancePlan); NO submit/settle/reject state, NO claim numbering (invoice `no` = `Math.random()` then `id.toString()`), NO remittance. COVERED bills write NO `Collection` row, so insurance settlement reconciliation does not exist in legacy at all. "Carried forward from prior-build V70" is unverifiable in this source tree.
- IMPACT: HIGH net-new context. This is the headline NEW-SCOPE item per my mandate (M22-equivalent: legacy has no claims workflow).
- RECOMMENDED: This is NEW SCOPE. AC must NOT be written as legacy parity. Recommend DEFERRING the claim-submission/settlement lifecycle out of Inc-04 (it has no money-critical dependency on the cashiering core) into a dedicated increment, OR explicitly ratifying it as net-new. For Inc-04, reproduce only the legacy mechanism: a PENDING PatientInvoice per (patient, plan) accumulating COVERED detail lines. ALSO open a question to engagement-lead/healthcare-domain-expert: does Zana perform payer claim submission/settlement OUTSIDE the system today? (Determines whether this is greenfield or digitizing a manual process.)
- SIGN-OFF: engagement-lead. Input: healthcare-domain-expert.

**CR-04-07 — CASHIER-ACCESS privilege code (invented)**
- SPEC: DoD line 100 — endpoints protected by `BILL-A`, `CASHIER-ACCESS`, `ADMIN-ACCESS`.
- LEGACY/BUILD: `CASHIER-ACCESS` does NOT exist among the 35 codes. Confirmed absent from seed (`V2__seed_iam.sql`, `V4__schema_iam_delta.sql` contain `BILL-A` but no `CASHIER-ACCESS`). Real legacy constant is `CASHIER_SERVICE = "CASHIER_SERVICE-ACCESS"` (`Object_.java:36`), itself NOT applied to any billing endpoint. The only code gating any cashiering endpoint in legacy is `ADMIN-ACCESS` (on `POST /cashiers/save`).
- IMPACT: MEDIUM. @PreAuthorize may use ONLY the 35 live codes. `CASHIER-ACCESS` would fail authorization parity.
- RECOMMENDED: Map the modern cashier gate to a REAL code: either `CASHIER_SERVICE-ACCESS` (if among the 35 live — confirm with security-architect) or `ADMIN-ACCESS`. Also resolve `BILL-A` vs `BILL-ALL`/`BILL-CREATE`: legacy `Object_.BILL = "BILL-ALL CREATE"` but the (commented) gate string is `BILL-A`; the seed uses `BILL-A`, so `BILL-A` is the live token — USE `BILL-A`.
- SIGN-OFF: security-architect (code mapping) → engagement-lead (gate-introduction is part of CR-04-05 hardening).

**CR-04-08 — Payment modes enum (CASH/INSURANCE/DEBIT_CARD/CREDIT_CARD/MOBILE)**
- SPEC: §15 PaymentType enum with 5 modes; EOD snapshot has per-mode columns (cash/mobile/card/insurance).
- LEGACY: NO PaymentType enum. `paymentType` is a free String only ever set to `CASH` or `INSURANCE` in code. DEBIT CARD/CREDIT CARD/MOBILE appear ONLY in a source comment (`PatientBill.java:57`) — unreachable. `Collection.paymentChannel` hard-coded `"Cash"` at all 3 sites (`PatientBillResource.java:206,250,331`). So legacy is single-mode (cash) + insurance-routing in practice.
- IMPACT: MEDIUM. Multi-mode collection is net-new capability; the EOD per-mode breakdown (spec §11.6 golden master) tests modes that never carry data in legacy.
- RECOMMENDED: APPROVE multi-mode as net-new IF the client genuinely takes mobile/card today (confirm with healthcare-domain-expert/engagement-lead — this is the open business question from extraction §3). Parity-only: model two live values (CASH, INSURANCE); the enum may include the others as inert until a CR activates them.
- SIGN-OFF: engagement-lead. Input: healthcare-domain-expert.

**CR-04-09 — DocumentNumberService (net-new util, PCN concurrency fix)**
- SPEC/BUILD: `DocumentNumberService.next(PCN)` one atomic nextval via `seq_pcn_no`; format `PCN{yyyyMMdd}-{seq}` EAT.
- LEGACY: numbering = `MAX(id)+1` (`PatientCreditNoteRepository.java:18-19`) + `Formater.formatWithCurrentDate("PCN", id)` → `PCN20260603-7` (NO zero-pad, NO fiscal reset). Race-prone: MAX read before insert + unique constraint → concurrent collisions throw. ALSO: legacy PCN date is UTC (`MainApplication.java:138` forces UTC; `Formater` uses `LocalDateTime.now()`), while `createdAt` is EAT (+3h). So legacy number-date and createdAt-date DIFFER for events 00:00–03:00 EAT.
- IMPACT: MEDIUM. Two non-behavioural-format-preserving changes: (a) sequence vs MAX(id)+1 — FORMAT preserved, concurrency fixed (recommend approve as non-behavioural). (b) UTC→EAT date in the number — CHANGES the calendar date in the number near midnight (behavioural for ~3h/day). The spec wants EAT.
- RECOMMENDED: APPROVE seq-based generation (format identical, fixes a real concurrency bug). FLAG the UTC→EAT shift as a deliberate (small) deviation needing sign-off — legacy emits UTC date; spec wants EAT. PREFIX collision note: legacy keys by entity-table MAX so `SPT` reused by two doc types does NOT actually collide on id; the modern DocumentNumberService MUST key counters per document-type, not per prefix string, to stay safe.
- SIGN-OFF: engagement-lead + data-architect.

**CR-04-10 — Invoice-delete count bug (`j = j++`)**
- LEGACY: `PatientResource.java:666-669` (+ `:2947-2950, :3453-3456`) uses `j = j++` (a no-op), so the empty-check is always 0 → the parent PatientInvoice is ALWAYS deleted whenever ANY one detail is removed, even if others remain. Intended: delete only when zero details remain.
- IMPACT: MEDIUM data-integrity. Reproducing exactly reproduces silent loss of other claim lines.
- RECOMMENDED: FIX under CR (this is a latent data-loss bug, not intended process). Implement intended guard (delete invoice only when last detail removed). Needs sign-off because it changes observable output.
- SIGN-OFF: engagement-lead + healthcare-domain-expert + qa-test-engineer.

**CR-04-11 — Ward top-up co-pay (effectively-dead path)**
- LEGACY: ward top-up (the ONLY co-pay mechanism) selection query `findByInsurancePlanAndCovered(p.getInsurancePlan(), true)` (`:1799`) returns ONLY rows whose plan == patient's plan, but the top-up guard (`:1880`) requires `eligiblePlan.plan.id != patient.plan.id`. So under the actual query the top-up branch is UNREACHABLE. Documented intent: referral to a higher ward tier covered by a DIFFERENT plan, patient tops up the difference (second UNPAID bill, billItem "Bed", desc "Ward Bed / Room (Top up)", principal/supplementary self-link).
- IMPACT: MEDIUM. Reproduce-the-dead-path vs implement-the-intent are materially different outputs. The "pick highest price" loop is also largely dead under the query scope.
- RECOMMENDED: Do NOT silently fix. Present both options to engagement-lead + healthcare-domain-expert. RECOMMEND implementing the documented INTENT (referral override + top-up across plans) as the parity target, since the dead path produces no co-pay at all and the intent is clearly the designed behaviour — but this is a judgment call requiring sign-off. Ward billing is Inc-06 scope; resolve before then.
- SIGN-OFF: engagement-lead + healthcare-domain-expert.

**CR-04-12 — Exact-equality float hazard → BigDecimal comparison semantics**
- LEGACY: payment confirmation uses primitive `double` exact equality `amount != totalAmount` (`PatientBillResource.java:389-391`). No rounding, no tolerance.
- IMPACT: LOW-MEDIUM. Under the money=BigDecimal directive, "equality" must be defined precisely.
- RECOMMENDED: Define as `tendered.compareTo(billsTotal) == 0` on values scaled to NUMERIC(19,2) HALF_UP. Pre-approved data-type change (per memory), but the comparison semantics need an explicit one-line decision recorded.
- SIGN-OFF: data-architect (records decision); no engagement-lead gate needed (data-type change pre-approved).

**CR-04-13 — Divergent cancel behaviours + procedure-cancel PCN inconsistency**
- LEGACY: consultation-cancel RETAINS bill (CANCELED) + payment detail (REFUNDED); lab/radiology/procedure/prescription/sale HARD-DELETE bill + payment detail. Both emit a PCN — EXCEPT one procedure-cancel PCN block is commented out (`PatientResource.java:3490-3494`), so a paid+canceled procedure there may NOT generate a refund credit note.
- IMPACT: MEDIUM. Inconsistent refund records across service types; the commented block means a real refund obligation can go unrecorded.
- RECOMMENDED: healthcare-domain-expert to rule which behaviour is canonical (likely: all cancellations of a PAID charge MUST emit a PCN). Treat the missing procedure PCN as a bug to fix under CR.
- SIGN-OFF: engagement-lead + healthcare-domain-expert.

**CR-04-14 — Dead fields + mapping inconsistencies (model cleanup)**
- LEGACY: `PatientInvoice.amountAllocated`/`amountUnallocated` declared but NEVER written (dead). `PatientInvoiceDetail.patientBill` is `@OneToOne(optional=true)` vs `@JoinColumn(nullable=false)` (inconsistent; de-facto NOT NULL). `PatientCreditNote.patient` nullable though always set.
- IMPACT: LOW. No behaviour to preserve.
- RECOMMENDED: DROP dead fields; set `patientBill` NOT NULL; decide `PatientCreditNote.patient` NOT NULL (tightening) vs nullable (migration fidelity). Confirm no report reads the dead fields (grep clean in com.orbix.api).
- SIGN-OFF: data-architect + data-migration-engineer.

**CR-04-15 — `active` flag ignored at resolve time**
- LEGACY: coverage routing filters `covered=true` ONLY; the `*InsurancePlan.active` boolean is NEVER consulted at resolve time.
- IMPACT: LOW-MEDIUM. If the rebuild also gates on `active`, that is a behavioural change.
- RECOMMENDED: Reproduce legacy — route on `covered=true` only. If `active` SHOULD gate (likely the design intent), that is a CR.
- SIGN-OFF: engagement-lead. Input: healthcare-domain-expert.

---

## PART B — ACCEPTANCE CRITERIA

Tagged: [PARITY] = reproduces verified legacy; [NEW-SCOPE: CR-xx] = valid only after that CR is approved; [PHI] = touches PII; [SAFETY-CRITICAL] = clinical/financial. All errors are RFC7807 ProblemDetail with a stable `ErrorCode`. Money = NUMERIC(19,2) HALF_UP; compare via `compareTo`. Every AC is verifiable without legacy source.

### EPIC 1 — Charge accrual & pricing engine (§2.3) [SAFETY-CRITICAL]

**Story 1.1 — Two-step build (cash-first, insurance override)** [PARITY]
- Given a chargeable service of any kind, When `recordClinicalCharge` runs for a non-insurance patient, Then a PatientBill is created at the cash price with paid=0, balance=amount, status=UNPAID, paymentType=CASH.
- Given an INSURANCE patient AND a covered plan row exists for (kind, serviceUid, patient's plan), When the charge is recorded, Then the bill is overridden to amount=paid=planPrice, balance=0.00, status=COVERED, paymentType=INSURANCE, with insurancePlan and membershipNo set, and a PatientInvoiceDetail is attached to the PENDING PatientInvoice for (patient, plan).
- Given a MEDICINE charge with qty=N covered by plan, Then bill amount = planPrice.multiply(N) rounded HALF_UP to 2dp.

**Story 1.2 — Per-service not-covered fallback asymmetry** [PARITY] [SAFETY-CRITICAL]
- CONSULTATION, INSURANCE patient, NO covered consultation plan row → HARD FAIL: 422 ProblemDetail, message EXACTLY "Plan not available for this clinic. Please change payment method", ErrorCode `PLAN_NOT_AVAILABLE_FOR_CLINIC`; NO cash bill persists (tx rolls back).
- LAB / RADIOLOGY / PROCEDURE / MEDICINE, no covered row, patient ADMITTED (inpatient) → cash-price bill, status=VERIFIED, attached to a null-plan PENDING invoice.
- Same kinds, no covered row, patient NOT admitted (outpatient insured) → bill stays cash, status=UNPAID, paymentType=CASH (silent cash fallback).
- REGISTRATION, no covered plan row → silent: bill stays cash (UNPAID if regFee>0, VERIFIED if regFee==0); NO exception.

**Story 1.3 — PriceLookup resolve (storage tier + missing-both)** [PARITY]
- Plan-X row exists for (LAB, svc) at 3500 → resolve returns 3500.00. Only cash row (plan=null) at 5000 → resolve returns 5000.00. NEITHER → throw ServicePriceNotFoundException → 422 `urn:hmis:error:service-price-not-found` (deny default; the 0/default behaviour is a per-deployment flag — confirm production default with legacy-analyst before fixtures freeze).
- Routing on `covered=true` ONLY (CR-04-15); `active` NOT consulted unless CR ratifies.

**Story 1.4 — Ward referral-override + top-up** [NEW-SCOPE / CR-04-11 — DO NOT freeze AC until intent vs dead-path decided] [SAFETY-CRITICAL]
- AC deferred pending CR-04-11. The legacy co-pay path is effectively unreachable; writing AC now would encode either a dead path or an unconfirmed intent. Ward billing is Inc-06 — resolve CR before authoring.

### EPIC 2 — Invoice lifecycle & balance [NEW-SCOPE / CR-04-01, CR-04-02]

**Story 2.1 — Invoice status transitions** [NEW-SCOPE: CR-04-01]
- (Conditional on approval) Given invoice total 10,000.00, When pay 6,000 → status PARTIALLY_PAID, balance 4,000.00; When pay 4,000 → status PAID, balance 0.00. All 2dp HALF_UP.
- PARITY ALTERNATIVE (if CR-04-01 rejected): header status ∈ {PENDING, APPROVED}; APPROVED set on next charge tx for the patient; on full cancel the detail is removed and the empty invoice deleted (per CR-04-10 decision); settlement tracked per-detail + amountPaid sum; NO PARTIALLY_PAID/PAID/CANCELLED header state.

**Story 2.2 — Payment recording & allocation** [PARITY core + NEW-SCOPE allocation]
- [PARITY] One PatientPayment header per cashier action, status=RECEIVED. Each selected bill must be UNPAID or VERIFIED; else 422 ErrorCode `BILL_NOT_PAYABLE`, message "One or more bills have been paid/covered/canceled...". On success bill → paid=amount, balance=0, status=PAID; a PatientPaymentDetail (status RECEIVED) links bill↔payment; a Collection row written (amount=bill.amount, itemName=bill.billItem, paymentChannel="Cash", paymentReferenceNo="NA", attributed to logged-in User).
- [PARITY] Tendered total must EXACTLY equal sum of selected bill amounts (`compareTo==0`, CR-04-12) else 422 `PAYMENT_AMOUNT_MISMATCH`, message "...Insufficient payment". NO overpayment/change handling.
- [PARITY] Side effects on payment: pending admissions → IN-PROCESS + bed OCCUPIED; in-process consultations → SIGNED-OUT; pharmacy sale order → APPROVED, details PAID/sold.
- [NEW-SCOPE: CR-04-02] Partial payment + per-line allocation: only if approved; requires an `amount` column on PatientPaymentDetail (legacy has none).

### EPIC 3 — Credit notes, numbering & refunds [SAFETY-CRITICAL]

**Story 3.1 — PCN numbering** [PARITY format + CR-04-09 concurrency]
- Format `PCN{yyyyMMdd}-{seq}` from `seq_pcn_no`, ONE atomic nextval+insert (no double-save, no Math.random placeholder). Date = EAT `Africa/Dar_es_Salaam` (per spec; NOTE this is a deliberate UTC→EAT deviation per CR-04-09).
- Concurrency: two cashiers create PCNs simultaneously → two distinct numbers, zero unique-constraint violations (Testcontainers concurrency test).
- No zero-padding on seq (legacy has none) unless data-architect rules otherwise.

**Story 3.2 — Refund / credit note on cancel** [PARITY core; CR-04-03 for signed-amount; CR-04-13 for divergence]
- [PARITY] On cancel of a PAID charge: create a PatientCreditNote, amount = bill.amount (full, never partial), status=PENDING, reference = cause label ("Canceled consultation" etc.), patient set, no = next PCN. PCN never auto-applied, stays PENDING (no approve/settle path in legacy).
- [PARITY] Consultation-cancel: bill → CANCELED, payment detail → REFUNDED (retained). Lab/radiology/procedure/prescription/sale-cancel: hard-delete payment detail + bill. (CR-04-13: confirm canonical behaviour with healthcare-domain-expert.)
- [NEW-SCOPE: CR-04-03] Signed negative PatientPaymentDetail refund (pay 12000 on 10000 → −2000 → totalPaid 10000 PAID): valid ONLY if CR-04-03 approved AND an amount column added. The spec §6 golden master is NOT parity — do not freeze it until CR-04-03 signed.
- [PARITY] totalPaid / amountPaid NOT decremented on refund in legacy (CR-04-03b): if recompute-on-refund is approved, that is a deliberate fix.

### EPIC 4 — Cashier shift & EOD collections [NEW-SCOPE / CR-04-04]

**Story 4.1 — Cashier shift lifecycle** [NEW-SCOPE: CR-04-04]
- (Conditional) Open shift → record N payments → close → per-mode totals (cash/mobile/card/insurance) snapshotted and frozen; report projects snapshot, never re-aggregates. Payment with no open shift → 409 `NO_OPEN_SHIFT`.
- PARITY ALTERNATIVE (if rejected): NO shift, NO payment gate. Reproduce read-time collections report only (Story 4.2).

**Story 4.2 — Collections report (read-time)** [PARITY]
- General: `SUM(amount) GROUP BY (itemName, paymentChannel)` over collections joined to users, date range `from.atStartOfDay()` .. `to.atStartOfDay().plusDays(1)` (inclusive of `to` day). Projection = {itemName, amount, paymentChannel}; aggregates across ALL users (not grouped by user despite selecting user_id).
- Per-cashier: same + `WHERE users.nickname = :nickname` (keyed on USER, not Cashier table — CR-04 personnel note).
- Because paymentChannel is always "Cash" in legacy data, all rows collapse to the "Cash" channel (multi-mode is CR-04-08 net-new).
- SUM must be over BigDecimal NUMERIC(19,2) to remain bit-identical (flag for data-migration-engineer).
- [BUG/CR] Per-kind detail reports use boxed-Long `==` id comparison (`ReportResource.java:705` etc.) — reproduce-or-fix is CR-04 (engagement-lead). Recommend fix to `.equals()`.

### EPIC 5 — Pay-before-service gate [NEW-SCOPE / CR-04-05] [SAFETY-CRITICAL]

**Story 5.1 — Settled flag + hard gate** [NEW-SCOPE: CR-04-05]
- (Conditional on approval) SettlementDispatcher writes `settled=true` in the SAME tx as the PAID transition (no async, billing→encounter direction only; verified by ApplicationModules.verify()). Clinical `accept()` on a CASH patient's order with unpaid invoice → 422 `PAY_BEFORE_SERVICE`; clears once PAID. Clinical modules read ONLY the local flag, never call billing.api.
- PARITY ALTERNATIVE (if rejected): NO hard gate; pay-before-service remains a UI worklist filter (filter clinical order lists to settled/UNPAID), reproducing legacy non-blocking behaviour. Healthcare-domain-expert must rule on emergency-care safety either way.

### EPIC 6 — Insurance claims [NEW-SCOPE — M22-class: legacy has NO claims workflow / CR-04-06]

**Story 6.1 — Insurance claim accumulator** [PARITY]
- A PENDING PatientInvoice per (patient, insurancePlan) accumulates COVERED PatientInvoiceDetail lines. NO submit/settle/reject state; NO claim numbering beyond the invoice number; COVERED bills write NO Collection row.

**Story 6.2 — Claim submission/settlement ledger** [NEW-SCOPE: CR-04-06 — MUST NOT be written as legacy parity]
- This is NET-NEW. The legacy has no claims submission/settlement workflow (confirmed: no Claim entity, no SUBMITTED/SETTLED/REJECTED, no remittance). AC for this story will be authored ONLY after engagement-lead ratifies it as net-new scope, and must explicitly state "net-new, approved via CR-04-06" — never "reproduces legacy". RECOMMEND deferring out of Inc-04 (no money-critical coupling to the cashiering core; Inc-10 is the real consumer).

### EPIC 7 — POS receipt & RBAC

**Story 7.1 — POS receipt** [NEW-SCOPE: BILL-1 hardening] [PHI: patient MR, name]
- `GET /billing/invoices/uid/{uid}/receipt` returns printable HTML (@media print) with: invoice reference (uid), patient MR number, payment mode, amount, cashier (logged-in user) name, shift/business-day date. Receipt anchor = PatientPayment.uid (no separate receipt sequence in legacy). Data-classification: PHI — gate per RBAC.

**Story 7.2 — RBAC on billing endpoints** [CR-04-07]
- @PreAuthorize uses ONLY the 35 live codes. Use `BILL-A` (confirmed seeded V2/V4). Replace `CASHIER-ACCESS` (does not exist) with `CASHIER_SERVICE-ACCESS` (if live) or `ADMIN-ACCESS`. NOTE: legacy gated almost NOTHING (most billing endpoints UNGATED) — adding gates is net-new hardening tied to CR-04-05; do not present as parity. Authorization parity test must assert every endpoint's code is in the 35.

### Cross-cutting AC (all stories)
- RFC7807 ProblemDetail with stable ErrorCode for every error; Angular reacts on ErrorCode, never string-match.
- Audit event on invoice status change + payment; `businessDayId` stamped via TxAuditContext on every write. (Audit trail is NET-NEW — Envers was a phantom dep; do NOT port a legacy audit baseline.)
- No `double` anywhere in billing; all money NUMERIC(19,2) HALF_UP.
- ApplicationModules.verify() + ArchUnit green: no billing entity imported outside billing; no encounter→billing call.

---

## PART C — RECOMMENDED BUILD CHUNKING

Money-critical + large → split into ordered, independently `mvn verify`-able sub-phases. Each phase is a mergeable PR with green Testcontainers + ApplicationModules.verify(). Migrations start at **V15**. CR-gated phases must NOT start until the relevant CR is signed by engagement-lead.

**P1 — Core invoice / bill / payment skeleton + PARITY payment** (no CR blockers)
- PatientBill, PatientInvoice, PatientInvoiceDetail, PatientPayment, PatientPaymentDetail, Collection entities (money NUMERIC(19,2)); status enums {UNPAID, VERIFIED, COVERED, PAID, NONE} on bill, {RECEIVED, REFUNDED} on payment detail, {PENDING, APPROVED} on invoice header (parity baseline).
- Parity payment path: full-bill-only, exact-total guard (CR-04-12 comparison), per-bill PAID, Collection write, side-effects. Flyway V15. Ships value with zero CR dependency.

**P2 — Pricing engine §2.3 + BillingCommands.recordClinicalCharge** (no CR blockers; CR-04-15 minor)
- Two-step build, COVERED override per kind, fallback asymmetry (consultation hard-fail / inpatient-VERIFIED / outpatient-UNPAID / registration-silent), medicine qty×price HALF_UP. Wire PriceLookup resolve-time logic deferred from Inc-02. REQUIRED propagation, no async/REQUIRES_NEW. Exposes billing.api for Inc-03/05.

**P3 — DocumentNumberService + credit notes + PARITY refund** (CR-04-09, CR-04-13; CR-04-03 only for signed-amount)
- DocumentNumberService.next(type) on seq_pcn_no, per-document-type keying (CR-04-09 prefix-collision note). PCN format + concurrency test. Credit-note creation on cancel (parity: full amount, PENDING). Parity refund (status REFUNDED / hard-delete per source). Signed-amount refund only if CR-04-03 signed.

**P4 — Cashier shift + EOD collections + POS receipt** (CR-04-04, CR-04-08 BLOCKING for shift/per-mode; receipt + read-time report unblocked)
- Read-time collections report (Story 4.2) is PARITY — build regardless. POS receipt (Story 7.1) — build regardless. CashierShift + NO_OPEN_SHIFT + persisted EOD snapshot + multi-mode — ONLY after CR-04-04 + CR-04-08 signed.

**P5 — Settled flag + pay-before-service gate** (CR-04-05 BLOCKING)
- SettlementDispatcher (same-tx, billing→encounter), settled flag, 422 PAY_BEFORE_SERVICE. Net-new hardening; do not start until CR-04-05 signed. Must land before Inc-03/05 wire clinical accept().

**P6 — Insurance claims ledger** (CR-04-06 BLOCKING; recommend DEFER out of Inc-04)
- P6a [PARITY, in P2 already]: PENDING PatientInvoice-per-(patient,plan) accumulator. P6b [NEW-SCOPE]: SUBMITTED/SETTLED/REJECTED claim ledger + numbering + remittance — recommend deferring to a dedicated increment before Inc-10; build only if engagement-lead ratifies CR-04-06.

RBAC (CR-04-07) applied incrementally per endpoint as each phase lands, using only the 35 live codes. CR-04-10 (invoice-delete bug fix) and CR-04-14 (model cleanup) fold into P1/P3 once signed.

Sequencing rationale: P1→P2 are pure parity (zero CR risk) and unblock downstream Inc-03/05; P3 adds the numbering util + refund; P4–P6 are the CR-gated net-new bands and can proceed in parallel once their CRs clear, without blocking the parity core.

---

## KEY FLAGS FOR ENGAGEMENT-LEAD (decision-blocking)
1. The spec's three headline golden masters (invoice balance math §1, signed-amount refund §6, cashier-shift/EOD §11.6) are ALL net-new — they CANNOT be accepted as "exact-process parity". Need CR-04-01/02/03/04/08 signed before their AC freeze.
2. InsuranceClaim (CR-04-06) and pay-before-service hard gate (CR-04-05) are the two biggest deliberate process changes — both are sound modernizations but MUST be ratified, not assumed. Recommend deferring the InsuranceClaim submission/settlement ledger out of Inc-04 entirely.
3. `CASHIER-ACCESS` (CR-04-07) is an invented code — must be remapped before any @PreAuthorize is wired, or authorization parity tests will fail.
4. Two latent legacy bugs (`j=j++` invoice-delete CR-04-10; ward top-up dead path CR-04-11) need explicit reproduce-or-fix rulings from healthcare-domain-expert + engagement-lead before P1/Inc-06.

Relevant files (all absolute):
- Spec: `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\04-billing-cashiering-core.md`
- Empty target module: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\billing\package-info.java`
- Seq + privilege seeds: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V13__masterdata_document_sequences.sql`, `...\V2__seed_iam.sql`, `...\V4__schema_iam_delta.sql` (next migration = V15)
- Legacy source of truth (read-only): `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\service\PatientServiceImpl.java`, `...\api\PatientBillResource.java`, `...\api\PatientResource.java`, `...\service\PatientCreditNoteServiceImpl.java`, `...\repositories\CollectionRepository.java`, `...\accessories\Formater.java`, `...\security\Object_.java`