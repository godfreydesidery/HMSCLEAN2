## EXTRACTION 1 — Invoice / Bill / Payment CORE (legacy, read-only)

Legacy root: `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api`. All money fields in legacy are primitive `double` (target = BigDecimal NUMERIC(19,2) HALF_UP per directives). All "status"/"paymentType" fields are plain `String` columns — **there is no JPA enum** for status or payment type anywhere.

### Early-discovery findings (mandatory in audit/auth-touching specs)
- **Audit trail:** This extraction touches no `@Audited` entity. The five core entities (`PatientBill`, `PatientInvoice`, `PatientInvoiceDetail`, `PatientPayment`, `PatientPaymentDetail`) carry NO `@Audited` annotation; each only has hand-rolled forensic columns `createdBy`/`createdOn`/`createdAt`. Consistent with the project memo: no Hibernate Envers audit baseline is active. Do not assume one.
- **Device fingerprint / binding:** Not relevant to these entities and none observed. No device-binding feature to preserve.

---

### 1. Entity structures (exact)

**PatientBill** — `domain\PatientBill.java:40` (`@Table patient_bills`). The atomic charge line; one per chargeable clinical/registration/ward item.
- `id` IDENTITY (`:41-43`); `billItem` String default `"NA"` (`:44`); `description` `@NotBlank` (`:46`); `qty` double default 1 (`:47`); `amount` `@NotNull` double (`:49`); `paid` `@NotNull` double (`:51`); `balance` `@NotNull` double (`:53`); `status` `@NotBlank` String (`:55`); `paymentType` String default `"CASH"` with code-comment `//CASH,DEBIT CARD, CREDIT CARD, MOBILE, INSURANCE` (`:57`); `membershipNo` String (`:58`).
- Relationships: `@ManyToOne patient` not-null, not-updatable (`:60-63`); **self-reference** `principalPatientBill` nullable (`:65-68`) and `supplementaryPatientBill` nullable (`:70-73`) — the ward top-up linkage; `@ManyToOne insurancePlan` nullable (`:75-78`).
- Forensic: `createdBy` (`:81`), `createdOn` (`:83`), `createdAt` (`:84`).
- NOTE: PatientBill has NO direct FK to the originating charge. The link is **inverse** — each charge entity (`Registration`, `Consultation`, `LabTest`, `Radiology`, `Procedure`, `Prescription`, `AdmissionBed`, `PharmacySaleOrderDetail`) owns a `patientBill` FK. The "kind" is recorded only as the free-text `billItem` string ("Registration", "Consultation", "Lab Test", "Bed", etc.) set at creation; there is no kind enum.

**PatientInvoice** — `domain\PatientInvoice.java:43` (`@Table patient_invoices`). The insurance-claim header (grouping of covered bills per plan). Created **only** for insurance-covered/inpatient bills, never for plain cash OPD.
- `id` IDENTITY (`:46`); `no` `@NotBlank @Column(unique=true)` (`:48-49`); `status` String default `"PENDING"` (`:50`); `amountPaid` double default 0 (`:51`); `amountAllocated` double default 0 (`:52`); `amountUnallocated` double default 0 (`:53`).
- **DEAD FIELDS:** `amountAllocated` and `amountUnallocated` are declared but NEVER written anywhere in the codebase (grep confirms only the field declarations match). Do not carry forward as behaviour.
- Relationships: `@ManyToOne patient` not-null (`:55-58`); nullable `consultation` (`:60-63`), `nonConsultation` (`:65-68`), `admission` (`:70-73`) — the originating encounter; nullable `insurancePlan` (`:75-78`); `@OneToMany PatientInvoiceDetails` mappedBy `patientInvoice`, EAGER, `orphanRemoval=true`, SUBSELECT fetch (`:80-84`).

**PatientInvoiceDetail** — `domain\PatientInvoiceDetail.java:39` (`@Table patient_invoice_details`). One claim line per covered PatientBill.
- `id` IDENTITY (`:42`); `description` `@NotBlank` (`:44`); `qty` `@NotNull` double (`:46`); `amount` `@NotNull` double (`:48`); `status` String (no default) (`:49`).
- Relationships: `@ManyToOne patientInvoice` not-null, not-updatable (`:51-54`); `@OneToOne patientBill` — note `optional=true` on the annotation but `nullable=false` on `@JoinColumn` (`:56-59`), an internal inconsistency (flag below).

