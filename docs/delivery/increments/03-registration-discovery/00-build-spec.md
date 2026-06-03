I now have a complete, verified picture of the codebase. I have everything needed to author the authoritative build spec. Let me synthesize the final deliverable.

# INCREMENT-03 (Registration & Patient) — AUTHORITATIVE BUILD SPEC + RATIFIED-DECISIONS REGISTER

Solution Architect, dated 2026-06-03. Synthesized from 5 file:line-cited legacy extractions + adversarial verification + data/security/HDE/BA designs, reconciled against the live HMSCLEAN2 codebase (billing.api, masterdata::lookup, iam::lookup, V1–V18 migrations, ADR-0005/0007/0008/0009/0014). Legacy is the source of truth; the planning doc `03-registration-patient.md` is the DRIFT source and is OVERRIDDEN on every conflict. Citations: legacy `D:/My_Works/HMS/ZANAHMIS-2-feature/.../com/orbix/api/...`; new-build paths absolute under `D:/My_Works/HMS/HMSCLEAN2/backend`.

---

## §0 — KEY RECONCILIATION FINDINGS (binding, before any code)

1. **`seq_mrno` ALREADY EXISTS** — `V13__masterdata_document_sequences.sql:57-58` (`BIGINT START WITH 1`). V19 MUST NOT `CREATE` it. (Data-architect's pre-flight confirmed; planning-doc DoD that implies creating it is satisfied by reuse.)
2. **The registration-fee bill is NOT built by the registration module.** `billing.api.BillingCommands.recordClinicalCharge(ChargeRequest, TxAuditContext)` already handles `ServiceKind.REGISTRATION` (`serviceUid=null`, `PriceLookup` resolves plan-only/cash row) AND runs the two-step cash-first/insurance-override engine AND the PENDING-invoice accumulator (`BillingCommands.java:20-33`, `ChargeRequest.java:19` "null only for REGISTRATION", `ServiceKind.java:28-29`, `PriceLookup.java:31-37`). Registration calls this ONCE for the reg fee and ONCE for the consultation fee. Registration owns NO bill/invoice table.
3. **Consultation fee source = `ServicePrice(kind=CONSULTATION, serviceUid=Clinic.uid)`**, NOT a `clinic.consultationFee` column. Legacy read `clinic.getConsultationFee()` (PatientServiceImpl.java:460); the new pricing matrix (`ServiceKind.CONSULTATION → serviceUid=Clinic.uid`, `ServiceKind.java:31-34`) is the ratified equivalent (inc-02 CR-04). This is a behaviour-preserving relocation, not a process change — the amount is identical for the same clinic.
4. **Registration fee source = `ServicePrice(kind=REGISTRATION, serviceUid=null)`** via `recordClinicalCharge`, NOT `CompanyProfile.registrationFee`. **This is a RATIFIED DEVIATION (CR-12, see §9)** — legacy reads the `CompanyProfile.registrationFee` singleton (PatientServiceImpl.java:230-233). The new build already models registration pricing in the `service_prices` matrix as plan-only-keyed (`ServiceKind.java:28`, inc-02 CR-18). The amount is identical when `service_prices(kind=REGISTRATION, plan_uid=null)` is seeded to the legacy `CompanyProfile.registrationFee` value. **The amount value is parity; the source-of-record is a ratified modernization.**
5. **Module boundary (ADR-0008 §6 allowed graph):** `registration → shared + billing::api + masterdata::lookup + iam::lookup`. Registration may NOT depend on `clinical` (inc-05, does not exist yet). **The PENDING `Consultation` is therefore created INSIDE the `registration` module in inc-03** (the legacy `do_consultation` is part of `PatientResource`/`PatientServiceImpl` — the same god-service that owns Patient). See §3 ratification ADR-0008-R1.
6. **Phantom features CONFIRMED ABSENT** (repo-wide zero matches, all 5 extractions + adversarial): no `@Audited`/Envers active, no device-fingerprint/device-binding. Do NOT implement either. Audit is net-new per ADR-0007.

---

## §1 — ENTITIES / TABLES / DTOs / MAPPERS

Package root `com.otapp.hmis.registration`. Layout per ADR-0014 §2: `api/` (controllers), `application/` (services, DTOs as records, MapStruct mappers, the `PatientRegistrationProcess` orchestrator), `domain/` (entities, repositories, enums), `infrastructure/` (MR-number generator). All entities extend the dual-id `@MappedSuperclass` base (ADR-0014 §1: hidden `id`, ULID `uid`, `createdAt/updatedAt/createdBy/updatedBy/version`).

### 1.1 Entities (three, distinct — do NOT collapse)

| Entity | Table | Cardinality to Patient | Legacy cite | Notes |
|---|---|---|---|---|
| `Patient` | `patients` | master | `Patient.java:36-107` | 25 legacy fields; next-of-kin = 3 flat columns (ONE kin, no child entity); NO `deceased` boolean (`type='DECEASED'`); NO `updated*` in legacy but base entity carries them (inert for parity, used by net-new audit). |
| `Registration` | `registrations` | **OneToOne** | `Registration.java:34-62`; created `PatientServiceImpl.java:293-302` | Thin Patient↔reg-fee-bill join; `status='ACTIVE'` only; carries loose `patient_bill_uid` (bill is in billing module). NO sequence/encounter fields. |
| `Visit` | `visits` | **ManyToOne** | `Visit.java:33-61`; created `PatientServiceImpl.java:409-419,501-512` | Per-encounter log; `sequence ∈ {FIRST, SUBSEQUENT, SUBSEQUENT-FOR-ADMISSION}`; `status` only ever `PENDING`; `type` copied from patient. |

`Consultation` (PENDING stub) — see §3; `PatientBill`/`PatientInvoice`/`PatientInvoiceDetail` are owned by the **billing** module (not registration). `DeceasedNote` is DEFERRED to the inpatient/discharge increment (it is created by `save_deceased_note`, out of inc-03 scope).

### 1.2 DDL — `V19__registration_patient.sql` (ratified from data-architect sketch)

The data-architect's V19 sketch is **RATIFIED with these binding amendments**:
- `gender` ships as **`VARCHAR(20) NOT NULL` with NO CHECK constraint** (legacy is free-text `@NotBlank`, `Patient.java:61-62`; enumerated values unknown — see CR-17). Bean Validation may enforce an app-level set; the DB does not, to avoid blocking future data and honour exact-process (legacy accepts any non-blank string).
- `ck_patients_type CHECK (type IN ('OUTPATIENT','OUTSIDER','INPATIENT','DECEASED'))` — RATIFIED (4-value vocabulary; HDE BLOCKER if reduced).
- `ck_patients_payment_type CHECK (payment_type IN ('CASH','INSURANCE'))` — RATIFIED (CR-10: DEBIT/CREDIT/MOBILE are invented).
- `ck_patients_insurance_consistency` — RATIFIED as written (INSURANCE ⇒ plan+membership non-empty; CASH ⇒ plan null). Note the legacy insurance-but-uncovered fall-through (R1) still satisfies this CHECK because the patient retains plan+membership even when no claim is built.
- `ck_visits_status CHECK (status IN ('PENDING'))` and `ck_registrations_status CHECK (status IN ('ACTIVE'))` — RATIFIED, with the standing note (ADR-0008-R2 below) that later increments widen these via additive migrations.
- `no VARCHAR(40)` nullable-until-assigned, `uq_patients_no`, `uq_patients_search_key`, `search_key TEXT NOT NULL` — RATIFIED.
- GIN trigram index on `search_key` + per-field indexes — RATIFIED; `CREATE EXTENSION IF NOT EXISTS pg_trgm;` at top of V19.
- Cross-module loose uids (`insurance_plan_uid`, `registrations.patient_bill_uid`) NO FK — RATIFIED per ADR-0008 §1.

### 1.3 DTOs (Java records, `application/dto/`) and MapStruct mappers (package-private, `componentModel="spring"`, ADR-0014 §3)

- `RegisterPatientRequest` — demographics + `paymentType` + `insurancePlanUid` (nullable) + `membershipNo` (nullable) + 3 kin fields. NO `no` field (server-assigned; any client `no` ignored — AC1.2). NO `id`.
- `UpdatePatientRequest` — demographics + kin (NOT payment/type/no — those have dedicated endpoints).
- `PatientDto` — response: `uid`, `no` (MRN), names, `dateOfBirth`, `gender`, `type`, `paymentType`, `membershipNo`, contact, kin, `active`, `insurancePlanUid`, `lastVisitAt`. PHI fields present but the **audit serializer** redacts them in `audit_log` (not in the API response — the API legitimately returns them to authenticated callers). `searchKey` MUST be `@PiiField` (derived PHI); `no` MUST NOT be `@PiiField` (audit locator) — per security design.
- `ChangePaymentTypeRequest`, `ChangePatientTypeRequest`, `SendToDoctorRequest` (`clinicUid`, `clinicianUserUid`, `followUp` boolean).
- Mappers: `PatientMapper`, `RegistrationMapper`, `VisitMapper` — pure, no repository injection (ADR-0014 §3). Money never appears on Patient (no money fields); bill amounts surface via billing read APIs.

---

## §2 — REGISTRATION FLOW + STATE MACHINE + mrNo NUMBERING

### 2.1 mrNo numbering — RATIFIED FORMAT (CR-02 resolved)

**Format: `MRNO/{year}/{seq}`** where:
- `{year}` = `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).getYear()` (EAT-pinned). **RATIFIED** as behaviour-preserving: legacy used bare `Year.now()` on a server whose tz is EAT (PatientServiceImpl.java:250); pinning EAT makes the year deterministic and matches ADR-0009 §7 document-date policy. This is the ratified clean-break from the implicit server-tz dependency.
- `{seq}` = `nextval('seq_mrno')` (the existing V13 sequence). **RATIFIED DECOUPLING (CR-02):** legacy embedded the IDENTITY PK (`patient.getId()`); the new build uses the dedicated sequence per ADR-0009 §5 (`seq_mrno`, prefix table line 137). **This is a ratified deviation** — the observable number no longer equals the surrogate id. Justification: (a) ADR-0014 §1 makes `id` a hidden, never-exposed key, so coupling the MRN to it is architecturally illegal in the new model; (b) the legacy author flagged the format as a placeholder ("change this to conventional no", PatientServiceImpl.java:248); (c) `seq_mrno` already exists and is the ratified mechanism. The MRN string format is byte-identical (`MRNO/2026/N`); only the numeric value's provenance differs. NO zero-padding (parity). NO per-year reset (parity — sequence is global; the planning-doc "gap-free per year" assertion is DROPPED as invented, CR-02).
- **Concurrency:** `nextval` is atomic — this is the ratified fix for the legacy save-then-MAX pattern. No double-save (ADR-0009 §5 pattern (c)): obtain `seq`, then build `no` before/at first insert. Because `no` is nullable-until-assigned the entity may be inserted then updated with `no` in the same tx, but the `seq` is NOT derived from the id, so a single flush is sufficient.
- 20-thread concurrency test (DoD) MUST produce 20 distinct MRNs.

### 2.2 searchKey generation — RATIFIED VERBATIM (CR-09)

Reproduce legacy `createSearchKey` (PatientServiceImpl.java:739-744) then `Sanitizer.sanitizeString` (Sanitizer.java:11-17) EXACTLY:
```
key = no + " " + firstName + " " + middleName + " " + lastName + " " + phoneNo
key = key.trim().replaceAll("\\s+", " ")
key = key.replaceAll("[+^]*#$%&", "")      // strips only the literal sequence #$%& (latent bug — reproduce)
// then Sanitizer: replace '+' -> ' ', trim, collapse ws, same [+^]*#$%& strip
```
**Case PRESERVED (NOT lowercased)** — the planning-doc lowercasing + `firstName+lastName`-only composition are DRIFT (CR-09). The full 5-field composition incl. `no` and `phoneNo` is RATIFIED. The `[+^]*#$%&` regex is reproduced verbatim as a ratified latent bug (byte-identical keys required for any future reconciliation). Step A placeholder (`Math.random()` before `no` is known) is replaced by: compute `no` from `seq_mrno` first, then compute the real `search_key` before insert — no random placeholder needed in the new single-flush model.

### 2.3 Registration flow (orchestrated by `PatientRegistrationProcess` application service, ADR-0008 §2)

One `@Transactional` boundary (ADR-0014 §5). One `TxAuditContext` built once (ADR-0008 §3). Steps:
1. Resolve `businessDayUid` via `BusinessDayService.currentUid()` (ADR-0009 §7; throws `NoDayOpenException`/422 if no open day).
2. Validate request (Bean Validation §5): INSURANCE ⇒ `insurancePlanUid` non-null AND `membershipNo` non-blank (`MissingInformationException`→422, legacy PatientResource.java:299-301). CASH ⇒ plan forced null.
3. Build `no` (`MRNO/{EAT-year}/{nextval seq_mrno}`) and `search_key` (§2.2).
4. Persist `Patient` (`active=true`, `type` defaults `OUTPATIENT` if null per PatientResource.java:410-414).
5. Call `billing.recordClinicalCharge(ChargeRequest{patientUid, planUid, membershipNo, kind=REGISTRATION, serviceUid=null, qty=1, paymentType, inpatient=false}, ctx)` → returns `ChargeResult{billUid, status, ...}`. This single call reproduces legacy reg-bill creation + the insurance override + the PENDING-invoice claim (PatientServiceImpl.java:267-405) — all of which now live in billing's ratified engine. Reg-bill `bill_item="Registration"`, `description="Registration Fee"`.
6. Persist `Registration{patientId, patientBillUid=result.billUid(), status="ACTIVE"}` (OneToOne).
7. Persist first `Visit{patientId, sequence="FIRST", status="PENDING", type=patient.type}`.
8. After-commit: publish `PatientRegistered` event (reporting/read-model ONLY, ADR-0008 §5 — non-essential).

**Patient state machine (RATIFIED, exact):**
- `type`: `OUTPATIENT ⇆ OUTSIDER` via `change_type` (guards in §state below); `INPATIENT` set by admission flow (inc-06, blocked here: "This operation is not allowed for inpatients", PatientResource.java:499-500); `DECEASED` set by deceased-note flow (deferred). `change_type` ONLY toggles OUTPATIENT↔OUTSIDER.
- `paymentType`: `CASH ⇆ INSURANCE` via `change_payment_type`; non-INSURANCE collapses to CASH (PatientResource.java:368-373).

---

## §3 — SEND-TO-DOCTOR, CONSULTATION CREATION, CROSS-MODULE CONTRACT (ADR-0008-R1)

**`POST /api/v1/patients/uid/{uid}/send-to-doctor` ≡ legacy `do_consultation`** (PatientResource.java:508; there is NO `send_to_doctor` endpoint in legacy — CR — naming DRIFT). Orchestrated by `PatientRegistrationProcess.sendToDoctor(...)`, one tx, one `TxAuditContext`.

### 3.1 RATIFIED Modulith boundary decision (ADR-0008-R1)

**The PENDING `Consultation` is created inside the `registration` module in inc-03.** Rationale: full clinical consultation (open/free/transfer, clinical notes, diagnosis) is inc-05 (`clinical` module, does not yet exist). The legacy `do_consultation` lives in the same `PatientResource`/`PatientServiceImpl` that owns Patient (PatientServiceImpl.java:425-679). Registration's allowed dependencies (ADR-0008 §6) are `shared + billing::api + masterdata::lookup + iam::lookup` — it may NOT depend on a `clinical.api` that does not exist. Therefore inc-03 owns a **minimal `Consultation` aggregate** sufficient for booking (the PENDING stub), and inc-05 will either (a) take ownership of the full Consultation aggregate via a documented migration, or (b) consume it via `registration.api`. **This is RATIFIED as a temporary home; ADR-0008-R1 records that the Consultation aggregate's permanent owner is the `clinical` module (inc-05), and the inc-05 spec MUST include the ownership-transfer plan.** Until then, `registration` exposes `registration.api` read access to the PENDING consultation for inc-05.

`Consultation` (inc-03 minimal stub): `uid`, `patientUid`(intra: patientId FK), `clinicUid`, `clinicianUserUid`, `visitId` FK, `patientBillUid` (consultation fee bill), `paymentType`, `followUp` boolean, `status` (PENDING only in inc-03). Table `consultations` in V19 (or a sibling V20 — see §8). Legacy 1:1 non-null bill (Consultation.java:70-73) → `patient_bill_uid NOT NULL`.

### 3.2 send-to-doctor flow (RATIFIED, exact)

Pre-guards (order per PatientResource.java:516-537 + PatientServiceImpl.java:427-455):
1. `followUp` is boolean (legacy `0/1`; modern bool — behaviour-preserving). 
2. Block if patient has PENDING/IN-PROCESS admission → 422 (legacy "active admission"). **Admission is inc-06; in inc-03 there are no admissions, so this guard is a no-op stub that always passes — document as DEFERRED-ENFORCEMENT (CR-19).**
3. `patient.type == "OUTPATIENT"` else 422 "Please change patient type to OUTPATIENT to continue with operation".
4. Clinician active + **affiliation check**: resolve clinician via `iam::lookup` and verify the clinician is affiliated to the target clinic via `ClinicianAffiliationService.clinicUidsOf(clinicianUserUid).contains(clinicUid)` (ClinicianAffiliationService.java:43-50). Legacy required active clinician (PatientServiceImpl.java:427); affiliation gating is the ratified equivalent of the clinic+clinician pairing.
5. No existing PENDING/TRANSFERED/IN-PROCESS consultation for the patient → 422.

Then atomically:
6. Call `billing.recordClinicalCharge(ChargeRequest{patientUid, planUid, membershipNo, kind=CONSULTATION, serviceUid=clinicUid, qty=1, paymentType, inpatient=false}, ctx)`. For INSURANCE-not-covered-at-clinic, the billing engine's per-service fallback applies (consultation is the hard-fail kind per PriceLookup.java/`ServicePriceNotFoundException`→422; legacy "Plan not available for this clinic", PatientServiceImpl.java:599-601 — this is RATIFIED PARITY routed through the billing engine).
   - **Follow-up = `NONE` bill status:** legacy sets consultation bill `status="NONE"` and skips charge when `followUp` (PatientServiceImpl.java:467-469). **RATIFIED: HDE BLOCKER — the `NONE` follow-up waiver MUST be preserved.** Implementation: when `followUp==true`, registration MUST NOT call `recordClinicalCharge` for a payable consultation; instead it records a `NONE`-status zero-charge consultation bill. **OPEN ITEM (CR-20):** `recordClinicalCharge` does not currently expose a `followUp`/`NONE` path. Routed to engagement-lead → backend-engineer: either extend `ChargeRequest` with a `followUp` flag (billing emits `NONE`) OR registration records the `NONE` bill directly. Architect recommendation: extend billing (the `NONE` status already exists in `ck_patient_bills_status`, V15:71) so all charge creation stays in one place.
7. Persist `Consultation{status="PENDING", patientBillUid=result.billUid(), paymentType=patient.paymentType, followUp}`.
8. Persist `Visit{sequence="SUBSEQUENT", type=patient.type, status="PENDING"}` — UNCONDITIONALLY (no same-day dedup; the legacy "reuse if not today" comment is dead code, PatientServiceImpl.java:499). RATIFIED parity (AC3.6); multiple same-day sends → multiple SUBSEQUENT visits.
9. After-commit: publish `ConsultationBooked` event (reporting read-model only).
10. Return `201 + Location` with the created `ConsultationDto` (**CR-06 FIX**: legacy returned null; modern returns the resource — behaviour-preserving on 201).

**Atomicity (DoD):** Consultation + consultation-fee PatientBill (+ PatientInvoiceDetail for insurance) all in one tx via the synchronous `recordClinicalCharge` (ADR-0008 §4: MUST-STAY-IN-TX, propagation REQUIRED, `BillingCommands.java:7-13`). Fault-injection rollback test asserts zero rows in `consultations`, `patient_bills`, `patient_invoice_details`.

### 3.3 Cross-module contract (binding)

`registration` → `billing.api.BillingCommands.recordClinicalCharge(ChargeRequest, TxAuditContext)` — in-process, caller's tx, no `@Async`, no `REQUIRES_NEW`, not `@PreAuthorize`-gated (authz at registration's REST edge). `registration` → `masterdata.lookup.PriceLookup` is NOT called directly by registration (billing calls it internally); registration only needs `masterdata::lookup` for clinic existence/validation if required, and `iam::lookup` for clinician affiliation. Registration's `package-info.java` `@ApplicationModule(allowedDependencies = {"shared","billing::api","masterdata::lookup","iam::lookup"})`.

