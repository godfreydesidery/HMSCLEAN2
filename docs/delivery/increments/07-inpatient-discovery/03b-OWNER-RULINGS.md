# Inc-07 Owner Rulings — CR dispositions & ADR ratifications

**Date:** 2026-06-05 · **Decided by:** product owner (godfrey.desidery@otapp.net) · **Supersedes** the "parked / owner-gated" status of the items below in [03-DECISIONS-RATIFIED.md](03-DECISIONS-RATIFIED.md).

The owner reviewed the 13 ratified decisions and ruled on every owner-gated item. **Net effect: nearly every parked CR is promoted into in-scope baseline work.** The exact-process baseline is now built *together with* these owner-approved deviations (each deviation explicitly authorized here — not silently in baseline).

> **Build consequence:** [05-INC07-BUILD-SPEC.md](05-INC07-BUILD-SPEC.md) is authored as the legacy-verbatim baseline with these improvements marked `frozen:false`. This rulings doc is the **overlay that promotes them to `frozen:true`** with their mandatory bundled dependencies. The build implements the legacy behaviour AND the approved deviation for each item below.

---

## Ratifications

| ADR | Ruling | Consequence |
|-----|--------|-------------|
| **ADR-0017** (pessimistic locking) | **RATIFIED** | Authorizes CR-07-Q3 (bed-claim lock). Note: ADR-0017's scope is stock/documents; the bed extension rides this explicit CR approval. |
| **ADR-0018** (background jobs/scheduling) | **RATIFIED — with the §Context correction first** | The solution-architect MUST rewrite the false "legacy charges one flat ward-bed bill at admission and does not re-accrue" premise (§Context line 15, §Exact-process-impact 130-132) to the verified rolling-24h chained re-accrual fact **before** the ratification is final. Authorizes CR-07-Q2 (cron) + ShedLock infra. |

> ADR register note: all ADRs were status `Proposed`; these two are now owner-ratified for inc-07. The register (docs/adr/README.md line 3) should be updated to reflect 0017 + 0018 as Accepted, and ADR-0022's self-"Accepted" drift reconciled (CR-INC07-Q7 raise-time).

---

## CR dispositions

