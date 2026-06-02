# Increment 03 — Registration & Patient

## Goal

Deliver a fully operational patient registry — from first registration through send-to-doctor — such that a receptionist can register a cash or insurance patient, pay the registration fee at the cashier, and route an outpatient to a PENDING consultation (with its fee invoice) or route an outsider directly to downstream services, all in one end-to-end vertical slice running against a live stack.

## Scope

**Bounded contexts:** `registration` (primary), `billing` (recordClinicalCharge integration), `clinical` (consultation creation stub).

**Key aggregates / entities:**

- `Patient` — hidden `BIGINT` id + ULID `uid`; fields: `mrNo` (`MRNO/{year}/{seq}` via `seq_mrno`, per ADR-0009), `firstName`, `lastName`, `middleName`, `gender`, `dateOfBirth`, `paymentType` (enum: `CASH`, `INSURANCE`, `DEBIT_CARD`, `CREDIT_CARD`, `MOBILE`), `patientType` (enum: `OUTPATIENT`, `OUTSIDER`), `phone`, `email`, `address`, `nationality`, `deceased` (boolean, default false), `searchKey` (generated from name, sanitised), `insurancePlanUid` (nullable CHAR(26) FK by uid to `md_insurance_plan`), `membershipNo` (nullable), up to three `NextOfKin` embedded entries, `businessDayId`.
- `Registration` — links `Patient` uid to the business day; records `registeredAt` (`Instant`).
- `PatientBill` (in `billing` module) — UNPAID registration bill created atomically with the patient; `amount` (`NUMERIC(19,2)`), `status` (UNPAID/PAID), `billType` = REGISTRATION.
- `Consultation` (stub in `clinical` module) — created by the `sendToDoctor` command; `status` = PENDING; `clinicUid`, `clinicianUid`, `patientUid`, `feeInvoiceUid`.
- `PatientInvoice` + `PatientInvoiceDetail` (in `billing` module) — consultation fee invoice created atomically with the PENDING consultation via `BillingCommands.recordClinicalCharge(...)`.

