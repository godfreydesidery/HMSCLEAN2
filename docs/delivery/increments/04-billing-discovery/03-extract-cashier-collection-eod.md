## EXTRACTION 3 — Cashier / Collection / EOD Reconciliation (legacy `com.orbix.api`, read-only)

### Early-discovery findings (mandatory in every audit/auth-touching spec)
- **Audit trail:** No `@Audited` annotation appears on `Cashier`, `Collection`, `Day`, or any payment entity examined. Consistent with the standing project finding — **no Hibernate Envers audit trail is effectively active in the legacy system**. Downstream agents must not assume an Envers audit baseline exists. (Full-codebase `@Audited` confirmation belongs to the dedicated security/audit spec; within this extraction's scope, nothing audited was found.)
- **Device-fingerprint / device-binding:** Out of scope for the cashier/collection slice; nothing device-related touches these entities. Per the standing project finding, **no device-fingerprint or device-binding feature exists in the legacy system.**

---

### 1. `Cashier` entity — what it IS

`Cashier` is a **personnel/role-extension directory entity**, NOT a billing-time or shift concept. It is auto-provisioned when a `User` is granted the `"CASHIER"` role, in exactly the same pattern as `Nurse`, `Pharmacist`, `Clinician`, `StorePerson`.

- `@Table(name = "cashiers")`, `Cashier.java:27`. Fields: `id` (IDENTITY), `code` (unique, `@NotBlank`), `type` (free String, never read in any flow examined), `firstName`/`middleName`/`lastName`, `nickname` (unique, `@NotBlank`), `active` (default `false`), `@OneToOne User user` (optional), audit-stamp columns `createdBy`/`createdOn`/`createdAt` (`Cashier.java:29-55`).
- Auto-provisioning: `UserServiceImpl.java:243-266` — on role `"CASHIER"`, find-or-create a `Cashier` linked to the `User`, set `active=true`. Mirrors the `NURSE` block immediately above (`UserServiceImpl.java:225-240`).
- `CashierServiceImpl.save` (`CashierServiceImpl.java:35-59`): requires a matching `User` by code; rejects name mismatch with `InvalidOperationException("Provided names do not match with user account")` (`:42-43`); overwrites `nickname` to `"{first} {middle} {last} {code}"` (`:48`); sets `active=true` on create.
- `deleteCashier` is **hard-disabled**: `allowDeleteCashier` always returns `false`, so delete always throws `InvalidOperationException("Deleting this cashier is not allowed")` (`CashierServiceImpl.java:84-101`).
- `CashierResource.save` is gated `@PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")` (`CashierResource.java:64`). All GET endpoints are ungated. `assign_user_profile` and `load_cashier_by_username` are ungated and marked "to do later" (`CashierResource.java:76-110`).

**Key point for billing spec:** At the point of payment, the `Cashier` entity is **never consulted**. Payments and Collections are attributed to the logged-in `User` via `createdBy = userService.getUser(request).getId()` (e.g. `PatientBillResource.java:209,253,334`). The per-cashier collections report joins `collections` to **`users`** (not `cashiers`) and filters by `users.nickname` (`CollectionRepository.java:54`). So "cashier" in the report is really "the user who recorded the collection."

---

### 2. `Collection` entity — what it IS

`Collection` is a **per-payment-line cash-receipt ledger row** (one row per bill line settled), NOT a shift and NOT a daily aggregate. The daily/cash-up figure is derived at report time by `SUM` + `GROUP BY` over these rows; nothing is persisted as a "collection close."

- `@Table(name = "collections")`, `Collection.java:37`. Fields (`Collection.java:39-64`):
  - `id` (IDENTITY)
  - `amount` **`double`** `@NotNull` (`:44`) — money as `double` in legacy; per directive must become `NUMERIC(19,2)` BigDecimal.
  - `itemName` `@NotBlank` default `"NA"` (`:46`) — the revenue category label (e.g. "Registration", "Consultation", or the bill's `billItem`).
  - `paymentChannel` default `"Cash"` (`:47`).
  - `paymentReferenceNo` default `"NA"` (`:48`).
  - `@ManyToOne Patient patient` (required, `:50-53`).
  - `@ManyToOne PatientBill patientBill` (**optional/nullable**, `:55-58`).
  - audit stamps `createdBy` (user id, NOT null, NOT updatable), `createdOn` (day id), `createdAt` (`:60-64`).
- **No status field, no lifecycle.** A `Collection` is write-once; nothing reverses, voids, or closes it. There is no `OPEN/CLOSED`, no `SETTLED`, no shift FK.

**Creation sites — exactly THREE, all in `PatientBillResource`, none in `PatientServiceImpl`:**
A full-codebase scan for `new Collection()` returns only:
1. `PatientBillResource.java:202-212` — registration payment leg (in the confirm-registration/consultation flow). `itemName="Registration"`, `amount=registrationBill.getAmount()`.
2. `PatientBillResource.java:246-256` — consultation payment leg (same flow, loop over PENDING/IN-PROCESS consultations). `itemName="Consultation"`.
3. `PatientBillResource.java:327-337` — generic `confirm_bills_payment` (the main cashier "pay these bills" endpoint, `:269`). `itemName=b.getBillItem()` (defaults `"NA"` if null, `:322-325`), `amount=b.getAmount()`.

At **all three** sites `paymentChannel` is **hardcoded `"Cash"`** (`:206,250,331`) and `paymentReferenceNo="NA"`. A grep for `setPaymentChannel` across the whole codebase finds only these three literal `"Cash"` assignments — **no code path ever sets mobile/card/insurance/bank.** (See §5 ambiguity.)

Note `confirm_bills_payment` only pays bills with status `UNPAID` or `VERIFIED`, flips them to `PAID`, writes a `PatientPaymentDetail`, and the `Collection` — and asserts `totalAmount == sum(amounts)` else `InvalidOperationException("Could not confirm payment. Insufficient payment")` (`PatientBillResource.java:261-263` for the reg/consult flow; the generic flow accumulates `amount` similarly). Pharmacy sale orders are flipped to `APPROVED`/`PAID` in the same transaction (`:369-379`).

---

### 3. Cashier SHIFT (OPEN→CLOSED) — DOES IT EXIST? **NO — net-new.**

A full-codebase grep for `shift`, `openShift`, `closeShift`, `cashUp`, `cash_up`, `reconcil` (case-insensitive) across `com.orbix.api` returns **zero matches**. There is **no shift entity, no shift table, no open/close shift endpoint, no `NO_OPEN_SHIFT` equivalent.**

The legacy's only temporal grouping primitive is the `Day` ("business day") entity:
- `@Table(name="days")`, single boolean-ish `status` String `"STARTED"`→`"ENDED"` (`Day.java:33-45`). Fields: `bussinessDate` (unique LocalDate), `startedAt`, `endedAt`, `status`.
- Exactly one `STARTED` day at a time: `DayRepository.getCurrentBussinessDay()` = `select d from Day d where d.status='STARTED'` (`DayRepository.java:17-18`).
- `DayServiceImpl.endDay()` (`:54-73`): sets old day `endedAt`/`status="ENDED"`, then creates a **new** `Day` with `status="STARTED"` (default) and rolls `bussinessDate` forward. Gated `@PreAuthorize("hasAnyAuthority('ADMIN-ACCESS','DAY-ACCESS')")` (`DayResource.java:50`).
- **`endDay()` performs NO collection snapshot, NO aggregation, NO cash-up.** It only flips the status flag and opens the next day. There is no per-cashier reconciliation at day-close.

**Conclusion:** The spec's `CashierShift OPEN→CLOSED` with `NO_OPEN_SHIFT 409` and an "EOD collections snapshot" is **entirely net-new (a spec invention)**. The closest legacy primitive is the single global `Day` (system-wide, not per-cashier), and even that does not snapshot collections.

---

### 4. End-of-day collections / cash-up / reconciliation — how legacy actually does it

There is **no persisted cash-up**. "Reconciliation" is purely a **read-time report** computed on demand over the `collections` rows by `created_at` range. Two queries (`CollectionRepository.java:21-59`):

**General (all cashiers):** `getCollectionReportGeneral(from, to)` —
```
SELECT users.id AS user_id, collections.item_name AS itemName,
       collections.payment_channel AS paymentChannel,
       SUM(collections.amount) AS amount
FROM collections JOIN users ON users.id = collections.created_by_user_id
WHERE collections.created_at BETWEEN :from AND :to
GROUP BY itemName, paymentChannel
```
Projection interface `CollectionReport` exposes only `itemName`, `amount`, `paymentChannel` (`CollectionReport.java:11-13`) — note `user_id` is selected but NOT in the projection, and the `GROUP BY` is by `itemName, paymentChannel` only (NOT by user), so the "general" report sums across all users.

**Per-cashier:** `getCollectionReportByCashier(from, to, nickname)` — identical but adds `AND users.nickname = :nickname` to the WHERE (`CollectionRepository.java:54`). Same `GROUP BY itemName, paymentChannel`.

**Endpoint:** `ReportResource.getCollectionReportReport` `POST /reports/collections_report` (`ReportResource.java:661-671`), ungated. Branch: if `args.user.getNickname()` is non-empty → per-cashier; else → general (`:666-669`). Date range: `from.atStartOfDay()` to `to.atStartOfDay().plusDays(1)` (inclusive-of-`to`-day, exclusive of next day).

So **payment-mode totals are grouped at read time by `(itemName, paymentChannel)`** — but because `paymentChannel` is always `"Cash"` (§2), every row collapses into the single `"Cash"` channel. The report's "per-mode" capability is structurally present but functionally inert in the legacy data.

**Per-revenue-kind detail reports** (separate from the SUM report): seven sibling endpoints in `ReportResource` build per-line detail by loading raw `Collection` rows (`findAllByCreatedAtBetween` or `findAllByCreatedByAndCreatedAtBetween`, `CollectionRepository.java:61-63`) and matching `collection.getPatientBill()` against the kind's bill — `lab_test_collection_report` (`:674`), `radiology_collection_report` (`:720`), `procedure_collection_report` (`:766`), `prescription_collection_report` (`:812`), `admission_bed_collection_report` (`:858`), `consultation_collection_report` (`:903`), `registration_collection_report` (`:948`). These are detail listings, not the cash-up SUM. (Note an `==` identity comparison on boxed `Long` bill ids, e.g. `:705` — flagged as a latent bug in §5.)

---

### 5. Is a payment blocked unless a shift/collection/day is open? **NO gate exists.**

- No `NO_OPEN_SHIFT`-style gate — no shift exists at all.
- No **Day-open gate** either. `getCurrentBussinessDay()` and `dayService.getDay()` are used **only to stamp `createdOn` = day id** on payments/collections (e.g. `PatientBillResource.java:210,254,335`); they are never used as a guard before accepting payment. A grep confirms `getCurrentBussinessDay` is referenced only inside `DayServiceImpl` itself. `dayService.getDay()` calls `dayRepository.findById(getLastId()).get()` (`DayServiceImpl.java:81-83`) — it returns the latest day unconditionally and will NPE/`NoSuchElement` only if no day rows exist; it does not check `status`.
- Therefore in legacy, **a payment can be recorded regardless of any day's open/closed status.** The spec's `NO_OPEN_SHIFT 409` has **no legacy equivalent** and would be net-new hardening.

---

### Net summary for the increment-04 reconciliation
| Spec concept | Legacy reality |
|---|---|
| `CashierShift OPEN→CLOSED` | **Net-new / invented.** No shift exists. Closest is global `Day` STARTED→ENDED, which does not snapshot. |
| `NO_OPEN_SHIFT 409` payment gate | **Net-new.** No open-state gate of any kind exists; Day is never a payment gate. |
| EOD collections snapshot | **Net-new persistence.** Legacy computes cash-up at read time via `SUM(amount) GROUP BY itemName, paymentChannel` over `collections`. |
| Per-payment-mode totals | Structurally `GROUP BY paymentChannel`, but `paymentChannel` is always `"Cash"` — single-mode in practice. |
| `Cashier` = iam personnel extension? | **Yes** — auto-provisioned on `CASHIER` role (`UserServiceImpl.java:243-266`); parallels Nurse/Pharmacist. Not used at payment time; attribution is by `User`. |
| `Collection` = ? | **Per-bill-line cash-receipt ledger row**, write-once, no status, optional `PatientBill` FK, attributed to `User`. |