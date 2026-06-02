# ADR-0008: Bounded-context decomposition & module boundaries

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"

## Context
The legacy backend is a single `com.orbix.api` package organised **by technical layer** (`domain`, `repositories`, `service`, `api`, `reports`, `security`, `filter`) — package-by-layer, not by feature. There are ~115 entity-bearing files, 110 files carrying `@Transactional`, and business operations routinely span multiple conceptual domains in one in-process transaction. Two artifacts concentrate the coupling: `PatientServiceImpl` injects ~45 repositories/services and touches every context except HR/Payroll and Assets (Patient + Bill + Registration + Visit + insurance plans + admission + pharmacy + lab + radiology); `ReportResource` injects 30+ repositories spanning all 14 contexts and performs O(n×m) nested-loop joins in Java memory. Without explicit module seams, decomposition is structurally blocked.

Two concrete hazards confirmed by code inspection must be addressed explicitly:

1. **Timestamp divergence.** `PatientServiceImpl` calls `dayService.getDay().getId()` and `dayService.getTimeStamp()` on **179 separate lines** (lines 239–1602) across every cross-context entity inside a single transaction. `getTimeStamp()` is `LocalDateTime.now().plusHours(3)` — a wall-clock call. If module APIs each stamp independently, the same logical transaction will record different millisecond values per sub-entity, diverging from legacy where all share one snapshot.

2. **Bill + invoice atomicity.** The consultation creation block (lines 459–674) writes `PatientBill` + `Consultation` + `Visit` + `PatientInvoice` + `PatientInvoiceDetail` in a single transaction. `PatientInvoice`/`PatientInvoiceDetail` are not side-effects — they are the insurance-claim record created at the moment of the clinical act. Moving them outside the synchronous transaction would silently break billing fidelity.

Per the client mandate, the **business process and results must stay identical**; data types and the data model may improve (e.g. `double`→`BigDecimal`).

## Decision
Adopt a **Spring Modulith modular monolith**, **package-by-context**, one module per bounded context under `com.zana.hmis.<context>`:
`registration`, `clinical`, `inpatient`, `pharmacy`, `inventory`, `laboratory`, `radiology`, `procedures`, `billing`, `insurance`, `hr`, `assets`, `iam`, `reporting`. A **`shared` (shared kernel)** module holds: patient-identity reference (`PatientRef` = id + uid + MR no), the `Money` value object (BigDecimal-backed, see ADR-0006), reference/master data, cross-cutting privilege + audit primitives, and — mandated by hazard 1 — the **`TxAuditContext`** value object.

Rules:

1. **Module API vs internals.** Each module exposes a `<context>.api` package (named interfaces, command/result DTOs, published events) and keeps entities/repositories/impls package-private in internal sub-packages. Cross-module calls use API types only; **no module imports another module's `@Entity`**. Modules reference foreign aggregates by **uid** only, never by internal `id` or entity reference.

2. **Cross-context transactions — keep, don't fragment.** The 110 in-process transactions stay atomic **for now**. Application/orchestration services (in a dedicated `process` sub-package or a thin `app` layer) own `@Transactional` boundaries and call multiple module APIs synchronously in one transaction. `PatientServiceImpl` is decomposed into per-context module services behind APIs, re-orchestrated by a `PatientRegistrationProcess` application service. This preserves identical process and results.

3. **Transaction-scoped audit/day context — mandatory.** The `shared` kernel exposes `TxAuditContext`, a value object constructed once per logical operation and carrying: `dayId` (the current business-day PK from `dayService.getDay().getId()`), `timestamp` (a single `LocalDateTime.now()` capture), and `userId`. Every module-API command accepts a `TxAuditContext` parameter and uses its values to stamp `createdOn`/`createdAt`/`createdBy`. **No module may call `dayService` or `LocalDateTime.now()` independently inside a command.** The orchestrating application service constructs one `TxAuditContext` before invoking any module API and passes it through all calls in the transaction. This is the direct replacement for the legacy pattern of 179 scattered `dayService` calls.

4. **Bill + invoice creation is MUST-STAY-IN-TX — not an event.** `PatientInvoice` and `PatientInvoiceDetail` creation is classified as **mandatory-synchronous**: it must complete in the same `@Transactional` boundary as the clinical act (consultation, lab order, prescription dispense, procedure) that triggers it. `billing.api` exposes a single command — `BillingCommands.recordClinicalCharge(ChargeRequest, TxAuditContext)` — that writes `PatientBill` + `PatientInvoice` (find-or-create) + `PatientInvoiceDetail` as one call. It carries **no `@Transactional(propagation = REQUIRES_NEW)`** and no `@ApplicationModuleListener` boundary; it participates in the caller's existing transaction. Engineers must not move this to an async event. The allowed-dependency graph (Clinical/Inpatient/Pharmacy/Lab/Radiology/Procedures → `billing.api`) exists precisely to make this synchronous call legal.

5. **Events for eventual decoupling — non-essential side-effects only.** Modules publish Spring Modulith `@ApplicationModuleListener` domain events (e.g. `PatientRegistered`, `GrnApproved`) exclusively for non-essential side-effects: audit projections, reporting read-model updates, notification dispatch. The boundary between "must-stay-in-tx" and "safe-to-async" must be documented per operation in the `PatientRegistrationProcess` and peer application services, reviewed by `legacy-analyst`.

6. **Allowed dependency graph.** Every module → `shared`. Clinical/Inpatient/Pharmacy/Lab/Radiology/Procedures → `billing.api` + `insurance.api` + `registration.api`. `billing` → `insurance.api`. `inventory` ↔ `pharmacy` via APIs/events. `reporting` reads via dedicated read-model/projection APIs only (no write-side entity access). `iam` and `shared` depend on nothing upstream. Cycles are forbidden.

