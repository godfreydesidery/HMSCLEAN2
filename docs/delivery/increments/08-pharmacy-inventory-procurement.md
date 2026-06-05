# Increment 08 — Pharmacy, Inventory & Procurement

## Goal

Deliver a fully operational pharmacy dispensing, retail-sales, stock-management, store-receiving, and procurement workflow: a pharmacist can receive a prescription, gate cash payment per line, dispense through the complete six-state lifecycle, and pull stock from a FEFO batch ledger; a store-keeper can receive goods against a GRN, ship stock to any pharmacy via the three-document RO/TO/RN dance, and a procurement officer can raise and close-out an LPO with three-way match — all with stock cards that update only at the correct terminal event.

---

## Scope

### Bounded contexts
`pharmacy` and `inventory` (distinct Spring Modulith modules); both call `billing.api`, `insurance.api`, and `registration.api` in the allowed direction per ADR-0008.

### Key aggregates and entities

**pharmacy module**
- `Prescription` (links to `clinical` module by `prescriptionUid` — reference only; no entity import) — status: `PENDING → ACCEPTED → HELD → VERIFIED → APPROVED → SOLD`; terminal: `REJECTED`, `CANCELLED`. Per-line `payStatus: UNPAID → PAID` gate for CASH patients.
- `PharmacySaleOrder` (OTC / OUTSIDER, no doctor Rx required) + `PharmacySaleOrderDetail` — identical status lifecycle to `Prescription`.
- `StockBatch` (FEFO batch per `(pharmacyUid, itemUid)`: `batchNo`, `expiryDate` nullable, `receivedQty NUMERIC(19,6)`, `remainingQty NUMERIC(19,6)`).
- `StockBalance` (running balance per `(pharmacyUid, itemUid)`; pessimistic-write lock on decrement, ADR-0017 §2).
- `StockMovement` — append-only ledger row per movement (RECEIPT, DISPENSE, TRANSFER_OUT, TRANSFER_IN, ADJUSTMENT, WASTAGE); carries `runningBalance` for each.
- `PharmacyToPharmacyRO` / `PharmacyToPharmacyTO` / `PharmacyToPharmacyRN` — RO/TO/RN three-document pharmacy-to-pharmacy transfer; `StockMovement` rows written only on RN `COMPLETED`.
- `PharmacyToStoreRO` / `StoreToPharmacyRN` — pharmacy ↔ store side.
- `ItemMedicineCoefficient` — conversion factor `NUMERIC(19,6)` applied on every movement crossing unit boundaries (carton → blister → tablet); multiplication uses full `BigDecimal` precision, no intermediate rounding (ADR-0009 §3).

**inventory module**
- `StoreItem` / `StoreItemBatch` — store-side stock, same FEFO discipline; `StoreStockBalance` + `StoreStockMovement` ledger.
- `StoreToPharmacyTO` — store prepares shipment.
- `LocalPurchaseOrder` (LPO) — `DRAFT → PENDING → VERIFIED → APPROVED → SUBMITTED → RECEIVED`; terminal: `REJECTED`, `RETURNED`.
- `GoodsReceivedNote` (GRN) — `PENDING → VERIFIED → APPROVED`; terminal: `REJECTED`, `RETURNED`. Stock credited to `StoreItemBatch` + `StoreStockBalance` atomically on `APPROVED` (per ADR-0008 §2 "must-stay-in-tx").
- `Supplier` / `SupplierItemPrice` — vendor master + per-supplier price list.
- `ThreeWayMatch` — LPO qty vs GRN qty vs supplier invoice qty; payment release gated on match.

