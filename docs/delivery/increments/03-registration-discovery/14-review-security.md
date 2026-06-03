Confirmed: the only `@EntityListeners` on `AuditableEntity` is Spring Data's `AuditingEntityListener` (which populates `createdBy`/`createdAt` etc.) — NOT a tamper-evident `audit_log` writer. ADR-0007's `AuditEntityListener` skeleton (§110-119) is not wired anywhere. Audit is entirely explicit via `AuditRecorder.record(...)`. So Registration and Visit creation produce NO `audit_log` row in inc-03, despite ADR-0007 §182 listing them in-scope.

I now have a complete and confirmed picture. Here is my security assessment.

---

# SECURITY & COMPLIANCE REVIEW — Increment 03 (Registration & Patient)

Scope reviewed: full `registration/**` module, `V19__registration_patient.sql`, the cross-module billing CR-20 change (`ChargeRequest`/`BillingChargeService`/`BillingCommandsImpl`/`PatientBill.markNoCharge`/`@NamedInterface` enums), the new `ErrorCode`/exceptions, `GlobalExceptionHandler`, `SecurityConfig`, `AuditRecorder`/`AuditLog`/`AuditableEntity`, `ClinicianAffiliationService`. Verdict at end.

## Verdict: PASS-WITH-NOTES

No CRITICAL or HIGH findings. RBAC is exact-to-legacy, SQL is parameterized, the CR-20 billing change does not weaken the safety-critical engine, and no PHI leaks to logs or JWT claims. Two MEDIUM items (one a real audit-coverage gap against ADR-0007, one a pre-existing shared error-handler PHI-echo risk that registration now feeds) and the logged net-new-control candidates.

---

## 1. RBAC — CONFIRMED CORRECT (exact legacy parity)

Every write endpoint is gated with a LIVE code, reads are authenticated-only, and the gates match the legacy `@PreAuthorize` table verbatim (06-verify.md Claim 3, lines 33-44; V2 seed lines 55-57).

| Endpoint | File:line | Gate | Legacy | Status |
|---|---|---|---|---|
| `POST /patients` (register) | PatientController.java:106-107 | `PATIENT-ALL,PATIENT-CREATE` | :288-289 | CORRECT |
| `PUT /patients/uid/{uid}` (update) | :132-133 | `PATIENT-ALL,PATIENT-UPDATE` | :378-379 | CORRECT |
| `PATCH .../patient-type` | :150-151 | `PATIENT-ALL,PATIENT-UPDATE` | :398-399 | CORRECT |
| `PATCH .../payment-type` | :169-170 | `PATIENT-ALL,PATIENT-UPDATE` | legacy ungated → CR-03 FIX | CORRECT — **CR-03 fix confirmed applied**, no privilege removal, closes a real missing guard |
| `POST .../send-to-doctor` | :188-189 | `PATIENT-ALL,PATIENT-CREATE,PATIENT-UPDATE` | :508-509 | CORRECT |
| `GET /patients`, `/uid/{uid}`, `/last-visit` | :72, :83, :91 | no `@PreAuthorize` | :261,267,274,281 (no gate) | CORRECT — CR-04 parity |

- Codes are LIVE: `PATIENT-ALL/CREATE/UPDATE` seeded at V2__seed_iam.sql:55-57. No invented codes (`PATIENT-VIEW/EDIT` etc.) appear anywhere.
- Reads are **authenticated-only, not open**: `SecurityConfig.java:126` `anyRequest().authenticated()` is the catch-all; the GET endpoints are not in the `permitAll` list (lines 115-124). So there is no unintentionally-open write or read endpoint, and nothing is over-gated.
- No endpoint is missing a gate: the four mutating endpoints all carry `@PreAuthorize`; `@EnableMethodSecurity` is active (SecurityConfig.java:48).

## 2. The clinician-affiliation gate (iam::lookup) — SOUND, NOT BYPASSABLE within inc-03 scope

