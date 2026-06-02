# Increment 09 — HR, Payroll & Assets

## Goal

Deliver the `hr` and `assets` bounded contexts as a complete vertical slice: a permanent employee register with optional User linkage, a payroll period workflow with the mandatory VERIFIED checkpoint and per-component line breakdown, a clinician-performance roll-up, and a fixed-asset register with tag/barcode scanning and a full status lifecycle — so that HR staff can manage headcount, run monthly payroll, and track every piece of equipment through a browser UI backed by a fully-tested REST API.

## Scope

**Bounded contexts:** `hr` (employee, payroll) and `assets` (asset register). Both are self-contained — they publish no mandatory-synchronous calls to `billing.api` and hold no clinical order references. The `hr` module may query `iam.User` by uid (allowed direction: `hr` reads from `iam.api`) to validate the optional employee–user link. The `assets` module is a standalone aggregate with no upstream module dependency beyond `shared`.

**Key entities / aggregates:**

- `Employee` — hidden `id` (BIGINT GENERATED ALWAYS) + public ULID `uid`; fields: employeeNo (format `EMP-{yyyyMMdd}-{seq_emp_no}`), fullName, gender, dateOfBirth, nationalId, phone, email, address, designation, department, hireDate, status (ACTIVE / INACTIVE / TERMINATED); optional FK `userUid` (CHAR 26, cross-module reference to `iam.User` by uid, no JPA FK crossing module boundary).
- `PayrollPeriod` — period reference (`PRL{yyyyMMdd}-{seq_prl_no}` per ADR-0009, sequence `seq_prl_no`), periodName, startDate, endDate, status: **DRAFT → VERIFIED → APPROVED → PAID** (DRAFT also → CANCELLED; PAID is terminal). Carries snapshot totals: totalGrossEarnings, totalDeductions, netPay (all `NUMERIC(19,2)`).
- `PayrollItem` — one per employee per period; employeeUid, employeeNo (snapshot), employeeName (snapshot), gross `NUMERIC(19,2)`, totalDeductions `NUMERIC(19,2)`, net `NUMERIC(19,2)`.
- `PayrollItemLine` — one row per payroll component per item; componentUid, componentName (snapshot), componentType (EARNING / DEDUCTION), computeMethod (FIXED / PERCENT / BAND), amount `NUMERIC(19,2)`. This is the per-component breakdown restored by M19/M24.
- `PayrollComponent` — masterdata: name, type (EARNING / DEDUCTION), computeMethod (FIXED / PERCENT / BAND), value `NUMERIC(19,6)` (rate or fixed amount). `PayrollComponentBand` child rows for bracketed/PAYE-style deductions (lowerBound, upperBound, rate — all `NUMERIC(19,6)`).
- `ClinicianPerformance` — read-only projection: period query rolling up consultation count, admission count, lab/radiology/procedure order counts for a given employee's linked username.
- `Asset` — uid, tag (unique barcode string), name, category, description, location, custodianEmployeeUid (cross-module uid reference), acquisitionCost `NUMERIC(19,2)`, acquisitionDate, status: **ACTIVE → RETIRED / DISPOSED / LOST**; RETIRED can be `reinstate`d to ACTIVE; DISPOSED is terminal.

**Key REST endpoints (all under `/api/v1`):**

