# Increment 11 — Hardening, Non-Functional & Cutover

## Goal

Harden the fully-featured system — enforcing facility/clinic-store scoping, wiring real-time SSE queue signals, landing i18n for English and Swahili, completing observability (Actuator/Micrometer/OTel/Prometheus/Grafana), validating the system at 3-year production volume, executing a full security and PHI audit, provisioning staged Terraform environments with a secrets store, finalising reference-data seed migrations, and executing a governed go-live with a documented rollback runbook — so that the new Zana HMIS is live in production with the legacy decommissioned.

## Scope

**Bounded contexts touched (cross-cutting hardening across all 14):** `iam`, `masterdata`, `encounter`, `billing`, `pharmacy`, `store`, `procurement`, `hr`, `reporting`, `messaging`, and the shared `common` kernel. No new business aggregates are introduced; this increment enforces, instruments, and validates what increments 00–10 delivered.

**Key entities/aggregates:**
- `ClinicClinician(clinicUid, userUid)` and `StoreStaff(storeUid, userUid)` — the two M:N affiliation tables mandated by ADR-0020. `book()` and store-issue operations must assert membership; no other role-to-unit affiliation is added (nurses, pharmacists, cashiers, lab, radiology remain facility-wide per the confirmed legacy model).
- `notification_outbox` — created unconditionally by Flyway (ADR-0019 D3); poller remains dormant (`hmis.notifications.enabled=false`); SSE emitter registry wired for `reception-queue`, `nurse-queue:{wardUid}`, `dispense-queue:{pharmacyUid}`.
- `BusinessDay` health indicator, `FlywayMigration` health indicator — custom `HealthIndicator` beans (ADR-0012).
- `CompanyProfile` singleton and all reference-data seed scripts finalised: 177 privilege codes, clinics/wards/pharmacies/stores/theatres, medicines, lab-test types, dosage/route/frequency picklists, analyte reference ranges, currencies.

**Key REST endpoints:**
- `GET /messaging/queues/{queueId}/events` — SSE stream, authenticated, scoped by `QueueAccessPolicy` to the caller's roles (ADR-0019 D2).
- `GET /masterdata/clinics/uid/{clinicUid}/clinicians` — scoped clinician lookup (primary; admin-only unscoped form remains).
- All existing billing, encounter, pharmacy, and store endpoints gain scoping-gate enforcement where PHARM-1 was previously open.
- `GET /actuator/health/liveness`, `GET /actuator/health/readiness` — internal management port 8081 only (ADR-0012).
- `GET /actuator/prometheus` — Prometheus scrape target, internal port only.

**Process flows from PROCESS.md this increment enforces (not adds):**
- PROCESS.md §14 (Admin/master data): `CompanyProfile` singleton confirmed seeded; all masterdata picklists confirmed fully seeded before go-live.
- PROCESS.md §2 (Registration): MRNO/{year}/{seq} format confirmed correct by golden-master at volume.
- PROCESS.md §8 (Pharmacy): working-pharmacy session context (`X-Pharmacy-Uid` header or session selection) enforced on every dispensing/stock call, closing PHARM-1.
- PROCESS.md §9 (Store): `StoreStaff` affiliation asserted on every store-issue and transfer authorisation.
- PROCESS.md §3 (Doctor): `ClinicClinician` affiliation enforced on `book()` and on consultation-transfer target assignment.
- All document-number formats (GRN, LPO, PCN, PRL, PPRN, PSR, PPR, SPTO, PPTO, PGRN, USR-, MRNO/) confirmed correct and race-safe (ADR-0009 PostgreSQL sequences) under load.

## Dependencies

Requires all prior increments (00–10) to be merged and green. Specifically:
- Increment 00 (walking skeleton): `ApplicationModules.verify()`, Flyway baseline, ArchUnit gates, CI pipeline.
- Increments 01–06 (Registration through Inpatient/Discharge): all clinical state machines and payment gates in place; scoping enforcement requires a complete aggregate surface to gate.
- Increment 07 (Pharmacy/Store): `StockBalance`/`StockBatch` per-pharmacy ledgers and FEFO dispensing must exist before working-pharmacy enforcement is testable.
- Increment 08 (Procurement/GRN): `StoreStaff` affiliation gating on store-issue requires GRN stock credit to be live.
- Increment 10 (Reports/HR/Billing extensions): reporting endpoints must exist before load tests can validate P99 latency SLOs (ADR-0012 alert thresholds: billing/clinical P99 > 2 s pages; report P99 > 10 s warns).

## Exact-Process Fidelity Targets

**Facility/clinic-store scoping (ADR-0020):**
- Parity assertion: given a clinician affiliated only to `General OPD` clinic, `POST /encounters/consultations` with `clinicUid` = `Dental` must return `HTTP 422` with `ErrorCode = CLINICIAN_NOT_AFFILIATED`. Legacy behaviour: `PatientServiceImpl` enforces `Clinician.clinic` membership at booking time.
- Parity assertion: given a store keeper affiliated only to `Central Store`, a store-issue request against `Satellite Store` must return `HTTP 422 STORE_STAFF_NOT_AFFILIATED`.
- Working-pharmacy context: any pharmacy stock or dispense call missing `X-Pharmacy-Uid` header must return `HTTP 400 PHARMACY_CONTEXT_REQUIRED` (closes PHARM-1 confirmed gap).

