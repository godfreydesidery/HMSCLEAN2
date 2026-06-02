# ADR-0010: Reporting Strategy — Reproduce 29 Reports at Parity

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by data-architect, qa-test-engineer)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"

## Context

The legacy system has 36+ report endpoints in a single 1,748-line `ReportResource.java` mega-controller, injecting 30+ repositories directly and spanning all 14 bounded contexts. Report data sources are heterogeneous: raw domain entity lists (`List<Consultation>`, `List<GoodsReceivedNoteDetail>`), hand-mapped DTOs, 21 Spring Data native-query projection interfaces in `reports/models`, and anonymous inner `@Data` classes defined at the bottom of `ReportResource` (e.g., `RevenueReport`, `IPDReport`, `PharmacySalesReport`). The inner classes have no stable API contract.

Approximately 7 active native queries across 4 repositories (`CollectionRepository`, `ClinicianPerformanceRepository`, `LabTestRepository`, `PrescriptionRepository`) drive the most aggregation-heavy reports: collections, clinician performance, fast/slow-moving drugs. The remaining reports are assembled by in-process nested-loop Java iteration — O(n×m) over full entity graphs — which will fail on production-scale datasets.

The Angular frontend generates final PDF output via pdfmake and Excel output via xlsx/exceljs entirely client-side, consuming raw data from these endpoints. Thymeleaf is present on the classpath but drives zero report templates.

The client mandate is explicit: the **business process and business results must be identical**; internal data types (notably `double` → `BigDecimal`) are pre-approved improvements. "Cent-for-cent" parity means identical business results from correct arithmetic, not bit-identical reproduction of legacy floating-point rounding artefacts.

All monetary and quantity fields are Java primitive `double` throughout the legacy codebase — zero `BigDecimal` anywhere. Migrating to `BigDecimal` (pre-approved) means aggregate totals will differ from legacy by sub-cent floating-point noise; this is a documented improvement, not a defect.

## Decision

**Server-side data assembly; client-side rendering retained for PDF/Excel.**

1. **Reporting module:** Implement a dedicated `reporting` module within the Spring Modulith monolith. All report data assembly moves to this module. No report logic lives in resource (controller) classes or leaks into other bounded-context modules.

2. **Data layer — JOOQ for reporting queries.** Replace all in-process nested-loop joins and native-query projection interfaces with JOOQ type-safe SQL queries against read-optimised views or materialised snapshots. JOOQ is chosen over JPQL/Criteria because (a) native SQL is already the pattern for the 7 heaviest reports, (b) reporting aggregations require window functions, CTEs, and multi-table joins that JPQL cannot express cleanly, and (c) JOOQ generates compile-time-safe code from the PostgreSQL schema. Spring Data JPA projections are retired for reporting; they remain for transactional domain operations.

3. **Report API contract.** Each of the 29 reports is exposed as a dedicated endpoint under `/reports/uid/{reportTypeUid}` (POST with a typed filter DTO body — date range, clinician uid, store uid, etc.). Responses are typed JSON DTOs: no raw domain entities, no anonymous inner classes. Response DTOs are defined in the `reporting` module and are the sole contract between backend and frontend. UIDs are used exclusively; internal ids never appear in report payloads.

4. **Rendering remains client-side.** Angular continues to render PDF (pdfmake) and Excel (xlsx/exceljs) from the JSON response. This preserves the existing UX and frontend investment with zero regression risk. Server-side PDF/Excel generation (e.g., JasperReports, Apache POI) is deferred unless a future requirement demands server-push delivery (email scheduling, scheduled exports) — at which point the data contract already exists and a server renderer can consume it independently.

5. **Golden-master parity.** The qa-test-engineer must establish a golden-master suite: for each of the 29 reports, capture a reference dataset from the legacy system (same date range, same filters) and assert row-count equality and column-value equality to the cent against the new system, using the migrated dataset. Floating-point rounding differences from `double`→`BigDecimal` are explicitly whitelisted in the assertion harness with a documented tolerance of ±0.01 (one cent), treated as an accepted precision improvement.

6. **Performance flag.** The collections report (O(n×m) nested-loop in legacy), clinician performance, and fast/slow-moving stock aggregations are flagged as **high-risk for regression under load**. The qa-test-engineer must include performance benchmarks (target: p95 < 3 s on 12 months of data) alongside functional parity. The data-architect must confirm index strategy on the reporting read path before these queries go to staging.

## Considered Alternatives