| CR | Item | Ruling | What now enters scope (with bundled deps) |
|----|------|--------|--------------------------------------------|
| **CR-07-Q3** | Pessimistic bed-claim lock | **APPROVED** (overrides Q3-RECOMMENDED) | `PESSIMISTIC_WRITE` lock on the WardBed/Admission bed-claim aggregate. **BUNDLED (mandatory):** new `ErrorCode.STALE_ENTITY` (`urn:hmis:error:stale-entity`, 409) + `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` in `GlobalExceptionHandler`. Concurrency IT now asserts a clean **409** on the losing claim (not just no-double-claim). **Revisits inc-08 CR-08-Q4** — the inc-08 `@Version`-only posture is no longer the project default for contended bed/stock aggregates; record this as a cross-increment note. |
| **CR-07-Q2** | Ward-accrual scheduler | **APPROVED — calendar-night (midnight) model** | Net-new `@Scheduled`+ShedLock cron `0 5 0 * * *` (the ADR-0018 JOB-001 mechanism). **Accept the documented midnight-vs-rolling-24h timing variance** as a tolerated, runbook-documented deviation (NOT true elapsed-24h parity). **BUNDLED:** ShedLock dependency, `shedlock` + `job_run_log` Flyway tables, `@EnableScheduling`. The accrual TOTAL/STATUS semantics stay legacy-parity ((1+N)×WardType.price, accrued VERIFIED); only the TRIGGER and per-calendar-night idempotency are net-new. |
| **CR-07-MAR** (Q1) | Closed-loop MedicationAdministration / MAR | **APPROVED** | Net-new MAR aggregate (routeUid, administeredAt, doseGiven, patientResponse) + closed-loop administration endpoint, OVER the legacy free-text `PatientPrescriptionChart` dosing-note path (which is ALSO still built — MAR is additive, not a replacement). **BUNDLED:** route/administration masterdata (data-architect), a PHI/audit decision (security-architect — administration is PHI, AuditRecorder-logged). MAR ACs are net-new acceptance tests, NOT golden-master parity items. Likely a **07b** (or 07d) line item — sequence after the legacy chart path is green. |
| **CR-07-SoD** (Q4) | Second-approver discharge gate | **APPROVED — APPROVE-suffixed per-entity privileges** | `approvedBy != createdBy` SoD gate on disposition approval. **BUNDLED:** (1) new IAM privileges `DISCHARGE-PLAN-APPROVE` / `REFERRAL-PLAN-APPROVE` / `DECEASED-NOTE-APPROVE` (APPROVE suffix is canonical → survives the legacy purge loop; `*-DISCHARGE`/`*-WRITE` are purge-incompatible and FORBIDDEN); (2) new `ErrorCode.SELF_APPROVAL_FORBIDDEN`. The legacy single-actor approval (approvedBy=createdBy) is REPLACED by the gate in the baseline build — this is the one place we deliberately diverge from copy-creator. Any deceased-path touch still reuses `PatientDeceasedEvent` (no boolean/admit-guard duplication). |
| **CR-07-deceased-guard** (Q5) | Admit-time deceased block | **APPROVED** | Net-new admit-time (and OPD-booking) deceased-patient block + new `ErrorCode.PATIENT_DECEASED` (RFC 7807 type). Closes the residual re-admit gap. **MUST NOT** add a `Patient.deceased` boolean — the guard reads `PatientType.DECEASED` (CR-05 preserved). The legacy open-admission re-admit block is ALSO still built (additive). |
| **CR-07-Q9** | Honour `WardTypeInsurancePlan.active` | **APPROVED** | Ward-price insurance eligibility now EXCLUDES inactive-but-covered plans from the `findByInsurancePlanAndCovered` selection loop. **Deviation from legacy** (which ignores the active flag). The top-up split + selection loop are built regardless (mandatory exact-process); this ruling only flips the active-flag handling. Golden-master: the active-flag-ignored parity test is replaced by an active-flag-HONOURED test. |
| **CR-07-Q10** | Close AdmissionBed at discharge | **APPROVED** | At discharge/sign-out, set the final `AdmissionBed.status='CLOSED'` + `closedAt` (fixes the legacy open-ledger leak). WardBed/AdmissionBed stay distinct. **Data-migration consequence:** the migration must NOT assume legacy terminal admissions have closed beds (legacy left them OPENED); new code closes them going forward — the ETL note about dangling OPENED rows stands for migrated history. |
| **CR-07-Q11** | Fix 3 latent consumable bugs | **APPROVED** | (1) `PatientInvoiceDetail.qty` = `chart.qty` (not hard-coded 1); (2) consumable `PatientCreditNote.reference` = consumable-specific text (not the mislabeled "Canceled lab test"); (3) replace the unconditional parent-`PatientInvoice` deletion (the `j=j++` no-op cascade-wipe at `PatientResource.java:3070-3076`) with a real emptiness check (delete parent ONLY when it has no remaining details). **Golden-master re-baseline:** the parity tests now assert the CORRECTED behaviour (qty=chart.qty; correct credit-note ref; sibling details survive a single-detail cancel) — NOT the legacy quirks. |
| **CR-07-consumable-stock** (Q12) | Consumables draw from inc-08 stock | **APPROVED** | Inpatient consumable issue now DECREMENTS inc-08 stock (net-new vs legacy billing-only). **BUNDLED:** a new non-transfer `CONSUMABLE_ISSUE` published seam over `StockService`/`StoreStockService` writing an ISSUE/CONSUMPTION-classified stock-card row (NOT `TRANSFER_OUT`); re-enables the last-unit concurrency AC (a `409`/insufficient-stock guard on the consumable issue path). **Revisits the inc-08 `@Version`-only posture** (CR-08-Q4/Q3) — consistent with the CR-07-Q3 lock ruling above, the contended stock decrement on the consumable path gets the pessimistic treatment too. This is the largest-impact ruling: 07c consumables now have a real stock effect. |
| **CR-07-Q13-billing-display** | Bill-line display literals | **APPROVED** (settled earlier) | Extend the billing API (`ChargeRequest`/`recordClinicalCharge` gain `billItem`+`description`, or a seam that accepts them) so the consumable charge reproduces `billItem='Medication'` / `description='Consumable: <name>'`. Q13 pricing kind confirmed = `MEDICINE`. |
| **CR-INC07-Q7** | ExternalMedicalProvider masterdata + real FK | **NOT ruled — stays parked** | The owner did not rule on Q7. Baseline keeps the mandatory **loose** `external_medical_provider_uid` (matching clinical V28). Surface again if/when masterdata is funded. |
| **CR-07-ward-transfer** (Q8) | Mid-stay ward/bed transfer | **NOT ruled — stays parked/excluded** | The owner did not rule on Q8. WardTransfer remains net-new + excluded; `AdmissionStatus` stays frozen (no TRANSFERRED). |