---

## §4 — THE CASH GATE — RATIFIED RECONCILIATION WITH inc-04 SettlementPolicy

**RATIFIED: registration-fee-unpaid blocking send-to-doctor is REJECTED as parity (it is NET-NEW + a patient-safety hazard). The planning-doc DoD items 4, 38, 58-59, 92 are OVERRIDDEN (CR-01).**

Verified facts (Extraction 3 §b + Adversarial Claim 2 + HDE §6): legacy has NO registration-fee gate at send-to-doctor; the pay-before-service gate is on the **consultation `PatientBill`** at **doctor-open time** (`open_consultation` → "Could not open. Payment not verified.", PatientResource.java:884-897), with a soft UI queue filter (PatientResource.java:822-826).

**Ratified scope:**
- **In inc-03, send-to-doctor is UNGATED on payment** (queueing must never be blocked — HDE BLOCKER-CLINICAL-2; mirrored in `SettlementPolicy` javadoc "blanket gate is a patient-safety hazard", SettlementPolicy.java:9-17).
- **The pay-before-service gate is enforced at doctor-OPEN (inc-05), on the consultation bill**, via `SettlementPolicy.requireSettled(settled, paymentType, inpatient, emergency, billUid)` (SettlementPolicy.java:60-65) evaluated against the consultation module's LOCAL `settled` flag (billing→encounter propagation, no reverse edge, ADR-0008 §6). This is PARITY (consultation-bill gate at open) aligned to the inc-04 ratified scoped policy: CASH outpatient/outsider REQUIRES prepayment; INSURANCE/inpatient/emergency bypass (SettlementPolicy.java:36-47).
- **The registration fee is settled at the cashier (inc-04)** and never gates clinical routing. Its UNPAID/VERIFIED/COVERED status (per `recordClinicalCharge` result) is a billing concern.