### Key REST endpoints
```
# Pharmacy dispensing
GET    /api/v1/pharmacy/prescriptions                          (worklist; ?pharmacyUid= &patientClass= &settledOnly=)
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/accept
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/hold
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/verify
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/approve
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/sell
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/reject
POST   /api/v1/pharmacy/prescriptions/uid/{uid}/cancel

# OTC sale orders
POST   /api/v1/pharmacy/sale-orders
GET    /api/v1/pharmacy/sale-orders/uid/{uid}
POST   /api/v1/pharmacy/sale-orders/uid/{uid}/details
POST   /api/v1/pharmacy/sale-orders/uid/{uid}/details/uid/{detailUid}/sell

# Stock
GET    /api/v1/pharmacy/stock/uid/{pharmacyUid}/items         (stock status per pharmacy)
GET    /api/v1/pharmacy/stock/uid/{pharmacyUid}/items/uid/{itemUid}/batches
GET    /api/v1/pharmacy/stock/uid/{pharmacyUid}/items/uid/{itemUid}/movements

# Transfers — Pharmacy-to-Pharmacy
POST   /api/v1/pharmacy/p2p-ros
GET    /api/v1/pharmacy/p2p-ros/uid/{uid}
POST   /api/v1/pharmacy/p2p-ros/uid/{uid}/verify
POST   /api/v1/pharmacy/p2p-ros/uid/{uid}/approve
POST   /api/v1/pharmacy/p2p-tos
POST   /api/v1/pharmacy/p2p-tos/uid/{uid}/goods-issue
POST   /api/v1/pharmacy/p2p-rns
POST   /api/v1/pharmacy/p2p-rns/uid/{uid}/complete            (triggers stock cards on both sides)

# Transfers — Pharmacy-to-Store
POST   /api/v1/pharmacy/ps-ros
POST   /api/v1/inventory/sp-tos
POST   /api/v1/inventory/sp-rns/uid/{uid}/complete

# Store
GET    /api/v1/inventory/store/uid/{storeUid}/stock
GET    /api/v1/inventory/store/uid/{storeUid}/batches/item/uid/{itemUid}

# Procurement
POST   /api/v1/inventory/lpos
GET    /api/v1/inventory/lpos/uid/{uid}
POST   /api/v1/inventory/lpos/uid/{uid}/verify
POST   /api/v1/inventory/lpos/uid/{uid}/approve
POST   /api/v1/inventory/lpos/uid/{uid}/submit
POST   /api/v1/inventory/lpos/uid/{uid}/receive
POST   /api/v1/inventory/grns
GET    /api/v1/inventory/grns/uid/{uid}
POST   /api/v1/inventory/grns/uid/{uid}/verify
POST   /api/v1/inventory/grns/uid/{uid}/approve               (stocks credited here)
POST   /api/v1/inventory/suppliers
GET    /api/v1/inventory/suppliers/uid/{uid}/price-list
POST   /api/v1/inventory/three-way-match/uid/{grnUid}/validate
```

### Process states implemented (PROCESS.md §8, §9, §10, §15, §16)
Full prescription lifecycle (§8.1); OTC sale order lifecycle (§8.2); FEFO stock model with batch + expiry + stock-card (§8.3); Pharmacy-to-Pharmacy RO/TO/RN dance (§8.4); Pharmacy ↔ Store transfer (§8.5); `issuePharmacy` / `salesPharmacy` split per line (§8.6); store GRN → `StoreItemBatch` credit (§9.1–9.2); conversion coefficients on every cross-unit movement (§16.3); negative-stock guard (§16, ADR-0017 §2); LPO lifecycle (§10.3); GRN PENDING→VERIFIED→APPROVED (§10.4); three-way match (§16.11).

---

## Dependencies

- **Increment 00 (Walking Skeleton & Shared Kernel)** — shared kernel (`AuditableEntity`, `Money`, `TxAuditContext`, `BusinessDay`), Flyway baseline, CI gates, `ApplicationModules.verify()`.
- **Increment 01 (Identity & Access)** — 177 privilege codes including `PRESCRIPTION-ALL`, `GOODS_RECEIVED_NOTE-CREATE`, `LOCAL_PURCHASE_ORDER-ALL`, etc.; `@PreAuthorize` wired.
- **Increment 02 (Master Data & Reference Seeding)** — `Pharmacy`, `Store`, `Medicine`, units/`ItemMedicineCoefficient`, `Supplier` masterdata seeded via Flyway.
- **Increment 04 (Billing, Cashiering & Insurance)** — `billing.api.recordClinicalCharge()`, the `ServicePrice` matrix, the `settled`-flag pattern + `SettlementDispatcher` — required for the per-line UNPAID→PAID dispensing gate and charge accrual.
- **Increment 05 (Clinical / OPD)** — the `Prescription` aggregate is created in the `clinical` module; `pharmacy` reads prescriptions by uid only (no entity import) and the `PENDING` state must exist before `accept()`. *(The stock, procurement, and retail-sale sub-features have no clinical dependency.)*

