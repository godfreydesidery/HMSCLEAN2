# Inc-07 Discovery & Reconciliation — Inpatient & Nursing

**Date:** 2026-06-05 · **Workflow run:** `wf_e0bd0efe-a0c` (16 agents, ~10.5 min, ~883K tokens)
**Raw output:** [00-discovery-raw.json](00-discovery-raw.json) · **Script:** [00-discovery-workflow.mjs](00-discovery-workflow.mjs)

**Method:** 1 as-built inventory (inc-00..08) ‖ 7 legacy extraction lanes (admission-lifecycle, discharge-plan, nursing-charts, mar-medication, consumable-stock, ward-accrual-billing, rbac-numbering) — each adversarially reconciled against the inc-07 planning doc → solution-architect scope synthesis. Every finding carries a legacy `file:line` cite; legacy = `ZANAHMIS-2-feature/.../com/orbix/api` (the admission/nursing logic lives in the ~9,900-LOC `PatientResource`/`PatientServiceImpl`). Specification oracle only; no migration.

**Reconciliation tally:** ~21 PHANTOM + ~25 DRIFT + ~13 ACCURATE(-with-corrections) verdicts across 7 lanes, plus **39 omitted legacy behaviours**. This is the **highest-drift planning doc of the engagement** — more idealized than inc-08.

---

## VERDICT: `INC07_IS_A_REAL_FULL_BUILD` — but the doc drifted hardest of all three increments

The `inpatient` module is a confirmed **empty stub** (only `package-info.java`) → a genuine ground-up build like inc-08. But the planning doc drifted heavily toward an **idealized clinical-safety design the legacy never implemented**. The five worst phantoms (each verified absent/contradicted by file:line):