So: **registration-fee gate = NET-NEW + REJECTED (CR-01); consultation-bill gate at doctor-open = PARITY, owned by inc-05.** The doctor's worklist queue-filter (show only PAID/COVERED, +NONE for follow-up) is PARITY, also inc-05.

---

## §5 — RBAC, PHI/AUDIT REDACTION, BEAN VALIDATION

### 5.1 RBAC — LIVE codes only (V2__seed_iam.sql:55-60: `PATIENT-ALL/CREATE/UPDATE/A/C/U`)

Planning-doc codes `PATIENT-EDIT/VIEW/REGISTRATION-ALL/CONSULTATION-BOOK` are REJECTED (invented — CR-04/DRIFT). `@PreAuthorize` parity (Extraction 5 §2, Adversarial Claim 3):

| Endpoint | Authority (RATIFIED) | Legacy cite |
|---|---|---|
| `POST /patients` (register) | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')` | PatientResource.java:288-289 |
| `PUT /patients/uid/{uid}` (update) | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` | :378-379 |
| `PATCH /patients/uid/{uid}/patient-type` | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` | :398-399 |
| `POST /patients/uid/{uid}/send-to-doctor` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` | :508-509 |
| `PATCH /patients/uid/{uid}/payment-type` | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` | **CR-03 FIX** (legacy ungated, :311) |
| Reads (`GET /patients`, `/uid/{uid}`, `/last-visit`) | **authenticated-only, NO privilege** | :261,267,274,281,788 (no gate) |