---

## Exact-process fidelity targets

1. **Prescription pay gate (PROCESS.md §8.1):** For CASH patients, `accept()` is callable on `PENDING` regardless of pay-status (pharmacist needs to see the Rx), but `sell()` must hard-reject unless every `Prescription` line has `payStatus = PAID`. Non-CASH patients are treated as COVERED and `sell()` proceeds without a pay-status check. This is a hard service-layer gate, not a worklist filter (see PHARM-2, M3, M23).

2. **FEFO dispatch with locking (PROCESS.md §8.3, ADR-0017 §2):** `StockBatchRepository.lockFefoForDispense(pharmacyUid, itemUid)` acquires `SELECT FOR UPDATE` on batches ordered `expiry_date ASC NULLS LAST`. Negative-stock guard runs after the lock is held, not before. Test: two concurrent `sell()` requests for the last unit of stock — exactly one succeeds, the other gets `422 Unprocessable` with `ErrorCode.INSUFFICIENT_STOCK`.

3. **Stock cards update only on RN COMPLETED (PROCESS.md §8.4 step 4, §16.2):** `StockMovement` rows for `TRANSFER_OUT` (source pharmacy) and `TRANSFER_IN` (destination) are written inside the same transaction as `PharmacyToPharmacyRN.complete()`, not at TO creation or goods-issue. A test must assert zero `StockMovement` rows on the source batch until the RN is marked `COMPLETED`.

4. **GRN approval credits store stock atomically (PROCESS.md §10.4–10.5, ADR-0008 §2):** `GoodsReceivedNote.approve()` writes `StoreItemBatch` rows + `StoreStockBalance` increment + `StoreStockMovement` (RECEIPT) + updates `LocalPurchaseOrder.status = RECEIVED` — all in one `@Transactional` boundary. No async event boundary crosses this set of writes.

5. **Document-number formats (PROCESS.md §10, ADR-0009 §5–6):**
   - GRN: `GRN{yyyyMMdd}-{seq}` via `seq_grn_no`
   - LPO: `LPO{yyyyMMdd}-{seq}` via `seq_lpo_no`
   - Pharmacy-to-Pharmacy RO: `PPR{yyyyMMdd}-{seq}` via `seq_ppr_no`
   - Pharmacy-to-Pharmacy TO: `PPTO{yyyyMMdd}-{seq}` via `seq_ppto_no` (replaces legacy `SPT`, ADR-0009 §6)
   - Pharmacy-to-Pharmacy RN: `PPRN{yyyyMMdd}-{seq}` via `seq_pprn_no`
   - Pharmacy-to-Store RO: `PSR{yyyyMMdd}-{seq}` via `seq_psr_no`
   - StoreToPharmacy TO: `SPTO{yyyyMMdd}-{seq}` via `seq_spto_no` (replaces legacy `SPT`, ADR-0009 §6)
   - StoreToPharmacy RN: `PGRN{yyyyMMdd}-{seq}` via `seq_pgrn_no`
   Each `next()` call uses `DocumentNumberService` (ADR-0009 §5); date formatted in `Africa/Dar_es_Salaam` timezone.

6. **Conversion coefficient precision (PROCESS.md §16.3, ADR-0009 §3):** `ItemMedicineCoefficient.coefficient` is `NUMERIC(19,6)`; `transferQty.multiply(coefficient)` carries full `BigDecimal` precision to the stored `StockMovement.qty`. Parity test: coefficient = 1/3, transfer qty = 9 cartons → movement qty = 3.000000 units (no truncation artefact).

