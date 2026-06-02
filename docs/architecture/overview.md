# Zana HMIS — Target Architecture Overview

> Ratified target architecture for the Zana HMIS modernization. This is a **fresh greenfield build** in `d:/My_Works/HMS/HMSCLEAN2` with **no production-data migration** from the legacy MySQL system. The legacy `com.orbix.api` application and the prior in-flight attempt (`hmis-engine-api`, `com.otapp.hmis.engine`) are **reference-only**; neither binds this build. Governed by 21 ADRs (0001–0021).

## Summary

- **Architecture style: modular monolith** on **Spring Modulith** (ADR-0001/0008). One deployable, one PostgreSQL 16 database, 14 modules where *module == bounded context*, boundaries verified at build time by Modulith `verify()` plus ArchUnit — chosen to honor the exact-process mandate while taming the legacy's pervasive cross-domain transactional coupling.
- **Platform: Java 21 + Spring Boot 3.3** (ADR-0002), authored fresh for the target stack (the **firm stack**, not the prior scaffold). Proven business logic — the 177 privilege codes, document-number formats, process sequencing — is ported verbatim; internal mechanics (`double`→`BigDecimal`, auto-DDL→Flyway, springfox→springdoc, `javax`→`jakarta`) are written from scratch.
- **Persistence: PostgreSQL 16 + JPA/Hibernate 6 + Flyway** (ADR-0003, reconciled), with `ddl-auto=validate` from day one and Flyway as the sole owner of schema state. The Flyway baseline `V1__schema.sql` is **authored directly** as PostgreSQL DDL — it is not derived from `mysqldump`.
- **Hidden internal id, public `uid` = ULID** (ADR-0003/0005). Internal PK is `BIGINT GENERATED ALWAYS AS IDENTITY`, **never serialized** and never in a URL. The public `uid` is a **ULID** — a 26-character Crockford base32 string stored as `CHAR(26)`, generated via the `ulid-creator` library — time-ordered (lexicographically sortable) and non-guessable, and is the only identifier in URLs, payloads, events, and cross-module references. A CI-failing ArchUnit gate enforces "no `id` on the surface".
- **Money is the headline correctness fix:** every monetary/quantity/balance field — 96 `double` declarations across 52 legacy entities, **zero** `BigDecimal` — is built as **`BigDecimal`** under one money/numbering policy (ADR-0009): money `NUMERIC(19,2)` / `RoundingMode.HALF_UP`, quantity & coefficients `NUMERIC(19,6)`. The `double → BigDecimal` change is **pre-approved by the client**.
- **AuthN/AuthZ preserved at parity:** Spring Security 6 + self-issued JWT, the existing **User → Role → Privilege** model, the `privileges` claim, and all **177 `@PreAuthorize`** gates carried over verbatim (ADR-0006). The four hardcoded `"secret"` HMAC sites and the dormant `"roles"`-claim refresh defect are corrected; the signing key moves to a secrets store with rotation.
- **14 bounded contexts + a shared kernel** (ADR-0008) replace the implicit, transactionally-entangled legacy domains, with an enforced allowed-dependency graph and a `TxAuditContext` value object that replaces the legacy's 179 scattered `dayService`/`LocalDateTime.now()` stamping calls.
- **Exact-process fidelity is enforced by behavioural golden-master parity** (ADR-0010/0011): the **29 legacy reports** and key workflows are reproduced cent-for-cent against golden-master fixtures, with `double→BigDecimal` trailing-digit differences whitelisted to ±0.01. Because there is no data to migrate, parity is **behavioural** (same inputs → same outputs), **not data reconciliation**.
- **Greenfield data strategy** (ADR-0011): the system starts empty. There is **no ETL**. Reference/master data — the 177 privilege codes, clinic/ward/pharmacy/store types, medicine/lab/radiology/procedure categories, insurance templates, company profile — is **seeded via Flyway migrations** on every fresh deployment.
- **Net-new cross-cutting capabilities** the legacy lacked: append-only audit (ADR-0007), uniform error model & engineering conventions (ADR-0014), observability (ADR-0012), and the new cross-cutting decisions — **file/blob storage** (ADR-0015), **caching** (ADR-0016), **concurrency/locking** (ADR-0017), **background jobs/scheduling** (ADR-0018), **notifications/messaging** (ADR-0019), **facility scoping/tenancy** (ADR-0020), and **i18n/localization** (ADR-0021).