---

## Revised freeze posture

With these rulings, the build is **no longer "07a/07b freezable, 07c blocked"** — it is now a fuller build where the owner-approved deviations are first-class scope:

- **07a (admission + discharge + ward):** legacy two-phase lifecycle + **pessimistic bed lock + STALE_ENTITY/409 (CR-07-Q3)** + **admit-time deceased guard + PATIENT_DECEASED (CR-07-deceased-guard)** + **second-approver SoD + APPROVE privileges + SELF_APPROVAL_FORBIDDEN (CR-07-SoD)** + **active-flag-honoured ward eligibility (CR-07-Q9)** + **close-bed-at-discharge (CR-07-Q10)**. AdmissionStatus enum still excludes TRANSFERRED.
- **07b (nursing charts):** the five legacy chart entities + free-text `PatientPrescriptionChart` path, **PLUS the net-new closed-loop MAR aggregate (CR-07-MAR)** with its route masterdata + PHI/audit handling (sequence MAR after the legacy chart path is green).
- **07c (consumable + ward-accrual):** consumable billing path **+ stock decrement via the new CONSUMABLE_ISSUE seam (CR-07-consumable-stock)** + **the three corrected behaviours (CR-07-Q11)** + **MEDICINE pricing with billItem/description literals (CR-07-Q13-billing-display)** + the **calendar-night @Scheduled+ShedLock accrual cron (CR-07-Q2)**.

## Remaining prerequisites before 07a code (owner-action follow-ons)

1. **ADR-0018 §Context correction** issued by the solution-architect (the false "one flat charge" premise) — gates the ADR-0018 ratification being final and the accrual golden-master being anchored correctly.
2. **MAR masterdata + PHI/audit decision** (data-architect + security-architect) — gates CR-07-MAR build, not 07a/07c.
3. **Cross-increment note:** record that the inc-08 `@Version`-only concurrency posture (CR-08-Q4) is now superseded for contended bed + consumable-stock aggregates by the CR-07-Q3 / CR-07-consumable-stock pessimistic-lock rulings.
4. **ADR register update:** 0017 + 0018 → Accepted; reconcile ADR-0022 status drift.

## CR-07-MAR prerequisites — RULED 2026-06-05 (owner)

The two bundled dependencies that gated CR-07-MAR (item 2 above) are now ruled. CR-07-MAR is
**cleared to build as chunk 07d** on `feat/increment-07-inpatient-nursing`, before the inc-07 PR.

| Prereq | Ruling | Build consequence |
|--------|--------|-------------------|
| **Route masterdata** (data-architect) | **New `administration_routes` masterdata table** — first-class entity (code / name / active) with full CRUD + a `RouteLookup` read seam, mirroring `DressingLookup` / `ConsumableLookup`. | `MedicationAdministration.routeUid` is a loose uid validated against `RouteLookup` (admin-manageable controlled vocabulary; no enum redeploy to add a route). New Flyway migration `administration_routes` + seed-optional. |
| **PHI / audit posture** (security-architect) | **Standard posture** — `AuditableEntity` + SHA-256 chained `AuditRecorder` on **create** (write-path only; no read-path auditing), gated behind a **new IAM privilege `MEDICATION-ADMINISTER`** (APPROVE-suffix convention N/A — this is a create privilege, not an approval). | Same audit/RBAC plumbing as the nursing-chart + consumable write paths. NOT elevated read-audit. One new privilege row (Flyway), one new `ErrorCode` only if a guard needs it. |
| **Sequencing** | **Build now as chunk 07d** on the inc-07 branch, before the PR — inc-07 merges as one complete unit. | MAR ACs are **net-new acceptance tests, NOT golden-master parity** (no legacy MAR exists). The legacy free-text `PatientPrescriptionChart` dosing-note path (already built in 07b) stays — MAR is additive over it. |

**MAR aggregate shape (from the CR-07-MAR ruling row, unchanged):** net-new `MedicationAdministration`
(`routeUid`, `administeredAt`, `doseGiven`, `patientResponse`) + a closed-loop administration endpoint,
linked to the prescription + admission. Admission must be IN-PROCESS; administering user (nurse) required.

## Still parked (not ruled)

- **CR-INC07-Q7** (ExternalMedicalProvider masterdata + real referral FK) — loose uid baseline stands.
- **CR-07-ward-transfer** (Q8) — excluded; no TRANSFERRED state.