**Document-number race-safety under load (ADR-0009):**
- Load test: 50 concurrent GRN creations on the same business date must produce 50 distinct `GRN{yyyyMMdd}-{N}` numbers with no gaps or duplicates (PostgreSQL `seq_grn_no` sequence). Same assertion for LPO, PCN, SPTO, PPTO.
- MRNO format golden-master: `MRNO/2026/{seq}` — confirm sequence starts at 1 on a fresh deployment and increments monotonically under concurrent registration load.

**Observability exact-process impact (ADR-0012):**
- PHI-in-logs golden-path test: execute full patient registration → consultation booking → bill payment → pharmacy dispense flow; capture `ListAppender` output; assert zero matches for `MRNO/\d{4}/\d+`, patient name fields, diagnosis text, and medication names in any log line at any level (in non-dev profile). This directly closes the legacy `org.hibernate.type=TRACE` PHI defect.
- `BusinessDayHealthIndicator`: assert `GET /actuator/health/readiness` returns `DOWN` when no `business_days` row has `status = OPEN`; returns `UP` when one does.
- SLO baseline: 3-year volume load test (see Non-Functional below) must confirm billing and clinical P99 latency under 2 s and report endpoints under 10 s at projected throughput.

**i18n exact-process guard (ADR-0021):**
- Report DTOs: all `BigDecimal` amount fields serialise as `"150000.00"` (plain decimal, 2 dp, no locale variation); all `LocalDate` fields serialise as `yyyy-MM-dd` ISO strings regardless of `Accept-Language: sw` header. Confirmed by a locale-invariant golden-master test comparing `en` and `sw` report responses byte-for-byte on all numeric and date columns.
- Validation messages: `POST /encounters/patients` with missing `firstName` under `Accept-Language: sw` must return `ProblemDetail.detail` in Swahili if the key exists in `messages_sw.properties`; fallback to English if not.

**RBAC full surface (ADR-0006):**
- Authorization parity suite: all 177 `@PreAuthorize` privilege codes loaded from the Flyway seed migration; for each code, assert that a token bearing that single privilege reaches the guarded endpoint with `2xx`/`403` as expected. No code may be missing from the seed migration.

**Reference-data completeness (ADR-0013, ADR-NEW-D):**
- On a clean `docker compose up`, before any user action, `GET /iam/privileges` must return exactly 177 records; `GET /masterdata/clinics` must return seeded clinics; all dosage/route/frequency picklists non-empty. These are acceptance gates before staging promotion.

## Prior-Attempt Pitfalls to Avoid

- **PHARM-1** — working-pharmacy session scoping was never implemented in the prior build's UI or API. This increment closes it as a hard API-level gate (not a UI-only concern). Every stock and dispense endpoint must reject requests without `X-Pharmacy-Uid`.
- **R1–R4** — prior build had no `ClinicClinician` affiliation at initial launch; booking offered all clinicians against all clinics. The `ClinicClinician` M:N was retrofitted in V56. The fresh build has carried this from increment 01 (increment spec for encounter); this increment confirms the gate fires under load and the parity test is green.
- **M19, M24** — payroll segregation (DRAFT → VERIFIED → APPROVED → PAID) was collapsed in the prior build. HR hardening in this increment includes a final payroll-state-machine parity check to confirm the VERIFIED checkpoint is present and the segregation-of-duties constraint (approver ≠ verifier) is enforced.
- **DISCH-1, DISCH-2** (HIGH severity open gaps) — no closure worklist, no printable closure document. These were remediated in the prior build's V73–V74 branches; confirm they are present and tested in the fresh build before go-live sign-off.
- **BILL-1, BILL-2** (HIGH severity open gaps) — no POS receipt print, no cashier collections report. The fresh build must have both (PDF/browser-print renderer per ADR-NEW-C and `/billing/cashier-shifts/uid/{uid}/collection-report` endpoint) confirmed working before go-live.
- **M3, M13, M23 (pay-before-service hard gates)** — load testing must confirm that concurrent CASH outpatient flows cannot advance a clinical order without settlement even under race conditions. The `settled` flag is a hard precondition on `accept()` and `complete()`; a race-condition test with two concurrent `accept()` calls on an unsettled order must confirm exactly one proceeds (or both reject, never both succeed).
- **Legacy `org.hibernate.type=TRACE` PHI defect** — the PHI-in-logs test in CI is a required gate; it must be green before staging promotion. This is not optional.
- **ADR-0019 D1 legacy-analyst confirmation** — before the outbox poller can ever be enabled (even in staging), the legacy-analyst must formally confirm no SMTP relay or SMS gateway is wired in the production legacy environment. This confirmation is a go-live checklist item.
- **ADR-0013 JWT key migration** — `grep -r 'HMAC256' src/` must return zero matches in the new codebase. This CI gate must pass before the first staging deployment and before go-live. The forced re-authentication event at cutover must be in the user-communication plan.