## Context & constraints

The legacy Zana HMIS is a Spring Boot 2.2.5 / Java 11 monolith (`com.orbix.api`, package-by-layer) on **MySQL 5** (`MySQL5InnoDBDialect`) with Hibernate `ddl-auto=update` and **no Flyway/Liquibase** — the schema is whatever Hibernate last inferred, silently auto-evolved over years. Identity is **User → Role → Privilege** with stateless JWT (8-hour access / 24-hour refresh), the HMAC key hardcoded as the literal `"secret"` in four sites (the `jwt.secret=javainuse` property is read by none of them), a `privileges` JWT claim, and **177 `@PreAuthorize`** annotations across 45 resource files. The dominant structural problem is **cross-domain coupling via transactions**: `@Transactional` appears on **114 files** (services and controllers), and single transactions span multiple conceptual domains (e.g., `PatientServiceImpl` injects ~45 collaborators and creates Patient + Bill + Registration + Visit; `GoodsReceivedNoteServiceImpl.approve()` mutates StoreItem stock, stock cards, item batches, purchases, and LPO status across Inventory/Procurement/Purchasing). Financially, **all money and quantities are Java `double`** (96 declarations across 52 entities; **zero** `BigDecimal` anywhere). `ReportResource.java` (1,748 lines, 36+ endpoints, 30+ injected repositories) performs in-memory O(n×m) nested-loop joins across all contexts.

Overriding all of this is the **exact-process mandate**: the modernized system must reproduce existing business processes and outputs (including the 29 reports) faithfully. This is a re-platforming with surgical correctness fixes (money, schema discipline, auditability, identifier hygiene), **not** a redesign of behavior. The single explicitly client-approved behavioral change is `double → BigDecimal`.

**Greenfield / no-migration constraint.** This build does **not** migrate the three years of production MySQL data. The system starts empty and is populated through normal operation. The consequences are deliberate and pervasive: there is no ETL, no `mysqldump`-derived schema, no row-count/financial-totals reconciliation oracle, and no cutover runbook. The legacy DB is consulted only as a *specification source* (to confirm process behaviour and to author golden-master fixtures), never as a *data source*. All timestamps are persisted as `TIMESTAMPTZ` in UTC from V1 — the legacy `DayServiceImpl.getTimeStamp()` UTC+3 wall-clock offset is **not** replicated. Fidelity is therefore proven by **behavioural golden-master parity**, not by reconciling migrated rows.

## Architecture style

The target is a **modular monolith on Spring Modulith** (ADR-0001/0008): a single deployable unit internally partitioned into 14 explicitly-bounded modules whose boundaries, allowed dependencies, and published events are verified at build time by Spring Modulith's `ApplicationModules.verify()` structure tests plus ArchUnit rules. This style is chosen because the exact-process mandate requires that today's cross-domain, single-transaction workflows keep working transactionally and synchronously — which a monolith provides natively via local ACID and microservices would jeopardize (sagas/2PC reintroduced onto ~110 atomic flows). Modulith still forces the legacy's implicit entanglement to be made explicit and policed: each module exposes a `<context>.api` package (interfaces, command/result DTOs, published events) and keeps `@Entity`/repository/impl types package-private in `internal` sub-packages; **no module imports another module's entity**, and cross-aggregate references are by `uid` only. Cross-context side-effects that are *not* mandatory-synchronous flow through Spring Modulith domain events (`@ApplicationModuleListener`), giving an extraction seam for the future without paying distributed-systems costs the project does not need now.

Two legacy hazards are designed out structurally:
- **Consistent stamping.** A `TxAuditContext(dayId, timestamp, userId)` value object is constructed once per logical operation in the orchestrating application service and passed into every module-API command. No `internal` class may call `dayService.getDay()` or `LocalDateTime.now()` directly (ArchUnit-enforced). This replaces the legacy's 179 scattered wall-clock stamping calls.
- **Mandatory-synchronous billing.** `PatientInvoice`/`PatientInvoiceDetail` creation is the insurance-claim record produced at the clinical act, not a side-effect. `billing.api` exposes one command — `recordClinicalCharge(ChargeRequest, TxAuditContext)` — that writes `PatientBill` + `PatientInvoice` (find-or-create) + `PatientInvoiceDetail` in the caller's transaction (`REQUIRED` propagation, never an event). The allowed-dependency graph exists precisely to make this synchronous call legal.