7. **Enforcement.** Combine **Spring Modulith `ApplicationModules.verify()`** (module boundaries, allowed dependencies, no leaking internals) with **ArchUnit** rules (no `@Entity` crossing modules, `api`-only imports, no controller → foreign-repository access, layering inside a module). Both run in CI. An additional ArchUnit rule forbids any class inside a module's internal packages from calling `dayService.getDay()` or `LocalDateTime.now()` directly — all stamping must route through the passed-in `TxAuditContext`.

## Considered alternatives

| Option | Boundary enforcement | Cross-ctx tx fidelity | Migration risk | Score (1-5) |
|---|---|---|---|---|
| **Spring Modulith modular monolith (chosen)** | Compile/test-time + runtime verify | Native (in-process tx preserved) | Low | **5** |
| Microservices now | Network = hard boundary | Breaks atomicity → sagas/2PC | Very high | 2 |
| Plain Maven multi-module | Compile-time only | Preserved | Medium | 3 |
| Keep package-by-layer, add ArchUnit only | Weak, advisory | Preserved | Low but no seams | 2 |

- **Microservices now —** rejected: would force distributed sagas onto 110 atomic flows, risking different business results; premature for a process-fidelity migration.
- **Maven multi-module —** rejected: enforces compile-time deps but lacks Modulith's event model and runtime boundary verification; does not solve the `TxAuditContext` stamping problem structurally.
- **ArchUnit-only on package-by-layer —** rejected: no real module seam; the `PatientServiceImpl`/`ReportResource` god-objects survive.

## Consequences
**Positive:** clear seams enable later service extraction; god-objects are dismantled behind APIs; identical process preserved via orchestration services; `TxAuditContext` eliminates the 179-call stamping scatter and guarantees consistent day/timestamp values per transaction; `billing.api` atomic command preserves invoice/bill fidelity; events provide an incremental decoupling path; enforced boundaries prevent regression.

**Negative / risks:** more boilerplate (DTOs/mappers via MapStruct; `TxAuditContext` threaded through all command signatures); orchestration services concentrate transaction scope; cross-module uid lookups add indirection; the `billing.api` synchronous coupling means `billing` module cannot be independently deployed without revisiting the calling modules.

**Mitigations:** MapStruct for entity↔API mapping; `data-architect` defines `PatientRef`/`TxAuditContext`/reference-data ownership in `shared`; treat `registration`/Patient as the **last** context to isolate (highest coupling); CI gates fail the build on boundary violations (`qa-test-engineer`, `devops-engineer`); ArchUnit rule blocks any direct `dayService`/`LocalDateTime.now()` call inside module internals.

## Exact-process impact
**Preserves:** every cross-context transaction stays atomic and synchronous; identical sequence of effects (e.g. consultation → conBill → patientInvoice → patientInvoiceDetail all in one tx; GRN approve → stock + stock-card + batch + purchase + LPO status all in one tx); all 179 stamp-sites produce the same `dayId` and a single consistent `timestamp` per operation instead of scattered wall-clock calls.

**`legacy-analyst` must confirm:** (a) the full call graph of `PatientServiceImpl` and `ReportResource` so each branch is captured in the correct module API; (b) per operation, an explicit classification of every side-effect as "must-stay-in-tx" (e.g. bill/invoice writes) vs "safe-to-async" (e.g. reporting projections); (c) that no other service besides `PatientServiceImpl` calls `dayService` in a cross-context scattered pattern requiring the same fix.

**Change-request implied:** none for process; this is internal structure. The `TxAuditContext` introduction is a structural refactor with no observable business-outcome change. Document-numbering and money-type improvements are handled in their own ADRs and remain pre-approved.

## Implementation notes
- Java 21, Spring Boot 3.3, **Spring Modulith** (`spring-modulith-starter-core`, `-events-jpa` for transactional event publication), Spring Data JPA/Hibernate 6, MapStruct, Bean Validation.
- `shared` kernel additions: `TxAuditContext` record — `record TxAuditContext(Long dayId, LocalDateTime timestamp, Long userId)` — constructed once per request in the application/process layer and passed into every module-API command as a parameter. No Spring-managed bean; no thread-local; explicit parameter passing only.
- `billing.api` command: `BillingResult recordClinicalCharge(ChargeRequest req, TxAuditContext ctx)`. `ChargeRequest` carries: `patientUid`, `chargeType` (enum: CONSULTATION/LAB/PRESCRIPTION/RADIOLOGY/PROCEDURE), `amount` (`Money`), `paymentType`, optional `insurancePlanUid`. The implementation does find-or-create `PatientInvoice` + create `PatientInvoiceDetail` + create `PatientBill` with no new transaction boundary. Propagation is `REQUIRED` (default) — it joins the caller's transaction.
- Package skeleton: `com.zana.hmis.<context>.api` (public), `.internal.domain` / `.internal.repository` / `.internal.service` (package-private). Document API in `package-info.java` with `@ApplicationModule(allowedDependencies = {...})`.
- Tests: `ApplicationModules.of(HmisApplication.class).verify()` as a JUnit 5 test; ArchUnit `@AnalyzeClasses` ruleset: (1) no foreign `@Entity` import across modules; (2) controllers must not inject repositories; (3) no class in `*.internal.*` may reference `DayService` or call `LocalDateTime.now()` directly — must receive `TxAuditContext`. Generate `spring-modulith-docs` C4/PlantUML module canvas for review.
- Cross-module references by **uid** only; `shared.Money` (BigDecimal) and `shared.TxAuditContext` are the sole types freely importable everywhere. Reporting consumes read-model projection APIs/SQL views, not write-side entities.