7. **issuePharmacy / salesPharmacy split (PROCESS.md §8.6):** Each `Prescription` line stores both `issuePharmacyUid` (where Rx filled) and `salesPharmacyUid` (where stock pulled). These may differ legally without a formal transfer document; the stock decrement hits `salesPharmacyUid`, the dispense record stamps `issuePharmacyUid`. Parity test: fill at pharmacy A, pull from pharmacy B — stock on B decrements, A's stock is unaffected.

8. **Three-way match (PROCESS.md §16.11):** `ThreeWayMatchService.validate(grnUid)` asserts `LPO.orderedQty == GRN.receivedQty == SupplierInvoice.qty` per line (within configurable tolerance). Supplier invoice qty is input at GRN detail level. Validation returns per-line `MATCH` / `VARIANCE` and an overall `PASS`/`FAIL`; payment release endpoint checks `PASS`.

9. **Pharmacy session scoping (lessons brief PHARM-1):** The front-end passes `pharmacyUid` on every dispensing, stock, and transfer call. The backend validates the authenticated user is affiliated with the named pharmacy (M:N `pharmacy_staff` table seeded in I01). No server-side session state. All worklist queries are implicitly scoped: `GET /api/v1/pharmacy/prescriptions?pharmacyUid=<uid>&settledOnly=true`.

---

## Prior-attempt pitfalls to avoid

- **PHARM-2 / M3 / M23 — pay gate as filter, not hard gate.** The prior build allowed `accept()` and `hold()` to proceed on unpaid CASH prescriptions; only `markSold()` had the gate. The fresh build must enforce: `sell()` on any CASH prescription line with `payStatus = UNPAID` returns `422` with `ErrorCode.PRESCRIPTION_UNPAID`. `accept()` through `approved()` transitions are payment-agnostic (the pharmacist needs to work the queue); the gate is on the terminal dispense event.
- **PHARM-1 — no pharmacy session scoping.** Every dispensing endpoint must require an explicit `pharmacyUid` parameter; the controller validates affiliation before any state transition. Do not default to "any pharmacy" or allow cross-pharmacy dispense without explicit `salesPharmacyUid`.
- **M13 / DIAG-2 — settle filter, not gate.** The prescription worklist must default `settledOnly=true` for OUTPATIENT and OUTSIDER CASH patients. INPATIENT CASH prescriptions remain visible when status is `VERIFIED` (insurer verifies at discharge) — this is the documented exception, not a gap.
- **Legacy finding C / ADR-0009 §5 — MAX(id)+1 race for document numbers.** All eight document types above must use their dedicated PostgreSQL sequence, obtained via `DocumentNumberService.next(DocType)`, inside the same transaction as the row insert (no double-save pattern). Test: concurrent GRN creation produces no duplicate `no` values.
- **Legacy finding D / ADR-0009 §6 — SPT prefix collision.** `StoreToPharmacyTO` must use `SPTO` and `PharmacyToPharmacyTO` must use `PPTO`. Any test or seed script using the legacy `SPT` prefix is a defect.
- **ADR-0017 §2 — optimistic lock on stock (not pessimistic by default).** Only the `StockBalance` and `StockBatch` decrement path uses `PESSIMISTIC_WRITE`; all other entity updates (Prescription status transitions, LPO status, GRN status) use the `@Version` optimistic lock from the base entity. Surfacing an `OptimisticLockException` as `409 Conflict` with `ErrorCode.STALE_ENTITY` is required so the Angular client can prompt retry without string-matching.
- **ADR-0008 §2 — GRN approve must stay in-tx.** Do not emit a `GrnApproved` application event and handle stock credit in an async listener. The stock-card write and GRN status flip are one atomic operation. Events are permitted only for non-essential side-effects (reporting projections, notifications).
- **BILL-5 (open gap) — pharmacy sales report.** The prior build had no pharmacy-sales revenue breakdown by payment mode. This increment must emit `StockMovement` rows with enough context (pharmacyUid, itemUid, patientUid, saleType=PRESCRIPTION/OTC, amount) that reporting module can aggregate without crossing module boundaries.

---

## Lead & supporting agents

