# Inc-08 Decisions — Ratified Register (Pharmacy / Inventory / Procurement)

**Date:** 2026-06-05 · **Workflow run:** `wf_f6e97061-b95` (23 agents: 11 resolve + 11 adversarial-challenge + 1 ratify; ~9 min, ~1.3M tokens)
**Raw output:** [02-decisions-raw.json](02-decisions-raw.json) · **Script:** [02-decisions-workflow.mjs](02-decisions-workflow.mjs) · **Inputs:** [01-RECONCILIATION-AND-SCOPE.md](01-RECONCILIATION-AND-SCOPE.md)

**Method:** each of the 11 open questions resolved by the relevant specialist against the engagement charter ("modern design, exact process") **and the actual ADR text**, then adversarially challenged by the code-reviewer on three axes (does it contradict a ratified ADR? does it smuggle an un-CR'd deviation into the baseline? is any owner-only action correctly flagged?), then consolidated by the engagement-lead. Challenge result: **6 UPHELD, 5 REVISE** (corrections applied below).

> **The decisive correction the adversarial pass caught:** *all ADRs are still status `Proposed`* (README.md line 3) — none are ratified. The charter's "do not re-litigate a ratified ADR" rule therefore does **not** yet fire for ADR-0017/0009/etc. So Q4 (pessimistic locking) and the Q8 NULLS-LAST mechanism are **not** "ADR pre-decided / build now" — they are deviations parked until ADR-0017 is ratified + the owner signs off. Without this catch we'd have wrongly built net-new locking and a lot-selection behaviour change into the exact-process baseline.

---

## Ratified decisions

| Q | Topic | Classification | Ratified decision (short) |
|---|-------|----------------|---------------------------|
| **Q1** | Dispense gate | `OWNER_RELEASE_GATE` | Baseline = legacy **worklist FILTER** (PAID\|COVERED for OUT/OUTSIDER; +VERIFIED for INPATIENT); dispense terminal has **no** bill-status check. Hard gate is a deviation; if elected, **only** via `SettlementPolicy.requireSettled()` on the local `settled` flag — **never** `BillingQueries.getBillStatus` (forbidden by ADR-0008 §6 Addendum). |
| **Q2** | Pharmacy scoping | `EXACT_PROCESS` + CR | `pharmacyUid` is a **required, server-validated** per-call param (reject missing/unresolvable) used only to select the stock source; **no** user-affiliation check, **no** `pharmacy_staff` table/JWT-claim/403. On point: **ADR-0020** (Proposed) mandates the required working-pharmacy param and *excludes* pharmacist affiliation. Affiliation gate → **CR-Q2**. |
| **Q3** | Three-way match | CR (baseline drop) | **Drop** three-way match entirely. Build only the real legacy reconciliation: two-way `receivedQty ≤ orderedQty` + per-line `sum(batch.qty)==receivedQty` gating the line VERIFIED transition; header PENDING→APPROVED; one-GRN-per-LPO + store-match + LPO==SUBMITTED. No `SupplierInvoice`/payment-release/`/validate`. Real match → **CR-03** (new feature). |
| **Q4** | Stock locking | `OWNER_RELEASE_GATE` (re-classed) | **Not auto-decided** (ADR-0017 is Proposed, not ratified). Engineering target retained: `PESSIMISTIC_WRITE` on balance+FEFO batches before the negative-stock guard; `@Version` + 409 STALE_ENTITY on transitions; 422 INSUFFICIENT_STOCK verbatim refusal. Parked behind **CR-08-Q4** + ADR-0017 ratification. |
| **Q5** | RBAC | `EXACT_PROCESS` + hardening + CR | "177" is the inc-06 phantom — **banned** from acceptance criteria; truth = **35** codes (26 live + 9 dead). inc-08 adds **zero** codes. Reproduce **coarse** gating verbatim. Closing default-open (deny-by-default) = approved ADR-0006 hardening (labelled net-new, not a parity assertion). Per-transition SoD → **CR-08-SoD**. `PRESCRIPTION-ALL` is a never-gated catalogue string. |
| **Q6** | SPTO/PPTO + seq names | ADR-governed + doc-fix | Emit **SPTO/PPTO**, never `SPT`. DB already correct (V13/V14) — **touch no migration**. As-built sequence names win → correct ADR-0009 + two build-instruction docs (see ADR corrections). Add enum constants + `DocumentNumberServiceImpl` switch cases → `seq_spto_no`/`seq_ppto_no`. PO sign-off + external 'SPT' sweep = owner action. |
| **Q7** | p2p dest batches | CR (reproduce gap) | **Reproduce the legacy gap**: p2p PPRN increments aggregate balance + one IN movement (when `receivedQty>0`), creates **no** destination `StockBatch`. The pharmacy↔store PGRN path **does** create dest batches — intentional asymmetry, asserted by parity. Fix (mirror store path) → **CR-Q7** (HDE-recommended). |
| **Q8** | FEFO null-expiry | Mixed (HDE gate) | Mechanism + dated-lot FEFO order ride ADR-0017 §2 (once ratified). **NULLS-LAST is a behaviour change** (legacy silently *excludes* null-expiry lots when dated stock exists) → **HDE sign-off required**; until then baseline **reproduces the legacy exclusion**. `id ASC` tiebreak pinned. FEFO-on-OTC → **CR-08-FEFO-ON-OTC** (legacy never consumed OTC batches). |
| **Q9** | OTC numbering/pricing | CR + exact-process | Pricing: flat `Medicine.price × qty` (NUMERIC(19,2) HALF_UP), `paymentType=CASH` literal (incoming ignored), GENERAL dummy patient → ADR-0010 OTC slice is all-CASH. Numbering: reproduce **verbatim format strings** `PSO/{n}`, `PCST{n}`, `PCST/{year}/{n}` (outside the shared service) — **but** back the suffix with a dedicated sequence, **not** the raw PK (ADR-0014 §1 forbids exposing id) → **CR-09-NUM1**. Real payment mode → **CR-09-NUM2** (implements BILL-5). |
| **Q10** | PPR/PSR numbering | CR (preserve contract) | Preserve the legacy **client-supplied preview-then-supply** contract for PPR/PSR verbatim; server does not `setNo()`. In-scope concurrency defense: back the preview with `seq_ppr_no`/`seq_psr_no` (not MAX(id)+1) + a **DB UNIQUE constraint** on the RO `no` → hard 409/422. Server-authoritative → **CR-Q10**. |
| **Q11** | DocNumberService ownership | Architecture (no CR) | **Promote** `DocumentNumberService` + `DocumentType` to the **shared kernel** (`shared.documentnumber`, `@ApplicationModule(OPEN)`) — no `inventory→billing`/`pharmacy→billing` edge for numbering. Generic `nextval(:seqName)` keyed off an enum allow-list; bind to as-built `seq_spto_no`/`seq_ppto_no`. Delete the billing-owned copy, rewire `CreditNoteService`. Drift-guard IT: `enum.prefix()==md_document_types.prefix` and `enum.sequenceName()` resolves to a real sequence. |

---

## What can FREEZE now vs. what BLOCKS the 08a build spec

**`readyToFreezeBuildSpec` = NO** — four gates block a clean freeze; one prerequisite must land first.

**BLOCKERS**
1. **ADR ratification (root gate).** Every ADR is `Proposed`. The engagement-lead must ratify at minimum **ADR-0017** (unblocks Q4 lock + Q8 mechanism), and reconcile ADR-0006/0008/0009/0014/0020 status headers. *Highest-leverage unblock.*
2. **Q8 / HDE sign-off on NULLS-LAST.** Until approved, 08a reproduces the legacy null-expiry **exclusion**; the dispense lot-selection parity oracle can't freeze (it changes which lot dispenses).
3. **Q1 / engagement-lead + HDE.** Whether CR-05's hard pay-gate extends to the dispense terminal. *Filter-only is a clean default*, so it does **not** hard-block a filter-only freeze — but acceptance criteria must state filter-only and the parity harness must not assert a terminal rejection.
4. **Q9 / CR-09-NUM1 (ADR-0014-forced).** Even to reproduce the OTC number *format* legally, the PK→sequence suffix decoupling must be approved (exposing raw id violates ADR-0014 §1). Small, but gates the OTC numbering acceptance criteria.

**PREREQUISITE:** Q11 shared-kernel `DocumentNumberService` promotion (architecture task) before 08a consumes it.

**CAN FREEZE NOW (no blockers):** Q1 worklist filter · Q2 pharmacyUid required/validated/no-affiliation · Q5 coarse-35-code gating + deny-by-default · Q3 two-way + batch-sum reconciliation · Q6 SPTO/PPTO + as-built sequence binding · Q7 reproduce-the-gap · Q10 client-supplied PPR/PSR + UNIQUE constraint. **Freeze 08a once blockers 1, 2, 4 clear (3 freezes as filter-only by default).**

---

## Change Requests raised (parked — NOT in the baseline)

| CR | Title | Owner / needs |
|----|-------|---------------|
| **CR-Q2** | Pharmacist→pharmacy (`pharmacy_staff`) affiliation 403 scoping gate | engagement-lead; **amends ADR-0020 §Decision.4** (currently excludes affiliation); assign owning increment |
| **CR-03** | Three-way procurement match (SupplierInvoice + invoice-qty + payment-release + `/validate`) | engagement-lead + finance/HDE (must supply reconciliation semantics — no legacy oracle) |
| **CR-08-Q4** | Pessimistic lock + `@Version` split on stock decrement | engagement-lead; **gated on ADR-0017 ratification** |
| **CR-08-SoD** | Per-transition segregation-of-duties privilege codes | engagement-lead (only if SoD wanted; else confirm coarse-verbatim frozen) |
| **CR-Q7** | p2p destination `StockBatch` creation (restore inter-pharmacy lot/expiry traceability) | HDE + engagement-owner (HDE recommends raising) |
| **CR-08-FEFO-ON-OTC** | Unified FEFO + lot-trace on OTC dispense | engagement-lead |
| **CR-09-NUM1** | OTC number suffix provenance: PK → dedicated `seq_pso_no`/`seq_pcst_no` (ADR-0014-forced); optional dual-PCST unification | engagement-lead (MRNO/CR-02 precedent) |
| **CR-09-NUM2** | Honor real OTC payment mode (stop hardcoding CASH) → implements BILL-5 | engagement-lead |
| **CR-Q10** | Server-authoritative PPR/PSR numbering (drop client-supplied) | engagement-lead |

---

## Owner actions required (only you / the product owner can resolve these)

1. **Ratify the ADRs** (root gate) — at minimum **ADR-0017**; reconcile status headers on 0006/0008/0009/0014/0020. *Without this, Q4 and Q8's targets stay parked, not baseline.*
2. **Q1** — decide whether the CR-05 pay-gate extends to the pharmacy dispense terminal (HDE: local-flag `SettlementPolicy` form is safe & recommended; a `getBillStatus`/blanket gate is **not** permitted). Default until then: filter-only.
3. **Q2** — ratify ADR-0020 as-is (required/validated `pharmacyUid`, no affiliation) **or** approve CR-Q2 (stricter gate, amends ADR-0020).
4. **Q3** — decide if the net-new three-way match is wanted (CR-03) before any code. No action needed to proceed with the baseline drop.
5. **Q4** — approve the lock deviation (CR-08-Q4) **and** ratify ADR-0017.
6. **Q5** — decide if per-transition SoD is wanted (CR-08-SoD) or confirm coarse-verbatim frozen. Acknowledge deny-by-default is an observable (labelled) delta.
7. **Q6** — product-owner sign-off on **SPTO/PPTO** prefixes (ADR-0009 §6 release gate); confirm the external/printed-document/audit-export 'SPT' sweep is done before 08b release.
8. **Q7** — decide CR-Q7 (HDE recommends; reproduced gap ships until then).
9. **Q8** — HDE sign-off on **NULLS-LAST** (recorded behaviour change + parity exception); separately decide CR-08-FEFO-ON-OTC. Baseline reproduces legacy exclusion until sign-off.
10. **Q9** — approve the OTC CRs (CR-09-NUM1 required even to reproduce the format legally; CR-09-NUM2 optional).
11. **Q10** — decide CR-Q10 (server-authoritative PPR/PSR) or accept the preserved client-supplied contract.

---

## ADR / doc corrections (factual drift — doc-only, no behaviour change)

These are **factual** corrections (stale names/numbers vs the as-built reality), not policy changes — safe to apply without an owner gate; applied in this commit where noted.

1. **ADR-0009 §5** (seq list): `seq_sto_no, seq_ptp_no` → `seq_spto_no, seq_ppto_no`.
2. **ADR-0009 Impl-notes SQL** (the two `CREATE SEQUENCE` lines): → `seq_spto_no` / `seq_ppto_no`.
3. **ADR-0009 prefix table** (Sequence column for the two TO rows): → `seq_spto_no` / `seq_ppto_no`; add a drift note + status/date bump. *DB is correct — touch no migration.*
4. **Build-instruction docs** (engineers bind to these): `08-pharmacy-inventory-procurement.md` line 121 (`seq_ptp_no`→`seq_ppto_no`) & 124 (`seq_sto_no`→`seq_spto_no`); `02-master-data.md` line 100 (both).
5. **ADR-0006** — replace stale "177 codes" text (≈8 occurrences) with the ratified **35-code** reality (26 live + 9 dead; 177 = `@PreAuthorize` *sites*).
6. **ADR status headers** — after the engagement-lead ratifies, flip each `Proposed` → `Ratified` (housekeeping; it is the gating fact for Q4/Q8).
7. **ADR-0009 Impl-notes `DocumentNumberService` snippet** — annotate the idealized `DocumentType.sequenceName()` accessor as *illustrative, not as-built* (the as-built uses a switch in `DocumentNumberServiceImpl`).
8. **Conditional on Q2** — if CR-Q2 is approved, amend/supersede **ADR-0020 §Decision.4** (currently excludes pharmacist affiliation).

---

## Net effect

The exact-process **08a/08b baseline is fully specified and internally consistent** — it reproduces verified legacy behaviour verbatim (filter-not-gate, two-way-not-three-way, coarse-35-RBAC, reproduce-the-p2p-gap, client-supplied RO numbers, all-CASH OTC) and holds every tempting modern improvement behind one of **9 explicit CRs**. The build is **not yet frozen**: it is gated on ADR-0017 ratification, the HDE NULLS-LAST call (Q8), the Q1 filter-vs-gate sign-off, and CR-09-NUM1 — plus the Q11 shared-kernel prerequisite. This is the same discovery-before-code discipline that caught the inc-06A latent MAJORs, applied to the decision layer.