`PATIENT-A/C/U` are seeded but appear only in commented legacy gates in the patient surface — NOT applied to inc-03 endpoints (they gate other resources). Reads stay authenticated-only (NO `PATIENT-VIEW` — CR-04); the bulk-PHI-enumeration risk (R4) is logged but exact-process is preserved.

### 5.2 PHI/audit redaction (ADR-0007 + security design)

Registration is audit-in-scope (`Patient, Registration, Visit`; `chain_checksum` nullable, ADR-0007:182). Net-new append-only `audit_log` (V1) via `@EntityListeners`; NO Envers (CR-15). `@PiiField` REDACT list (security taxonomy): `firstName, middleName, lastName, dateOfBirth, phoneNo, email, address, nationalId, passportNo, membershipNo, kinFullName, kinPhoneNo, searchKey` (+ `kinRelationship` default-redact). **`no`/MRN explicitly NOT redacted** (audit locator). Security findings R-1 (PHI in GET query strings) and R-4 (ungated bulk search) are logged as recommended net-new controls, NOT inc-03 process changes.

### 5.3 Bean Validation (ADR-0014 §4, messages.properties keys)

- `firstName, lastName` `@NotBlank`; `dateOfBirth` `@NotNull`; `gender` `@NotBlank`; `paymentType` `@NotNull` enum; `type` defaults OUTPATIENT.
- **INSURANCE cross-field rule**: a class-level `@PaymentTypeConsistent` validator — INSURANCE ⇒ `insurancePlanUid != null && membershipNo` non-blank (else `MissingInformationException`/422 "Membership number required"); CASH ⇒ plan ignored/nulled. Mirrors DB `ck_patients_insurance_consistency`.
- All errors via `GlobalExceptionHandler` → RFC 7807 `ProblemDetail` with stable `ErrorCode` (ADR-0005 §5, ADR-0014 §6).

