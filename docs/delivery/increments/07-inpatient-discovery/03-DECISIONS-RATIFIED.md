# Inc-07 Decisions — Ratified Register (Inpatient & Nursing)

**Date:** 2026-06-05 · **Workflow run:** `wf_b81b5f70-e2c` (27 agents: 13 resolve + 13 adversarial-challenge + 1 ratify; ~13 min, ~1.17M tokens)
**Raw output:** [02-decisions-raw.json](02-decisions-raw.json) · **Script:** [02-decisions-workflow.mjs](02-decisions-workflow.mjs) · **Inputs:** [01-RECONCILIATION-AND-SCOPE.md](01-RECONCILIATION-AND-SCOPE.md)

**Method:** each of the 13 open questions resolved by the relevant specialist against the engagement charter ("modern design, exact process") **and the actual ADR text + ratified inc-08 precedents**, then adversarially challenged by the code-reviewer (ADR contradiction? smuggled deviation? owner action mis-flagged?), then consolidated by the engagement-lead. **Challenge result: 10 UPHELD, 3 REVISE** (Q3, Q11, Q13 — corrections applied below).

> **The decisive corrections the adversarial pass caught:**
> 1. **Q3** — I'd assumed `409 STALE_ENTITY` is the inherited as-built baseline for the bed-claim race. It is **not**: `ErrorCode.STALE_ENTITY` and the `OptimisticLockException` handler are **absent** (inc-08 froze them out behind CR-08-Q4). Correctness is preserved by `@Version` at commit (first commit wins; the loser's tx doesn't commit), but a clean 409 is itself parked.
> 2. **Q11** — the challenger found a **third latent legacy bug**: a `j=j++` no-op (`PatientResource.java:3070-3076`) deletes the parent `PatientInvoice` **unconditionally** (cascade-wiping sibling details via `orphanRemoval`). Must be reproduced verbatim.
> 3. **Q13** — `ChargeRequest` has **no `billItem`/`description` fields**, so reproducing the legacy "Medication"/"Consumable: …" bill-line literals is **display drift requiring a billing-API extension** (CR-07-Q13-billing-display), not zero-impact.
>
> And the same root fact as inc-08: **all ADRs are status `Proposed`** (README line 3), so ADR-0017 (lock) and ADR-0018 (ward-accrual cron) are **owner-gated, not "build now."**

---

## Ratified decisions