- `POST /hr/employees` — create employee (HR_ACCESS)
- `GET /hr/employees` — paginated list with search (HR_ACCESS)
- `GET /hr/employees/uid/{uid}` — get employee (HR_ACCESS)
- `PUT /hr/employees/uid/{uid}` — update employee (HR_ACCESS)
- `POST /hr/employees/uid/{uid}/link-user` — link to IAM user (HR_ACCESS)
- `GET /hr/employees/uid/{uid}/clinician-performance?from=&to=` — performance roll-up (HR_ACCESS)
- `POST /hr/payroll/components` — create payroll component (HR_ACCESS)
- `GET /hr/payroll/components` — list components (HR_ACCESS)
- `POST /hr/payroll/periods` — create DRAFT period (HR_ACCESS)
- `GET /hr/payroll/periods` — paginated list (HR_ACCESS)
- `GET /hr/payroll/periods/uid/{uid}` — get period with items summary (HR_ACCESS)
- `POST /hr/payroll/periods/uid/{uid}/items` — add / upsert payroll item (HR_ACCESS)
- `GET /hr/payroll/periods/uid/{uid}/items` — list items (HR_ACCESS)
- `POST /hr/payroll/periods/uid/{uid}/verify` — DRAFT → VERIFIED; locks items for further editing (HR_VERIFY)
- `POST /hr/payroll/periods/uid/{uid}/approve` — VERIFIED → APPROVED; second-approver gate, approver != verifier (HR_APPROVE)
- `POST /hr/payroll/periods/uid/{uid}/pay` — APPROVED → PAID (HR_PAY)
- `POST /hr/payroll/periods/uid/{uid}/cancel` — DRAFT/VERIFIED → CANCELLED (HR_ACCESS)
- `POST /hr/assets` — register asset (HR_ACCESS)
- `GET /hr/assets` — paginated list with category/status filter (HR_ACCESS)
- `GET /hr/assets/uid/{uid}` — get asset (HR_ACCESS)
- `GET /hr/assets/by-tag/{tag}` — barcode scanner lookup (HR_ACCESS)
- `PUT /hr/assets/uid/{uid}` — update asset metadata (HR_ACCESS)
- `POST /hr/assets/uid/{uid}/retire` — ACTIVE → RETIRED (HR_ACCESS)
- `POST /hr/assets/uid/{uid}/dispose` — ACTIVE/RETIRED → DISPOSED (HR_ACCESS)
- `POST /hr/assets/uid/{uid}/mark-lost` — ACTIVE → LOST (HR_ACCESS)
- `POST /hr/assets/uid/{uid}/reinstate` — RETIRED → ACTIVE (HR_ACCESS)

**Process states / flows from PROCESS.md §12:**

Employee register → (optional) User link. Payroll: HR staff builds a DRAFT period, enters per-employee items with component lines, manager VERIFIES (locks items, segregation-of-duties checkpoint), director APPROVES (second approver, must differ from verifier), finance marks PAID. Clinician performance: date-range roll-up over consultations + orders for the linked username. Asset register: ACTIVE → RETIRED / DISPOSED / LOST; RETIRED can be reinstated.

## Dependencies

- **Increment 00 (Walking Skeleton & Shared Kernel)** — `AuditableEntity` (hidden `id` + ULID `uid`), `TxAuditContext`, `Money`/`NUMERIC(19,2)`, `ProblemDetail`/`ErrorCode`, Flyway baseline, `ApplicationModules.verify()` gate, CI pipeline.
- **Increment 01 (Identity & Access)** — `Employee.userUid` is a cross-module uid reference into `iam.User`; `link-user` verifies the target user exists and carries the CLINICIAN/STAFF role via `iam.api`. The seeded privilege codes include `HR_ACCESS`, `HR_VERIFY`, `HR_APPROVE`, `HR_PAY`, which gate every endpoint here.
- **Increment 02 (Master Data & Reference Seeding)** — payroll component definitions and designations are reference data.

No dependency on clinical, billing, pharmacy, or inventory increments. The `clinician-performance` roll-up queries across module boundaries via read-model projection queries (reporting read path, not write-side entity access), so it requires that encounter data is available — in practice this endpoint returns zeros until clinical slices are active, which is acceptable.

## Exact-process fidelity targets

1. **Payroll document number format (ADR-0009, PROCESS.md §12, legacy-findings doc-numbering section):** every `PayrollPeriod.no` must match `PRL{yyyyMMdd}-{seq}` using PostgreSQL sequence `seq_prl_no` (starts at 1) and `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))` for the date segment. Golden-master assertion: two concurrent `createPeriod` calls must produce two distinct, gap-free `PRL` numbers with no collision.