---

## §6 — SEARCH / LAST-VISIT (RATIFIED, with approved improvements)

- `GET /patients?query=X` (paginated — **CR-07 APPROVED improvement**, behaviour-preserving): OR-match `LIKE %X%` across `no, firstName, middleName, lastName, phoneNo` (PatientRepository.java:41-42) AND `membershipNo` (the card variant, PatientRepository.java:50-51 — **REG-1 closed**: single unified search includes membership). Backed by GIN trigram on `search_key` + per-field indexes (§1.2).
- `GET /patients/uid/{uid}/last-visit` and `lastVisitAt` on `PatientDto`: **CR-08 FIX** — explicit `ORDER BY created_at DESC LIMIT 1` (legacy relied on unordered list last-element, PatientResource.java:795-799; fragile). Backed by `idx_visits_patient_created_at (patient_id, created_at DESC)`.
- Exact searchKey lookup (`get_by_search_key`) preserved internally; typeahead may use the all-keys feed but server-side pagination is the ratified primary path. NO DOB/nationalId/passport search (none in legacy — do not add).

---

## §7 — FLYWAY / MODULITH / ENFORCEMENT

- **Flyway:** next migration **`V19__registration_patient.sql`** (`patients`, `registrations`, `visits`, `consultations`; `pg_trgm` extension; indexes/CHECKs per §1.2). Reuse existing `seq_mrno` (V13) — do NOT recreate. If `consultations` is split for clarity, use `V20__registration_consultation_stub.sql`. `ddl-auto=validate` MUST pass against the entities. NO data migration (greenfield — ADR-0009/0011).
- **Modulith:** registration `package-info.java` `@ApplicationModule(allowedDependencies = {"shared","billing::api","masterdata::lookup","iam::lookup"})`. `ApplicationModules.verify()` MUST pass. Registration exposes a `registration.api` named interface for inc-05 (PatientRef-by-uid read + PENDING-consultation read).
- **ArchUnit (ADR-0005 §, ADR-0014 §1):** no `@Entity` crossing modules; no `{id}` in URLs / no `@PathVariable("id")`; no DTO `Long id`; no verb path segments; no `*.internal.*` class calling `LocalDateTime.now()`/`dayService` (must use `TxAuditContext`, ADR-0008 §3/§7); MapStruct mappers pure.
- **Audit assertion fixtures (ADR-0007 §Testing):** every Patient/Registration/Visit mutation emits exactly one `audit_log` row with correct `action/actor_uid/entity_uid`, non-null `checksum`, PHI redacted.