| Q | Topic | Classification | Ratified decision (short) |
|---|-------|----------------|---------------------------|
| **Q1** | MAR | `DEVIATION_NEEDS_CR` | **Drop MAR** (confirmed phantom — zero legacy basis, the "dispensedAt" premise is false). 07b builds the legacy **free-text `PatientPrescriptionChart` dosing-note** path verbatim (GIVEN-prescription guard, exactly-one-encounter, admission-IN-PROCESS gate, nurse-uid) + clinical::api read surface + **24h DELETE-only** guard (no edit path). MAR → **CR-07-MAR**. |
| **Q2** | Ward-day accrual | `OWNER_RELEASE_GATE` | **Parity baseline fixed & buildable now:** total = `(1+N) × WardType.price` (N = completed rolling-24h intervals while IN-PROCESS/STOPPED; accrual stops on status change; admission bill UNPAID, accrued VERIFIED, insured COVERED; top-up split). The **cron+ShedLock mechanism is net-new + parked** (CR-07-Q2) behind ADR-0018 ratification + scheduling-infra authorization. 07c may stage the idempotent `accrueWardDay` service method + manual trigger; **no scheduler binding** until owner ratifies. |
| **Q3** | Bed-claim lock | `OWNER_RELEASE_GATE` | **`@Version`-only** on WardBed/Admission (inherits inc-08 CR-08-Q4 parked posture). Bed double-claim **prevented at commit** by optimistic lock (first wins EMPTY→WAITING; loser's tx doesn't commit). The pessimistic lock → **CR-07-Q3**, which **bundles** the net-new `STALE_ENTITY` ErrorCode + `OptimisticLockException` handler (both absent today — do NOT build into 07a). Concurrency IT asserts only no-double-claim, **not** a 409. |
| **Q4** | Second-approver gate | `DEVIATION_NEEDS_CR` | 07a reproduces **single-actor** approval (approvedBy=createdBy, PENDING→APPROVED), recorded via `AuditRecorder`. The SoD gate → **CR-07-SoD** (bundles: approve SoD + IAM operation-vocabulary decision [APPROVE-suffix only; `*-DISCHARGE`/`*-WRITE` are purge-incompatible] + `SELF_APPROVAL_FORBIDDEN` code). |
| **Q5** | Deceased-readmit guard | `DEVIATION_NEEDS_CR` | 07a reproduces the legacy **open-admission re-admit block** (verbatim 422 over {PENDING,IN-PROCESS}) + the deceased terminal via the existing **`PatientDeceasedEvent`/`PatientClosureListener`**. The explicit **admit-time deceased guard + `PATIENT_DECEASED`** → **CR-07-deceased-guard**. No boolean `Patient.deceased`. |
| **Q6** | Deceased flag modelling | `ARCHITECTURE_DECISION` | **Confirmed:** DECEASED stays a `PatientType` enum value (no boolean, CR-05). Inpatient deceased-finalize publishes the existing `PatientDeceasedEvent` in-tx from **its own** inpatient service (not clinical `ClosureService`, which is OPD-only); registration's listener flips the type. No new event/listener/field/code; no inpatient→registration compile edge. |
| **Q7** | Referral FK | `EXACT_PROCESS_REPRODUCE` | Reproduce the legacy mandatory-provider **rule** via the merged as-built mechanism: a **mandatory loose `external_medical_provider_uid`** (NOT NULL, @NotBlank, no FK) — matching the clinical V28 ReferralPlan precedent. Building `ExternalMedicalProvider` masterdata + real FK → **CR-INC07-Q7**. (DISCH-5 "legacy was free-text" premise is **wrong** — legacy used an FK.) |
| **Q8** | WardTransfer | `DEVIATION_NEEDS_CR` | **Net-new, EXCLUDED** from 07a (legacy has no ward-to-ward transfer). No WardTransfer entity / `/transfer-ward` / TRANSFERRED state; AdmissionStatus frozen at PENDING/IN-PROCESS/STOPPED/HELD/SIGNED-OUT. Park as **CR-07-ward-transfer** (ACs authored net-new). RTM negative-scope note. |
| **Q9** | Active-flag-ignored quirk + top-up | `DEVIATION_NEEDS_CR` | 07a **reproduces** the active-flag-ignored eligibility verbatim AND **builds the load-bearing top-up split** (COVERED principal at eligiblePlan.price + UNPAID "Ward Bed / Room (Top up)" supplementary; the no-top-up branch flips IN-PROCESS/OCCUPIED at admit, the top-up branch stays PENDING/WAITING until top-up payment). Honouring the active flag → **CR-07-Q9** (HDE recommends approve). Build the (near-)unreachable top-up path verbatim; do not "fix" the query scope. |
| **Q10** | AdmissionBed leak | `DEVIATION_NEEDS_CR` | **Reproduce the leak verbatim** (final AdmissionBed left OPENED at discharge; CLOSED only at accrual rollover). WardBed/AdmissionBed stay distinct. Close-at-discharge fix → **CR-07-Q10**. Data-migration guardrail: expect dangling OPENED rows; do NOT assert "all terminal admissions have CLOSED beds". |
| **Q11** | Consumable quirks | `DEVIATION_NEEDS_CR` | **Reproduce 3 latent bugs verbatim:** (1) `PatientInvoiceDetail.qty=1` hard-coded (bill qty = chart.qty); (2) credit-note reference mislabeled "Canceled lab test"; (3) **unconditional parent-invoice cascade-delete** (`j=j++` no-op). Fixes → **CR-07-Q11**. QA asserts all three as positive expectations + the cascade-wipe of a shared multi-detail invoice. |
| **Q12** | Consumables draw from stock | `DEVIATION_NEEDS_CR` | **Billing-only baseline** (legacy touches no stock): create chart + accrue 3-way bill, Consumable-registration guard, admission-status gate, delete→credit-note (no stock restoration). No stock decrement, no seam, **D5 last-unit-409 AC removed**. Stock-decrement → **CR-07-consumable-stock** (needs a new non-transfer `CONSUMABLE_ISSUE` seam; revisits inc-08 CR-08-Q4). |
| **Q13** | ServiceKind for consumables | `OWNER_RELEASE_GATE` | **Charge as `ServiceKind.MEDICINE`** (recommended; no schema change; `PriceLookup.resolve(plan,MEDICINE,medicineUid)` reproduces cash=Medicine.price + MedicineInsurancePlan). Owner-routed → engagement-lead confirmation. **Reproducing the legacy "Medication"/"Consumable: \<name\>" bill-line literals requires CR-07-Q13-billing-display** (ChargeRequest has no billItem/description). 07c must golden-master the cash amount quirk (legacy amount=unit price vs as-built price×qty) at 2dp. |