- **Lead:** backend-engineer, healthcare-domain-expert
- **Supporting:** engagement-lead (scope authority), solution-architect (module-boundary enforcement, ADR-0008/0017 compliance), data-architect (StockBatch/StockBalance schema, FEFO query design, coefficient precision), security-architect (RBAC — 177-privilege gate for all pharmacy and procurement endpoints), ux-ui-designer (pharmacy worklist UX: working-pharmacy selector, per-line pay-status indicators, FEFO batch picker, GRN detail form), frontend-engineer (Angular 18 standalone + signals screens), qa-test-engineer (golden-master concurrency tests, parity harness extension), code-reviewer (PR gate), devops-engineer (CI gate for new module boundaries)

---

## Definition of Done

- [ ] `ApplicationModules.verify()` passes: `pharmacy` and `inventory` modules expose only `*.api` types externally; no `@Entity` crosses module boundaries; `billing.api`, `insurance.api`, and `registration.api` are the only allowed outbound dependencies.
- [ ] ArchUnit rule confirms: no class inside `pharmacy.internal.*` or `inventory.internal.*` calls `LocalDateTime.now()` directly or calls `dayService`; all stamp through `TxAuditContext` received from the application/process layer.
- [ ] Full prescription lifecycle driven end-to-end via the Angular UI against a live PostgreSQL 16 (Testcontainers): PENDING → ACCEPTED → HELD (cash gate visible) → VERIFIED → APPROVED → SOLD; stock decremented on SOLD.
- [ ] CASH payment gate: `sell()` with any UNPAID line returns HTTP 422 with `ErrorCode.PRESCRIPTION_UNPAID`; golden-master test asserts the error code.
- [ ] FEFO concurrency test: two parallel `sell()` requests for the last unit — one 200 OK, one 422 INSUFFICIENT_STOCK; no negative balance in `stock_balance` table after test.
- [ ] RN-only stock-card test: zero `StockMovement` rows on source pharmacy until `PharmacyToPharmacyRN.complete()` fires; after `complete()`, exactly one TRANSFER_OUT row on source and one TRANSFER_IN row on destination with correct coefficients applied.
- [ ] GRN approve atomicity test: GRN → APPROVED → `StoreItemBatch` rows created + `StoreStockBalance` incremented + `StoreStockMovement` RECEIPT row — all in one transaction; simulated mid-transaction failure rolls back all four writes.
- [ ] Document numbers: each of the eight document types produces `{PREFIX}{yyyyMMdd EAT}-{seq}` with no duplicates under concurrent creation (parallel test per type). SPTO and PPTO are used, never SPT.
- [ ] Coefficient precision test: `coefficient = 1/3`, `transferQty = 9` → `StockMovement.qty = 3.000000` (NUMERIC(19,6), no truncation).
- [ ] Pharmacy session scoping: all dispensing and stock endpoints require `pharmacyUid`; requests from a user not in `pharmacy_staff` for that pharmacy return `403 Forbidden`.
- [ ] Prescription worklist defaults `settledOnly=true` for OUTPATIENT/OUTSIDER CASH; INPATIENT CASH visible at VERIFIED status — golden-master assertion.
- [ ] LPO full lifecycle (DRAFT→SUBMITTED→RECEIVED) and GRN (PENDING→APPROVED) driven end-to-end via UI; three-way match endpoint returns per-line MATCH/VARIANCE.
- [ ] OTC / OUTSIDER pharmacy sale order created, dispensed, stock decremented via UI.
- [ ] All pharmacy and procurement endpoints annotated with the correct `@PreAuthorize` privilege codes (from the 177-code set seeded in I01); security integration test asserts 403 for wrong roles.
- [ ] OpenAPI 3 spec updated; generated Angular client used by all pharmacy and inventory screens (no hand-rolled HTTP calls).
- [ ] Audit events (`AuditableEntity` + `TxAuditContext`) emitted for every state transition; `businessDayId` stamped on all new transactional rows.
- [ ] Code-reviewer approved; no Spring Modulith boundary violations; no ArchUnit failures; CI green on build, Testcontainers suite, Flyway validate, and parity harness extension for pharmacy/inventory scenarios.
