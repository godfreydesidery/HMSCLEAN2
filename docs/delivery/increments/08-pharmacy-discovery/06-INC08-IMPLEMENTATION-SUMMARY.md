# Inc-08 Implementation Summary — Pharmacy / Inventory / Procurement

**Date:** 2026-06-05 · **Branch:** `feat/increment-08-pharmacy-inventory-procurement`
**Status:** ✅ **COMPLETE & GREEN** — `mvn clean verify` BUILD SUCCESS (63 unit + 647 integration tests, 0 failures).

The full pharmacy + inventory bounded contexts, built from empty greenfield stubs to the
exact-process baseline ratified in [03-DECISIONS-RATIFIED.md](03-DECISIONS-RATIFIED.md) and specified
in [05-INC08A-BUILD-SPEC.md](05-INC08A-BUILD-SPEC.md). Driven discovery-before-code, then built in 9
verified chunks, each committed green.

---

## What was built (by chunk)

| # | Slice | Migration | Key entities / services | IT |
|---|-------|-----------|--------------------------|-----|
| 0 | Stock-core schema + `ErrorCode.INSUFFICIENT_STOCK` | V39 | — | Flyway clean |
| 1 | `clinical::api` Rx read/worklist/dispense seam + inc-05 worklist FILTER fix + `billing::api.worklistAdmits` | — | `PrescriptionApiAdapter` | 22 regression |
| 2 | Pharmacy stock model + FEFO engine | (V39) | `PharmacyMedicine`/`StockBatch`/`StockMovement`, `StockService` | ddl-validate |
| 3 | Clinical prescription dispense + worklist + stock REST | — | `PharmacyDispenseService`, `PharmacyStockQueryService` | 7/7 |
| 4 | OTC `PharmacySaleOrder` lifecycle (event-driven approve, 24h sweeps, flat-CASH billing) | V40 | `PharmacySaleOrderService`, `OtcSettlementListener` | 7/7 |
| 5 | Store stock + LPO→GRN procurement (atomic approve, Purchase ledger) | V41 | `StoreStockService`, `LpoService`, `GrnService` | 7/7 |
| 6 | Pharmacy↔store transfer (PSR→SPTO→PGRN, coefficient, store-debit-at-issue, pharmacy-credit-at-RN + dest batches) | V42 | `PharmacyStoreTransferService` | 3/3 |
| 7 | Pharmacy↔pharmacy transfer (PPR→PPTO→PPRN, 1:1, source-debit-at-issue, **dest-batch GAP reproduced**) | V43 | `PharmacyPharmacyTransferService` | 4/4 |
| 8 | OpenAPI (`pharmacy.yaml` 13 paths, `inventory.yaml` 45 paths) + full-verify gate | — | `OpenApiExportIT` | full verify |

**Cross-module seams added (no cycles; `ApplicationModules.verify()` green):**
- `pharmacy → clinical::api` (dispense orchestration), `clinical → billing::api.worklistAdmits` (worklist filter).
- `pharmacy::api PharmacyStockCredit` (credit/debit transfer lots) consumed by `inventory → pharmacy::api`.
- `billing::api.recordFlatCashSale` (OTC flat-CASH path, NOT the plan-pricing engine — Q9).
- OTC PENDING→APPROVED via the existing `shared.event.BillSettledEvent` (billing→pharmacy, no compile edge).
- New `masterdata::lookup`: `PharmacyLookup`, `StoreLookup`, `ItemLookup`, `SupplierLookup`,
  `MedicinePriceLookup`, `SupplierItemPriceLookup`, `CoefficientLookup`.
- `shared.documentnumber.DocumentType` extended: GRN/LPO/SPTO/PPTO/PPRN/PGRN/PSR/PPR.

---

## Exact-process fidelity — what the build reproduces verbatim

- **Clinical dispense:** NOT-GIVEN→GIVEN, four legacy guards in order (under-issue rejected before over-issue), all-or-nothing, hard negative-stock 422, FEFO **null-expiry exclusion + id-ASC**, `PrescriptionBatch` lot-trace, stock-card "Issued in prescription: id …". Pay enforcement is the **worklist FILTER** (PAID|COVERED; +VERIFIED inpatient), NOT a terminal gate (Q1).
- **OTC:** PENDING→APPROVED (bill-payment event)→ARCHIVED / CANCELED, two detail status fields, flat `Medicine.price×qty` CASH against the GENERAL sentinel, 24h auto-sweeps, dispense with **no FEFO** (Q9), "Issued in sale: id …".
- **Procurement:** LPO state machine + edit-only-PENDING + price-copied-from-SupplierItemPrice; GRN per-line verify gated on `sum(batch.qty)==receivedQty`; **atomic approve** (store credit + StoreItemBatch + Purchase row + LPO→RECEIVED in one tx); **NO three-way match** (Q3 — dropped).
- **Transfers:** stock posts at the exact legacy moments — store/source debit at **TO.issue** (FEFO), destination credit at **RN.complete**; coefficient conversion on the store path; the p2p **destination-batch gap reproduced** (Q7); RN guard moved into the service (double-post fix); SPTO/PPTO replace the legacy SPT collision (CR-10).

## Deliberate deltas (all labelled, none silent)

- `double → BigDecimal` NUMERIC(19,6)/(19,2) (ADR-0009 §3, pre-approved).
- ULID uid + hidden id (ADR-0014 §1) — entities use `@ManyToOne` for intra-module FKs, never read the id.
- Document numbers via concurrency-safe sequences (ADR-0009 §5), not MAX(id)+1.
- OTC PSO/PCST numbers sequence-backed, not raw-PK-suffixed (CR-09-NUM1, ADR-0014-forced).
- Deny-by-default security floor (ADR-0006 hardening, labelled — not a parity assertion).
- Batch-shortfall surfaced as 422 instead of the legacy silent empty-catch (labelled defect-correction).

## Parked as CRs (NOT built — held behind owner sign-off)

Pessimistic stock lock (Q4/CR-08-Q4 — only `@Version` in baseline) · FEFO NULLS-LAST (Q8 — baseline
reproduces legacy exclusion) · three-way match (CR-03) · pharmacy_staff affiliation 403 (CR-Q2) ·
per-transition SoD codes (CR-08-SoD) · FEFO-on-OTC (CR-08-FEFO-ON-OTC) · real OTC payment mode
(CR-09-NUM2) · server-authoritative PPR/PSR numbering (CR-Q10) · hard dispense pay-gate (Q1, owner-gated).

## Migrations
V39 (pharmacy stock) · V40 (OTC sale order + seq_pso_no/seq_pcst_no) · V41 (store stock + LPO/GRN +
purchases) · V42 (pharmacy↔store transfer) · V43 (pharmacy↔pharmacy transfer). All additive; apply
clean V1→V43 on PostgreSQL 16.

## Verification
`mvn clean verify` GREEN — 63 surefire + 647 failsafe, 0 failures. ModularityTest, PrivilegeGateArchTest,
ApiConventionsArchTest, IamNoEntityLeakArchTest all green. Per-flow ITs: PharmacyDispenseIT 7/7,
PharmacySaleOrderIT 7/7, ProcurementIT 7/7, PharmacyStoreTransferIT 3/3, PharmacyPharmacyTransferIT 4/4.