## Lead and Supporting Agents

- **Lead:** devops-engineer (infrastructure, Terraform, CI/CD pipeline, secrets store, go-live execution), security-architect (PHI audit, HMAC256 gate, Actuator security, cutover runbook sign-off), qa-test-engineer (load/volume tests, PHI-in-logs test, authorization parity suite, accessibility audit, alert-firing validation)
- **Supporting:** engagement-lead (go/no-go decision, rollback runbook owner), solution-architect (ADR-0012/0019/0020/0021 final ratification), data-architect (reference-data seed script finalisation, `BusinessDayHealthIndicator` SQL alignment), backend-engineer (SSE messaging module, scoping gate enforcement, `show-sql` startup assertion, custom health indicators, MDC `userId` injection), frontend-engineer (Angular `Accept-Language` interceptor, locale switching, SSE `EventSource` consumers per queue, working-pharmacy session context), legacy-analyst (ADR-0019 D1 SMTP/SMS environment confirmation, i18n display-format confirmation per ADR-0021), business-analyst (go-live user-communication plan, rollback decision criteria), code-reviewer (gates every PR including infrastructure PRs)

## Definition of Done

- [ ] `ClinicClinician` and `StoreStaff` scoping gates verified end-to-end: a UI-driven booking attempt with an unaffiliated clinician is rejected with a structured `ProblemDetail` (`ErrorCode = CLINICIAN_NOT_AFFILIATED`); same for store-issue without affiliation.
- [ ] Working-pharmacy context (`X-Pharmacy-Uid`) enforced at the API layer: dispense and stock-op calls without the header return `HTTP 400 PHARMACY_CONTEXT_REQUIRED`; Angular pharmacy screens pass the selected pharmacy on every call (PHARM-1 closed).
- [ ] SSE messaging module (`GET /messaging/queues/{queueId}/events`) live: Angular reception-queue, nurse-queue, and dispense-worklist screens update in real time without manual refresh; integration test asserts `SseEmitter` broadcasts after a `ConsultationBookedEvent` and a `ClinicalOrderRaisedEvent`.
- [ ] `notification_outbox` Flyway migration applied; `hmis.notifications.enabled=false` confirmed; `@Scheduled` poller bean absent when flag is false; `NoOpNotificationAdapter` unit-tested.
- [ ] `messages.properties` (English) complete for all Bean Validation constraints and `DomainException` messages; `messages_sw.properties` stub present; CI Swahili-coverage check passes (warning, not failure, on incomplete Swahili keys); ArchUnit rule blocking inline `DomainException(String)` constructors is green.
- [ ] Report locale-invariance golden-master: `en` and `sw` responses for all 29+ report endpoints are byte-for-byte identical on numeric and date columns.
- [ ] Actuator health probes live on management port 8081 only: `BusinessDayHealthIndicator` and `FlywayMigrationHealthIndicator` return correct `UP`/`DOWN`; Prometheus scrape target returning metrics; `show-sql=true` startup assertion blocks boot outside dev profile.
- [ ] PHI-in-logs integration test green in CI: full registration → bill → dispense golden path produces zero log lines matching PHI regex patterns in the non-dev profile.
- [ ] Authorization parity suite: all 177 privilege codes from the Flyway seed migration tested; zero missing codes; zero incorrect access decisions.
- [ ] 3-year volume load test (projected patient census × 3 years of daily transactions) complete: billing and clinical endpoint P99 < 2 s; report endpoint P99 < 10 s; no data corruption on concurrent payment-gate scenarios.
- [ ] Full security review passed: OWASP Dependency-Check CVSS < 7; Trivy no CRITICAL/HIGH CVEs; `grep -r 'HMAC256' src/` returns zero; CORS allow-list confirmed locked; Actuator management port confirmed unreachable from public interface.
- [ ] Terraform IaC: `dev`, `staging`, and `production` environments provisioned; secrets store configured; database credentials, JWT secret, and all PHI-adjacent config injected via environment variables — none committed to source; `.env.example` committed with placeholder values only.
- [ ] Reference-data seed finalisaton: clean `docker compose up` produces 177 privileges, seeded clinics/wards/pharmacies/stores/theatres/dosages/routes/frequencies/analytes/currencies; `GET /iam/privileges` returns exactly 177 records; `CompanyProfile` singleton present.
- [ ] Accessibility audit: Angular screens meet WCAG 2.1 AA for registration, cashier, and pharmacy worklist flows (the highest-frequency roles); findings documented and P1 issues resolved before go-live.
- [ ] Go-live execution: devops-engineer triggers production deployment; engagement-lead signs go/no-go checklist (all DoD items above green, legacy-analyst SMTP confirmation on file, user-communication sent); rollback runbook tested against staging (Flyway `repair`, container rollback to prior image, JWT key rotation procedure documented for all four ex-legacy sites).
- [ ] All changes delivered via PR; code-reviewer approved each; `main` branch CI green end-to-end; `ApplicationModules.verify()` and ArchUnit gates passing on final merge.