2. **Payroll state machine gates (M19, M24):** VERIFIED checkpoint is mandatory — the transition DRAFT → APPROVED must not exist; `approve()` must throw `PERIOD_NOT_VERIFIED` (`ProblemDetail` with `type = "urn:hmis:error:period-not-verified"`) if status is DRAFT. `pay()` must throw `PERIOD_NOT_APPROVED` if status is not APPROVED. An empty period (zero items) must be rejected from `verify()`. Golden-master: a period run from DRAFT through all four states in sequence must have each intermediate state persisted and audited.

3. **Per-component breakdown (M19, M24):** `PayrollItemLine` rows must be persisted per component, not a single gross/deductions snapshot. The golden-master test drives a period with three components (one EARNING FIXED, one DEDUCTION PERCENT, one DEDUCTION BAND) and asserts: `item.net == item.gross - sum(lines where type=DEDUCTION)`, all amounts are `NUMERIC(19,2)` rounded `HALF_UP`, and the period's `totalGrossEarnings` == `sum(item.gross)` across all items.

4. **Segregation of duties (M19):** the user who calls `verify()` must not be the same user who calls `approve()`. The `approve()` transition must read `period.verifiedByUid` and compare to the authenticated user's uid; if equal, return `ProblemDetail` with `type = "urn:hmis:error:self-approval-not-allowed"`. Privilege separation: `HR_VERIFY` is granted to payroll-manager role; `HR_APPROVE` is granted to director role only.

5. **Asset tag uniqueness:** `Asset.tag` carries a unique database constraint. Attempting to register a second asset with a duplicate tag must return RFC 7807 `ProblemDetail` with `type = "urn:hmis:error:asset-tag-conflict"`, HTTP 409.

6. **Asset status transitions:** DISPOSED is a terminal state — `reinstate()` and `retire()` from DISPOSED must be rejected. RETIRED → ACTIVE is the only valid `reinstate()` path. Golden-master: assert that calling `dispose()` then `reinstate()` returns `INVALID_STATUS_TRANSITION`.

7. **Clinician performance roll-up (PROCESS.md §12.3, PROCESS.md §13):** the `GET .../clinician-performance` endpoint must count: consultations where `clinicianUsername = employee.linkedUsername` in the date range; lab, radiology, and procedure orders raised by `createdByUsername = employee.linkedUsername` in the date range. Results must match the count produced by the same query run against the legacy's `ClinicianPerformanceRepository` native SQL for an identical dataset — this is the parity assertion target.

## Prior-attempt pitfalls to avoid

- **M19 / M24 — collapsed state machine and missing per-component lines.** The prior attempt shipped `PayrollPeriod` as DRAFT → APPROVED → PAID with no VERIFIED checkpoint and stored only a gross/deductions/net snapshot. This increment must model VERIFIED as a first-class status with a dedicated `verifiedByUid` / `verifiedAt` audit pair, and `PayrollItemLine` must be its own table — not an inline JSON blob or a computed field. The parity test must fail if `PayrollItemLine` rows are absent.
- **M19 — self-approval.** The prior build initially had no approver != verifier check. Encode this as a hard gate in `approve()`, not a comment or an advisory warning.
- **DISCH-1 pattern (missing worklist endpoint).** The prior build left the PENDING discharge plan with no `findByStatus` query and no worklist screen. Repeat of this pattern here would mean HR managers have no endpoint to list all DRAFT or VERIFIED payroll periods. Every state must be queryable: `GET /hr/payroll/periods?status=DRAFT|VERIFIED|APPROVED|PAID`.
- **BILL-1 / BILL-2 pattern (backend data, no endpoint/screen).** Entity data without a working Angular screen is a HIGH-severity gap. The Angular `HrPayrollModule` must deliver working screens for: employee list + edit, payroll period list + detail + state transitions, asset list + edit + status transitions — all wired to the generated OpenAPI client. No increment is done until a user can drive the full workflow through the UI.
- **ADR-0009 sequence correctness.** The prior build used `SELECT MAX(id)+1` for payroll numbers — a race condition that produced duplicate `PRL` numbers under concurrent load. Use `seq_prl_no` (PostgreSQL `SEQUENCE`) as specified in ADR-0009. Do not introduce a counter table or application-level lock.
- **ADR-0006 privilege codes.** `HR_ACCESS`, `HR_VERIFY`, `HR_APPROVE`, `HR_PAY` must be seeded verbatim in the Flyway privileges migration (carried from increment 02). Every endpoint must carry the exact `@PreAuthorize("hasAnyAuthority('HR_ACCESS')")` (or the appropriate finer code); no endpoint may be left unannotated or use a catch-all.