**PatientPayment** — `domain\PatientPayment.java:39` (`@Table patient_payments`). A payment receipt header; one per cashier payment action.
- `id` IDENTITY (`:42`); `amount` `@NotNull` double (`:44`); `status` String, no default (`:45`). Forensic columns only. **No `no`/receipt-number column, no patient FK, no paymentType, no date-of-payment beyond `createdAt`.** Repository is empty (`PatientPaymentRepository.java` — no custom finders).

**PatientPaymentDetail** — `domain\PatientPaymentDetail.java:37` (`@Table patient_payment_details`). Links one paid PatientBill to one PatientPayment.
- `id` IDENTITY (`:40`); `description` String (`:41`); `status` String (`:42`). **No amount field** — the paid amount is implicit (= the linked bill's `amount`).
- Relationships: `@OneToOne patientBill` not-null, not-updatable (`:44-47`); `@ManyToOne patientPayment` not-null (`:49-52`).

---

### 2. Invoice lifecycle (legacy reality — NOT the spec's PENDING→PARTIALLY_PAID→PAID/CANCELLED)

PatientInvoice header `status` only ever takes two values in code:
- `"PENDING"` at creation (e.g. `PatientServiceImpl.java:342, :631, :871, :940, :1111, :1362, :1581, :1831, :1909, :1978`).
- `"APPROVED"` — batch-applied to all of a patient's PENDING invoices at the *start* of the next charge transaction (`PatientServiceImpl.java:586-590` for consultation; `:1789-1793` for ward). This "closes" the prior claim before a new plan/encounter begins; it is NOT a payment event.

The header is **never** set to `"PAID"`, `"PARTIALLY_PAID"`, or `"CANCELLED"`. There is **no recompute of invoice `status` from its detail totals**. On payment (`PatientBillResource.java:341-349`) the code: sets the matching `PatientInvoiceDetail.status = "PAID"` and increments `PatientInvoice.amountPaid += detail.amount` — but leaves the header `status` untouched (stays PENDING/APPROVED). `amountPaid` is a raw running sum of paid detail amounts; there is no `totalAmount`/`balance` field on the invoice and none is computed. Invoice "totals" exist only as the sum of detail `amount`s (totals are reconstructed by clients, not stored).

Cancellation does NOT set invoice status to CANCELLED — instead the linked `PatientInvoiceDetail` is **deleted** (`PatientResource.java:663-664`), and if the invoice has zero remaining details the whole `PatientInvoice` is **deleted** (`:665-672`). (The detail-count loop at `:666-669` is buggy — `j = j++` never increments, so `j` is always 0 and the invoice is ALWAYS deleted whenever a detail is removed. Flag below.)

**Conclusion:** the spec's `PENDING→PARTIALLY_PAID→PAID/CANCELLED` invoice lifecycle is a modernization INVENTION. The legacy invoice is an insurance-claim batch with statuses {PENDING, APPROVED} plus physical deletion on cancel; settlement is tracked per-detail (detail.status="PAID") and via the running `amountPaid` sum, not on the header.

---

### 3. The real status machine lives on PatientBill (the cash gate)

Observed `PatientBill.status` string values and transitions:
- **Created** `"UNPAID"` (cash, fee>0) — e.g. registration `PatientServiceImpl.java:274`, consultation `:466`, lab `:828`, radiology `:1070`, procedure `:1321`, prescription `:1540`, ward bed `:1760`, ward top-up `:1886`.
- **`"VERIFIED"`** — created free (regFee==0, `:276`) OR insurance-not-covered-but-patient-is-INPATIENT cash fallback (lab `:917`, and the parallel radiology/procedure/prescription/ward-procedure blocks). Treated as payable like UNPAID (see payment gate).
- **`"COVERED"`** — insurance plan row found; amount/paid set to plan price, balance 0 (registration `:390`, consultation `:605`, lab `:845`, radiology `:1085`, procedure `:1336`, prescription `:1555`, ward `:1818`).
- **`"NONE"`** — follow-up consultation, no charge (`:468`, `:607`).
- **`"PAID"`** — set at payment confirmation (`PatientBillResource.java:176, :227, :307`).
- **`"CANCELED"`** (single L) — on cancellation of the underlying charge (`PatientResource.java:627`).

Payment eligibility gate: only `"UNPAID"` or `"VERIFIED"` bills may be paid (`PatientBillResource.java:295-296, :304`). Anything else throws `InvalidOperationException("One or more bills have been paid/covered/canceled...")`.

Per-service insurance-not-covered fallback **asymmetry** (confirms the deferred build-spec §2.3):
- CONSULTATION: HARD-FAIL — `InvalidOperationException("Plan not available for this clinic. Please change payment method")` at `PatientServiceImpl.java:599-601`. No cash fallback.
- LAB / RADIOLOGY / PROCEDURE / PRESCRIPTION: cash fallback to `"VERIFIED"` ONLY when admission is present (`a.isPresent()`), e.g. lab `:912-918`; non-admitted stays cash `"UNPAID"`.
- REGISTRATION: silent — stays `"UNPAID"`, no exception (the `if(plan.isPresent())` block at `:321` simply does nothing on absence).

Two-step build (cash-first then insurance override) is confirmed: the bill is constructed at CASH price/UNPAID first (e.g. lab `:821-835`), then, if INSURANCE/covered, overwritten with plan price, paid=amount, balance=0, status=COVERED (`:842-849`).

Ward referral-override + top-up split confirmed: `PatientServiceImpl.java:1797-1809` loads all covered ward-type rows, picks highest price, short-circuits (`break`) on a row whose plan == patient's plan (referral override). If eligible plan differs AND `wardType.price - eligiblePlan.price > 0` (`:1880`), a **second PatientBill** is emitted (`:1881-1897`): amount/balance = the difference, status UNPAID, billItem "Bed", description "Ward Bed / Room (Top up)", `principalPatientBill` = the covered ward bill (`:1889`), and the covered bill's `supplementaryPatientBill` back-points to it (`:1896`). This is the ONLY co-pay/top-up mechanism in the system.

---

### 4. How a payment is recorded (per-bill, NOT per-invoice-line, NOT partial)

Two entry points, both in `PatientBillResource.java` (class is `@Transactional`, `:84`):
1. `confirm_registration_and_consultation_payment` (`:146-267`).
2. `confirm_bills_payment` (`:269-393`) — the general path; takes `List<PatientBill>` + `total_amount`.

Mechanism (general path):
- One `PatientPayment` header is created per call: `amount = total_amount`, `status = "RECEIVED"`, forensic fields (`:277-285`). No receipt number, no patient, no mode.
- For each submitted bill, it is re-fetched by id (`:291`), validated present (`NotFoundException` `:293`) and validated UNPAID/VERIFIED (`:295-297`).
- Payment is **full and per-bill** — `balance=0; paid=amount; status="PAID"` (`:305-307`). There is **no partial payment** and **no per-line allocation of a payment amount** — each `PatientPaymentDetail` carries no amount; it just links bill↔payment with `description` = bill description and `status = "RECEIVED"` (`:310-320`).
- A `Collection` row is written per bill for cashier reconciliation (`:327-337`): amount = bill.amount, itemName = bill.billItem, paymentChannel hard-coded `"Cash"`, paymentReferenceNo `"NA"`, patient, forensic. (`Collection` = `domain\Collection.java:38`; the cashier-collection ledger.)
- If the bill is on an insurance invoice, the matching `PatientInvoiceDetail.status="PAID"` and `invoice.amountPaid += detail.amount` (`:341-349`).
- Side effects: pending admissions → "IN-PROCESS" + bed "OCCUPIED", in-process consultations → "SIGNED-OUT" (`:352-365`); pharmacy sale order → "APPROVED" and details → pay "PAID"/sold-stamped (`:369-387`).
- **Overpayment guard:** after the loop, `if(amount != totalAmount) throw InvalidOperationException("...Insufficient payment/ amount mismatch")` (`:389-391`; reg/con path `:261-263`). The sum of selected bill amounts must EXACTLY equal the tendered total. There is NO change/overpayment handling and no rounding logic — exact `double` equality is required (a latent floating-point hazard; flag below).

**Payment modes / PaymentType:** there is NO `PaymentType` enum (glob/grep confirm none). `paymentType` is a free String on PatientBill defaulting `"CASH"`; in actual code paths it is only ever set to `"CASH"` or `"INSURANCE"` (`PatientServiceImpl.java:391, :550, :566, :609, :846, :1086, :1815`, etc.). The values DEBIT CARD / CREDIT CARD / MOBILE appear ONLY in the source comment at `PatientBill.java:57` — they are documented but unreachable. `Collection.paymentChannel` is hard-coded `"Cash"` at every call site. So the only live payment modes are CASH and INSURANCE.

---

### 5. Refund / credit note (the spec's "negative PatientPaymentDetail amount" is NOT the legacy pattern)

`PatientPaymentDetail` has **no amount field at all**, so a "negative amount" detail is impossible in legacy. The actual refund/cancel pattern (canonical example: cancel consultation, `PatientResource.java:605-673`):
1. Underlying charge → "CANCELED"; its `PatientBill.status → "CANCELED"` (`:618, :627`).
2. If a `PatientPaymentDetail` exists for that bill with status "RECEIVED", flip it to **`"REFUNDED"`** (`:636-639`) — no negative/reversing row is created.
3. Create a new **`PatientCreditNote`** (`domain\PatientCreditNote.java:35`, `@Table patient_credit_notes`): positive `amount` = bill.amount (`:644`), `patient`, `reference` = "Canceled consultation"/"Canceled lab test"/"Canceled radiology"/"Procedure canceled", `status = "PENDING"`, `no` from `PatientCreditNoteService` (`PatientResource.java:643-654`). The credit note is the refund instrument; it is never auto-applied and stays "PENDING".
4. Delete the linked `PatientInvoiceDetail` and (per the buggy count loop) the parent `PatientInvoice` (`:659-672`).

Same pattern repeats for lab/radiology/procedure cancellations at `PatientResource.java:2927+, :2986+, :3050+, :3433+` (a procedure block at `:3490` is commented-out, so a paid+canceled procedure there does NOT create a credit note — inconsistency, flag below).

PCN numbering (`PatientCreditNoteServiceImpl.java:33-40`): `id = MAX(PatientCreditNote.id)+1` (`PatientCreditNoteRepository.java:18-19`), formatted via `Formater.formatWithCurrentDate("PCN", id)` = **`PCN{yyyyMMdd}-{id}`** (`accessories\Formater.java:14-17`). Not zero-padded; no fiscal reset; the seq is the global max id, so concurrent calls can collide (no DB sequence — flag).

---

### 6. Cashier reconciliation context
- `Cashier` (`domain\Cashier.java:28`) is a **person/profile** (code, names, nickname, active flag, optional 1:1 User) — NOT a shift. `CashierResource.java` only does CRUD + user linkage. There is **no CashierShift, no OPEN/CLOSED, no NO_OPEN_SHIFT, no EOD snapshot** anywhere (glob `*Shift*` = none). Spec's CashierShift lifecycle is an INVENTION.
- Reconciliation data = the `collections` table (one row per paid bill, `Collection.java:38`), aggregated by the `CollectionReport` model (`reports\models\CollectionReport.java`).
- There is **no InsuranceClaim entity** (grep `InsuranceClaim`/`class Claim` = none). The PatientInvoice (claim batch) + PatientInvoiceDetail are the only claim representation. Spec's `InsuranceClaim` SUBMITTED→SETTLED/REJECTED ledger is an INVENTION (not carried forward from any legacy V70 artefact in this source tree).

---

### 7. Invoice numbering quirk (carry into DocumentNumberService design)
PatientInvoice `no` is set via a two-write hack: first `no = String.valueOf(Math.random())` (a random double string) to satisfy the unique-not-blank constraint, save to get the IDENTITY id, then overwrite `no = id.toString()` and save again (`PatientServiceImpl.java:339/348-349, :627/637-638, :857/877-879, :926/946-948, :1827/1837-1838, :1905/1915-1916, :1974/1984-1985`). So in practice the invoice number == its DB id as a string. No prefix, no padding, no fiscal reset.