`PatientRegistrationProcess.java:438-442`: `clinicianAffiliationService.clinicUidsOf(req.clinicianUserUid()).contains(req.clinicUid())` — server-side, evaluated inside the transaction before any bill/consultation row is created. This is a sound authz/clinical-safety control:

- It is a positive check (membership in the affiliation set), so an attacker cannot bypass it by omitting a field — a non-affiliated or unknown clinician yields an empty/non-matching list → 422.
- It depends only on `iam::lookup` (ADR-0008 boundary honoured; package-info.java:40-41 declares the allowed dependency).
- It runs before `recordClinicalCharge` and the Visit/Consultation persist (line 438 precedes line 460), so a failed gate creates no rows.

One observation (not a defect): the gate verifies affiliation but does not independently verify the clinician's *active* status — the legacy guard was "clinician active" (PatientServiceImpl.java:427), and the build-spec §3.2 step 4 maps active+pairing onto the affiliation check. If `ClinicianAffiliationService` returns affiliations for a deactivated clinician, an inactive-but-affiliated clinician could be sent a patient. This is a parity question for legacy-analyst/iam, not an inc-03 regression — flagging as LOW.

**Reg-fee gate correctly absent — CONFIRMED safe, not an accidental omission.** Send-to-doctor (`sendToDoctor`, lines 423-500) performs no registration-bill status read. This is the ratified, safe choice (CR-01; 06-verify.md Claim 2, lines 18-27): the legacy pay-before-service gate lives on the *consultation* bill at doctor-open (inc-05), never on the registration fee at send time. Reproducing a reg-fee send-block would be NET-NEW and a patient-safety hazard. The deliberate absence is documented in the method (no bill check) and consistent with the legacy. Correct.

## 3. PHI/PII + audit

### 3a. No PHI leakage to logs — CONFIRMED
Grep for any logging facility across `registration/**` returned **zero matches** (no `Slf4j`, `Logger`, `System.out`, `printStackTrace`). No PHI in logs from this module. Patient/Registration/Visit/Consultation entities have no `@ToString` that would risk PHI in incidental logging.

### 3b. No PHI in JWT claims or the @NamedInterface exposure — CONFIRMED
- JWT carries only `sub` (username) and the `privileges` array (SecurityConfig.java:54, 87). `jwt.getSubject()` is the only claim read (PatientController.java:115, 201). No PHI.
- The CR-20 `@NamedInterface("api")` additions on `PaymentMode`, `BillStatus`, `CoverageStatus` (PaymentMode.java:15, BillStatus.java:23, CoverageStatus.java:12) expose only enum value names — **not PHI** (confirmed). They are exposed because the published `ChargeRequest`/`ChargeResult` records carry them; this is a correct Modulith boundary mechanism, not a data exposure.

### 3c. API returns PHI to authenticated callers — LEGITIMATE, no over-exposure
`PatientDto` (PatientDto.java:24-72) carries the full PHI set (names, DOB, phone, email, address, nationalId, passportNo, membershipNo, kin). Per build-spec §5.2 this is legitimate for authenticated callers. `searchKey` is correctly **excluded** from the DTO (PatientDto.java:13-18 documents it as `@PiiField`-tagged, internal-only). `no`/MRN is exposed as the audit locator — correct. No over-exposure.

### 3d. Audit coverage — MEDIUM finding (real gap vs ADR-0007 §182)
ADR-0007 §178-182 lists the Registration & Patient context audit scope as **Patient, Registration, Visit**. The implementation audits **Patient** (PatientRegistrationProcess.java:186, 244, 315, 375) and **Consultation** (line 493), but **never emits an `audit_log` row for `Registration` creation (line 177-178) or `Visit` creation (lines 181-182, 476-477)**.