## Module map

Fourteen bounded contexts (ADR-0008) sit on top of a small **Shared Kernel** holding: `PatientRef` (id + uid + MR no), the `Money` value object (`BigDecimal` + TZS), reference/master data, document-numbering, audit and privilege primitives, the `ApiError`/error model, the `AuditableEntity` base (hidden `id`, public ULID `uid`, audit columns, `@Version`), and the mandatory `TxAuditContext`. Dependencies flow toward `shared` and the published `*.api` packages only; no cyclic dependencies are permitted, and any cross-context interaction that is not a mandatory-synchronous transactional call goes through published domain events.

```
                          ┌───────────────────────────────────────────────┐
                          │            PLATFORM (cross-cutting)           │
                          │  IAM/RBAC · Audit · Reporting · Config · Obs  │
                          └───────────────────────────────────────────────┘
                                              ▲
                                              │ (all modules may use)
   ┌───────────────┐   ┌───────────────┐   ┌──┴────────────┐   ┌───────────────┐
   │ Registration  │──▶│   Clinical    │──▶│   Inpatient   │   │   Laboratory  │
   │  & Patient    │   │   (OPD/Enc.)  │   │   & Nursing   │   │               │
   └──────┬────────┘   └──────┬────────┘   └──────┬────────┘   └──────┬────────┘
          │                   │                   │                   │
          ▼                   ▼                   ▼                   ▼
   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
   │   Pharmacy    │   │   Radiology   │   │  Procedures   │   │   Insurance   │
   │ & Dispensing  │   │               │   │  & Theatre    │   │  & Plans/Price│
   └──────┬────────┘   └──────┬────────┘   └──────┬────────┘   └──────┬────────┘
          │                   │                   │                   │
          ▼                   ▼                   ▼                   ▼
   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
   │  Billing &    │◀──│   Inventory   │   │    HR &       │   │    Assets     │
   │  Cashiering   │   │(Stores/GRN/LPO)│  │   Payroll     │   │               │
   └───────────────┘   └───────────────┘   └───────────────┘   └───────────────┘

       ──────────────────────  SHARED KERNEL  ──────────────────────
       AuditableEntity(hidden id, CHAR(26) ULID uid) · Money(BigDecimal/TZS) ·
       PatientRef · DocumentNumber · TxAuditContext · ApiError ·
       reference/master data · privilege & audit primitives
```

| # | Bounded context (`com.zana.hmis.<ctx>`) | Core responsibility | Allowed dependencies |
|---|---|---|---|
| 1 | **registration** | Patient demographics, registration, visit creation, business-day | shared, billing.api, clinical.api, insurance.api |
| 2 | **clinical** | OPD consultations, clinical notes, diagnoses, vitals, prescriptions, orders | shared, registration.api, billing.api, insurance.api |
| 3 | **inpatient** | Admissions, nursing charts, medication administration, fluid balance, discharge | shared, registration.api, billing.api, insurance.api |
| 4 | **pharmacy** | Pharmacy sale orders, dispensing, stock balances, medicine batches | shared, inventory.api, billing.api, insurance.api |
| 5 | **inventory** | Stores, store items, GRN, LPO, purchases, suppliers, stock cards | shared, pharmacy.api (via API/events) |
| 6 | **laboratory** | Lab tests/types, analytes, reference ranges, results, charges | shared, clinical.api, billing.api, insurance.api |
| 7 | **radiology** | Radiology tests/types, results, charges | shared, clinical.api, billing.api, insurance.api |
| 8 | **procedures** | Procedures, procedure types, theatres, charges | shared, clinical.api, billing.api, insurance.api |
| 9 | **billing** | PatientBill, PatientInvoice(+Detail), credit notes, collections, cashier sessions, claims | shared, insurance.api |
| 10 | **insurance** | Insurance plans/providers, price tables, claim eligibility | shared |
| 11 | **hr** | Employees, payroll periods, payroll items/details | shared |
| 12 | **assets** | Asset register and tracking | shared |
| 13 | **iam** *(platform)* | User/Role/Privilege, JWT issuance/validation, `@PreAuthorize` enforcement | shared |
| 14 | **reporting** *(platform)* | The 29 parity reports via JOOQ read models | Read-only via projection/read-model APIs and SQL views |