| # | Alternative | Decision | Reason rejected |
|---|---|---|---|
| A | Keep in-process Java nested-loop joins | Rejected | O(n×m) in JVM heap; will OOM on production data volumes |
| B | Spring Data JPA projections for all 29 reports | Rejected | Cannot express window functions, CTEs, multi-join aggregations; already proven insufficient for the 7 native-query reports |
| C | Migrate Thymeleaf templates for server-side PDF | Rejected | Thymeleaf has zero report templates today; introduces a full new rendering stack with no legacy basis; client-side rendering already works |
| D | JasperReports / BIRT server-side rendering | Deferred | High integration cost; no business requirement for server-push delivery yet; can be added later without changing the data contract |
| E | Separate reporting microservice | Rejected | Premature decomposition; shared transactional data makes cross-service joins expensive; modular monolith boundary is sufficient |

## Consequences

**Positive:**
- All 29 report data paths are testable in isolation; golden-master suite is feasible and automatable.
- JOOQ compile-time query validation eliminates the runtime `NullPointerException` and serialization hazards present in the legacy inner-class returns.
- Typed filter DTOs replace the current pattern of passing full domain entity objects (`@RequestBody Clinician`) just to read one field — a clean API.
- `BigDecimal` arithmetic eliminates accumulated floating-point error in large aggregations (e.g., month-end collection totals).

**Negative:**
- JOOQ code generation must be integrated into the build pipeline (data-architect owns schema; devops-engineer owns the JOOQ generate step in the CI build).
- The 21 legacy projection interfaces must be audited and mapped to JOOQ record types — migration cost estimated at 2–3 sprints.

**Risks and mitigations:**
- *Golden-master data capture window:* legacy system must be snapshotted with known data before cutover. The data-migration-engineer must coordinate the snapshot date with the legacy-analyst.
- *BigDecimal tolerance whitelisting:* if the qa-test-engineer's golden-master harness uses strict equality, `BigDecimal` improvements will surface as false failures. The harness must implement cent-tolerance comparison from day one.
- *JOOQ schema drift:* if the Flyway migration adds or renames columns, JOOQ generated classes become stale. The CI pipeline must regenerate JOOQ classes as part of every Flyway migration step (devops-engineer action item).

## Exact-Process Impact

**Preserved exactly:**
- All 29 report business definitions: date-range scoping, per-clinician and per-store filtering, GRN/LPO document number inclusion, collection breakdown by payment method, debt-tracker outstanding balance logic, fast/slow-moving stock thresholds, inventory valuation method.
- The human-facing document numbers (`GRN{yyyyMMdd}-{id}`, `LPO{yyyyMMdd}-{id}`, `MRNO/{year}/{rawId}`, etc.) appear verbatim in report rows — these are process values carried forward unchanged.
- PDF and Excel output format (columns, headings, row order) is owned by the Angular rendering layer; no change required there.

**Legacy-analyst must still confirm:**
- The exact column definitions and sort order for the 8 reports that return raw domain entity lists (`List<Consultation>` etc.) — the frontend may depend on fields that are not obvious from the entity alone.
- Whether the two commented-out native queries in `ConsultationRepository` (lines 130–161) correspond to any active report endpoint, or are dead code safe to drop.
- The business definition of "fast-moving" vs "slow-moving" thresholds (currently encoded in the native query predicates).

**Change requests implied:**
- The StoreToPharmacy-TO / PharmacyToPharmacy-TO shared prefix "SPT" is a document-numbering collision; the product owner must assign a distinct prefix before report row identification is unambiguous. This is a prerequisite for the transfer-order report, not a post-go-live item.

## Implementation Notes

- **JOOQ version:** 3.19.x (PostgreSQL 16 dialect). Add `jooq-codegen-maven` plugin; generate from Flyway-migrated test schema via Testcontainers in CI.
- **Module boundary:** `com.zana.hmis.reporting` — no direct repository injection from other modules; data access only via the reporting module's own JOOQ queries or read-model views defined by the data-architect.
- **Endpoint pattern:** `POST /reports/uid/{reportTypeUid}` with filter body `ReportFilterRequest { LocalDate from; LocalDate to; String entityUid; ... }`. Responses: `ReportResponse<T>` envelope with `generatedAt`, `filterApplied`, `rows: List<T>`.
- **Golden-master tooling (qa-test-engineer):** JUnit 5 + Testcontainers + a pre-loaded reference dataset; custom `AssertJ` assertion `assertReportRowsMatch(expected, actual, CENT_TOLERANCE)` where `CENT_TOLERANCE = BigDecimal("0.01")`.
- **Performance benchmarks:** Gatling or k6 load tests scoped to the 5 heavy aggregation reports; run in CI against a 12-month synthetic dataset; p95 threshold enforced as a pipeline gate.
- **Security:** report endpoints must carry `@PreAuthorize` annotations using the existing privilege codes (e.g., `DAY-ACCESS`, `ADMIN-ACCESS`) verbatim — the security-architect must map each report to its legacy privilege before implementation begins.
- **Flyway dependency:** JOOQ code generation runs after Flyway migrations in the test container; this ordering is mandatory and must be explicit in the Maven/Gradle build lifecycle (devops-engineer to configure).