- I confirmed there is no transparent fallback: `AuditableEntity` (AuditableEntity.java:44) wires only Spring Data's `AuditingEntityListener` (populates `createdBy`/`createdAt`), **not** ADR-0007's tamper-evident `AuditEntityListener` (the §110-119 skeleton is not implemented/wired anywhere — grep for `AuditEntityListener` returns nothing). Audit is entirely explicit via `AuditRecorder.record(...)`, so the missing calls mean the rows are genuinely absent.
- Impact assessment: this is MEDIUM, not HIGH. Registration is a thin Patient↔bill join created 1:1 with the Patient (which *is* audited at the same instant by the same actor), and the first Visit is likewise created in the same transaction. The clinically/financially material mutations — the Patient identity record and the PatientBill (audited in the billing module) — are captured. But Visit on send-to-doctor (line 476) is a distinct clinical-encounter event with no covering audit row, and ADR-0007 explicitly scopes Visit in.
- **Fix (one of):** (a) add `auditRecorder.record("registration.Registration", registration.getUid(), CREATE, ...)` after line 178 and `auditRecorder.record("registration.Visit", visit.getUid(), CREATE, ...)` after lines 182 and 477; or (b) if the design intent is to fold these under the Patient/Consultation aggregate audit, document that explicitly in the build-spec and update ADR-0007 §182's scope row so the contract and code agree. Either is acceptable; the current state is a silent contract divergence. Recommend (a) for Visit at minimum (encounter event), and an explicit aggregate-coverage note for Registration. Hand to data-architect (owns audit schema) + backend-engineer.

### 3e. AuditRecorder itself — clean
`AuditRecorder.record` (AuditRecorder.java:38-44) and `AuditLog` (AuditLog.java) store only `entityType`, `entityUid` (ULID, not PHI), `action`, `actorUsername`, `occurredAt`, `checksum`. No before/after PHI state is serialized in inc-03 (the JSONB before/after of ADR-0007 §28 is not yet implemented). So there is no PHI-in-audit risk here, and the SHA-256 checksum (lines 55-69) provides tamper evidence. Append-only is enforced (no setters; repository update/delete not exposed). Good.

### 3f. Logged net-new-control candidates (per task instruction — NOT blockers)
- **R4 — ungated bulk patient search**: `GET /patients?query=` (PatientController.java:72-78) is authenticated-only with no privilege and no rate limit; an authenticated low-privilege user can enumerate the full PHI patient set (blank query matches all; PatientQueryService.java:43 `q = ""` matches everything). This is CR-04 exact-process parity (legacy reads were ungated) and is correctly preserved. Logged as a recommended net-new control candidate (e.g., a future `PATIENT-VIEW`-style read gate or query-result audit), to be raised as a change request — not an inc-03 defect.
- **R1 — PHI in GET query strings**: search terms (which may be a patient name/phone) travel in the URL query string and may land in access logs / proxies. Net-new-control candidate (already logged in build-spec §5.2 R-1), not an inc-03 process change.

## 4. SQL / injection — CONFIRMED SAFE

- Search query (`PatientRepository.java:56-65`): JPQL with a single named parameter `:q` bound via `@Param`. The `%`-wrapping uses JPQL `CONCAT('%', :q, '%')` — the user value is a bound parameter, never string-concatenated into the query text. No injection vector. The repository `search(q, pageable)` is called with the raw user `query` (PatientQueryService.java:42-44) — safe because binding is parameterized.
- MR-number sequence (`PatientRepository.java:42-43`): `SELECT nextval('seq_mrno')` — a fixed, hardcoded sequence name; no user input concatenated. `MrNumberGenerator.java:49` calls it and only formats the returned long. Safe.
- All other finders are derived/parameterized Spring Data methods (`findByUid`, `findByNo`, `findFirstByPatientOrderByCreatedAtDesc`). No native DML, no `createNativeQuery` with user input. Pagination `page`/`size` are typed `int` (PatientController.java:75-76). Clean.

## 5. CR-20 billing change — SAFETY-CRITICAL ENGINE NOT WEAKENED — CONFIRMED

