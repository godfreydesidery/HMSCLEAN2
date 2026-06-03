## EXTRACTION 4 — Credit Notes, Document Numbering, Refunds (legacy, read-only, cited)

Scope: `com.orbix.api`. Legacy root: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api`. All money fields in legacy are `double` (modernize to BigDecimal NUMERIC(19,2) HALF_UP per directive).

### Early-discovery findings (mandatory in audit/auth-touching specs)
- **Audit trail:** No `@Audited` annotation and no `org.hibernate.envers` import exists anywhere in `com.orbix.api` (Grep over the whole package returned zero matches). State in spec: *"No Hibernate Envers audit trail is effectively active in the legacy system — the dependency is present but no entity is annotated `@Audited`. Downstream agents must not assume an Envers audit baseline exists."* `PatientCreditNote` carries only manual stamp columns `createdBy` / `createdOn` / `createdAt` (`PatientCreditNote.java:51-55`); there is no automatic audit history.
- **Device fingerprint / binding:** No fingerprint / device-binding / device-id / user-agent-binding logic found in `com.orbix.api` (Grep `fingerprint|deviceId|device_id|deviceBinding|userAgent|X-Device` = zero matches). State in spec: *"No device-fingerprint or device-binding feature exists in the legacy system. Agents must not treat this as a feature to preserve or re-implement."*

---

### 1. PatientCreditNote entity (`domain/PatientCreditNote.java`)
- `@Table(name = "patient_credit_notes")`, `@Id` IDENTITY `Long id` (`PatientCreditNote.java:34-38`).
- `String no` — `@NotBlank @Column(unique = true)` (`:39-41`). This is the PCN document number.
- `double amount = 0` (`:42`).
- `String status = ""` (`:43`) — default empty; set to `"PENDING"` at every creation site (see §3). **No code anywhere transitions a credit note off `PENDING`** (verified: only `setStatus("PENDING")` writes exist; no approve/cancel/settle path for PCN).
- `String reference = ""` (`:44`) — a free-text cause label (e.g. `"Canceled consultation"`, `"Canceled lab test"`, `"Canceled radiology"`, `"Canceled procedure"`, `"Canceled prescription"`, `"Canceled Sale Detail"`).
- `@ManyToOne Patient patient` — **nullable=true, optional=true** (`:46-49`); credit note can exist without a patient FK.
- `createdBy` (Long, `created_by_user_id`, nullable=false), `createdOn` (Long, `created_on_day_id`, nullable=false), `createdAt` (LocalDateTime, default `LocalDateTime.now()`) (`:51-55`).
- **There is NO link from PatientCreditNote to PatientBill / PatientInvoice / PatientPaymentDetail.** The credit note is a standalone, write-only ledger record. It does not reference, reduce, or settle any invoice. `PatientCreditNoteRepository` exposes only `getLastId()` (`PatientCreditNoteRepository.java:18-19`).

### 2. Credit-note "write-off" logic — CRITICAL: it does NOT exist as the spec assumes
The Increment-04 spec's notion of "partial vs full write-off affecting invoice balance/status, full write-off → invoice CANCELLED" is **not present in the legacy**. The legacy reality:
- A credit note is **never partial**. Amount is always the full bill amount: `patientCreditNote.setAmount(<bill>.getAmount())` at every site (`PatientResource.java:644, 2928, 2987, 3051, 3434, 3501, 3568, 3568, 6368`). There is no partial-amount path, no proration, no balance arithmetic on the credit note.
- The credit note **does not touch the invoice balance**. Invoice "adjustment" on cancellation is done by **deleting the `PatientInvoiceDetail`**, and **deleting the parent `PatientInvoice` only if it has zero remaining details** — NOT by setting invoice status to CANCELLED. See the canonical block `PatientResource.java:659-673` (consultation cancel), repeated at `:2940-2954` (lab), `:3446-3460` (radiology). The empty-check loop is buggy: `int j = 0; for(...) { j = j++; }` (`:666-669`, `:2947-2950`, `:3453-3456`) — `j = j++` is the classic Java no-op, so **`j` always remains 0**, meaning the parent invoice is ALWAYS deleted whenever a detail is deleted, regardless of how many other details remain. **Flag this as an ambiguity / latent bug** (see decisions).
- **No invoice is ever set to "CANCELLED" by credit-note logic.** PatientInvoice status values observed: default `"PENDING"` (`PatientInvoice.java:50`); details set to `"PAID"` on payment (`PatientBillResource.java:345`). There is no CANCELLED transition for the invoice header anywhere in the credit-note / cancellation paths.

### 3. The six credit-note creation sites (all in `PatientResource.java`)
All share the identical shape: created only when a paid `PatientPaymentDetail` (status `"RECEIVED"`) exists for the bill; status set `"PENDING"`; amount = bill amount; number from `patientCreditNoteService.requestPatientCreditNoteNo().getNo()`.
1. `cancelConsultation` — `:643-654`. Guard at `:611` (only PENDING consultation). Refund path: sets `PatientPaymentDetail.status="REFUNDED"` (`:638`) then creates PCN. Bill set `"CANCELED"` (`:627`), payment detail KEPT (status REFUNDED), invoice detail deleted.
2. `deleteLabTest` — `:2927-2938`. **Refund path differs:** does NOT set REFUNDED; instead **deletes** the `PatientPaymentDetail` (`:2958`) and **deletes** the `PatientBill` (`:2961`). PCN still created.
3. `deleteLabTest` (second consultation/non-consultation variant) — `:2986-2997`.
4. `deleteLabTest` (third variant) — `:3050-3061`.
5. `deleteRadiology` — `:3433-3444`; deletes payment detail (`:3464`) and bill (`:3467`).
6. `deleteProcedure` — `:3500-3511`; `deletePrescription` — `:3567-3578`; `cancel sale detail` — `:6367-6378`. (Commented-out older PCN variants that used `setNo("NA")` then `setNo(id.toString())` remain at `:3490-3498`, `:3557-3565`, `:6357-6365` — **dead code, do not reproduce**.)

**Two divergent cancellation behaviours to flag:** consultation cancel *preserves* the bill (status CANCELED) and payment detail (status REFUNDED); lab/radiology/procedure/prescription/sale *hard-delete* the bill and payment detail. Both still emit a PCN. (See decisions.)

### 4. Credit-note numbering (`PatientCreditNoteServiceImpl.java:32-41`)
```
Long id = 1L;
try { id = patientCreditNoteRepository.getLastId() + 1; } catch(Exception e) {}
model.setNo(Formater.formatWithCurrentDate("PCN", id.toString()));
```
- Counter is **MAX(id)+1**, NOT a database sequence: `@Query("SELECT MAX(p.id) FROM PatientCreditNote p")` (`PatientCreditNoteRepository.java:18-19`). On empty table MAX returns NULL → unboxing NPE → caught → `id` stays `1L`.
- **Race / collision risk:** MAX(id)+1 is computed at "request number" time, before the row is inserted; two concurrent cancellations can read the same MAX and produce the same `no`. The `@Column(unique=true)` on `no` (`PatientCreditNote.java:40`) would then throw a DB constraint violation on the second insert. (Flag in decisions.)
- **Exact format produced** by `Formater.formatWithCurrentDate("PCN", id)` (`Formater.java:14-17`): `prefix + yyyyMMdd + "-" + suffix`, i.e. **`PCN20260603-7`** for id 7 on 2026-06-03. **No zero-padding on the suffix** — it is the raw `Long.toString()`. (The spec's target `PCN{yyyyMMdd}-{seq}` matches the legacy shape exactly; just note the seq is unpadded MAX(id)+1, not a fiscal-reset sequence.)
- **No fiscal-year reset, no per-day reset.** The numeric suffix is a global monotonic id; only the embedded date string changes daily. So two PCNs on different days with the same `id` cannot occur (id is unique), and the date prefix does NOT reset the counter.
- **Date / timezone:** `Formater` uses `LocalDateTime.now()` with `DateTimeFormatter.ofPattern("yyyyMMdd")` (`Formater.java:15-16`). The JVM default zone is forced to **UTC** at startup: `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` (`MainApplication.java:138`). Therefore the **PCN date component is UTC**, NOT EAT. Contrast: `createdAt` is stamped via `DayServiceImpl.getTimeStamp()` = `LocalDateTime.now().plusHours(3)` (`DayServiceImpl.java:86-88`) = **UTC+3 (EAT)**. **The number's date and the createdAt date can differ near midnight UTC** (a PCN created 00:00–03:00 EAT carries the *previous* UTC calendar date in its `no` but the current EAT date in `createdAt`). Flag this for faithful reproduction (target wants EAT; legacy actually emits UTC date in the number). (See decisions.)

### 5. General document-number pattern (build DocumentNumberService faithfully)
Every document type uses the **identical idiom**: `try { id = repo.getLastId() + 1; } catch(Exception e){}` with `id` defaulting to `1L`, then `Formater.formatWithCurrentDate(PREFIX, id.toString())`. `getLastId()` is always `SELECT MAX(x.id) FROM Entity x`. Confirmed prefixes:
- **GRN** (Goods Received Note): `GoodsReceivedNoteServiceImpl.java:207-214`, repo `GoodsReceivedNoteRepository.java:23-24`.
- **LPO** (Local Purchase Order): `LocalPurchaseOrderServiceImpl.java:329-332`, repo `LocalPurchaseOrderRepository.java:21-22`.
- **PSR** (Pharmacy→Store Requisition Order): `PharmacyToStoreROServiceImpl.java:371-374`.
- **SPT** (Store→Pharmacy / Pharmacy→Pharmacy Transfer Order): `StoreToPharmacyTOServiceImpl.java:435-438` AND `PharmacyToPharmacyTOServiceImpl.java:425-428` — **NOTE: same prefix "SPT" reused for two different document types** (collision risk in any global lookup by prefix). (Flag.)
- **PGRN** (Store→Pharmacy Received Note): `StoreToPharmacyRNServiceImpl.java:336-339`.
- **PPR** (Pharmacy→Pharmacy Requisition Order): `PharmacyToPharmacyROServiceImpl.java:376-379`.
- **PPRN** (Pharmacy→Pharmacy Received Note): `PharmacyToPharmacyRNServiceImpl.java:325-328`.
- **PRL** (Payroll): `PayrollServiceImpl.java:403-406`.
- **PCN** (Patient Credit Note): `PatientCreditNoteServiceImpl.java:33-39`.

`Formater` helper methods (`Formater.java`): `formatWithCurrentDate(prefix,suffix)` → `prefix+yyyyMMdd+"-"+suffix` (`:14-17`); `formatNine` → zero-pad to 9 then insert dashes at pos 3 and 6 producing `XXX-XXX-XXX` (`:18-35`); `formatSix` → pad to 6 + dash at 3 → `XXX-XXX` (`:36-52`); `formatThree` → pad to 3, no dash (`:53-68`); `formatFive` → pad to 5 + dash at 3 (`:69-85`); `formatNinePlain` → pad to 9, no dashes (`:87-102`). **Document numbers (PCN/GRN/LPO/etc.) use ONLY `formatWithCurrentDate` — the suffix is NEVER zero-padded.** The `formatNine`/`formatSix`/etc. padders are used elsewhere (e.g. registration/membership/MRN-style IDs) and apply zero-padding; do not conflate them with document numbers.

The request returns a `RecordModel` DTO with fields `no` and `code` (both default `""`) (`models/RecordModel.java:13-16`); document numbering returns via `model.setNo(...)`.

### 6. Refund mechanism — there is NO negative-amount PatientPaymentDetail
- **The spec's "refund via signed/negative `PatientPaymentDetail` amount" does NOT exist in legacy.** `PatientPaymentDetail` has **no amount field at all** (`PatientPaymentDetail.java:37-58`): its columns are `id`, `description`, `status`, FK `patientBill` (OneToOne), FK `patientPayment` (ManyToOne), and the create-stamps. A payment detail's monetary value is implied by its linked `patientBill.getAmount()`. There is no field on which a negative could be stored.
- **Actual legacy refund = a status flag, not a ledger reversal.** On consultation cancel the existing payment detail is set `status="REFUNDED"` (`PatientResource.java:636-639`): guard requires `pd.getStatus().equals("RECEIVED")`, then `ppd.setStatus("REFUNDED")`, save. For lab/radiology/procedure/prescription/sale cancels there is **no REFUNDED state at all** — the payment detail and bill are simply **deleted** (`PatientResource.java:2958, 2961` lab; `:3464, 3467` radiology; analogous for the others). A `PatientCreditNote` (status PENDING, amount = bill amount) is created in all cases as the only durable record of the refund obligation.
- **`totalPaid` recomputation:** there is **no recompute on refund**. `PatientPayment.amount` (`PatientPayment.java:43-44`) is set once at collection time to the cashier-entered `totalAmount` (`PatientBillResource.java:164, 278`) and is **never decremented** when a detail is later REFUNDED or deleted. `PatientInvoice.amountPaid` is **incremented** on payment (`PatientBillResource.java:347`: `invoice.setAmountPaid(invoice.getAmountPaid()+invd.getAmount())`) but is **never decremented** on cancel/refund (the cancel path only deletes the `PatientInvoiceDetail` / parent invoice; it does not adjust `amountPaid`). Fields `amountAllocated` / `amountUnallocated` on `PatientInvoice` (`PatientInvoice.java:52-53`) are declared but **never written** anywhere — dead fields. So legacy invoice/payment totals can become internally inconsistent after a refund; reproducing "exact process" means reproducing this non-recompute behaviour (or raising it as a change request). (Flag in decisions.)

### 7. Bill/invoice status vocabulary observed (for downstream state-machine specs)
- `PatientBill.status`: `UNPAID`, `PAID`, `VERIFIED`, `COVERED`, `CANCELED` (one L) — e.g. payment gate accepts `UNPAID`/`VERIFIED` → `PAID` (`PatientBillResource.java:295-307`); cancel sets `CANCELED` (`PatientResource.java:627`); note-add gate checks `PAID`/`COVERED`/`VERIFIED` (`PatientResource.java:3408`).
- `PatientPaymentDetail.status`: `RECEIVED` (`PatientBillResource.java:192, 238, 314`), `REFUNDED` (`PatientResource.java:638`).
- `PatientPayment.status`: `RECEIVED` (`PatientBillResource.java:170, 283`).
- `PatientInvoiceDetail.status`: `PAID` (`PatientBillResource.java:345`).
- `PatientInvoice.status`: default `PENDING` (`PatientInvoice.java:50`); no observed transition writer. (The Inc-04 lifecycle PENDING→PARTIALLY_PAID→PAID/CANCELLED is **net-new / invented** — legacy never sets the invoice header beyond default PENDING.)
- `PatientCreditNote.status`: `PENDING` only (no other value ever written).

### Source file index (absolute paths)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/PatientCreditNote.java`
- `.../service/PatientCreditNoteServiceImpl.java`, `.../service/PatientCreditNoteService.java`
- `.../repositories/PatientCreditNoteRepository.java`
- `.../accessories/Formater.java`
- `.../api/PatientResource.java` (all six PCN creation sites + refund flags)
- `.../api/PatientBillResource.java` (payment/collection/invoice.amountPaid increment)
- `.../api/CashierResource.java` (cashier CRUD only; no payment/refund logic)
- `.../domain/{PatientBill,PatientInvoice,PatientInvoiceDetail,PatientPayment,PatientPaymentDetail}.java`
- `.../service/DayServiceImpl.java` (getTimeStamp +3h), `.../MainApplication.java:138` (UTC default)
- `.../models/RecordModel.java`
- Other numbering services: GoodsReceivedNoteServiceImpl, LocalPurchaseOrderServiceImpl, Payroll/Pharmacy/Store transfer service impls (see §5).