---

## §8 — CHUNKED BUILD PLAN (each chunk independently `mvn verify`-able + committable)

- **C1 — Schema + domain + repositories.** V19 migration; `Patient/Registration/Visit/Consultation` entities + repositories; enums; `ddl-validate` green; ArchUnit green. IT: persist/load round-trip. Commit.
- **C2 — MR-number + searchKey infrastructure.** `MrNumberGenerator` (EAT-year + `seq_mrno`, §2.1); `SearchKeyBuilder` (verbatim regex, §2.2). Unit tests (format, no-pad, case-preserved, verbatim regex) + 20-thread concurrency IT. Commit.
- **C3 — Register patient (CASH + INSURANCE).** `PatientRegistrationProcess.register`, DTOs, mappers, `POST /patients`, Bean Validation, RBAC, reg-fee via `recordClinicalCharge(REGISTRATION)`, Registration+FIRST Visit. Audit-log assertion. Parity IT: reg-bill amount (round-2dp), MRN format, insurance edge-cases R1/R2. Commit.
- **C4 — Update + type-flip + payment-flip.** `PUT /patients/uid/{uid}`, `PATCH .../patient-type` (OUTPATIENT↔OUTSIDER guards, PatientResource.java:421-495), `PATCH .../payment-type` (open-work guard + CR-03 gate). ITs for each guard. Commit.
- **C5 — Search + last-visit.** Paginated `GET /patients?query=` (incl. membershipNo), `GET /patients/uid/{uid}`, `last-visit` (ordered). ITs. Commit.
- **C6 — Send-to-doctor (Consultation stub).** `sendToDoctor` orchestration, consultation-fee via `recordClinicalCharge(CONSULTATION)` + follow-up `NONE` (pending CR-20 resolution), affiliation gate via `iam::lookup`, atomicity rollback IT, `ConsultationBooked` event. `registration.api` exposure for inc-05. Commit.
- **C7 — Cross-cutting hardening + OpenAPI.** Finalize `ProblemDetail`/`ErrorCode` mapping, `messages.properties`, OpenAPI 3 contract + CI drift-gate, golden-master parity suite, full `ApplicationModules.verify()` + ArchUnit + Testcontainers green. Commit.