**Key REST endpoints (`/api/v1/...`):**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/patients` | Register new patient (201 + Location) |
| `GET` | `/api/v1/patients` | Search: `?query=` (name/mrNo/searchKey/membershipNo), paginated |
| `GET` | `/api/v1/patients/uid/{uid}` | Get patient detail incl. last-visit timestamp |
| `PUT` | `/api/v1/patients/uid/{uid}` | Edit demographics (name, contact, kin) |
| `PATCH` | `/api/v1/patients/uid/{uid}/payment-type` | Flip payment type (validates open-invoice guard, REG-2/REG-3) |
| `PATCH` | `/api/v1/patients/uid/{uid}/patient-type` | Flip OUTPATIENT ↔ OUTSIDER (same open-invoice guard) |
| `POST` | `/api/v1/patients/uid/{uid}/send-to-doctor` | Atomic: create PENDING Consultation + consultation-fee PatientInvoice |
| `GET` | `/api/v1/patients/uid/{uid}/registration-bill` | Retrieve the unpaid registration bill |
| `GET` | `/api/v1/patients/uid/{uid}/last-visit` | Last-visit timestamp for continuity display |

**Process states / flows implemented (PROCESS.md §2 + §3.1):**

1. Lookup by `mrNo`, name substring, `searchKey`, or membership-card scan.
2. Create patient — all required + optional fields; `searchKey` generated server-side (name normalised, spaces stripped, lower-cased).
3. UNPAID registration `PatientBill` created in the same `@Transactional` boundary as `Patient` (via `BillingCommands.recordClinicalCharge`).
4. Cash-patient gate: `sendToDoctor` is refused with `ProblemDetail` `ErrorCode = REGISTRATION_FEE_UNPAID` unless the registration `PatientBill` is `PAID`.
5. `sendToDoctor` atomically: creates `Consultation{status=PENDING, clinicUid, clinicianUid}` + creates consultation-fee `PatientInvoice` via `BillingCommands.recordClinicalCharge`; publishes `ConsultationBookedEvent` (after-commit, for reporting read-model only).
6. Patient-type and payment-type flip: blocked if any open outsider orders or draft invoices exist (REG-2/REG-3 guard).
7. `deceased = true` set via a dedicated `PATCH /api/v1/patients/uid/{uid}/deceased`; `sendToDoctor` checks `deceased` and returns `ProblemDetail` `ErrorCode = PATIENT_DECEASED`.

## Dependencies

- **Increment 00** (walking skeleton): `AuditableEntity`, ULID generation, `BusinessDay`, `TxAuditContext`, `ProblemDetail`/`ErrorCode`, Flyway baseline, CI pipeline, Angular shell with OpenAPI-generated client.
- **Increment 01** (IAM): users, roles, privileges seeded; JWT auth working; the `PATIENT-CREATE`, `PATIENT-EDIT`, `PATIENT-VIEW`, `REGISTRATION-ALL`, `CONSULTATION-BOOK` privilege codes must exist and be enforced here.
- **Increment 02** (Master data): `md_insurance_plan`, `md_clinic`, `md_clinician` (ClinicClinician affiliation), `md_service_price` (registration fee, consultation fee) must be queryable for price lookups and affiliation checks.
- **Increment 04** (Billing/Cashier — pay registration bill): the `UNPAID` bill created in Increment 03 is settled in Increment 04; however the `billing.api` `BillingCommands.recordClinicalCharge` interface and the `settled`-flag callback (`SettlementDispatcher`) must be stubbed or partially present in Increment 03 since registration bill creation calls into it atomically.

## Exact-process fidelity targets

**MR number format (ADR-0009 §5, PROCESS.md §2, legacy-findings.md §Document Numbering):**
`MRNO/{year}/{seq}` where `{year}` = `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).getYear()` and `{seq}` = `nextval('seq_mrno')`. Parity assertion: for 50 back-to-back registrations in the same EAT calendar year the sequence is gap-free and unique; no two patients share an `mrNo`. Concurrent-creation test: 20 parallel threads each registering a patient must produce 20 distinct `mrNo` values with no duplicates (replaces the legacy `MAX(id)+1` race).

**Registration bill amount (PROCESS.md §2 step 4):**
`PatientBill.amount` = `ServicePrice.resolve(planUid=null, kind=REGISTRATION, currency=TZS)` for CASH patients; plan-specific price for INSURANCE patients. Golden-master assertion: register one CASH patient and one INSURANCE patient from the legacy scenario; compare `PatientBill.amount` rounded to 2 dp using `round(BigDecimal.valueOf(legacyDouble), 2).equals(newAmount)` per ADR-0009 §4.

**Cash gate (PROCESS.md §2 step 4, prior-attempt M3):**
`POST /api/v1/patients/uid/{uid}/send-to-doctor` for a CASH patient with `PatientBill{status=UNPAID}` must return HTTP 422 with `ProblemDetail{type="urn:hmis:error:registration-fee-unpaid"}`. After the cashier settles the bill (`SettlementDispatcher` flips `registrationBill.settled=true`), the same call must succeed. Parity test drives both legs.

**sendToDoctor atomicity (PROCESS.md §2→§3.1, prior-attempt M1/M2):**
A single call to `sendToDoctor` must produce: one `Consultation{status=PENDING}` row, one `PatientInvoice` row, and one `PatientInvoiceDetail` row — all committed or all rolled back. Insert a deliberate fault after `Consultation` creation and before the `billing` call; assert zero rows in all three tables after the rollback.

**Consultation fee invoice (PROCESS.md §3.1 cross-role handoff, prior-attempt M2):**
The consultation fee is looked up via `ServicePrice.resolve(planUid, kind=CONSULTATION, clinicUid, currency)` at `sendToDoctor` time, not at payment time. For an INSURANCE patient the plan-specific price applies; for CASH the cash price applies. The doctor's reception queue (Increment 05) must show the PENDING consultation only when the consultation-fee invoice is `settled=true` for CASH patients.

**Deceased flag (prior-attempt DISCH-4):**
After `PATCH .../deceased`, `Patient.deceased=true` is persisted. A subsequent `sendToDoctor` returns HTTP 422 `ProblemDetail{type="urn:hmis:error:patient-deceased"}`. Parity test confirms the legacy guard is reproduced.

**Search key (PROCESS.md §2 step 1, legacy-findings.md §Sanitizer):**
`searchKey` is generated server-side at registration from `firstName + lastName` (lowercased, whitespace collapsed, special chars stripped — mirrors legacy `Sanitizer.sanitizeString`). `GET /api/v1/patients?query=joh` matches prefix of `searchKey` and also matches membership card scan via `membershipNo` LIKE (gap REG-1 closed).

## Prior-attempt pitfalls to avoid

- **M1/M2 — sendToDoctor was a separate manual step:** In the prior build the receptionist had to re-search the patient and create the consultation independently. This increment must treat `sendToDoctor` as one atomic command: `patient → consultation record → fee invoice`, all in the same transaction. Separate screens or separate POST calls are not acceptable.
- **M3 — Cash gate was a queue filter, not a state-transition guard:** The prior build hid unpaid patients from worklists but did not block the `sendToDoctor` transition itself. The gate must be a hard precondition on the service method, returning a structured `ProblemDetail` `ErrorCode`.
- **BILL-6 — Registration bill and consultation fee treated as one till:** The prior build (low severity) sometimes combined these. Ensure `PatientBill{billType=REGISTRATION}` and the consultation-fee `PatientInvoice` are separate financial records from the first commit.
- **REG-1 — No membership-card / insurance-number lookup:** Patient search must include `membershipNo` LIKE match, not only name/mrNo. Confirm against PROCESS.md §2 step 1 "Card-scan-style lookup also supported."
- **REG-2/REG-3 — Flip of patientType/paymentType left orphaned work-in-progress:** Any PATCH that flips `patientType` or `paymentType` must check for open outsider orders or draft invoices and return a guard error if any exist.
- **DISCH-4 — Deceased patients left re-bookable:** The `deceased` flag is set here (registration module owns `Patient`) and must be checked in the `sendToDoctor` guard from day one.

## Lead & supporting agents

- **Lead:** backend-engineer (module implementation), frontend-engineer (Angular registration screens)
- **Supporting:** business-analyst (PROCESS.md §2 walkthroughs and acceptance scenarios), legacy-analyst (verify `PatientServiceImpl` call graph, `searchKey` generation logic, registration-bill amount lookup, `mrNo` format confirmation), healthcare-domain-expert (kin information fields, deceased-flag clinical implications), solution-architect (module boundary review — `registration → billing.api` call legality per ADR-0008 allowed-dependency graph), data-architect (`patient`, `registration`, `patient_bill` DDL + `seq_mrno` Flyway migration, `NUMERIC(19,2)` money columns), security-architect (PHI-field taxonomy for `Patient` audit log entries per ADR-0007; `@PiiField` annotations on `dateOfBirth`, `phone`, `email`, `address`, `membershipNo`), ux-ui-designer (patient-list and patient-register screens, card-scan UX, insurance-field conditional display), qa-test-engineer (golden-master parity scenarios, concurrent MR-number test, cash-gate negative test, deceased-flag test), code-reviewer (PR gate — boundary violations, ArchUnit, OpenAPI drift)
- **Informing (no action required this increment):** devops-engineer, integration-engineer, data-migration-engineer

## Definition of Done

- [ ] `Patient` aggregate persisted with all required fields; `mrNo` generated via `seq_mrno` PostgreSQL sequence producing `MRNO/{EAT-year}/{seq}` format; 20-thread concurrency test produces 20 unique `mrNo` values with no duplicates.
- [ ] Registration `PatientBill{status=UNPAID, billType=REGISTRATION}` created atomically with `Patient` in the same `@Transactional` boundary via `BillingCommands.recordClinicalCharge`; rollback test confirms zero rows in all tables on simulated fault.
- [ ] Cash-gate parity test: `sendToDoctor` with UNPAID registration bill returns HTTP 422 `ProblemDetail{type="urn:hmis:error:registration-fee-unpaid"}`; after bill settled, same call succeeds and produces one `Consultation{status=PENDING}` + one `PatientInvoice` + one `PatientInvoiceDetail` row.
- [ ] Insurance patient: `paymentType=INSURANCE` requires non-null `insurancePlanUid` + `membershipNo` enforced by Bean Validation; consultation fee resolves to the plan-specific price from `md_service_price`.
- [ ] Patient search covers `mrNo` exact, name prefix, `searchKey` prefix, and `membershipNo` LIKE (REG-1 closed); results are paginated; response includes `lastVisitAt` timestamp.
- [ ] `patientType` and `paymentType` flip guarded against open outsider orders or draft invoices (REG-2/REG-3); guard returns structured `ProblemDetail`.
- [ ] `PATCH .../deceased` sets `Patient.deceased=true`; subsequent `sendToDoctor` returns HTTP 422 `ProblemDetail{type="urn:hmis:error:patient-deceased"}` (DISCH-4).
- [ ] All `Patient`, `Registration` mutations emit `audit_log` rows with correct `action`, `actor_uid`, `entity_uid`; PHI fields (`dateOfBirth`, `phone`, `email`, `membershipNo`) redacted with `[REDACTED]` per ADR-0007.
- [ ] RBAC enforced: `PATIENT-CREATE`, `PATIENT-EDIT`, `PATIENT-VIEW`, `REGISTRATION-ALL`, `CONSULTATION-BOOK` privilege codes on all endpoints; unauthorized calls return 403 `ProblemDetail`.
- [ ] Angular standalone screens: patient-list (search, last-visit column), patient-register/edit form (insurance fields conditionally shown), send-to-doctor modal (clinic + clinician selector, affiliation-gated); screens generated from OpenAPI-generated TypeScript client.
- [ ] Spring Modulith `ApplicationModules.verify()` passes; ArchUnit rules pass (no `@Entity` crossing module boundaries, no `{id}` in URLs, no verb path segments); Flyway `ddl-auto=validate` passes.
- [ ] Testcontainers integration tests green in CI; golden-master parity test for registration-bill amount and MR-number format confirmed against legacy scenario dataset.
- [ ] OpenAPI 3 contract updated and committed; CI drift-gate passes; no breaking changes to existing endpoints.
- [ ] PR approved by code-reviewer with no unresolved HIGH findings.