> The legacy god-transactions are split behind APIs: e.g. GRN approval becomes an `inventory`/`procurement` command updating stock through an explicit in-process API/event boundary rather than reaching into another domain's entities, while remaining within one local transaction where the exact-process mandate requires atomicity. `registration`/Patient is treated as the **last** context to isolate (highest coupling).

## Technology stack

| Layer | Choice | Replaces (legacy) | Rationale |
|---|---|---|---|
| Language / runtime | **Java 21 (LTS)** | Java 11 | Records/sealed types/pattern matching for clean value objects; virtual threads absorb blocking report JDBC (ADR-0002) |
| App framework | **Spring Boot 3.3** | Spring Boot 2.2.5 | Modern Boot baseline, Jakarta namespace, native Modulith support (ADR-0002) |
| Module structure | **Spring Modulith** | Package-by-layer, no boundaries | Build-time boundary verification + ArchUnit, domain events, future extraction seam (ADR-0001/0008) |
| Database | **PostgreSQL 16** | MySQL 5 (`MySQL5InnoDBDialect`) | `GENERATED ALWAYS AS IDENTITY`, `NUMERIC`, partial indexes, `TIMESTAMPTZ`, indexed `CHAR(26)` ULID keys (ADR-0003) |
| ORM | **JPA / Hibernate 6** | Hibernate 5 | Aligns with Boot 3.3 / Jakarta; entities map to `CHAR(26)` ULID `uid` (ADR-0003) |
| Schema management | **Flyway 10** | `ddl-auto=update`, no migrations | `V1__schema.sql` authored directly; `ddl-auto=validate`; ends schema drift (ADR-0003) |
| Identifier | **Hidden `id` (BIGINT IDENTITY) + public `uid` (ULID, `CHAR(26)`)** | `Long id` only, exposed everywhere | `id` never serialized; ULID is time-ordered, 26-char Crockford base32, non-guessable; generated via `ulid-creator`; uid-only surface (ADR-0003/0005) |
| API | **REST + OpenAPI 3 (springdoc), uid-only** | RPC verb-in-path, 575 ad-hoc routes | Contract-first, typed Angular client, correct verbs/status codes, `/{resources}/uid/{ulid}` (ADR-0005) |
| DTO mapping | **MapStruct** | Hand-rolled mappers | Compile-time entity↔DTO mapping; keeps internal `id`/entities off the API surface (ADR-0014) |
| Frontend | **Angular 18+** | Angular 16 | Modern SPA; consumes OpenAPI-generated typed client; retains client-side PDF/Excel rendering (ADR-0004) |
| Security | **Spring Security 6 + JWT** | Custom auth0 filters, hardcoded `"secret"` | Preserve User→Role→Privilege RBAC + `privileges` claim + 177 `@PreAuthorize`; key in secrets store + rotation (ADR-0006) |
| Money / numeric | **`BigDecimal` — `NUMERIC(19,2)` money / `NUMERIC(19,6)` qty** | primitive `double` (96 fields) | Eliminate float rounding; client pre-approved; one money/numbering policy (ADR-0009) |
| Document numbering | **PostgreSQL sequences + format strings** | `SELECT MAX(id)+1` (racy) | Atomic, format-identical (`GRN{yyyyMMdd}-{id}`, `MRNO/{year}/{id}`); SPT→SPTO/PPTO (ADR-0009) |
| Audit | **Explicit append-only `audit_log` (JSONB + hash chain)** | Envers on classpath, zero `@Audited` | Net-new, tamper-evident, in-transaction trail for PHI/financial actions (ADR-0007) |
| Reporting | **Server-side JOOQ assembly; client-side PDF/Excel** | 1,748-line `ReportResource`, in-Java joins | 29 reports at golden-master parity; window functions/CTEs replace O(n×m) (ADR-0010) |
| Error model | **RFC 7807 `ProblemDetail`** | Ad-hoc error shapes | Uniform exception-to-response mapping with structured `ErrorCode` `type` URI (ADR-0005/0014) |
| File / blob storage | **Object storage (S3-compatible) + metadata in PostgreSQL** | Loose `fileName` references | Attachment bytes off-DB; `storage_key` reference; virus-scan on ingest (ADR-0015) |
| Caching | **Spring Cache + Caffeine (Redis deferred)** | No caching | Reference/price lookups served from heap; financial/stock values never cached (ADR-0016) |
| Concurrency | **Optimistic `@Version` default; pessimistic for stock/docs** | None (race conditions) | Safe concurrent stock and document numbering under contention (ADR-0017) |
| Background jobs | **`@Scheduled` + Spring Batch, state in PostgreSQL** | Manual/none | Business-day rollups, scheduled exports, retries with persistent job state (ADR-0018) |
| Notifications | **Pluggable gateway (SMS/email/in-app) with retry** | None | Outbound messaging behind an abstraction with retry semantics (ADR-0019) |
| Facility scoping | **`facilityId` scoping; single schema, multi-facility** | Single-facility implicit | Scope clinical/financial data to a facility without physical schema separation (ADR-0020) |
| i18n | **`messages.properties` + locale variants (e.g. `_sw`)** | Hard-coded English | Externalized strings, locale-aware date/number/currency formatting (ADR-0021) |
| Observability | **Actuator + structured logs + metrics/tracing** | Minimal logging | Health, metrics, correlation/trace IDs for ops (ADR-0012) |
| Testing | **Testcontainers (real PostgreSQL 16)** | H2/none | Integration and parity tests run against a real PostgreSQL 16 in CI, no external DB (ADR-0013) |
| Build / deploy | **Multi-stage Docker, Docker Compose, GitHub Actions, Terraform** | Manual JAR, plaintext secrets | Reproducible CI artifact; one containerized monolith; IaC-provisioned infra; externalized config (ADR-0013) |

