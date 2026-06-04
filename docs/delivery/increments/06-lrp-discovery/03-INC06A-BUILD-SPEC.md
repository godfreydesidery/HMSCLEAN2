# Inc-06A Build Spec — Clinical-Order Fulfilment Top-Up (closes inc-05 deferrals)

**Date:** 2026-06-04 · **Workflow:** `wf_fed42f7d-d07` (build-spec; ITEM6 incorporated from main-loop code-verified finding after its extraction agent stuck in a schema-retry loop).
**Raw:** [02-inc06a-buildspec-raw.json](02-inc06a-buildspec-raw.json) · **Reconciliation/scope:** [01-RECONCILIATION-AND-SCOPE.md](01-RECONCILIATION-AND-SCOPE.md)

**Owner-ratified:** build inc-06A now; ITEM 5 = legacy-parity local-disk storage. Branch `feat/increment-06-lab-radiology-procedure-theatre` off `main` (HEAD `21fd676`). Flyway ceiling V37 → inc-06A starts V38. Each chunk: `mvn clean verify` GREEN + commit; verify every controller endpoint + IT exists.

---

## CHUNK PLAN (ordered)

| Chunk | Item | Size | Flyway | Depends |
|-------|------|------|--------|---------|
| **C1** | ITEM1 — wire L/R/P delete → `billing.api.cancelCharge` (refs `Canceled lab test`/`Canceled radiology`/`Canceled procedure`) + fix delete-guard messages to legacy verbatim | SMALL | none | none |
| **C2** | ITEM6 — consultation **cancel** child-order bill cascade (free path already cascades; cancel does NOT — wire it; refactor `cancelUnsettledChildOrders` to take a reference param) | SMALL | none | C1 |
| **C3** | ITEM3 — `save_reason_for_rejection` re-callable post-rejection `rejectComment` edit (lab+rad; guard status==REJECTED, verbatim `Could not save. Only allowed for rejected tests`) | MEDIUM | none | C1 |
| **C4** | Bill-status read seam — publish `billing.api.BillingQueries.getBillStatus(billUid)` (enables C5/C6) | SMALL | none | C1 |
| **C5** | ITEM2 — Radiology stand-alone **bill-gated** `add_report` (NEW) + correct lab `add_report` to the same bill-status gate; verbatim `Could not add report. Payment not verified` | MEDIUM | none | C4 |
| **C6** | ITEM4 — post-VERIFIED report-amendment policy: **DECISION-GATED** (escalate AMB-INC06A-ITEM4; do NOT self-resolve) | MEDIUM | conditional V38 | C5 |
| **C7** | ITEM5 — attachment local-disk storage + multipart upload + inline download (lab+rad); exact guard order, filename scheme, 10 MiB cap, verbatim messages | LARGE | conditional | C1 |

---

## KEY DECISIONS BAKED IN

- **C1:** wire `cancelCharge` BEFORE `repository.delete(...)` (same tx). **Do NOT** reproduce legacy hard-delete of bill/payment (ratified soft `CANCELED`/`REFUNDED`) nor the **j=j++ always-delete-parent-invoice bug** (CR-10 fix is the ratified deviation — parent invoice deleted only when empty). The clinical **order row** is still hard-deleted (matches legacy for the order entity).
- **C4 bill-status seam (the central architecture call):** the order-time `settled` boolean is **provably insufficient** — legacy `add_report` reads the LIVE `PatientBill.status ∈ {PAID,COVERED,VERIFIED}`, and a bill paid at the cashier *after* order creation still shows `settled=false` (cash-PAID→settled propagation is deferred), so a settled-flag gate would *wrongly reject* an add_report legacy allows. Fix: a narrow read-only `BillingQueries.getBillStatus(billUid)` returning the already-published `BillStatus` enum. No new module edge (clinical already depends on `billing::api`), no cycle. Requires an **ADR-0008 addendum** narrowly relaxing §6 ("clinical never reads billing bill-status post-hoc") **for the add_report parity case only** — settlement gates keep using the local flag.
- **C6 ITEM4 = `NEEDS_HDE_LEGACY_CONFIRM`:** legacy `add_report` has no order-status guard → a VERIFIED report is silently overwritable forever with no amendment trail (Envers is a confirmed phantom dep). Three options to route via engagement-lead/HDE: (a) pure legacy overwrite (unsafe), (b) symmetric post-VERIFIED lock, (c) **recommended** — audited AMEND path (`amendedBy/On/At` + prior-narrative retention). Code deferred until the CR verdict lands.

---

## REJECTED / DEFERRED

Legacy hard-delete of bill/payment (soft-flag supersedes); j=j++ invoice bug (CR-10 fix); prescription-delete divergences (`GIVEN` vs `RECEIVED` guard, `PENDING|NOT-GIVEN` delete, `Deleted prescription` vs legacy `Canceled prescription` ref) → ambiguity register; `CompanyProfile.publicPath` singleton → owner-chosen configured app property; legacy download path-traversal + inline `application/*` → security-architect (hardening = approved deviation, not silent); dead-code `fileUri`/`Procedure canceled`/`no=NA` blocks; `switch_to_consultation` NONE→UNPAID activation (pre-existing inc-05 deferral).

## RISKS (mitigations in raw spec)

ADR-0008 §6 relaxation (scope narrowly); ITEM4 patient-safety (don't merge C5 overwrite-after-VERIFIED until CR ruled); silent as-built divergences (each correction must be ratified); ITEM5 `patientNo` cross-module (resolve via registration seam or order-time value — a `clinical→registration` edge WOULD cycle, avoid); ITEM5 path-traversal (security-architect); HTTP 409-vs-422 (default as-built 422, get ruling); ITEM5 cap concurrency; conditional-Flyway data continuity.

---

## OPEN DECISIONS FOR OWNER (before/within the relevant chunk)

1. **C4/ADR-0008 §6 relaxation** — approve the narrow clinical→billing bill-status read seam (recommended; required for exact `add_report` parity)?
2. **C6 ITEM4 policy** — (a) legacy overwrite / (b) symmetric lock / (c) audited AMEND path (recommended)?
3. **C3 & C5 HTTP status** — keep as-built 422 (recommended) or byte-exact legacy 409?
4. **C7 storage path property name** + whether to add optional attachment metadata columns (size/mime/original-name) beyond legacy parity.
5. **Endpoint path naming** for the new reject-comment / add-report endpoints (legacy-aligned vs as-built convention).
