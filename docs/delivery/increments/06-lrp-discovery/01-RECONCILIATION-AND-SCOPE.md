# Inc-06 Discovery & Reconciliation — Lab / Radiology / Procedure / Theatre

**Date:** 2026-06-04 · **Workflow run:** `wf_3d82df11-0d0` (19 agents, ~44 min, 1.68M tokens)
**Raw output:** [00-discovery-raw.json](00-discovery-raw.json) · **Workflow script:** [00-discovery-workflow.mjs](00-discovery-workflow.mjs)

**Method:** as-built inventory (inc-00..05) ‖ 6 legacy extractions (each adversarially verified — all verdicts `ACCURATE_WITH_CORRECTIONS`, none materially wrong) → BA reconciliation → solution-architect scope synthesis.

---

## VERDICT: `INC06_IS_MOSTLY_DONE_THIN_REMAINDER`

The clinical Lab/Radiology/Procedure/Theatre **order + result + attachment + worklist loops are already SHIPPED in inc-05** under legacy-accurate names. The inc-06 planning doc is **~80% already-built-or-phantom**. inc-06 is therefore **NOT a full build increment** — the genuine remainder is a thin set of inc-05 closure items.

**The decisive new finding (from reading the code, not the doc):** the single biggest genuine gap — credit-note + invoice reversal on L/R/P **delete** — was deferred in inc-05 because *"billing.api does not yet publish a cancel/credit-note command."* **That seam has since landed** (`BillingCommands.cancelCharge` at `billing/api/BillingCommands.java:58`; `CreditNoteService.cancelCharge` implements the exact legacy reversal). The three inc-05 TODOs (`LabTestService.java:449-450`, `ProcedureService.java:349`, RadiologyService equivalent) are now **stale and closeable immediately**.

---

## ALREADY BUILT in inc-05 (do NOT rebuild)

Three separate order tables (not polymorphic ClinicalOrder); results as flat columns (no LabResultLine); Lab `PENDING→ACCEPTED→COLLECTED→VERIFIED` (+REJECTED, +HOLD revert); Radiology `ACCEPTED→VERIFIED` direct (COLLECTED dead-state retained); Procedure `PENDING→ACCEPTED→VERIFIED` via `add_note` (no APPROVED); lab save-result + separate report field; per-type named attachments (max 5, add-gate, VERIFIED delete-lock + download-gate); radiology inline BYTEA blob at verify; per-insurance-plan-vs-cash pricing at order-raise; settlement enforcement as the **worklist filter** (`settled=true`) — NOT an accept/verify precondition; duplicate-order guard; reject-with-comment (lab clears on accept, radiology does not — asymmetry preserved); Theatre as master-data only (`Procedure.theatreUid` = nullable loose tag); encounter binding exactly-one; delete PENDING-only; authenticated-only (NO @PreAuthorize).

---

## GENUINE GAPS (the real "inc-06" = a thin top-up, "inc-06A")

| # | Gap | Size | Status | Legacy cite |
|---|-----|------|--------|-------------|
| **1** | L/R/P DELETE → credit-note + invoice reversal seam | MEDIUM | **UNBLOCKED NOW** (billing.api.cancelCharge landed) | PatientResource.java:2912-2965 (lab), 3418-3471 (rad), 3473-3537 (proc) |
| **2** | Radiology stand-alone **bill-gated** `add_report` (independent of order status) | SMALL | needs a billing bill-status read seam (arch decision) | PatientResource.java:3183-3197 |
| **3** | `save_reason_for_rejection` discrete status-guarded endpoint (lab+rad; only when status==REJECTED) | SMALL | ready | PatientResource.java:2034-2048 (lab), 2018-2032 (rad) |
| **4** | Post-VERIFIED **report amendment** via bill-gated add_report (report overwritable after VERIFIED; result/range/level/unit not) | SMALL | **GATED** — needs legacy-analyst + HDE confirm (asymmetry vs defect) | PatientResource.java:3381-3395 (lab), 3183-3197 (rad) |
| **5** | Attachment **file STORAGE + download streaming** + 10 MiB cap (as-built stores only name+fileName refs) | MEDIUM | **FORK**: legacy-parity local-disk vs ADR-0015 object-store (owner decision) | PatientServiceImpl.java:2823-2906/2922-2996; PatientResource.java:5960-6007/6093-6140 |
| **6** | Encounter cancel/sign-off **cascade** for L/R/P bills (cancel→hard-delete PENDING UNPAID; sign-off→UNPAID→CANCELED; block sign-off/referral on UNPAID order bill) | MEDIUM | **verify-first** (may be partly wired by inc-05 closure) | PatientResource.java:434-494, 701-720, 5465-5489 |