## Cross-cutting concerns

- **Identifier hygiene (ADR-0003/0005).** Internal `id` (`BIGINT GENERATED ALWAYS AS IDENTITY`) is never serialized to any DTO, URL, event payload, or response — an absolute rule enforced by a CI-failing ArchUnit gate (no `@PathVariable("id")`, no `{id}` mapping, no `Long id` in DTO packages). Public `uid` is a **ULID** (26-char Crockford base32, stored `CHAR(26)`, generated via `ulid-creator`), the only identifier on the wire and across module boundaries; RESTful routes are `/{resources}/uid/{ulid}`.
- **Auth / RBAC (ADR-0006).** Spring Security 6 validating self-issued HS256 JWTs; the **User → Role → Privilege** model, the `privileges` claim, and all **177 `@PreAuthorize`** gates preserved verbatim (`JwtAuthenticationConverter` with no `SCOPE_`/`ROLE_` prefix). Access 15 min / refresh 8h with rotation + reuse-detection; key sourced from a secrets store (`kid` for rotation). CORS locked to an allow-list. The four hardcoded `"secret"` sites and the `"roles"`-claim refresh defect are eliminated (recorded as defect fixes).
- **Audit (ADR-0007).** A **net-new, explicit, append-only** `audit_log` (JPA `@EntityListeners`, written in the same ACID transaction as the mutation) records who/what/when with `before_state`/`after_state` JSONB, a per-row `checksum`, and a `chain_checksum` for tamper-evidence. Insert-only by DB grant; PHI redacted via a `@PiiField` taxonomy. References entities by `uid`, never internal `id`.
- **Money / decimal (ADR-0009).** A single `Money` (`BigDecimal` + TZS) policy: money columns `NUMERIC(19,2)` with `RoundingMode.HALF_UP` at persistence; quantities and coefficients `NUMERIC(19,6)` (six dp so 1/3-derived coefficients survive without truncation across stock-card balances); `double` banned in the domain. `BigDecimal` serialized as a JSON string on the wire.
- **Numbering (ADR-0009).** Centralized document numbering via PostgreSQL sequences; the legacy id=`no` embedded-number invariant is preserved (insert row → derive `no` from returned identity). Formats unchanged; `SPT` collision resolved to `SPTO` (StoreToPharmacyTO) / `PPTO` (PharmacyToPharmacyTO), pending product-owner sign-off.
- **Error model (ADR-0005/0014).** One uniform error contract and exception-to-response mapping across all modules, surfaced through OpenAPI 3 (RFC 7807 `ProblemDetail` with a structured `ErrorCode` `type` URI so the Angular client reacts programmatically without string-matching); a single `@RestControllerAdvice`.
- **File / blob storage (ADR-0015).** Attachment bytes live in S3-compatible object storage; metadata (`name`, `file_name`, `content_type`, `size_bytes`, `storage_key`, `order_uid`/`order_kind`) lives in a flat relational table (no JSONB) keyed by `uid`. Virus-scan on ingest; PHI-at-rest encryption; no attachment table is authored until ADR-0015 is approved.
- **Caching (ADR-0016).** Spring Cache + Caffeine (in-process L1) for low-mutation/high-read data (reference data, insurance plans, `service-prices`, default currency, company profile, analyte ranges); explicit `@CacheEvict` on `AFTER_COMMIT`. **Financial balances, settlement flags, worklists, and report output are never cached.** Redis is deferred until multi-instance deployment (config-only swap behind the cache abstraction).
- **Concurrency / locking (ADR-0017).** Optimistic locking via `@Version` is the default; pessimistic locking is applied to high-contention paths — stock decrement/dispensing and document-number assignment — to prevent lost updates and duplicate numbers.
- **Scheduling / background jobs (ADR-0018).** `@Scheduled` and Spring Batch run async/scheduled work (business-day rollups, scheduled report exports, notification dispatch) with persistent job state in PostgreSQL and retry semantics.
- **Notifications (ADR-0019).** Outbound SMS/email/in-app delivery goes through a pluggable gateway abstraction with retry; triggered by domain events (`@ApplicationModuleListener`) so dispatch never blocks a transactional flow.
- **Facility scoping / tenancy (ADR-0020).** All clinical and financial data carries a `facilityId`; multi-facility deployments share one physical schema with row-level facility scoping rather than schema-per-tenant.
- **i18n / localization (ADR-0021).** All user-facing validation and message strings are externalized to `messages.properties` with locale variants (e.g. `messages_sw.properties`); a `MessageSource` bean resolves them; date/number/currency formatting is locale-aware. No inline English strings in code.
- **Observability (ADR-0012).** Actuator health/metrics, structured logging, and request **correlation/trace IDs** threaded through modules; cache stats via `/actuator/caches`.
- **PHI.** Patient health information is access-controlled via RBAC, audited on access/mutation (with redaction), facility-scoped, and protected by the conventions in ADR-0014; transport/at-rest protections are an ops/deploy concern (ADR-0013/0015).