---

## What freezes now vs what blocks the build

**`readyToFreezeBuildSpec` = PARTIAL.** The exact-process baseline is fully specified.
- **07a (admission + discharge + ward) — FREEZABLE NOW:** `@Version` bed-claim baseline (no-double-claim assertion; STALE_ENTITY explicitly OUT); verbatim AdmissionBed leak; active-flag-ignored ward billing **+ the load-bearing top-up split**; legacy single-actor approval + AuditRecorder; deceased-event reuse; loose referral uid; open-admission re-admit guard; AdmissionStatus 1:1 (no TRANSFERRED).
- **07b (nursing charts) — FREEZABLE NOW:** the **five** legacy chart entities + the free-text `PatientPrescriptionChart` dosing-note write path + read surface + 24h DELETE-only guard. **No MAR, no edit path.**
- **07c (consumable + ward-accrual) — BLOCKED** on: (1) engagement-lead **confirms Q13 = MEDICINE**; (2) **CR-07-Q13-billing-display** raised/decided (else 07c silently emits "Medicine"/"Medicine" display drift); (3) the **ADR-0018 §Context correction** issued before its ratification; (4) the **Q2 scheduler owner gate** (only the billing-only consumable path + the `accrueWardDay` parity oracle are freezable now; the scheduler stays parked).

---

## Change Requests raised (parked — NOT in the baseline)

| CR | Title | Owner / needs |
|----|-------|---------------|
| **CR-07-MAR** | Closed-loop MedicationAdministration aggregate (net-new clinical safety) | engagement-lead + HDE; route + PHI/audit decisions if approved |
| **CR-07-Q2** | `@Scheduled`+ShedLock ward-accrual cron (calendar-night) | engagement-lead + data-architect; **gated on ADR-0018 ratification + scheduling-infra** |
| **CR-07-Q3** | Pessimistic bed-claim lock + **bundled** net-new `STALE_ENTITY` ErrorCode + optimistic-lock handler | engagement-lead; **gated on ADR-0017 ratification + explicit deviation approval** |
| **CR-07-SoD** | Second-approver discharge gate + IAM vocabulary decision + `SELF_APPROVAL_FORBIDDEN` | engagement-lead + security-architect |
| **CR-07-deceased-guard** | Admit-time deceased block + `PATIENT_DECEASED` type | engagement-lead + HDE |
| **CR-INC07-Q7** | `ExternalMedicalProvider` masterdata + real referral FK (clinical V28 + inpatient) | data-architect; reconcile ADR-0022 status at raise-time |
| **CR-07-ward-transfer** | Mid-stay ward/bed transfer (entity + endpoint + state + billing re-anchor) | engagement-lead phase-gate |
| **CR-07-Q9** | Honour `WardTypeInsurancePlan.active` in ward eligibility | engagement-lead (HDE recommends approve) |
| **CR-07-Q10** | Close AdmissionBed at discharge (fix the leak) | engagement-lead (optional cleanup) |
| **CR-07-Q11** | Fix 3 latent consumable bugs (qty=1, mislabeled ref, cascade-delete no-op) | engagement-lead; legacy-analyst confirms intent |
| **CR-07-consumable-stock** | Inpatient consumable issue decrements inc-08 stock (new `CONSUMABLE_ISSUE` seam) | engagement-lead → data-architect + inventory/pharmacy owners |
| **CR-07-Q13-billing-display** | Extend billing API for the "Medication"/"Consumable: \<name\>" bill-line literals | engagement-lead; **required before 07c freezes** |