**Excluded from inc-06 (belong elsewhere):**
- Admission-scoped L/R/P order paths + inpatient worklists + admission-verification gate → **inc-07 Inpatient/Nursing** (LARGE).
- Lab/rad/proc **report endpoints** (by-date, statistics, sample-collection, collection reports) → **inc-10 Reporting** (LARGE).

---

## PLANNING-DOC DRIFT (rejected — phantom or contradicts verified legacy)

Polymorphic `ClinicalOrder`+kind · `COMPLETED`/`CANCELLED` states (real terminal = VERIFIED; cancel = hard delete) · Procedure `APPROVED` + surgeon/anaesthetist approve() + `allow-self-approve` · `OperativeRecord` (zero operative/surgeon files in legacy) · per-analyte `LabResultLine` + server-side reference-range flag computation (real = flat free-text verbatim from client; `LabTestTypeRange` is a bare label never evaluated) · `LabBatch` + `seq_lab_batch_no`/`LABB{date}-{seq}` (no batch entity, no lab doc number) · generic ADR-0015 attachment + MinIO/S3/ClamAV/pre-signed **framed as legacy parity** (legacy = per-type tables + local-disk + inline stream; object-store is NET-NEW) · `ATTACHMENT_DELETE_LOCKED` on COMPLETED/CANCELLED (real lock = VERIFIED) · CASH `ORDER_NOT_SETTLED` hard gate on accept()/complete() (legacy = worklist FILTER only) · theatre scheduling subsystem · "177 @PreAuthorize codes on every endpoint" (legacy lifecycle endpoints carry NONE; IAM has 35) · `@ApplicationModuleListener` audit events named after non-existent states · unified ServicePrice/PriceLookup presented as inc-06 (already shipped inc-02/04) · three sibling top-level modules laboratory/radiology/procedures (as-built = single `clinical` module per ADR-0022).

---

## RECOMMENDED SEQUENCE

`inc-06A` clinical-order top-up (closes inc-05 deferrals) → **`inc-08` Pharmacy/Inventory/Procurement** (the true topological next full context; inc-07 doc itself says "08 is built before 07") → `inc-07` Inpatient/Nursing (folds in admission-scoped L/R/P) → `inc-09` HR/Payroll/Assets → `inc-10` Reporting. Run discovery-before-code on inc-08 and inc-07 — the legacy survey shows both their planning docs carry the same class of drift.

## OPEN QUESTIONS FOR THE ENGAGEMENT OWNER

1. **Sequencing:** one short `inc-06A` top-up before inc-08, or pull the items forward as CRs and go straight to inc-08? (SA recommends a single short inc-06A.)
2. **ITEM 5 storage backend:** pin ADR-0015's first concrete use here (object storage + virus-scan), or ship legacy-parity local-disk + 10 MiB cap now and defer object storage?
3. **ITEM 4** post-VERIFIED report amendment: reproduce the legacy asymmetry, or treat as a defect? (needs legacy-analyst + HDE.)
4. **ITEMs 2/4 bill-status read seam:** approve a narrow `billing.api` bill-status query (clinical today holds only the order-time `settled` flag and per ADR-0008 §6 never reads bill status post-hoc), or rule the settled-flag sufficient?
5. **NEW-SCOPE register:** open any rejected modern capabilities (generic attachment+object-store+ClamAV, tamper-evident clinical audit events, RBAC on clinical endpoints, lab batching, per-analyte results) as explicit net-new CRs, or park them?
6. **Module-map reconciliation:** amend ADR-0008 to reflect the as-built single `clinical` module (vs the stale three-module diagram)?
