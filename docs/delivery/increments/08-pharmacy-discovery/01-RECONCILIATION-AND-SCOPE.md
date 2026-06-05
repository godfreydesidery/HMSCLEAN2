# Inc-08 Discovery & Reconciliation — Pharmacy / Inventory / Procurement

**Date:** 2026-06-05 · **Workflow run:** `wf_94b8d3cf-031` (18 agents, ~12 min, ~1.15M tokens)
**Raw output:** [00-discovery-raw.json](00-discovery-raw.json) · **Workflow script:** [00-discovery-workflow.mjs](00-discovery-workflow.mjs)

**Method:** 1 as-built inventory (inc-00..06) ‖ 8 legacy extraction lanes (rx-lifecycle, otc-saleorder, fefo-stock, p2p-transfers, ps-sp-transfers, grn-lpo-procurement, numbering, rbac-scoping) — each adversarially reconciled against the inc-08 planning doc → solution-architect scope synthesis. Every finding carries a legacy `file:line` citation; legacy source = `ZANAHMIS-2-feature/.../com/orbix/api` used as a **specification oracle only** (no migration).

---

## VERDICT: `INC08_IS_A_REAL_FULL_BUILD` — the categorical *opposite* of inc-06

inc-06 was `MOSTLY_DONE`: the L/R/P code already existed, so the dominant risk was the planning doc **asserting phantom lifecycle states over an already-built module**. inc-08 is the **reverse**: the `pharmacy` and `inventory` bounded contexts are confirmed **EMPTY GREENFIELD STUBS** — `pharmacy/package-info.java:1-4` and `inventory/package-info.java:1-4` each hold only an `@ApplicationModule` package declaration ("Stub for increment 00"); no domain/application/web subpackages. Almost every entity, state machine, stock ledger, transfer chain and procurement document must be **built from scratch against the legacy oracle**.

**The inc-06 lesson still applies, inverted.** With no as-built to constrain it, the planning doc has **drifted hard toward an idealized modern design** (8-state prescription lifecycle, three-way match, salesPharmacy split, `pharmacy_staff` RBAC, FEFO-on-OTC, destination-batch-on-p2p-receipt, pessimistic locking) that has **no legacy basis**. The build must extract the real — often messier, two-state, race-prone, exception-swallowing — legacy behaviour and treat every "improvement" as a **change request**, not as exact-process.

**Headline counts:** 18 reconciliation drift items (3 PHANTOM rejected, 7 DRIFT corrected, 5 CORRECTED data/detail, plus the RBAC/scoping pair) · 20 NEW-DRIFT omissions the plan never mentions · 11 owner decisions. **Recommendation: split into `08a` (dispensing + stock core) and `08b` (store / procurement / transfers).**

---

## ALREADY BUILT in inc-00..06 (do NOT rebuild)

1. **Clinical `Prescription` aggregate** — the ONLY prescription state machine that exists is `NOT-GIVEN → GIVEN` (`PrescriptionStatus.java:23-26`, exactly two hyphenated values via `PrescriptionStatusConverter`). The single transition `Prescription.issue()` exists with the **stock-decrement TODO explicitly deferred to inc-08** (`Prescription.java:534-537`; `PrescriptionPort.issueMedicine` javadoc `PrescriptionPort.java:73`). inc-08 must NOT model ACCEPTED/HELD/VERIFIED/APPROVED/SOLD — those columns are dead in legacy.
2. **Per-line clinical settlement** — the `settled` boolean (`Prescription.java:216-217`, V36), `paymentType`/`membershipNo` denormalisation, `markSettled()` driven by `shared.event.BillSettledEvent`, and the `SettlementDispatcher` + `@TransactionalEventListener(BEFORE_COMMIT)` wiring all exist (billing inc-04 + clinical). inc-08 reuses this; it does **not** build a new `payStatus` mechanism for clinical prescriptions.
3. **`billing::api` charge + read seams** — `BillingCommands.recordClinicalCharge(ChargeRequest{kind=MEDICINE,…}, TxAuditContext) → ChargeResult` (`BillingCommands.java:34`) for medicine pricing (route medicine charges through this; do **not** reimplement plan pricing); `cancelCharge` / `approveInvoicesForBills`; `BillingQueries.getBillStatus(billUid) → BillStatus` (UNPAID/VERIFIED/COVERED/PAID/CANCELED, `BillingQueries.java:36`, added inc-06A C4) for any live dispense bill-gate; `SettlementPolicy.requiresPrepayment(…)` for the CASH pay-before-service rule.
4. **`DocumentNumberService` scaffold** (in `billing.application`, **not** `shared.documentnumber`) — `next(DocumentType) → prefix+yyyyMMdd(EAT)+'-'+seq` **UNPADDED**, test-overridable `Clock` (`DocumentNumberServiceImpl.java:24-49`). **Limitation:** `DocumentType` enum has ONLY `PCN`; the impl switch maps only `PCN → creditNoteRepository.nextPcnNo()`. inc-08 reuses the *pattern*, not a finished multi-type service.
5. **DB document sequences ALL pre-seeded in V13** (inc-02, START WITH 1, currently unused): `seq_grn_no` (V13:20), `seq_lpo_no` (:23), `seq_spto_no` (:33), `seq_ppto_no` (:37), `seq_pgrn_no` (:41), `seq_pprn_no` (:45), `seq_ppr_no` (:49), `seq_psr_no` (:53). **`md_document_types` registry rows** seeded in V14 (CR-10 ratified: SPTO+PPTO; **NO row carries `SPT`**). **inc-08 needs NO new sequence-creation migrations** for these streams; it starts at **V39**.
6. **Masterdata SCHEMAS** (inc-02, V6/V7) — `pharmacies`, `stores`, `theatres`, `medicines` (cash `price` on row), `items` (`pack_size`/`vat`/`cost`/`selling`), `item_medicine_coefficients` (`coefficient = medicine_qty/item_qty`, CHECK `>0`, UQ(item,medicine)), `suppliers`, `items_suppliers`, `supplier_item_prices`. **Created but NOT seeded** with business rows — empty schemas awaiting CRUD. No separate units table (uom/category free-text, CR-07).
7. **Shared kernel** (inc-00) — `AuditableEntity` (hidden BIGINT id + ULID uid + `@Version` optimistic lock), `Money` `@Embeddable` (NUMERIC(19,2) HALF_UP, default TZS), `TxAuditContext`, `BusinessDay` (OPEN/CLOSED + `NoDayOpenException` gate), `ErrorCode` RFC7807 catalogue, `shared.event`, `shared.storage` (`FileStoragePort`/`LocalDiskFileStorage`), and **`shared.audit` (`AuditRecorder` + `AuditLog` + `AuditAction`) — the REAL application-level audit (Envers is NOT used)**.
8. **IAM `Pharmacist` personnel record** (`Pharmacist.java`, `@Table "pharmacists"`, OneToOne User, created on PHARMACIST role) exists — but it is a **role-personnel record, NOT a pharmacy-location affiliation**. There is no pharmacist→pharmacy table (and legacy has none either).