## Exact-process fidelity strategy

Fidelity is enforced, not assumed — and because this is a greenfield build with **no migrated data**, it is proven **behaviourally**, not by data reconciliation. **Golden-master / parity testing** (ADR-0010/0011) captures legacy outputs — especially the **29 reports**, plus key transactional workflows (registration+billing, GRN approval, dispensing, invoicing, consultation→bill→invoice→invoice-detail) — and the modernized system, run against equivalent **seeded fixture inputs**, must reproduce them at parity (row-count and column-value equality to the cent). Each context ships a `*ParityIT` suite that is a merge gate. Any intended behavioral deviation must go through an explicit **change-request control gate** with sign-off, so the modernization cannot silently drift from documented legacy behavior.

The single sanctioned exception is the **`double → BigDecimal` risk**: because legacy values were computed in binary floating point with no explicit rounding step, recomputing them in exact decimal can produce *correct* numbers that differ from legacy *rounding artifacts*. The strategy is to (a) fix canonical scale/rounding per field class in ADR-0009 (`NUMERIC(19,2)` money / `NUMERIC(19,6)` quantity, `HALF_UP`), (b) assert parity as `round(legacy_double_total, 2) == round(new_BigDecimal_total, 2)` with a documented ±0.01 tolerance, and (c) record any remaining sub-cent delta on a documented improvement allow-list as a deliberate, approved correctness improvement — never an unreviewed regression. Legacy-analyst must still confirm, per monetary entity, whether each total is recomputed on read or stored at write, before fixtures are encoded.

## Build & data strategy