## Lead & supporting agents

- **Lead:** backend-engineer
- **Supporting:** engagement-lead, solution-architect, data-architect, security-architect, ux-ui-designer, frontend-engineer, qa-test-engineer, code-reviewer
- **Consulted:** legacy-analyst (confirm payroll component data-driven design carries forward; confirm `ClinicianPerformanceRepository` native SQL columns to match in the projection), business-analyst (confirm segregation-of-duties roles for HR_VERIFY vs HR_APPROVE), healthcare-domain-expert (confirm asset status lifecycle and re-instatement rules)

## Definition of Done

- [ ] Flyway migrations: `hr_employee`, `hr_payroll_component`, `hr_payroll_component_band`, `hr_payroll_period`, `hr_payroll_item`, `hr_payroll_item_line`, `hr_asset` tables created with all constraints (unique `asset.tag`, unique `(period_uid, employee_uid)` on payroll item, FK from `hr_payroll_item_line.item_uid` to `hr_payroll_item.uid`); `seq_emp_no` and `seq_prl_no` sequences present (ADR-0009).
- [ ] All REST endpoints listed in Scope section implemented, mapped via MapStruct, documented in OpenAPI 3 (`springdoc`), and returning RFC 7807 `ProblemDetail` on all error paths.
- [ ] RBAC enforced: every endpoint annotated with exact `@PreAuthorize` privilege code; a generated authorization parity test asserts all mappings (ADR-0006).
- [ ] Payroll state machine: DRAFT → VERIFIED → APPROVED → PAID enforced with no DRAFT → APPROVED shortcut; empty-period guard on `verify()`; self-approval guard on `approve()`.
- [ ] `PayrollItemLine` table populated for every upsert; `item.net` == `item.gross - sum(DEDUCTION lines)` enforced in service layer; period totals recomputed on every item upsert.
- [ ] Document number parity test: concurrent creation of 20 payroll periods produces 20 unique, gap-free `PRL{date}-{n}` numbers.
- [ ] Asset status lifecycle: all valid and invalid transitions covered by unit tests; DISPOSED terminal state enforced; RETIRED → ACTIVE `reinstate()` tested.
- [ ] `by-tag/{tag}` endpoint returns 404 `ProblemDetail` for unknown tag; returns 200 with correct asset for a known tag.
- [ ] `clinician-performance` roll-up returns correct counts for a test employee linked to a seeded username with known consultation/order fixtures in a Testcontainers integration test.
- [ ] Audit events (`AuditEvent`) emitted for: employee created/updated, payroll period state transitions (DRAFT→VERIFIED, VERIFIED→APPROVED, APPROVED→PAID), asset status transitions — verified in integration test.
- [ ] Angular vertical slice complete: Employee list + create/edit form; Payroll period list + period detail with item grid + verify/approve/pay action buttons (with correct privilege gating); Asset list + register form + status action buttons + tag-scan lookup field — all functional against a live Testcontainers-backed dev API.
- [ ] `ApplicationModules.verify()` and ArchUnit rules pass: no `@Entity` from `hr`/`assets` imported by any other module; `hr` references `iam.api` only via uid lookup; no direct repository cross-module reference.
- [ ] All Testcontainers integration tests green on the CI pipeline; no open HIGH-severity findings from code-reviewer.
- [ ] PR reviewed and approved by code-reviewer before merge to main.