---

## GROUND-TRUTH LEGACY MODEL (supersedes the planning doc where they conflict)

### A. Two parallel dispensing record types — *no single pharmacy aggregate*
1. **Clinical `Prescription`** (doctor-created): `NOT-GIVEN` (`PatientServiceImpl.java:1532`) → `GIVEN` (`PatientResource.java:3230`). accept/hold/reject/verify columns are **dead for medicine**. Dispense reuses `approvedBy/On` (`:3233-3235`), not `soldBy`.
2. **Walk-in `PharmacySaleOrder`** + `PharmacySaleOrderDetail`, tied to a standalone `PharmacyCustomer` (NOT a clinical Patient).

### B. Clinical dispense (`POST /patients/issue_medicine`, `PatientResource.java:3199-3293`)
Guards in order: (1) prescription exists; (2) status `== NOT-GIVEN`; (3) issued valid; (4) **issued == qty** ("You can only issue the prescribed qty" — all-or-nothing). **NO bill-status check at this terminal.** Then: status→GIVEN (saved BEFORE the stock check), set `issuePharmacy`, hard negative-stock guard `PharmacyMedicine.stock < qty` else throw, decrement `PharmacyMedicine.stock` by `getQty()`, write a `PharmacyStockCard` OUT row (`balance`=post-stock, reference `"Issued in prescription: id <n>"`, qtyOut=`getIssued()`), then `deductBatch` FEFO over `PharmacyMedicineBatch` (writes `PrescriptionBatch` lot-trace rows).

### C. CASH pay gate = WORKLIST FILTER, *not* a hard service gate (clinical)
Enforced ONLY in the worklist endpoints: OUTPATIENT & OUTSIDER require bill `PAID|COVERED` (`PatientResource.java:4347,4364`); INPATIENT additionally admits `VERIFIED` (`:4381,4410`). Bill status set at prescription creation: CASH→UNPAID; INSURANCE+covered→COVERED (paid in full at plan price); **INPATIENT+no covered plan→VERIFIED (post-pay/credit — this is *inpatient credit*, NOT "insurer verifies at discharge")**. The clinical terminal is **bypassable by direct call**.

### D. Walk-in OTC lifecycle (DISTINCT from prescription, NOT identical)
Header `PENDING → APPROVED → ARCHIVED` (+ `PENDING → CANCELED`). **PENDING→APPROVED is a side effect of paying the linked PatientBill via billing `confirm_bills_payment`** (`PatientBillResource.java:269-393`) — no standalone approve endpoint. Detail carries TWO independent fields: fulfilment `status` (NOT-GIVEN→GIVEN) and `payStatus` (UNPAID→PAID). Dispense (`give_medicine`, `PatientResource.java:6212-6322`) hard-gates on **header `status==APPROVED` only** (never re-checks payStatus), forces issued==qty, hard negative-stock guard, decrements aggregate stock, writes a stock-card OUT row — and **does NOT consume FEFO batches (`deductBatch` COMMENTED OUT at `:6317`)**. cancel only from PENDING; archive only from APPROVED + all details GIVEN; **24h auto-cancel** of stale PENDING and **24h auto-archive** of completed APPROVED (`PatientServiceImpl.java:3199-3229`). OTC bills hang off a **GENERAL dummy patient**; amount = flat `Medicine.price * qty`, **paymentType hardcoded CASH** (incoming value ignored), no insurance/co-pay/discount. Customer numbering has TWO divergent formats (`'PCST'+id` vs `'PCST/'+year+'/'+id`). Order no = `'PSO/'+id` (no date, no Formater).