**Build (ADR-0013).** The backend compiles to a single versioned artifact in CI via a multi-stage Docker build (Temurin 21 JDK → JRE-alpine); the Angular frontend builds separately (node 20 → nginx-alpine), image-tagged by Git SHA. GitHub Actions runs, on every PR and merge: build, unit tests, Testcontainers integration tests (real PostgreSQL 16, no external DB), Spring Modulith `verify()` + ArchUnit boundary tests, Flyway validation, the golden-master/parity suite, SAST (SpotBugs/Find Security Bugs, ESLint), dependency scans (OWASP, `npm audit`), and Trivy image scan. A green pipeline is required to produce a deployable image. `docker compose up` brings the full seeded stack up locally in under five minutes.

**Deploy (ADR-0013).** A single containerized monolith deploys as one runtime unit with externalized configuration (DB credentials, JWT signing key, token lifetimes, cache backend, facility config), Actuator readiness/liveness probes, and structured logs plus metrics/traces wired to the ops stack (ADR-0012). Infrastructure is provisioned via Terraform (IaC). Horizontal scaling is by running multiple stateless instances behind a load balancer — at which point the Caffeine cache is swapped for Redis (ADR-0016).

**Data strategy — greenfield, seeding, no ETL (ADR-0011).** There is **no production-data migration**. The system starts empty; the legacy MySQL database is a specification source, never a data source. There is no `mysqldump`-derived schema, no ETL pipeline, no identifier backfill pass, no row-count/financial-totals reconciliation oracle, and no cutover/rollback runbook. Instead:

- **Schema:** `V1__schema.sql` is authored directly as PostgreSQL 16 DDL — every table has hidden `id BIGINT GENERATED ALWAYS AS IDENTITY`, `uid CHAR(26) NOT NULL UNIQUE` (ULID), audit columns, and `version`. `ddl-auto=validate`; Flyway is forward-only.
- **Reference / master-data seeding (Flyway, non-negotiable):** the **177 `@PreAuthorize` privilege codes** are seeded in a Flyway migration (not a dev-only runner) on every fresh deployment, alongside clinic/ward/pharmacy/store types, medicine/lab/radiology/procedure categories, dosages/routes/frequencies, insurance plan templates, currency, and company profile. The business-day open/close workflow (`Day`) is reproduced (or replaced by a business-date service) pending product-owner confirmation.
- **Parity, not reconciliation:** correctness is demonstrated by behavioural golden-master tests on seeded fixtures, as above — not by reconciling migrated rows.

## ADR index

