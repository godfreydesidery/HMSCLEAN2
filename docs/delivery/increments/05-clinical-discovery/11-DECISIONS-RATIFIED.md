# Increment 05 (Clinical / OPD) — Ratified Decisions & Change-Request Register

**Status:** RATIFIED 2026-06-04 by the engagement owner.
**Source of truth:** the verified legacy extractions ([01-extractions.md](01-extractions.md)),
adversarially verified ([02-verifications.md](02-verifications.md), all 7 areas
ACCURATE_WITH_CORRECTIONS, **0 materially wrong**), design reviews
([03-design-reviews.md](03-design-reviews.md)), and the solution-architect synthesis
([04-synthesis-buildspec.md](04-synthesis-buildspec.md)).

**The planning doc [`05-clinical-opd.md`](../05-clinical-opd.md) is SUPERSEDED IN PART** — it carried
heavy drift (see §Drift Corrections). This file + the synthesis are the build contract.

---

## 1. Engagement-owner rulings (the 6 NEEDS_HUMAN + 3 ownership questions)

Every consequential decision was put to the engagement owner. **The standing contract
("modern design, EXACT process") was applied: reproduce legacy, defer net-new.**

| CR / Topic | Ruling | Build consequence |
|---|---|---|
| **CR-INC05-01** Pay-before-service hard gate at order accept/verify | **Gate at OPEN only (parity)** | `SettlementPolicy.requireSettled` is called at the consultation `open_consultation` transition (against the LOCAL settled flag). Order `accept`/`verify` do **NOT** re-check the bill — exactly as legacy. Worklists filter unsettled rows (UI parity). The direct-API-bypass gap is reproduced, not closed. |
| **CR-INC05-02** Method-level RBAC on clinical endpoints | **Authenticated-only (parity)** | Only the 4 REAL legacy gates are applied: `PATIENT-ALL`/`PATIENT-CREATE`/`PATIENT-UPDATE` on `do_consultation`/`switch`/`cancel`/`free`, and `ADMIN-ACCESS` on `diagnosis_types`/save. **Every other clinical endpoint — incl. death recording, diagnosis, orders, notes, worklists — is `isAuthenticated()` only.** No new privilege codes. (HDE's BLOCKER is documented as a known gap for a future security CR.) |
| **CR-INC05-06** Side-effecting GET loaders | **Reproduce faithfully (parity)** | `load_clinical_note`/`load_general_examination` persist an empty row on GET (HTTP 201). `get_deceased_summary`/`get_referral_summary` are GETs that mutate state + approve invoices. All reproduced verbatim. Non-idempotency + blank-note pollution are preserved (the legacy UI relies on them). |
| **CR-INC05-09** Closure bill-gate inconsistency | **Preserve both gates exactly (parity)** | death/inpatient closure rejects `{UNPAID, VERIFIED}`; OPD-referral closure rejects `{UNPAID}` only. Asymmetry preserved verbatim — NO harmonisation (an NHIF/finance decision deferred). |
| **CR-INC05-10** Deceased-patient booking guard | **Reproduce incidental block (parity)** | No explicit `PATIENT_IS_DECEASED` guard. A DECEASED patient is blocked only incidentally by the `patient.type=='OUTPATIENT'` check (misleading message reproduced). No reversal/un-decease path (legacy has none). |
| **Allergy-check gate at prescribing** (HDE safety gap, NOT legacy) | **Defer** | No drug-allergy contraindication check (legacy parity). Recorded as a **KNOWN SAFETY GAP** in the edge-case register for a future deliberate clinical-safety CR. Not silently implemented. |
| **ClinicianPerformance ownership** | **Defer entirely** | `clinician_performances` is NOT built in inc-05, and the `open_consultation` side-effect that writes it is **skipped**. It is a reporting/iam concern for its proper increment. The OPD flow is unaffected. |

---

## 2. Auto-ratified CRs (synthesis decisions, confirmed)

These were decided by the synthesis with a clear legacy basis and stand as-is:

| CR | Decision | Kind | Note |
|---|---|---|---|
| **CR-INC05-03** Tamper-evident audit for clinical/financial transitions | **ACCEPT (requirement) — DDL DEFERRED** | net-new-hardening | The audit *requirement* is ratified (per ADR-0007). **Approver attribution MUST come from the real authenticated principal** (legacy copies `approvedBy` from the CREATOR — a bug we do NOT reproduce; we capture the true approver). The audit DDL (V29) + covered-entity set are DEFERRED to security-architect's PHI classification — not self-selected. |
| **CR-INC05-04** Long-id `==` reference-equality routing bugs | **ACCEPT — resolved by design** | reproduce-legacy | Cross-module refs are ULID Strings compared with `.equals()`; the boxed-`Long ==` bug (wrong clinical routing for ids > 127) cannot exist. No boxed-id path to preserve. |
| **CR-INC05-05** OUTSIDER duplicate-drug NPE | **ACCEPT corrected** | net-new-hardening | Legacy crashes (`existsByConsultationAndMedicine` on an empty Optional) for outsider prescriptions. We implement a proper `existsByNonConsultationAndMedicine` so the duplicate rule *evaluates* instead of NPE-ing. Business intent (block duplicate drug per encounter) preserved + extended to the type legacy crashed on. |
| **CR-INC05-07** Diagnosis duplicate-guard consultation-vs-admission asymmetry | **ACCEPT — reproduce asymmetry** | reproduce-legacy | App-layer duplicate check on consultation paths only; NO DB unique on `(admission_uid, diagnosis_type_uid)`. Do NOT auto-unify (would silently change admission behaviour). |
| **CR-INC05-08** Empty-invoice deletion bug (`j = j++`) | **ACCEPT corrected — in BILLING** | net-new-hardening | The `j=j++` no-op always-deletes-parent-invoice bug lives in billing's invoice ownership. inc-04 already reproduced the correct "delete invoice only when empty" behaviour (its CR-10). The clinical prescription-delete path delegates invoice cleanup to billing; we do NOT re-introduce the bug. No clinical-side action beyond delegating correctly. |
| **CR-INC05-12** Non-deterministic "last row" selection | **ACCEPT deterministic** | net-new-hardening | same-medicine alert = `MAX(approvedAt)`; admission note/exam loaders = `ORDER BY created_at/id DESC`. Same business intent + field set, deterministic result (HDE flagged the stale-note hazard). |
| **CR-INC05-14** `collect_radiology111` dead endpoint | **ACCEPT** | reproduce-legacy | Model radiology as `ACCEPTED → VERIFIED` (the active path). Do NOT expose the malformed `/collect_radiology111` URL. Keep `COLLECTED` in the `radiologies` status CHECK for data fidelity, with no live transition into it. |
| **CR-INC05-11** Empty catch-all in day-rollover sweep | **DEFER** | scope-cut | The 48h ARCHIVED sweep belongs to the business-day/day-management context, not inc-05 clinical write paths. Document only. |
| **CR-INC05-13** Vitals free-text → typed numeric | **REJECT for default build** | scope-cut | Preserve free-text VARCHAR vitals (incl. BMI/BSA) verbatim. The `double→BigDecimal` pre-approval does NOT extend to adding range validation / server-side BMI computation (net-new). A separate future CR if desired, with backfill. |
| **CR-INC05-15** Procedure REJECTED reachability + admission-procedure worklist gap | **DEFER (reproduce-legacy default)** | reproduce-legacy | No `reject_procedure` endpoint (so Procedure never reaches REJECTED); `get_procedures_by_patient_id` omits admission-scoped procedures. Reproduce both gaps; adding either is net-new, sequenced after HDE input. |

---

## 3. Drift corrections (planning-doc inventions NOT built)

Every one of these is a planning-doc invention contradicted by the verified legacy:

| Planning-doc claim | Legacy reality (built) | Citation |
|---|---|---|
| Consultation states `BOOKED → IN_PROGRESS → COMPLETED \| CANCELLED \| TRANSFERRED` | `PENDING / IN-PROCESS / TRANSFERED / CANCELED / SIGNED-OUT / HELD` — String-backed enum, EXACT legacy spellings (single-R "TRANSFERED", single-L "CANCELED"). **No "COMPLETED" on Consultation** (that's on ConsultationTransfer). `STOPPED` is an Admission-only ghost, EXCLUDED. | Consultation.java:55-56; PatientServiceImpl.java:494; PatientResource.java:886 |
| Polymorphic `ClinicalOrder` (kind=LAB_TEST\|RADIOLOGY\|PROCEDURE) + `OrderResult` entity | THREE separate tables `lab_tests` / `radiologies` / `procedures`; results are COLUMNS, no `OrderResult` entity | LabTest.java / Radiology.java / Procedure.java |
| One `ConsultationDiagnosis` with `kind=WORKING\|FINAL` | TWO separate tables `working_diagnoses` + `final_diagnoses` (byte-for-byte twins) | WorkingDiagnosis.java / FinalDiagnosis.java |
| Prescription `PENDING→ACCEPTED→HELD→VERIFIED→APPROVED→SOLD\|REJECTED\|CANCELLED` + `payStatus` | EXACTLY `{NOT-GIVEN, GIVEN}`. The PENDING→…→SOLD lifecycle belongs to `PharmacySaleOrderDetail` (pharmacy context, OUT OF SCOPE) | Prescription.java:50 |
| Order states `REQUESTED → ACCEPTED → APPROVED → COMPLETED`; PROCEDURE has an APPROVED state | lab/radiology: `PENDING/ACCEPTED/REJECTED/COLLECTED/VERIFIED`. Procedure: `PENDING/ACCEPTED/REJECTED/VERIFIED` — **NO APPROVED, NO COLLECTED**; `approve()` does not exist | LabTest.java:55; Radiology.java:51; Procedure.java:54 |
| `CONS{yyyyMMdd}-{seq}` consultation + `ORD{yyyyMMdd}-{seq}` order numbering; new sequences | **No clinical document numbering anywhere** — IDENTITY id + ULID uid only | (absence; confirmed two independent extractions) |
| `Patient.deceased = true` boolean set on death | `Patient.type = 'DECEASED'` String (inc-03 finding confirmed) | DeceasedNote flow → Patient.type |
| "177 privilege codes"; `CONSULTATION_START`/`PROCEDURE_ORDER_APPROVE`/`PRESCRIPTION_CREATE`; a `ProviderProfile` | clinical endpoints are authentication-only bar 4 real coarse gates; no `ProviderProfile` (inc-01 finding confirmed) | PatientResource.java @PreAuthorize survey |
| A "prior-attempt `PrescribingAlertService`" as legacy | Legacy DOES have advisory alerts but inline (same-medicine-30-day; unfinished-course by parsed-days String) — free-text, never blocking; the "service" was a prior MODERN attempt, not legacy | PatientResource.java:4498-4500 |
| `Envers/@Audited`, device-binding, ICD coding | NONE present (confirmed; consistent with the phantom-features memory) | (absence) |

---

## 4. Aggregates built in inc-05 (16 tables)

`consultations` (EXTEND V19 stub, CR-21 ownership transfer), `non_consultations`,
`consultation_transfers`, `clinical_notes`, `general_examinations`, `patient_vitals`,
`working_diagnoses`, `final_diagnoses`, `lab_tests` (+`lab_test_attachments`),
`radiologies` (+`radiology_attachments`), `procedures`, `prescriptions`
(+`prescription_batches`, `patient_prescription_charts`), `deceased_notes`, `referral_plans`.

**Cross-module discipline (ADR-0008):** clinical → `billing.api` only (settlement seam);
all patient/clinic/clinician/bill/medicine/diagnosis-type/admission refs are loose ULID
uids (no FK) EXCEPT the intra-schema `consultations.patient_id`/`visit_id` which remain
real FKs (the V19 stub already FKs them in the same schema — confirmed schema topology).
`ApplicationModules.verify()` + ArchUnit enforce no reverse edge into billing.

## 5. Settlement seam (the inc-04 deferred item #3, now wired)

- The LOCAL `settled` flag on each gated clinical row (consultation, orders) is set:
  - at **booking/charge time** to `true` for COVERED / NONE / inpatient (these auto-pass);
  - by `SettlementDispatcher.onBillPaid` (billing→encounter, SAME tx, no async, no reverse
    edge) when the cash bill transitions to PAID — the dispatcher ALSO writes the downstream
    clinical row's local flag in that tx.
- `SettlementPolicy.requireSettled(localSettled, paymentType, inpatient, emergency, billUid)`
  is evaluated at `open_consultation` only (per CR-INC05-01 ruling). The clinical module
  NEVER calls back into billing to check settlement.

## 6. Numbering

NONE. Consultations and orders have no document number (drift-corrected). Identity is the
hidden `id BIGINT` + public `uid VARCHAR(26)` ULID, per the global conventions.

---

See [04-synthesis-buildspec.md](04-synthesis-buildspec.md) for the full per-aggregate state
machines (every transition + guard + citation), the endpoint surface, and the C1..C12 build
chunk plan that this increment executes.