- **Non-followUp path unchanged**: `BillingChargeService.recordCharge` (lines 96-104) short-circuits **only** when `followUp && kind == ServiceKind.CONSULTATION`. The entire two-step cash-first/insurance-override algorithm (lines 106-170), the per-service fallback asymmetry (lines 177-206) including the CONSULTATION hard-fail (line 184), and the REGISTRATION regFee==0→VERIFIED logic (lines 122-124) are untouched below the guard. The non-followUp consultation path and all other kinds are behaviorally identical to pre-CR-20.
- **The follow-up NONE path is correct and contained**: creates a `PatientBill` with `Money.zero()` then `markNoCharge()` (PatientBill.java:251-257) which sets amount/paid/balance=0 and status=NONE, satisfying `assertInvariant()` (paid+balance==amount holds at 0). It skips price resolution, invoice, and claim — matching legacy PatientServiceImpl.java:467-469. `NONE` is a valid `ck_patient_bills_status` value (BillStatus.java:44).
- **followUp flag is constrained correctly at all call sites**: registration's REGISTRATION charge passes `false` (PatientRegistrationProcess.java:172, with the explicit comment "REGISTRATION is never a follow-up"); the CONSULTATION charge passes `req.followUp()` (line 471). `ChargeRequest.java:23-25` documents "must be false for all other service kinds." The guard at BillingChargeService.java:96 enforces this defensively — a stray `followUp=true` on a non-CONSULTATION kind is ignored (falls through to the normal path), so it cannot accidentally zero-out a lab/medicine/registration charge. Good defensive design.
- **Atomicity preserved**: `BillingCommandsImpl` is `Propagation.REQUIRED` (line 44), `recordCharge` is `Propagation.MANDATORY` (BillingChargeService.java:85) — runs in the caller's tx, no `@Async`/`REQUIRES_NEW`, not `@PreAuthorize`-gated (authz at registration's REST edge — correct, BillingCommandsImpl.java:26). The send-to-doctor flow calls the charge before persisting Visit/Consultation (PatientRegistrationProcess.java:460 precedes 477/490), so a charge hard-fail rolls back everything — no orphan rows. Confirmed.

## 6. New ErrorCode / exceptions — NO INTERNAL-DETAIL LEAK (with one shared-handler note)

- `MISSING_INSURANCE_INFORMATION` (ErrorCode.java:107-109): stable URN `urn:hmis:error:missing-insurance-information`, 422, generic non-PHI title. `MissingInsuranceInformationException` (MissingInsuranceInformationException.java:16-19) carries a fixed, non-PHI, non-internal detail string. Clean.
- `InvalidPatientOperationException` (InvalidPatientOperationException.java:33-35) maps to `BUSINESS_RULE` (422); the detail is the verbatim legacy business message (e.g., "This operation is not allowed for inpatients") — business-facing, no stack/SQL/internal detail. Clean.
- `CLINICIAN_ROLE_REQUIRED`, `PLAN_NOT_AVAILABLE_FOR_CLINIC` etc. all carry fixed business titles. No leak.

**MEDIUM (shared, pre-existing, now exercised by registration):** `GlobalExceptionHandler` echoes raw `ex.getMessage()` for `AuthenticationException` (line 40), `AccessDeniedException` (line 45), and the catch-all `Exception` (line 67 → `INTERNAL` 500). For the catch-all, `ex.getMessage()` of an arbitrary exception can surface internal detail (e.g., a constraint-violation message, or — in a worst case — a DB error string containing a PHI field value such as a duplicate `search_key`/`membership_no`). Because `Patient` has `uq_patients_search_key` and `uq_patients_no` UNIQUE constraints, a `DataIntegrityViolationException` reaching line 67 would echo a message that can include the conflicting value. Fix: for `handleUnexpected`, do **not** pass `ex.getMessage()` to the client — log it server-side (without PHI) and return the static `ErrorCode.INTERNAL.title()` ("Unexpected error") only. This is a shared-module item but inc-03's UNIQUE-constrained PHI columns increase its exposure surface, so I am flagging it now. Hand to backend-engineer/code-reviewer; coordinate with whoever owns `shared/error`.