---

## Owner actions required (only you / the product owner can resolve)

1. **ADR ratification (cross-cutting):** all ADRs are `Proposed`; none binding. The two owner-gated ratifications here are **ADR-0018** (Q2) and **ADR-0017** (Q3).
2. **Q2** — ratify ADR-0018 **after** the §Context false-premise correction (below); authorize scheduling infra; decide the parity bar (accept midnight-vs-rolling-24h variance, or require true elapsed-24h). The accrual **service method + parity oracle proceed now**.
3. **Q3** — approve CR-07-Q3 **and** ratify ADR-0017 (a bed-lock is an *extension* of ADR-0017's stock/document scope even post-ratification); the bundled STALE_ENTITY surfacing rides this gate.
4. **Q1 / Q4 / Q5 / Q9 / Q10 / Q11 / Q12** — decide each parked CR (none blocks the baseline; the legacy-faithful build is the record until then).
5. **Q13** — **confirm `MEDICINE`** placement (recommended) and acknowledge **CR-07-Q13-billing-display** is required for 07c to reproduce the legacy bill-line literals.

---

## ADR / doc corrections

These are factual drift fixes (stale premises vs the as-built/legacy reality). The pure-factual ones are applied in this commit; the ADR-0018 §Context rewrite is the solution-architect's to make at ratification (flagged, not silently edited).

1. **ADR-0018 §Context (line 15) + §Exact-process-impact (130-132)** — the premise "legacy charges one flat ward-bed bill at admission and does not re-accrue" is **factually wrong**: legacy re-accrues per **chained rolling-24h** via `UpdatePatient.java` (5-min poll, closes+reopens AdmissionBed + new VERIFIED bill at ≥24h). JOB-001's golden-master must reconcile the **elapsed-24h chained total + poll-latency** mechanic, not a calendar-night count. *(Solution-architect to rewrite at ratification — flagged here, not edited.)*
2. **ADR-0008** — when cited for the consumable charge seam, use its real title "Bounded-Context Decomposition & Module Boundaries"; `recordClinicalCharge`/`ChargeRequest` carry **no qty/billItem/description** and are silent on invoice-detail qty / credit-note text. *(Citation discipline — no edit needed.)*
3. **ADR-0022** — self-declares "Accepted" while the README holds all ADRs "Proposed"; reconcile at CR-INC07-Q7 raise-time. *(Register drift — flagged.)*
4. **01-RECONCILIATION-AND-SCOPE.md line 16** — states "both bed FKs updatable=false"; legacy `Admission.wardBed` is actually `updatable=TRUE`. Immaterial to the WardTransfer verdict (no logic mutates wardBed post-admission) but **applied in this commit** so the HMSCLEAN2 DDL isn't built marking the FK non-updatable.

---

## Net effect

The exact-process **07a + 07b baseline is fully specified and internally consistent** — it reproduces verified legacy behaviour verbatim (free-text dosing note, single-actor approval, active-flag-ignored ward billing + top-up split, verbatim AdmissionBed leak, billing-only consumables, the three latent consumable bugs, `@Version`-only concurrency) and holds every modern improvement behind one of **12 explicit CRs**. **07c is not freezable** until the owner confirms Q13, CR-07-Q13-billing-display is decided, and the ADR-0018 §Context correction lands (the scheduler stays owner-gated regardless). This is the same discovery-before-code discipline that held the "exact process" line through inc-06 and inc-08.