### E. Stock model: TWO independent, frequently-DESYNCED stores
1. **Aggregate** `PharmacyMedicine.stock` (plain double, authoritative, read-modify-write). **NO `StockBalance` entity.** On pharmacy registration, a stock=0 row + opening stock-card are eagerly created for EVERY medicine (`PharmacyServiceImpl.java:67-91`).
2. **Per-lot** `PharmacyMedicineBatch` (no `@NotBlank`, dates nullable, qty double, **no audit cols, no `@Version`**); a single `qty` decremented in place (NOT received/remaining).
3. **Ledger** `PharmacyStockCard` append-only (qtyIn/qtyOut/balance doubles + free-text `reference` — **no movement-type enum**; type conveyed only via the reference string). `balance` is a stored post-movement snapshot, not recomputed.

**FEFO** = in-memory over an **UNORDERED** query. `getEarlierBatch`: if ANY batch has a non-null expiry → pick earliest expiry and **silently EXCLUDE null-expiry lots** (can be stranded); if none has expiry → fall back to **lowest id (FIFO)**. `deductBatch` recurses and **swallows ALL exceptions in an empty catch** (`PatientResource.java:3296-3336`) — a batch shortfall is silent; only `PharmacyMedicine.stock < qty` is a hard gate. **NO database locking anywhere** (zero `@Lock`/PESSIMISTIC/SELECT FOR UPDATE; no `@Version` on batch). FEFO logic is **DUPLICATED** in `PatientResource` (writes trace rows) and `PharmacyToPharmacyTOServiceImpl` (no trace rows). Manual `update_stock` (`MEDICINE_STOCK-UPDATE`) **OVERWRITES stock absolutely** (not delta), rejects negative, touches NO batches.

### F. Pharmacy↔Pharmacy transfer (3-document chain)
`PharmacyToPharmacyRO` (PPR) → `PharmacyToPharmacyTO` (legacy prefix **SPT**) → `PharmacyToPharmacyRN` (PPRN). RO: PENDING→VERIFIED→APPROVED→SUBMITTED, then IN-PROCESS (on TO create)→GOODS-ISSUED (on TO issue)→COMPLETED (on RN approve); RETURNED/REJECTED **only from SUBMITTED** — once IN-PROCESS the RO can never be returned (dead-end). TO created only from a SUBMITTED RO (idempotent). TO: PENDING→VERIFIED→APPROVED→GOODS-ISSUED→COMPLETED; **no ownership guard**. **SOURCE stock deduction + source stock-card OUT + source FEFO deduction happen on `TO.issue()` (APPROVED→GOODS-ISSUED), NOT on RN** (`PharmacyToPharmacyTOServiceImpl.java:215-283`). `transferedQty` accumulated via `add_batch` (PENDING-only, cumulative ≤ orderedQty). RN is **two-state PENDING→COMPLETED** (no verify/approve). DESTINATION increment + IN card on RN approve_receipt — **but destination `PharmacyMedicineBatch` rows are NEVER created (commented out, `PharmacyToPharmacyRNServiceImpl.java:221-232`)**. Source-debit and destination-credit are different documents AND different transactions (a credited-late window exists).