---

## Findings summary

| Sev | Finding | File:line | Fix |
|---|---|---|---|
| MEDIUM | Audit-coverage gap: `Registration` and `Visit` creations emit no `audit_log` row, but ADR-0007 §182 scopes them in. No transparent listener covers them (AuditableEntity wires only Spring Data auditing, not ADR-0007's `AuditEntityListener`). | PatientRegistrationProcess.java:177-178, 181-182, 476-477; AuditableEntity.java:44; ADR-0007 §182 | Add explicit `auditRecorder.record("registration.Registration"/"registration.Visit", uid, CREATE, actor)` calls (esp. for Visit on send-to-doctor), OR amend ADR-0007 §182 + build-spec to document aggregate-level coverage. Data-architect + backend-engineer. |
| MEDIUM | `GlobalExceptionHandler.handleUnexpected` echoes raw `ex.getMessage()` (500) and same for auth/access-denied; a `DataIntegrityViolationException` on `uq_patients_search_key`/`uq_patients_no`/`membership_no` could surface a PHI value to the client. Pre-existing shared code; inc-03's PHI UNIQUE constraints raise the exposure. | GlobalExceptionHandler.java:65-68 (also 40, 45) | For the catch-all, return static `INTERNAL.title()` only; log detail server-side without PHI. Backend-engineer / shared-error owner. |
| LOW | Affiliation gate checks clinic membership but not clinician *active* status; legacy guard was "clinician active". Parity question, not a regression. | PatientRegistrationProcess.java:438-442 | Confirm with legacy-analyst/iam whether `clinicUidsOf` excludes deactivated clinicians; if not, add an active-status check via `iam::lookup`. |
| NOTE (R4) | Ungated bulk patient search returns full PHI set to any authenticated user; blank query matches all. CR-04 exact-process parity — correctly preserved. | PatientController.java:72-78; PatientQueryService.java:43 | Logged net-new-control candidate (read gate / query-result audit / rate limit) — raise as change request, not an inc-03 blocker. |
| NOTE (R1) | PHI search terms travel in GET query strings (access-log exposure). | PatientController.java:73-77 | Logged net-new-control candidate; not an inc-03 process change. |

## Confirmed correct (clean areas)
- RBAC gates on all 4 write endpoints, exact legacy parity; CR-03 payment-type gate fix applied; reads authenticated-only (CR-04). No open/over-gated endpoints.
- Reg-fee gate correctly absent at send-to-doctor (CR-01) — ratified, safe, intentional.
- Clinician-affiliation gate is server-side, positive-membership, pre-mutation, not bypassable within inc-03 scope.
- SQL fully parameterized: JPQL `:q` bound param; fixed `nextval('seq_mrno')`; no native DML with user input.
- CR-20 billing change does not weaken the safety-critical engine: non-followUp paths unchanged, followUp NONE path contained and invariant-safe, defensively guarded against misuse, atomicity preserved.
- New ErrorCodes/exceptions carry stable URNs and business-only detail — no internal/stack/SQL leak from the new types themselves.
- `@NamedInterface("api")` billing enums expose enum names only — not PHI.
- No PHI in logs (zero logging in module), no PHI in JWT claims, `searchKey` excluded from `PatientDto`.

**Relevant files (absolute):**
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/registration/application/PatientRegistrationProcess.java (audit gap: lines 177-178, 181-182, 476-477)
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/shared/error/GlobalExceptionHandler.java (line 67 PHI-echo)
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/shared/domain/AuditableEntity.java (line 44 — no ADR-0007 listener)
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/registration/web/PatientController.java (RBAC gates confirmed)
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/registration/domain/PatientRepository.java (parameterized search confirmed)
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/billing/application/BillingChargeService.java (CR-20 follow-up path confirmed safe)
- d:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis/billing/domain/PatientBill.java (markNoCharge invariant confirmed)
- d:/My_Works/HMS/HMSCLEAN2/docs/adr/0007-audit-trail.md (§182 audit scope)