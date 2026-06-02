# ADR-0001: Architecture style — modular monolith (Spring Modulith)
- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"

## Context
The legacy Zana HMIS is a single Spring Boot application (`com.orbix.api`, package-by-layer) over one MySQL 5 database (`zana_hmis_db_test`). Verified facts force the macro-architecture decision:
- **Cross-context atomicity is pervasive.** `@Transactional` appears 114 times across 110 files (confirmed by direct grep). Operations routinely span domains in one in-process transaction: `PatientServiceImpl` creates Patient + PatientBill + Registration + Visit (Registration + Billing + Clinical) and injects ~45 collaborators crossing every context bar HR and Assets; `GoodsReceivedNoteServiceImpl.approve()` mutates StoreItem stock, StoreStockCard, StoreItemBatch, Purchase, and LPO status across Inventory + Procurement + Purchasing atomically.
- **One shared database, no DDL.** Hibernate `ddl-auto=update`, no Flyway/Liquibase, no authoritative schema. Reports (`ReportResource`, 36+ endpoints injecting 30+ repositories) perform in-Java nested-loop joins across all 14 contexts.
- **Team & ops maturity is modest.** No migration tooling, hardcoded JWT secret (`"<REDACTED>"`), single deployable today. There is no evidence of a platform team capable of running distributed transactions, service mesh, or per-service datastores.
- **Mandate:** the business PROCESS and RESULTS must stay identical; data types/model may improve (double→BigDecimal pre-approved). "Fidelity" = identical process/results, not bit-exact.

## Decision
**Adopt a modular monolith built on Spring Modulith (Java 21, Spring Boot 3.3).** A single deployable, single PostgreSQL 16 database, organized into 14 modules where **module == bounded context**. Cross-module interaction is **in-process only, via published module APIs** (interfaces + DTOs); no module reaches into another's repositories or entities. Boundaries are **mechanically enforced** by Spring Modulith verification plus ArchUnit tests in CI. Domain events (Spring Modulith `ApplicationModuleListener`) model the seams that would later become integration events, so contexts can be extracted to services **if and when** load or org structure demands it — without rewriting callers. This ratifies the recommended target.

## Considered alternatives
| Option | Exact-process risk | 110 cross-context @Tx | Single MySQL/PG | Team/ops maturity | Deploy + data-migration | Score |
|---|---|---|---|---|---|---|
| **A. Modular monolith** | Low — local ACID preserved | Native in-process @Transactional | Fits one DB | Matches current team | One artifact; one Flyway baseline | **Chosen** |
| B. Microservices | High — Saga/2PC reintroduces every atomic op | Breaks 110 ops; needs eventual consistency | Forces DB-per-service split | Exceeds team capacity now | N deploys; cross-DB migration | Rejected (for now) |
| C. Traditional layered monolith | Medium — preserves @Tx but keeps the mud-ball | Works, but no boundaries | One DB | Matches | One artifact | Rejected — no enforced seams; reproduces `PatientServiceImpl`/`ReportResource` coupling |

**Microservices rejected FOR NOW:** distributing 110 atomic cross-context operations would replace local transactions with Sagas/compensation, directly threatening process fidelity and identical results; one shared schema cannot be cleanly split DB-per-service without re-engineering every join in `ReportResource`; operational maturity is insufficient. The modular monolith keeps published APIs + events as extraction seams, so microservices remain a future option, not a foreclosed one.

## Consequences
**Positive:** local ACID keeps all 110 atomic operations exactly; one Flyway-managed schema; enforced boundaries prevent regressing to today's coupling; one simple deploy; clear path to later extraction.
**Negative / risks & mitigations:**
- *Big-ball-of-mud regression* → ArchUnit + Spring Modulith `verify()` gate every build; PRs fail on boundary violations.
- *`PatientServiceImpl`/`ReportResource` resist decomposition* → strangler-fig: wrap each context in an anti-corruption facade first, split packages incrementally; Patient context isolated last (data-architect + legacy-analyst to map the seam).
- *Hidden coupling via shared entities* → each module owns its tables; cross-module reads go through APIs/read-models, not foreign entities (data-architect ratifies schema ownership).

## Exact-process impact
**Preserved:** every multi-entity transaction stays a single in-process `@Transactional`, so business results and ordering are unchanged. **legacy-analyst must still confirm:** the precise call graph and atomicity boundary of `PatientServiceImpl` and `GoodsReceivedNoteServiceImpl.approve()`, and that `ReportResource` in-Java joins are reproduced as DB-level aggregation with identical golden-master rows/cents (qa-test-engineer owns parity tests). **Change-request implied:** none for process; module boundaries and event seams are internal-structure improvements within the pre-approved model-change envelope.

## Implementation notes
- **Stack:** Java 21, Spring Boot 3.3, **Spring Modulith** (`spring-modulith-starter-core`, `-events-api`, `-actuator`), Spring Data JPA / Hibernate 6, **Flyway** (chosen) over PostgreSQL 16, Spring Security 6 (OAuth2 Resource Server), springdoc-openapi 3, MapStruct, Bean Validation, JUnit 5 + Testcontainers.
- **Module layout:** package-by-feature `com.zana.hmis.<context>` (14 modules). Each exposes an `api` sub-package (public interfaces + DTOs); `domain`/`repository`/`internal` stay package-private. Add `package-info.java` with `@ApplicationModule(allowedDependencies = …)`.
- **Enforcement:** `ApplicationModules.of(App.class).verify()` test + ArchUnit rules (no cross-module repository/entity access; no `internal` leakage) wired into GitHub Actions.
- **Seams:** publish domain events for cross-context side effects (e.g., GRN-approved → stock update) using `@ApplicationModuleListener` (same-transaction by default) so future extraction swaps to a broker without caller changes.
- **Out of scope here (peer ADRs):** uid/identifier strategy (data-architect), document-numbering sequences and SPT-prefix collision (data-architect + product owner), security/JWT externalization (security-architect), Flyway baseline + UTC offset normalization (data-migration-engineer), CI/CD + observability (devops-engineer), API/REST conventions (this series, separate ADR).