### G. Pharmacy↔Store transfer (3-document chain, *with* unit conversion)
`PharmacyToStoreRO` (PSR, moves no stock) → `StoreToPharmacyTO` (legacy prefix **SPT** — collision twin) → `StoreToPharmacyRN` (PGRN, the PHARMACY's own GRN). **STORE stock decrements ONLY in `TO.issue()`** (`StoreToPharmacyTOServiceImpl.java:221-289`): hard negative-stock reject, decrement `StoreItem.stock`, OUT card, FEFO over `StoreItemBatch`. **PHARMACY stock increments ONLY in RN approve_receipt** (`StoreToPharmacyRNServiceImpl.java:201-242`): IN card (only if qty>0) and **DOES create one `PharmacyMedicineBatch` per `StoreToPharmacyBatch`** (unlike p2p). **Unit conversion is real here**: `pharmacySKUQty = storeSKUQty * coefficient` (`StoreToPharmacyTOServiceImpl.java:417-424`), hard-fails if no coefficient. **CRITICAL:** the RN PENDING→COMPLETED guard lives in the **CONTROLLER** (`InternalOrderResource.java:725-748`), not the service — direct service call double-posts (latent bug). No over-receipt guard. Store uses the SAME single-ledger + scalar-`StoreItem.stock` discipline (no balance table).

### H. Procurement: LPO → GRN (two-document linear, **NO three-way match**)
`LocalPurchaseOrder` (LPO): initial **PENDING** (no DRAFT) → VERIFIED → APPROVED → SUBMITTED → (RECEIVED, flipped by GRN approval). REJECTED/RETURNED only from PENDING/VERIFIED; RETURNED has no re-open. LPO editable only while PENDING and only `validUntil` mutable. LPO detail price **copied from `SupplierItemPrice`**; a (supplier,item) with no price row is **hard-rejected**; active flags exist but NOT enforced. `GRN` HEADER = **PENDING → APPROVED only** (no header VERIFIED/REJECTED/RETURNED — copy-paste worklist filter). Verification is **per-LINE** (`GoodsReceivedNoteDetail`: NOT-VERIFIED → VERIFIED), gated on `receivedQty ≤ orderedQty` AND `sum(batch.qty) == receivedQty` (the ONLY real reconciliation). `approve()` hard-gates that EVERY line is VERIFIED, then per line with `receivedQty>0`: `StoreItem.stock += receivedQty`, IN card, copy each GRN batch into a NEW `StoreItemBatch` (no merge), **write a `Purchase` ledger row** (qty=receivedQty, amount=receivedQty*price) — then flip LPO→RECEIVED, GRN→APPROVED, **all in one `@Transactional`**. GRN-create requires `request store == LPO store` AND `LPO status==SUBMITTED`; **one GRN per LPO** (no partial/multi-receipt). **There is NO three-way match and NO `SupplierInvoice` entity anywhere.** `GRN.approve()` returns `null` despite its signature (legacy bug). All money/qty is double.

### I. Document numbering
Server-assigned = `Formater.formatWithCurrentDate(prefix, MAX(id)+1)` = `PREFIX + yyyyMMdd + '-' + rawId` (UNPADDED, no fiscal reset, **MAX(id)+1 race**). Verbatim legacy prefixes: GRN, LPO, PPR (p2p RO), PPRN (p2p RN), PSR (p2s RO), PGRN (s2p RN). **TO prefix is literal `SPT` for BOTH transfer types — the confirmed collision.** Two ownership models: **TO/RN/GRN numbered server-side**; **RO numbers (PPR, PSR) are CLIENT-SUPPLIED** (preview-and-post-back; server never `setNo`). `PharmacySaleOrder.no = 'PSO/'+id`. GRN double-saves with `no='NA'` placeholder first (off-by-one quirk).

### J. RBAC / scoping
Method-level `@PreAuthorize` only; **NO `.anyRequest().authenticated()` (commented)** → any un-annotated endpoint is anonymous-reachable. Coverage deliberately **UNEVEN**: the entire `PharmacySaleOrder` flow is UNGATED (`@PreAuthorize` commented at `:298`), GRN list/search ungated, all Supplier endpoints ungated. Live pharmacy codes: only `ADMIN-ACCESS` (pharmacies/save) and `MEDICINE_STOCK-UPDATE`. LPO/GRN transitions gated by a SINGLE coarse code each (`LOCAL_PURCHASE_ORDER-ALL`, `GOODS_RECEIVED_NOTE-ALL`) — **no segregation of duties**. JWT carries only subject+privileges — **no pharmacy/store claim**. **No server-side pharmacy session scoping and NO pharmacist→pharmacy affiliation table** (PHARM-1 confirmed). The only staff↔location precedent is `StorePerson.@ManyToMany Collection<Store>`, a **soft UI filter for stores only**, never a write gate.

---

## PLANNING-DOC DRIFT (rejected / corrected — phantom or contradicts verified legacy)

| # | Type | Claim | Reality (legacy cite) |
|---|------|-------|------------------------|
| D1 | **PHANTOM** | 8-state Rx lifecycle PENDING→ACCEPTED→HELD→VERIFIED→APPROVED→SOLD (+REJECTED/CANCELLED) | Medicine flow is exactly `NOT-GIVEN→GIVEN`; accept/hold/verify columns dead; no SOLD (`PrescriptionStatus.java:23-26`). The inc-06 dormant-columns trap. |
| D2 | **DRIFT** | Hard service-layer `sell()` gate on per-line payStatus, "not a worklist filter" | `issue_medicine` has **no terminal pay check**; enforcement is the worklist FILTER only. No `accept()` exists. Hardening is a **CR**, not exact-process. |
| D3 | **PHANTOM** | `issuePharmacyUid` vs `salesPharmacyUid` split; "fill at A, pull from B" | `salesPharmacy` is NEVER assigned (dead field). One pharmacy per dispense = issuePharmacy = stock source. The A/B parity test is invented behaviour. |
| D4 | **DRIFT** | OTC has "identical lifecycle to Prescription" | OTC header `PENDING→APPROVED→ARCHIVED(+CANCELED)`, payment-driven approve, 24h auto-sweeps; detail has TWO fields. Not identical; omits ARCHIVED/CANCELED/auto-transitions. |
| D5 | **DRIFT/PHANTOM** | FEFO `StockBatch` model applied to OTC dispense | OTC `deductBatch` is COMMENTED OUT (`:6317`): OTC decrements aggregate + card only, NEVER batches. FEFO-on-OTC is net-new (CR). |
| D6 | **DRIFT** | `StockBalance` entity + PESSIMISTIC_WRITE "reproducing legacy" | Legacy aggregate is a plain `double PharmacyMedicine.stock` with ZERO locking. The re-model is OK; the pessimistic lock is **net-new hardening**, not reproduction. |
| D7 | **DRIFT** | `lockFefoForDispense` SELECT FOR UPDATE ordered "expiry ASC NULLS LAST", guard after lock | Legacy: no lock, no SQL ordering (in-memory), and **silently EXCLUDES null-expiry lots** when any dated lot exists (≠ NULLS LAST). NULLS LAST changes which lot dispenses. |
| D8 | **DRIFT** | p2p: StockMovement written ONLY on RN COMPLETED; zero on source until complete() | Source card OUT + source FEFO deduction happen at **TO goods-issue** (different doc + tx, `PharmacyToPharmacyTOServiceImpl.java:215-283`). Only the destination credit is at RN. Two posting points collapsed into one. |
| D9 | **PHANTOM** | Coefficient applied on p2p TRANSFER_OUT/IN | p2p moves 1:1 (`transferedQty==receivedQty`); no coefficient on the p2p path. (Coefficient is real on pharmacy↔**store** only.) |
| D10 | **DRIFT** | GRN header PENDING→VERIFIED→APPROVED (+REJECTED/RETURNED) | GRN HEADER = **PENDING→APPROVED only**; VERIFIED belongs to the **LINE**; REJECTED/RETURNED never assigned to a header (copy-paste filter). |
| D11 | **PHANTOM** | `ThreeWayMatch` (LPO=GRN=SupplierInvoice qty; payment-release gate; `/validate` endpoint) | **ZERO legacy basis**: no SupplierInvoice entity, no invoice-qty field, no payment-release gate. Legacy reconciliation is two-way + batch-sum==receivedQty. **Remove from exact-process; re-scope as new feature.** |
| D12 | **DRIFT** | "all endpoints @PreAuthorize'd from a 177-code set" | Coverage deliberately uneven (whole OTC flow, GRN list, all Supplier endpoints UNGATED); no `anyRequest().authenticated()`. **177 = @PreAuthorize *sites* (26 live, 9 dead); ratified IAM record = 35 distinct codes.** The inc-06 phantom recurring. |
| D13 | **PHANTOM** | `pharmacy_staff` M:N affiliation as a write-time 403 gate, "seeded in I01" | No pharmacist→pharmacy field, no `pharmacy_staff` table; no affiliation check on save. The factual premise (no server-side scoping, pharmacy client-supplied) is ACCURATE; the remedy is **net-new scope**. |
| D14 | **DRIFT** | LPO transitions "each separately privilege-gated implying SoD" | All of verify/approve/submit/return/reject gated by the SINGLE `LOCAL_PURCHASE_ORDER-ALL`; no SoD; `request_no` ungated. |
| D15 | **DRIFT** | "all eight doc types assigned server-side inside the insert tx" | True for GRN/LPO/TO/RN (6); **FALSE for PPR and PSR** (client-supplied preview-and-post-back). Server-authoritative is a behaviour change to disclose. |
| D16 | **CORRECTED** | `StockBalance`/`StockMovement` two-table re-model | Legacy is ONE ledger (`PharmacyStockCard`/`StoreStockCard`) + a scalar stock field. Acceptable re-model but needs data-architect sign-off + a parity test proving single-ledger + scalar semantics; must not be presented as reproduced legacy structure. |
| D17 | **CORRECTED** | Parity oracle `1/3 * 9 = 3.000000` | A NUMERIC(19,6) coefficient stores `0.333333`; `0.333333 * 9 = 2.999997`. The expected value must be the actual NUMERIC(19,6) round-trip, not 3.000000. |
| D18 | **CORRECTED** | "INPATIENT visible at VERIFIED because insurer verifies at discharge" | VERIFIED is the inpatient **credit/post-pay** state (balance retained), NOT insurer verification; insured patients get **COVERED**. `settledOnly` must treat COVERED as settled too. |

---

## CONFIRMED-ACCURATE planning-doc claims (build as written — with corrections noted in the ground-truth model)

1. The pharmacy↔pharmacy 3-document chain naming & flow direction (RO → TO → RN).
2. The pharmacy↔store 3-document entity set (PSR → SPTO → PGRN), with the correction that the RN's only stock effect is a pharmacy-side increment.
3. Document-number output FORMAT for all eight types: `{PREFIX}{yyyyMMdd}-{seq}`, UNPADDED, no fiscal reset; verbatim prefixes GRN/LPO/PPR/PPRN/PSR/PGRN.
4. The SPTO/PPTO collision fix (legacy emits `SPT` for both TO types; ADR-0009 §6 → SPTO + PPTO; CR-10 ratified in V14; "any SPT in tests/seed is a defect").
5. Legacy used MAX(id)+1 (race-prone) — replacing with dedicated PG sequences via `DocumentNumberService` (ADR-0009 §5, CR-09) is an approved fix preserving the output format; the single-insert design drops the legacy GRN double-save defect.
6. PHARM-1 factual premise: pharmacy is client-supplied per call; NO server-side session state and NO pharmacy JWT claim.
7. `ItemMedicineCoefficient` + cross-unit multiplication (`storeSKUQty * coefficient`, hard-fail if absent) on the pharmacy↔store path; the double→BigDecimal NUMERIC(19,6) change is the pre-approved ADR-0009 §3 migration (full precision, no intermediate rounding).
8. Per-line `payStatus UNPAID→PAID` is real **only on `PharmacySaleOrderDetail`** (walk-in); for clinical prescriptions the pay signal is the linked `PatientBill.status`. Unifying both is acceptable **only if COVERED and VERIFIED are preserved as non-blocking equivalents** (binary UNPAID/PAID loses them).
9. OTC dispense decrements stock + writes a stock-card row (accurate at aggregate level); the pharmacy-sales revenue-by-payment-mode breakdown (BILL-5) is correctly flagged as an OPEN GAP / new scope.
10. The hard negative-stock reject is real on both dispense and transfer-issue — preserve it. (The pessimistic LOCK around it is the net-new part.)
11. `PharmacySaleOrder`/`Detail` are the OTC/walk-in entities (no doctor Rx), with the correction that they tie to a standalone `PharmacyCustomer` and bill via a **GENERAL dummy patient**.
12. GRN/LPO atomic-write-in-one-transaction (no async event boundary): `GoodsReceivedNoteServiceImpl.approve` does all stock effects synchronously; LPO→RECEIVED is flipped by GRN approval, not an LPO method.

---

## NEW DRIFT (legacy behaviour the doc OMITS — implement or consciously skip via CR)

- **N1** `Purchase` ledger row written in `GRN.approve()` per line `receivedQty>0` when GRN is LPO-linked (`GoodsReceivedNoteServiceImpl.java:167-183`) — feeds purchase/daily-purchase reports.
- **N2** Stock-card rows written ONLY when a line's `receivedQty>0` (GRN approve, p2p/p2s issue) — a zero-received line writes NO ledger row. Plan's unconditional RECEIPT-per-line implies phantom rows.
- **N3** GRN per-line state machine NOT-VERIFIED→VERIFIED + the `sum(batch.qty)==receivedQty` integrity rule = the REAL reconciliation gate (needs its own stories). The plan collapses verification to a non-existent header VERIFIED state.
- **N4** One-GRN-per-LPO; GRN-create preconditions (request store == LPO store, LPO `SUBMITTED`). No partial/multi-receipt/back-order.
- **N5** LPO edit immutability (only PENDING, only `validUntil`); detail price copied from `SupplierItemPrice` (missing price row hard-rejected); active flags NOT enforced. **Safety-critical financial** — acceptance criteria; do not silently add active-flag gating.
- **N6** p2p destination `PharmacyMedicineBatch` NEVER created on RN (commented out) — transferred stock has no batch/expiry at destination. The plan implies batches ARE created. Net-new (likely correct clinical fix) → CR. Contrast: pharmacy↔store RN DOES create destination batches.
- **N7** p2p/p2s `deductBatch` swallows all exceptions (empty catch); the only hard gate is aggregate stock. The plan's 422 INSUFFICIENT_STOCK is a behaviour change; the parity harness must not expect an error where legacy emits none; the silent batch under-deduction must be CR'd as a fixed defect.
- **N8** FEFO null-expiry exclusion + secondary sort by lowest **id** (NOT NULLS LAST). Pin secondary sort to `id ASC`; decide explicitly preserve vs fix the null-expiry exclusion.
- **N9** Clinical issue writes `PrescriptionBatch` lot-trace rows; the p2p path does NOT (per-path divergence the plan erases). Risk of silently dropping dispense lot-traceability.
- **N10** RO state machines richer than the plan (PENDING/VERIFIED/APPROVED/SUBMITTED/RETURNED/REJECTED/IN-PROCESS/GOODS-ISSUED/COMPLETED) with hard prior-status guards; **SUBMIT (APPROVED→SUBMITTED) REQUIRED before a TO can be created** — the missing `/submit` endpoint is a functional gap. RN is two-state PENDING→COMPLETED with NO verify/approve (adding them = PHANTOM).
- **N11** `add_batch`/`delete_batch` sub-flow + over-order guard (cumulative `transferedQty ≤ orderedQty`); `transferedQty` accumulated via `add_batch`, NOT entered on the TO header; RN snapshots TO quantities and re-parents batch rows — load-bearing for how destination batches are produced (pharmacy↔store) and unmentioned.
- **N12** Two coexisting TO-detail write paths (controller `add_batch` AND service `saveDetail`) both mutate detail qty — decide keep-both vs consolidate (consolidation is a behaviour change).
- **N13** Worklist endpoints across ALL flows are **FILTERS, not gates** — carry the inc-06 "hard gate vs filter" distinction into every worklist.
- **N14** OTC delete-detail gate + clinical `delete_prescription` credit-note path (raises a PENDING `PatientCreditNote` if a GIVEN payment exists; contains a `j=j++` no-op bug; a dead `PatientCreditNote` branch) — the whole reversal/credit-note story is missing from the plan.
- **N15** Opening-stock eager creation (registering a Pharmacy auto-creates a stock=0 row + opening card for EVERY medicine). Decide reproduce vs lazy (lazy is a behaviour change).
- **N16** Manual stock-update OVERWRITE semantics (absolute set, not delta; no batch effect) — plan lists only "ADJUSTMENT".
- **N17** `PharmacySaleOrder` (`'PSO/'+id`) and `PharmacyCustomer` (dual `'PCST'+id` / `'PCST/'+year+'/'+id`) numbering — NO Formater, no date, no `-` separator; must not be coerced into `{PREFIX}{yyyyMMdd}-{seq}`. The GENERAL dummy-patient OTC billing dependency is unmodelled.
- **N18** Legacy default-open security posture (no `anyRequest().authenticated()`; anonymous fall-through; HMAC secret literally `'secret'`; token-integrity recheck commented). inc-08/I01 must consciously CLOSE this (deny-by-default) as approved hardening — not port the vulnerability.
- **N19** The `qty==issued` invariant (clinical decrements `getQty()` but the card records `getIssued()`; they match only because issued is forced to equal qty) — preserve if the modern model unifies on a single remaining-qty decrement.
- **N20** `GRN.approve()` returns null despite its signature (legacy bug) — the rebuild should return the approved GRN; QA must not assert an empty approve response.

---

## RECOMMENDED SCOPE & SEQUENCE

### Split: **YES — `08a` (dispensing + stock core) then `08b` (store / procurement / transfers)**
Different dependency profiles and risk surfaces. `08a` unblocks the clinical stock-decrement TODO deferred since inc-05/06 and is the smaller, higher-clinical-priority slice; `08b` carries the bulk of the state-machine surface and the procurement money-ledger. `08a` first so the shared FEFO/stock-ledger primitives are designed once and reused.

### Prerequisites (architecture, BEFORE either slice — solution-architect owns; route joint changes via engagement-lead)
1. **New `clinical::api` published prescription read + dispense port** (the confirmed critical gap — `PrescriptionPort` is intra-module). Define: read prescription by uid, a pharmacy worklist contract, a dispense/decrement seam. Add a Spring Modulith allowed edge `pharmacy → clinical::api` (mirror `billing::api`/`SettlementDispatcher`; no reverse edge, no cycle).
2. **`DocumentType`/`DocumentNumberService` ownership** — recommend **promoting to the shared kernel** (avoids `inventory → billing` coupling); else extend the billing-owned enum + switch. Short ADR addendum.
3. **Resolve the sequence-name conflict (REAL drift):** ADR-0009 §5 names TO sequences `seq_sto_no`/`seq_ptp_no`, but V13 seeded `seq_spto_no`/`seq_ppto_no`. **The as-built names win** (migrations already ran) — correct ADR-0009; do NOT create new sequences.
4. **Add ErrorCodes:** `INSUFFICIENT_STOCK` (422), `STALE_ENTITY` (409 optimistic-lock surface), and a dispense-gate code (gated on Q1/HDE).
5. **Author/extend ADRs:** concurrency model (ADR-0017: pessimistic-write on stock decrement + optimistic `@Version` on transitions — explicitly NET-NEW vs legacy's no-lock); stock-ledger re-model (single `StockBatch` + `StockMovement`-with-type-enum + scalar/derived balance — data-architect sign-off; map enum values onto legacy reference strings; no WASTAGE unless a legacy disposal path is found).

### 08a — Dispensing + stock core
Medicine masterdata CRUD (medicines/items/coefficients/suppliers as needed); `PharmacyMedicine` aggregate-stock model; `StockBatch` (FEFO) + `StockMovement` ledger primitives; clinical dispense (NOT-GIVEN→GIVEN, all-or-nothing, negative-stock gate, FEFO consumption + lot-trace rows, OUT card); pharmacy worklist as a **FILTER** (PAID/COVERED for OUT/OUTSIDER, +VERIFIED for INPATIENT); manual stock-update (overwrite); opening-stock decision; OTC `PharmacySaleOrder` full lifecycle (PENDING→APPROVED→ARCHIVED/CANCELED, payment-driven approve via billing, 24h auto-sweeps, GENERAL dummy patient, PSO/PCST numbering, OTC dispense with NO FEFO unless CR'd). Route medicine pricing through `billing::api.recordClinicalCharge`. Reuse `BillingQueries.getBillStatus` only if HDE approves a hard dispense gate (Q1).

### 08b — Store / procurement / transfers
Store masterdata + `StoreItem`/`StoreItemBatch`/`StoreStockCard`; LPO→GRN procurement (PENDING-initial LPO machine, per-line GRN verification + batch-sum rule, one-GRN-per-LPO, store-match/SUBMITTED preconditions, `Purchase` ledger row, atomic approve); pharmacy↔store transfer (PSR→SPTO→PGRN with coefficient conversion, store-debit-at-TO-issue, pharmacy-credit-at-RN with destination batch creation, **move the RN guard into the service**); pharmacy↔pharmacy transfer (PPR→PPTO→PPRN, 1:1 no-coefficient, source-debit-at-TO-issue, two-state RN, destination-batch decision Q7); `SupplierItemPrice` CRUD; all numbering via the shared `DocumentNumberService`.

### Within each slice
schema/migrations (V39+) → aggregates + state machines → module-API commands (TxAuditContext-threaded) → web/OpenAPI → Testcontainers parity tests (round-to-2dp money per ADR-0009 §4; FEFO + coefficient round-trip cases per D17; concurrent-document-number uniqueness; BusinessDay close-gate). Audit every transition via `AuditRecorder` (net-new, not Envers — labelled). Treat all clinical/financial stories as safety-critical.

### Items that MUST be RE-SCOPED out of exact-process (CR + engagement-lead approval before any code)
ThreeWayMatch (+ SupplierInvoice + payment-release gate + `/validate`); 8-state Rx lifecycle; salesPharmacy split / A-B parity; `pharmacy_staff` affiliation hard-gate + 403; FEFO-on-OTC; p2p destination-batch creation; pessimistic locking + SELECT-FOR-UPDATE; uniform per-endpoint `@PreAuthorize` + granular per-transition SoD codes; closing the default-open security posture; server-authoritative PPR/PSR numbering.

---

## OPEN QUESTIONS FOR THE ENGAGEMENT OWNER

| # | Question | Owners |
|---|----------|--------|
| **Q1** | **Dispense hard-gate:** legacy `issue_medicine` has NO terminal bill-status check (worklist filter only, bypassable). (a) reproduce filter-only, or (b) add a hard dispense gate via `BillingQueries.getBillStatus` (live PAID/COVERED/VERIFIED) — a security/integrity improvement that DEVIATES from legacy? | HDE + engagement-lead |
| **Q2** | **Pharmacy session scoping:** legacy has NO pharmacist→pharmacy affiliation and NO server-side scoping. (a) reproduce client-trust, or (b) introduce a net-new `pharmacy_staff` affiliation enforced as a write-time 403? If (b): which increment owns the schema/seed (the plan's "seeded in I01" is unverified, table absent), and does pharmacy become stricter than stores (soft filter only)? | engagement-lead + security-architect + BA |
| **Q3** | **Three-way match:** confirmed PHANTOM. Is a real three-way match wanted as a NEW feature? If yes → CR + `SupplierInvoice` entity + GRN-detail invoice-qty capture + payment-release workflow (all net-new, safety-critical financial). | engagement-lead + finance/HDE |
| **Q4** | **Pessimistic locking + SELECT FOR UPDATE** on stock decrement: net-new hardening (legacy is race-prone, zero locks). Approve as a deliberate correctness improvement (ADR-0017)? Confirm `@Version`-on-transitions + pessimistic-on-decrement split is acceptable given legacy's structural idempotency (return-existing-doc) on TO/RN create. | engagement-lead |
| **Q5** | **RBAC granularity + the 177-vs-35 figure:** legacy uses single coarse codes with NO SoD and leaves whole flows ungated. (a) reproduce coarse gating, or (b) granular per-transition SoD codes + close the default-open posture? Re-derive the code figure from the I01 fixture (ratified = 35) before any acceptance criteria cite "177". Confirm `PRESCRIPTION-ALL` (unverified; legacy dispense is ungated). | security-architect + engagement-lead |
| **Q6** | **SPT→SPTO/PPTO product-owner sign-off:** ADR-0009 §6 gates the rename on PO sign-off — obtained? Any printed-document/audit-export hard-coding `SPT` must be updated. Also ratify the ADR-0009 sequence-name correction (`seq_sto_no`/`seq_ptp_no` → as-built `seq_spto_no`/`seq_ppto_no`). | engagement-lead |
| **Q7** | **Destination batch creation on p2p receipt:** legacy NEVER creates them (commented out) — transferred stock loses batch/expiry traceability at destination. Reproduce the gap, or fix it (like the pharmacy↔store path)? Clinically the fix is likely correct but is a behaviour change → CR. | HDE + engagement-lead |
| **Q8** | **FEFO null-expiry semantics:** legacy SILENTLY EXCLUDES null-expiry lots when any dated lot exists, and uses lowest-id secondary sort. Preserve the exclusion quirk, or fix to NULLS LAST (changes which lot dispenses)? Related: FEFO-on-OTC (legacy never did it). | HDE |
| **Q9** | **OTC numbering + pricing:** keep `'PSO/{id}'` and the dual `PCST` customer formats verbatim, or normalize (CR)? Confirm OTC pricing stays flat `Medicine.price*qty` with paymentType hardcoded CASH (so any "by payment mode" OTC report is all-CASH unless changed via CR). | BA + engagement-lead |
| **Q10** | **PPR/PSR request-order numbering:** legacy RO numbers are client-supplied (preview-and-post-back). Move to server-authoritative `next()` inside the insert tx (a behaviour change), or preserve the preview-then-supply contract? | engagement-lead |
| **Q11** | **`DocumentNumberService` ownership:** promote `DocumentType`/service from `billing.application` to the shared kernel (recommended — avoids `inventory → billing` coupling), or extend the billing-owned enum? Affects the module dependency graph. | solution-architect + engagement-lead |

---

## RECOMMENDED SEQUENCE (engagement level)

Prereqs (1–5) → **`08a`** dispensing/stock → **`08b`** store/procurement/transfers → `inc-07` Inpatient/Nursing (consumes the inc-08 stock ledger via `ConsumableStockService.decrementForIssue`) → `inc-09` HR/Payroll/Assets → `inc-10` Reporting. inc-08 must close out the **11 open questions** (especially Q1–Q5, the safety-critical/financial deviations) before its build spec is frozen — exactly the discipline that caught the inc-06A latent MAJORs.