1. **WardTransfer entity + `POST /transfer-ward` + a `TRANSFERRED` state** — legacy has **NO ward-to-ward transfer at all** (no business logic mutates the bed FK post-admission; the only `*Transfer` class is the unrelated `ConsultationTransfer`). *(Correction, inc-07 decisions Q8: `Admission.wardBed` at `Admission.java:62-65` is actually `@JoinColumn(nullable=true, updatable=TRUE)`, not `updatable=false`. Immaterial to this verdict — nothing mutates it — but the HMSCLEAN2 Flyway DDL must NOT mark the FK non-updatable on the strength of the original stale claim.)*
2. **Closed-loop `MedicationAdministration`/MAR aggregate** (M15, "mandatory") — legacy has **NO MAR**; the closest artefact is the free-text `PatientPrescriptionChart` (dosage/output/remark). Even the M15 rationale ("prior build tracked only pharmacy `dispensedAt`") is wrong — there is **no `dispensedAt` field** in legacy.
3. **`ConsumableStockBalance.decrementForIssue` as an inc-08 dependency** — this exact name exists **nowhere** in HMSCLEAN2 (zero source matches), AND legacy consumable issue touches **no stock entity whatsoever** (it is billing-only).
4. **`WardDayAccrualJob` as a `@Scheduled`+ShedLock midnight cron** presented as exact-process — there is **zero scheduling infra** in HMSCLEAN2, and legacy accrual is a hand-rolled 5-min polling Thread keyed on **rolling-24h-from-openedAt**, not a calendar cron. (The doc's "legacy charges one flat amount at admission" is also factually wrong — legacy re-accrues per 24h.)
5. **Typed `DischargePlan.kind` discriminator + second-approver `SELF_APPROVAL_FORBIDDEN` gate + invented privilege codes** — legacy has **three separate disposition tables** (no `kind`), and the discharge approver is **ALWAYS copied from the creator** (`approvedBy == createdBy`), so the M17 "missing gate" is net-new SoD, not a fix.

**Net:** build inc-07 as a genuine full module, but **strip the phantoms first** and re-baseline against the corrected legacy model. Split into **07a / 07b / 07c** and park the safety improvements (MAR, second-approver, deceased-readmit guard, ward-transfer, consumables-from-stock, cron-accrual idempotency) as explicit CRs.

---

## ALREADY BUILT in inc-00..08 (do NOT rebuild)

1. **Stock-decrement engines (inc-08) — the doc's named dependency is WRONG.** No `ConsumableStockBalance`/`decrementForIssue` anywhere. The real seams are `pharmacy.application.StockService.decrementFefo` (`StockService.java:69-85`, hard negative-stock gate then FEFO + ledger) and `inventory.application.StoreStockService.decrementFefo` (`:83-95`) — both `@Service` but **package-private** (no `@NamedInterface`), so inc-07 cannot call them cross-module as-is.
2. **Published pharmacy seam (caveat):** the only `pharmacy::api` type is `PharmacyStockCredit` (credit/transfer); its `debitTransferOut` is a published hard-gated FEFO decrement but writes a **TRANSFER_OUT** card with a transfer-flavoured reference — semantically wrong for a consumable issue. `inventory` has **no `api` package**. → **No published consumable-issue decrement seam exists today.**
3. **Billing charge seam (inc-06):** `billing::api.BillingCommands.recordClinicalCharge(ChargeRequest, ctx)` runs in the caller's tx; `ChargeRequest` already carries `inpatient(boolean)`. `SettlementPolicy.requiresPrepayment(...)` returns **false for inpatient always** ("settle at discharge") — the policy inc-07 must honour.
4. **Billing settled-flag/event seam (inc-06):** `shared.event.BillSettledEvent` + `SettlementDispatcher.onBillPaid` (publishes in-tx). **BUT** `ConsultationSettlementListener` silently ignores ward/registration bills → inc-07 must add its **own admission settlement listener** if it wants a ward/consumable settled projection.
5. **Masterdata ward entities + DDL (zero seed data):** `Ward`/`WardType`/`WardCategory`/`WardBed` exist with full CRUD (`V6`). `WardType.price` NUMERIC(19,2) is the per-**stay** cash anchor; **no per-ward price**. `WardBed` is the physical bed master; **`AdmissionBed` (occupancy/billing ledger) is NOT built**. Inc-07 must seed ward masterdata + WARD `service_prices` itself.
6. **Ward pricing via `ServiceKind.WARD`:** present (`ServiceKind.java:46-51`), per-stay (no `WARD_DAY`); `service_prices` replaces `WardTypeInsurancePlan`. `PriceLookup.resolve(plan, WARD, wardTypeUid, ccy)` handles it (cash fallback). **Caveat:** the legacy ward referral-override + **top-up split** is a deferred billing-engine gap inc-07 ward billing inherits.
7. **Clinical seam (inc-05):** `clinical::api` publishes consultation booking/lookup + `PrescriptionReadPort/DispensePort/WorklistPort`; `PrescriptionView` already carries `admissionUid`; INPATIENT worklist admits PAID|COVERED|VERIFIED. The **consultation→admission trigger does NOT exist yet** — inc-07 owns it.
8. **`PatientPrescriptionChart` (inc-05, write-path deferred to inc-07):** entity exists (`V27`, dosage/output/remark, prescription FK, exactly-one encounter, nurse loose ref); **no write endpoints, no clinical::api read surface.** Deferred rules: linked prescription GIVEN + admission IN-PROCESS + nurse uid. Inc-07 owns this dosing-note write path (the legacy-faithful alternative to MAR).
9. **Shared kernel (reuse wholesale):** `AuditableEntity` (`@Version` = the **only** concurrency guard, **no pessimistic lock anywhere**), `Money`, `TxAuditContext`, `BusinessDay`/`BusinessDayService`, `shared.event`, `shared.audit.AuditRecorder` (SHA-256 chained, ADR-0007 — not Envers), `shared.documentnumber`.
10. **ShedLock / Quartz / `@Scheduled`: CONFIRMED ABSENT** (zero matches). A periodic ward-charge job needs **net-new scheduling infra + a CR**.

---

## GROUND-TRUTH LEGACY MODEL (supersedes the planning doc where they conflict)

### A. Admission lifecycle — two-phase, payment-gated activation
`Admission.status` is **free-text String**: `PENDING → IN-PROCESS → STOPPED → HELD → SIGNED-OUT` (NOT the doc's typed `ADMITTED→.../TRANSFERRED`). **Activation gate (HIGH-RISK omission, N1):** admit creates a `PENDING` admission + `WAITING` bed + `UNPAID` ward bill; only **full insurance cover** activates at admit, else activation is on **ward-bed bill payment** (`PatientBillResource.java:352-365`) → promotes `PENDING→IN-PROCESS` + bed `OCCUPIED`. PENDING admissions appear in no worklist and **reject charting** ("Admission not verified"). One `wardBed` relation (derive ward from bed), not separate `wardUid`+`bedUid`. Admit blocks an existing open `PENDING`/`IN-PROCESS` admission; blocks outpatient consultation while admitted. **No `WardTransfer`**, **no `billsCleared` field** (recomputed live), **no `businessDayId` on Admission**.

### B. WardBed cycle
`EMPTY → WAITING (admit) → OCCUPIED (payment/full-cover) → EMPTY (signout)`. No `FREE`/`RESERVED`/`OUT_OF_SERVICE`. `AdmissionBed` (status OPENED/CLOSED, `@OneToOne PatientBill`, openedAt/closedAt) is the billing ledger backing ward-day accrual — and is **left OPENED at discharge** (legacy leak, N7).

### C. Disposition — three separate entities, controller-layer, single-actor
`DischargePlan`, `ReferralPlan`, `DeceasedNote` are **three tables** (no `kind`), status `PENDING → APPROVED` only. The create/finalize logic lives in the **controller** (class `@Transactional`). The approver is **always copied from the creator** (no second-approver gate). DISCHARGE does **no required-field validation** at create; DECEASED **does** validate `patientSummary`+`causeOfDeath` and splits user-entered `date`+`time` (not a server timestamp); REFERRAL already uses a **mandatory FK** to a provider. STOPPED/HELD intermediate states + side-effect choreography (finalize only from STOPPED; deceased uses IN-PROCESS→HELD, frees bed at HELD). ReferralPlan + DeceasedNote also run **outpatient consultation** paths (N17). Hard **bills-cleared gate** on all three dispositions, all admissions (UNPAID **and** VERIFIED count as outstanding) — behaviour faithful; the doc's denormalised-flag + CASH-only framing is wrong.

### D. Nursing charts — FIVE entities (not six), mostly billing-bearing
`PatientVital` (single-row upsert, `EMPTY→PENDING→SUBMITTED` submit machine, one `pressure` field not split, context-exclusivity gate, copies to GeneralExamination; supports consultation/nonConsultation too — **not admission-only**), `PatientNursingChart` (**fluid-balance + care-activity are free-text COLUMNS here**, not separate entities), `PatientNursingCarePlan` (one snapshot, four free-text strings, no status), `PatientNursingProgressNote` (single free-text `note`, no `kind`), `PatientDressingChart` (**a BILLING record**: mandatory `PatientBill` + `ProcedureType`, no `WoundStatus`/wound field; create raises a bill trichotomy UNPAID/COVERED/VERIFIED; delete raises a credit note + cascade). All charts: nurse-required, admission-status gate, **24h edit/delete window** (not "immutable"). No `MedicationAdministration` anywhere.

### E. Consumable issue — billing-only, NO stock effect
`save_patient_consumable_chart` creates a `PatientConsumableChart` + accrues a `PatientBill` (three-way: UNPAID cash | COVERED via MedicineInsurancePlan + PatientInvoiceDetail | VERIFIED cash-on-admission) — **touches no stock entity at all**. `invoiceDetail.qty` hard-coded to **1** in both branches even though bill qty = chart qty (N14, likely a latent bug). Requires a `Consumable` master row for the Medicine ("Medicine is not listed as consumable", N15). Delete → GIVEN guard + 24h window + PatientCreditNote (reference mislabeled "Canceled lab test", N11) + cascade, **no stock restoration**.

### F. Ward-day accrual — rolling-24h polling Thread, not a calendar cron
A 5-min polling Thread re-accrues per **rolling 24h from `openedAt`** while status is IN-PROCESS or STOPPED; **stops** the moment status flips to SIGNED-OUT/HELD (N5). First (admission-time) bill `UNPAID`; accrued bills `VERIFIED`; insurance `COVERED`. Total = `(1 + N) × WardType.price`. Top-up supplementary bill for the plan-price difference (N3). The active-flag-ignored insurance quirk (N2).

### G. RBAC / numbering
Inpatient/nursing/discharge endpoints were **largely UNGATED** (commented-out `@PreAuthorize`); only `ADMISSION-CREATE` is legacy-derivable. The legacy **privilege purge loop** (`MainApplication.java:306-338`) DELETES any privilege whose operation suffix is outside a fixed set — so the doc's `*-WRITE`/`*-DISCHARGE` codes are **convention-incompatible**, not just absent (N22). Worklists are status FILTERS, not RBAC gates (N23). Admission has no number/prefix — uid surrogate only.

---

## PLANNING-DOC DRIFT (rejected/corrected — see raw `synthesis.drift` D1–D26)

**PHANTOM (no legacy basis — park as CR, never exact-process AC):** WardTransfer + `/transfer-ward` + TRANSFERRED state (D1) · closed-loop MAR (D2) + the "dispensedAt" premise (D3) · `ConsumableStockBalance`/`decrementForIssue` + pessimistic lock on consumable (D4) + the last-unit 409 concurrency test (D5) · `WardDayAccrualJob` cron+ShedLock mechanism (D8) · `billsCleared` denormalised field (D15) · NursingProgressNote.kind (D18) · DressingChart `WoundStatus` enum + "immutable" (D19) · second-approver `SELF_APPROVAL_FORBIDDEN` gate (D23) · deceased-readmit guard + `PATIENT_DECEASED` (D24) · `*-WRITE`/`*-DISCHARGE`/`DISCHARGE_PLAN_APPROVE` privilege codes (D22).

**DRIFT (contradicts legacy — correct the model):** typed Admission states incl. TRANSFERRED (D13) · WardBed FREE/OCCUPIED/RESERVED/OUT_OF_SERVICE + "OCCUPIED on admit" (D14, real cycle EMPTY→WAITING→OCCUPIED→EMPTY) · typed `DischargePlan.kind` over one entity (D12, real = 3 tables) · CASH-only + denormalised-flag discharge gate (D16) · six chart aggregates with first-class FluidBalance/CareActivity (D17, real = 5, columns) · CarePlan lifecycle (D20) · Vitals append-only + BP split (D21, real = upsert + submit machine) · ConsumableIssue sourceKind/sourceLocationUid/unitCost-snapshot (D6) + typed consumable status (D7) · ServicePrice(kind=WARD) simple lookup ignoring the top-up split (D10) · "one flat charge at admission" (D11, factually wrong) · accrual calendar-night idempotency (D9) · DISCHARGE required-field validation + "recommendations" naming (D26) · PENDING-only approver worklist (D25).

---

## CONFIRMED-ACCURATE (build as written, with the noted corrections — raw `synthesis.confirmedAccurate`)
Admission has admittedAt/dischargedAt + a bed ref (derive ward from bed; dischargedAt null on deceased) · disposition PENDING→APPROVED (typed enum constrained to those two values OK) · REFERRAL requires a provider FK (legacy already enforces it — but the masterdata entity isn't built, Q7) · consumable charge accrued in-tx via `recordClinicalCharge` (reproduce the 3-way outcome) · `POST/GET .../consumables` (but **no stock effect** — "creates chart + accrues bill") · hard bills-cleared discharge precondition (all admissions, all 3 dispositions, UNPAID+VERIFIED) · nursing charts admission-scoped (except vitals) with status gate · WardBed released to EMPTY on terminal · admission uid surrogate numbering · `ADMISSION-CREATE` (real, but legacy endpoint was ungated → CR-hardening) · MAR round-trip as a **net-new feature** acceptance test (not parity) · tamper-evident audit via `AuditRecorder` (net-new; no Envers; no device-fingerprint).

## NEW DRIFT (legacy behaviour the doc OMITS — raw `synthesis.newDrift` N1–N23)
Activation gate PENDING-vs-IN-PROCESS (N1, HIGH-RISK) · ward-type insurance active-flag-ignored quirk (N2) · top-up ward bill (N3) · ward-bill status distinction load-bearing for the gate (N4) · accrual stops on status change (N5) · AdmissionBed ledger (N6) + not-closed-at-discharge leak (N7) · dressing-chart billing side-effects + credit-note reversal (N8) · 24h edit/delete window across all charts (N9) · vitals submit state machine (N10) + context-exclusivity gate (N11) + nurse-required guard (N12) + vitals FK asymmetry (N13) · consumable invoiceDetail.qty=1 quirk (N14) + consumable-registration guard (N15) + delete/credit-note path (N16) · outpatient referral/deceased paths (N17) · STOPPED/HELD choreography (N18) + deceased date/time split (N19) + HELD status (N20) · controller-layer logic (N21) · privilege purge loop incompatibility (N22) · worklists are filters not gates (N23).

---

## RECOMMENDED SCOPE & SEQUENCE (raw `synthesis.recommendedScope`)

**Architecture prerequisites (land before 07a):** (1) ServiceKind for consumables — recommend charge as **MEDICINE** (matches legacy) vs new `CONSUMABLE` kind (CR); (2) inpatient stock-decrement seam — **none needed for the exact-process baseline** (consumable = billing-only); a published seam is only for the net-new "consumables draw from stock" CR; (3) billsCleared — **recompute live** (legacy-faithful) vs a new `BillingQueries.billsCleared` query + a new inpatient settlement listener; (4) ErrorCodes — `INSUFFICIENT_STOCK` exists; `PATIENT_DECEASED`/`ADMISSION_BILLS_OUTSTANDING`/`SELF_APPROVAL_FORBIDDEN` only if their CRs are approved; (5) `AdmissionStatus` enum mapped 1:1 to legacy PENDING/IN-PROCESS/STOPPED/HELD/SIGNED-OUT (no TRANSFERRED).

- **07a — Admission + Discharge + Ward** (exact-process): two-phase payment-gated admission lifecycle; admit guards; consultation→admission trigger; WardType.price + WardTypeInsurancePlan max-price/exact-match + **top-up bill**; AdmissionBed ledger; the **three** disposition entities (admission + consultation paths); the hard bills-cleared gate; bed/patient resets. **Park as CR:** WardTransfer, second-approver gate, deceased-readmit guard, calendar-date accrual idempotency.
- **07b — Nursing charts** (exact-process): the **five** entities (vitals upsert + submit machine; nursing chart with fluid-balance/care-activity **columns**; care-plan four strings; single-note progress; dressing as a **billing record** + credit-note reversal). Admission-only inserts (except vitals), nurse-required, status gate, 24h window. **Park as CR:** FluidBalance/CareActivity as first-class entities, ProgressNote.kind, WoundStatus, care-plan lifecycle, vitals append-only + BP split.
- **07c — Consumable + ward-charge accrual:** consumable issue (**billing-only**, 3-way status, qty=1 quirk, registration guard, status gate, delete/credit-note) as exact-process; **ward-day accrual = the NET-NEW @Scheduled+ShedLock cron** (ADR-0018, CR-tagged) reproducing the legacy per-24h **total/status** semantics, golden-master reconciled against the rolling-24h Thread (document the midnight-vs-24h variance). Needs net-new scheduling infra + a CR.

**RBAC:** gating the inpatient surface is entirely **net-new** (legacy ungated). Keep `ADMISSION-CREATE`; all other codes are CR-approved hardening **plus** an IAM operation-vocabulary decision (the purge loop rejects `*-WRITE`/`*-DISCHARGE`).

---

## OPEN QUESTIONS FOR THE ENGAGEMENT OWNER (raw `synthesis.openQuestions` Q1–Q13)

| # | Question | Owners |
|---|----------|--------|
| Q1 | **MAR** as a net-new clinical-safety CR? (legacy has none; else inpatient med tracking is the free-text `PatientPrescriptionChart` dosing note inc-07 still owns) | HDE + engagement-lead |
| Q2 | **Ward-day accrual** — ratify the net-new `@Scheduled`+ShedLock cron (ADR-0018) over the legacy rolling-24h Thread? Accept the midnight-vs-24h timing variance + add scheduling infra at all? | engagement-lead + data-architect |
| Q3 | **Pessimistic lock** — accept inc-08's parked `@Version`-only posture (CR-08-Q4) for the bed-claim race, or lift the lock for the admission/bed aggregate? | engagement-lead |
| Q4 | **Second-approver discharge gate** (M17, SELF_APPROVAL_FORBIDDEN) — approve as net-new SoD? (legacy approver always = creator) Needs a privilege + IAM vocabulary decision. | engagement-lead + security-architect |
| Q5 | **Deceased-readmit guard** (DISCH-4, PATIENT_DECEASED) — approve as net-new? (legacy has no deceased boolean / no admit-time check, but DOES block re-admit while an admission is open) | HDE + engagement-lead |
| Q6 | Confirm **DECEASED stays a `PatientType` enum value** (no boolean `Patient.deceased`, CR-05) via the existing `PatientDeceasedEvent`/`PatientClosureListener`. | engagement-lead |
| Q7 | **Referral FK** — build an `ExternalMedicalProvider` masterdata entity (absent in HMSCLEAN2) or keep the legacy loose `external_medical_provider_uid`? | data-architect |
| Q8 | **WardTransfer** — confirm net-new; scope to a later increment or defer entirely (no exact-process AC). | engagement-lead |
| Q9 | **WardTypeInsurancePlan active-flag-ignored** quirk — preserve or CR-fix? | HDE + engagement-lead |
| Q10 | **AdmissionBed not closed at discharge** (legacy leak) — reproduce for parity or CR-fix? | engagement-lead |
| Q11 | **Consumable `invoiceDetail.qty=1` quirk + mislabeled credit-note reference** — preserve verbatim or CR-fix as latent bugs? | legacy-analyst + engagement-lead |
| Q12 | **Consumables-draw-from-stock** — confirm billing-only baseline (legacy), or approve the net-new CR (needs a new published `CONSUMABLE_ISSUE` decrement seam over inc-08)? | engagement-lead |
| Q13 | **ServiceKind for consumables** — charge as `MEDICINE` (recommended, matches legacy) vs new `ServiceKind.CONSUMABLE` (CR + CHECK change)? | engagement-lead |

---

## RECOMMENDED SEQUENCE (engagement level)
Resolve Q1–Q13 (esp. the net-new safety CRs) → ratify the 07a baseline → build 07a (admission+discharge+ward) → 07b (nursing charts) → 07c (consumable + ward-accrual) → `inc-09` HR/Payroll/Assets → `inc-10` Reporting. As with inc-08, **the build spec is not frozen until the owner decisions land** — this is the discovery-before-code discipline that has held the "exact process" line through inc-06 and inc-08.