(Angular screens are frontend-engineer's parallel track per planning-doc §82; not blocking backend chunks.)

---

## §9 — CONSOLIDATED DEVIATION / SIGN-OFF REGISTER (CR-xx)

Tags: PARITY (reproduce exactly) / NET-NEW (new behaviour, needs sign-off) / FIX (corrects a legacy defect, behaviour-preserving) / DRIFT-CORRECTION (planning-doc error overridden). Approval authority = `engagement-lead`.

| CR | Title | Tag | Ratification |
|---|---|---|---|
| **CR-01** | Reg-fee gate blocking send-to-doctor | NET-NEW → **REJECTED** | Not parity; patient-safety hazard (HDE BLOCKER). Gate belongs on consultation bill at doctor-open (inc-05) via `SettlementPolicy`. Planning-doc DoD 4/38/58-59/92 OVERRIDDEN. Send-to-doctor UNGATED in inc-03. |
| **CR-02** | mrNo `seq_mrno` vs IDENTITY-PK; EAT year | NET-NEW (decoupling) + DRIFT-CORRECTION | **RATIFIED:** `MRNO/{EAT-year}/{nextval seq_mrno}`. Decoupling from PK ratified (ADR-0014 §1 forbids exposing id; ADR-0009 §5 mechanism; legacy author flagged placeholder). NO per-year reset, NO zero-pad. "Gap-free per year" + "MAX(id)+1 race" claims DROPPED (invented). Engagement-lead to confirm decoupling is acceptable vs byte-value parity. |
| **CR-03** | Secure `change_payment_type` | FIX | **RATIFIED:** apply `PATIENT-ALL,PATIENT-UPDATE` (legacy gate commented, PatientResource.java:311). Security-architect notified; adds a missing guard, no privilege removal. |
| **CR-04** | Patient read gate / `PATIENT-VIEW` | DRIFT-CORRECTION | **RATIFIED:** reads stay authenticated-only (PARITY). `PATIENT-VIEW` is invented — do NOT create. Bulk-PHI-enumeration (R4) logged as net-new control candidate. |
| **CR-05** | `deceased` boolean + `PATCH .../deceased` + `PATIENT_DECEASED` guard | DRIFT-CORRECTION + NET-NEW | **RATIFIED:** reproduce `type='DECEASED'` via deceased-note flow (deferred to inpatient/discharge increment); the implicit freeze (OUTPATIENT-only check) is PARITY. No boolean, no PATCH, no explicit send-guard in inc-03. Explicit guard, if wanted, is NET-NEW for engagement-lead. |
| **CR-06** | send-to-doctor returns null | FIX | **RATIFIED:** return created `ConsultationDto` on 201 (legacy returned null, PatientServiceImpl.java:678). Behaviour-preserving. |
| **CR-07** | Pagination on search | PARITY-RISK → **APPROVED** | **RATIFIED:** server-side pagination (behaviour-preserving result set; changes response envelope — coordinate OpenAPI/frontend). |
| **CR-08** | last-visit ordering | FIX | **RATIFIED:** explicit `ORDER BY created_at DESC LIMIT 1` + guard missing uid (legacy unordered/unsafe, PatientResource.java:795-799). |
| **CR-09** | searchKey composition + case | DRIFT-CORRECTION | **RATIFIED:** verbatim 5-field composition (`no+first+middle+last+phone`), case-preserved, `[+^]*#$%&` regex reproduced as latent bug. Planning-doc `firstName+lastName`+lowercase OVERRIDDEN. |
| **CR-10** | paymentType enum set | DRIFT-CORRECTION | **RATIFIED:** `CASH, INSURANCE` only. DEBIT/CREDIT/MOBILE invented (rejected at PatientServiceImpl.java:581). |
| **CR-11** | patientType enum set | DRIFT-CORRECTION | **RATIFIED:** 4 values `OUTPATIENT,OUTSIDER,INPATIENT,DECEASED` (HDE BLOCKER if reduced). `change_type` toggles only OUTPATIENT↔OUTSIDER. |
| **CR-12** | Registration-fee source | NET-NEW (source) + amount PARITY | **RATIFIED:** amount sourced from `service_prices(kind=REGISTRATION, plan_uid=null)` via `recordClinicalCharge` (NOT `CompanyProfile.registrationFee`). Already the ratified inc-02 model (ServiceKind.REGISTRATION, CR-18). **Seed the REGISTRATION cash price to the legacy `CompanyProfile.registrationFee` value** so the amount is parity. Legacy multi-row CompanyProfile last-wins nondeterminism (PatientServiceImpl.java:230-233) is resolved by the single-row price matrix (FIX). |
| **CR-13** | `billType` field | DRIFT-CORRECTION | **RATIFIED:** no `billType`; categorization is `bill_item="Registration"` + `kind=REGISTRATION` (V15:31,35). Planning-doc `billType=REGISTRATION` OVERRIDDEN. |
| **CR-14** | NextOfKin "up to three embedded" | DRIFT-CORRECTION | **RATIFIED:** exactly ONE kin, 3 flat columns (`Patient.java:83-85`). Planning-doc kin collection OVERRIDDEN (HDE: clinical gap documented, not changed). |
| **CR-15** | Net-new audit trail (Envers/audit_log) | NET-NEW (pre-approved) | **RATIFIED:** append-only `audit_log` (ADR-0007), NOT Envers (phantom). `@PiiField` redaction per §5.2. Not a legacy-parity item. |
| **CR-16** | Device-fingerprint/binding | **DROP** | **RATIFIED:** phantom feature, zero matches. Do NOT implement. |
| **CR-17** | gender DB CHECK | NET-NEW (rejected for DB) | **RATIFIED:** `gender VARCHAR(20)` NO DB CHECK (legacy free-text). App-level Bean Validation may restrict; DB does not (exact-process). Business-analyst to confirm the value set for app-level validation. |
| **CR-18** | Visit/Registration status CHECK widening (ADR-0008-R2) | PARITY + forward-note | **RATIFIED:** `visits.status='PENDING'`, `registrations.status='ACTIVE'` are the only inc-03 values. Later increments (admission/discharge) widen these via ADDITIVE migrations — no silent CHECK relaxation. |
| **CR-19** | Active-admission guard on send-to-doctor | PARITY (deferred enforcement) | **RATIFIED:** guard is a no-op stub in inc-03 (no admissions exist until inc-06); enforce when admission lands. Document so it is not forgotten. |
| **CR-20** | Follow-up `NONE` consultation bill path | PARITY (HDE BLOCKER) — **OPEN** | **RATIFIED requirement; mechanism OPEN.** `NONE` waiver MUST be preserved (PatientServiceImpl.java:467-469). `recordClinicalCharge` has no `followUp` path today. Architect recommendation: extend `ChargeRequest` with a `followUp` flag so billing emits `status=NONE` (already in `ck_patient_bills_status`, V15:71). Routed to engagement-lead → backend-engineer. |
| **CR-21** | Consultation aggregate ownership (ADR-0008-R1) | NET-NEW (boundary) | **RATIFIED:** PENDING `Consultation` created in `registration` for inc-03 (clinical module absent). Permanent owner = `clinical` (inc-05); inc-05 spec MUST carry the ownership-transfer plan. `registration.api` exposes PENDING-consultation read. |
| **CR-22** | send-to-doctor naming | DRIFT-CORRECTION | **RATIFIED:** `POST /patients/uid/{uid}/send-to-doctor` ≡ legacy `do_consultation` (no `send_to_doctor` endpoint in legacy). 1:1 map. |

**Planning-doc DRIFTs corrected (overridden):** MR seq/EAT/gap-free (CR-02), `md_service_price` reg-fee implication corrected to seeded REGISTRATION price (CR-12), searchKey composition+lowercase (CR-09), `PATIENT-EDIT/VIEW/REGISTRATION-ALL/CONSULTATION-BOOK` (CR-04), reg-fee send-gate (CR-01), `deceased` boolean/PATCH/guard (CR-05), `billType` (CR-13), kin-collection (CR-14), payment/type enum sets (CR-10/CR-11), `send-to-doctor` as a novel endpoint (CR-22).

---

## §10 — EXPLICITLY DEFERRED OUT OF INC-03

- **Full clinical consultation** (open/free/transfer/cancel, clinical notes, examination, working/final diagnosis, the consultation-bill PAID/COVERED open-gate via `SettlementPolicy`, doctor worklist queue-filter) = **inc-05** (`clinical` module). Inc-03 ships only the PENDING-consultation booking stub (CR-21).
- **Cashier settlement of the registration & consultation bills** (flipping `settled=true`) = **inc-04** (already partially present: `SettlementDispatcher`, `patient_bills.settled` V18).
- **Admission/inpatient** (the active-admission guard's real enforcement, `INPATIENT` type, ward charges) = **inc-06**.
- **Deceased-note workflow** (`save_deceased_note`, `DeceasedNote` entity, deceased-summary bill-clearance gate, `type='DECEASED'` transition, ICD-10 cause-of-death — pending legacy-analyst `DeceasedNote` field extraction for MOH validation) = **inpatient/discharge increment**.
- **Insurance claim lifecycle beyond PENDING/APPROVED** (claim submission, credit notes on cancel) = billing/claims increments.
- **Read-side reporting projections** consuming `PatientRegistered`/`ConsultationBooked` events = reporting increment.

---

## §11 — OPEN ITEMS ROUTED TO ENGAGEMENT-LEAD (gating before C6 / final close)

1. **CR-02** — confirm MRN decoupling (`seq_mrno`) is acceptable vs byte-value PK parity; confirm legacy prod server tz = EAT (makes EAT-year pin behaviour-preserving).
2. **CR-12** — confirm the REGISTRATION cash price seed equals the legacy `CompanyProfile.registrationFee` value (business-analyst to supply the figure for the parity golden-master).
3. **CR-20** — ratify the follow-up `NONE` mechanism (extend `ChargeRequest` vs registration-records-NONE-directly). Architect recommends extending billing.
4. **CR-17** — business-analyst to confirm the gender value set for app-level Bean Validation.
5. **R1/R2 (HDE/BA)** — confirm insurance-but-uncovered (silent cash UNPAID) and insurance-but-regFee==0 (VERIFIED, no claim) fall-throughs are intended policy (reproduce as parity meanwhile).
6. **CR-21** — schedule the Consultation ownership-transfer plan into the inc-05 spec.

**Relevant files (absolute):**
- Build targets: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V19__registration_patient.sql` (to author), `...\src\main\java\com\otapp\hmis\registration\` (domain/application/api/infrastructure to author), `...\registration\package-info.java` (update allowedDependencies).
- Binding contracts (read, do not modify): `...\billing\api\{BillingCommands,ChargeRequest,ChargeResult,SettlementPolicy,PayBeforeServiceException}.java`; `...\masterdata\lookup\{PriceLookup,ServiceKind,ServicePriceResult}.java`; `...\iam\lookup\{ClinicianAffiliationService,IamLookupService}.java`; `...\db\migration\{V13(seq_mrno:57),V15(billing core),V18(settled flag)}.sql`.
- ADRs: `D:\My_Works\HMS\HMSCLEAN2\docs\adr\{0005,0007,0008,0009,0014}.md`.
- Live privilege seed: `...\db\migration\V2__seed_iam.sql:55-60`.
- DRIFT source (overridden): `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\03-registration-patient.md`.
- Legacy SoT: `D:/My_Works/HMS/ZANAHMIS-2-feature/.../com/orbix/api/{domain/Patient.java,domain/Visit.java,domain/Registration.java,service/PatientServiceImpl.java,api/PatientResource.java,api/accessories/Sanitizer.java}`.