| ADR | Title | One-line summary |
|---|---|---|
| 0001 | Architecture style — modular monolith (Spring Modulith) | Single deployable with build-time-enforced module boundaries and domain events. |
| 0002 | Backend Platform & Language — Java 21 + Spring Boot 3.3 | Ground-up rebuild on the firm Java 21 / Boot 3.3 stack; verbatim port of proven business logic. |
| 0003 | Database and Persistence — PostgreSQL 16, JPA/Hibernate 6, Flyway (reconciled) | PostgreSQL with Flyway `validate`; hidden `id` BIGINT IDENTITY + public ULID `uid` (`CHAR(26)`); `NUMERIC` precision. |
| 0004 | Frontend platform — Angular 18+ | Angular 18+ SPA consuming an OpenAPI-generated typed client; client-side PDF/Excel retained. |
| 0005 | API style & contract — RESTful + OpenAPI 3, uid-only surface | Contract-first REST; `id` never serialized; `/{resources}/uid/{ulid}`; RFC 7807 `ProblemDetail`. |
| 0006 | AuthN/AuthZ — Spring Security 6, JWT, Preserved Privilege RBAC | Keep User→Role→Privilege RBAC, `privileges` claim, and all 177 `@PreAuthorize`; key in secrets store. |
| 0007 | Audit Trail — Explicit Append-Only (Net-New) | New tamper-evident, in-transaction `audit_log` with JSONB diffs and hash chaining. |
| 0008 | Bounded-context decomposition & module boundaries | 14 contexts + shared kernel; `*.api` boundaries; `TxAuditContext`; mandatory-synchronous billing. |
| 0009 | Monetary, Numeric & Document-Numbering Policy | `BigDecimal`: money `NUMERIC(19,2)`/`HALF_UP`, qty `NUMERIC(19,6)`; sequence-based numbering; SPTO/PPTO. |
| 0010 | Reporting Strategy — Reproduce 29 Reports at Parity | Server-side JOOQ assembly, client-side PDF/Excel; 29 reports verified by golden-master to the cent. |
| 0011 | Data Strategy — Greenfield Start, Reference-Data Seeding, Parity by Golden-Master | No migration/ETL; Flyway-seeded reference data; fidelity proven behaviourally, not by reconciliation. |
| 0012 | Observability & Operations | Actuator health/metrics, structured logging, correlation/tracing. |
| 0013 | Build, Deploy, and Runtime Platform | Multi-stage Docker, Docker Compose, GitHub Actions, Testcontainers, Terraform, externalized config, single containerized monolith. |
| 0014 | Cross-cutting Engineering Conventions | Uniform base entity, MapStruct mapping, transactions, error model, validation, testing baseline, i18n readiness. |
| 0015 | File & Attachment (Blob) Storage | Bytes in S3-compatible object storage, metadata in PostgreSQL, virus-scan on ingest. |
| 0016 | Caching Strategy | Spring Cache + Caffeine (Redis deferred); financial/stock/report data never cached. |
| 0017 | Concurrency & Locking for Stock and Documents | Optimistic `@Version` by default; pessimistic locking for high-contention stock and document workflows. |
| 0018 | Background jobs and scheduling | `@Scheduled` + Spring Batch with persistent PostgreSQL job state and retries. |
| 0019 | Notifications and Outbound Messaging | Pluggable SMS/email/in-app gateway with retry, triggered by domain events. |
| 0020 | Facility scoping & tenancy model | `facilityId` row-level scoping; multi-facility on one physical schema. |
| 0021 | Internationalization & Localization | Externalized resource bundles and locale-aware date/number/currency formatting. |

## Open questions / next phase

- **Module-to-aggregate mapping:** confirm that the 14 contexts cleanly own every aggregate without orphans/overlaps — especially shared/ambiguous data (price/plan tables, stock cards, `Day`) — before V1 schema is finalized.
- **Transaction-boundary inventory:** classify each legacy cross-context flow (the 114 `@Transactional` sites) as **must-stay-in-tx** (e.g., bill/invoice writes) vs **safe-to-async** (e.g., reporting projections, notifications), documented per operation in the application/process services.
- **Money policy per field class:** ratify the per-field money (`NUMERIC(19,2)`) vs quantity (`NUMERIC(19,6)`) bucket for every legacy `double`; confirm no live coefficient/qty exceeds 6 dp; and confirm, per monetary entity, whether totals are stored-at-write or recomputed-on-read (drives parity fixtures).
- **Reporting source-of-truth:** confirm exact definitions, filters, and numeric formatting for the 29 reports so behavioural parity is deterministic; lock the JOOQ read-model/view design and reporting indexes; validate p95 < 3 s on 12 months of seeded data.
- **Reference-data seeding scope:** finalize the authoritative seed set (privileges, type/category lookups, insurance templates, company profile) and the seeding mechanism (Flyway migrations vs first-run wizard) — privilege codes are a hard Flyway requirement.
- **Business-day workflow:** confirm with the product owner whether the `Day` open/close gate is a mandatory business process or replaceable by a configuration-driven business-date service before the Registration context is built.
- **Document numbering:** product-owner sign-off on the `SPTO`/`PPTO` prefix reassignment and confirmation that no external consumer parses the numeric suffix of a `no` back to a row id.
- **Secret & token management:** confirm the production secret store and rotation policy, and whether the 15 min / 8h access/refresh lifetimes are appropriate for clinical sessions.
- **Cross-cutting decisions to finalize for first use:** ADR-0015 (storage backend, PHI-at-rest encryption, scan tooling) must be approved before any attachment table is authored; ADR-0017 (exact lock scope for stock/numbering), ADR-0018 (job catalogue), ADR-0019 (gateway providers), ADR-0020 (facility-scoping enforcement and admin cross-facility access), and ADR-0021 (target locales) each need their first concrete implementation pinned during the relevant context build.
- **i18n locale set:** confirm whether any report requires locale-specific number/date formatting (none observed in legacy) and which locales beyond `en`/`sw` are in scope.
