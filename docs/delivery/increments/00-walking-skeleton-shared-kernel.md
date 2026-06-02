# Increment 00 — Walking Skeleton & Shared Kernel

## Goal

Stand up the complete end-to-end spine — Spring Modulith deployable, shared kernel, PostgreSQL 16, Flyway V1 baseline, Angular 18 shell consuming a generated OpenAPI client, and a fully green CI pipeline — so that every subsequent increment is an incremental business-logic add, not an integration gamble. No clinical or financial process is delivered; the deliverable is a provably-connected, gate-enforced skeleton that demonstrates the full stack working from HTTP request to database row.

## Scope

**Bounded context:** `shared` (kernel module) only; no business-context modules created yet beyond stubs required for `ApplicationModules.verify()` to pass.

**Key entities / aggregates:**
- `AuditableEntity` — `@MappedSuperclass` with hidden `id BIGINT GENERATED ALWAYS AS IDENTITY`, public `uid CHAR(26)` ULID generated via `UlidCreator.getMonotonicUlid()` in `@PrePersist`, `createdAt`/`updatedAt` (`Instant`, `@CreatedDate`/`@LastModifiedDate`), `createdBy`/`updatedBy` (username, `@CreatedBy`/`@LastModifiedBy`), `@Version Long version` for optimistic locking. `id` is private and never mapped into any DTO.
- `Money` — immutable `@Embeddable` value object: `BigDecimal amount` (`NUMERIC(19,2)`) + `String currency` (default `TZS`). `RoundingMode.HALF_UP` at persistence boundary. Paired `MoneyDto { BigDecimal amount, String currency }` and `MoneyMapper` (MapStruct).
- `TxAuditContext` — a plain record `(String dayUid, Instant timestamp, String actorUsername)` constructed once per logical operation and threaded into every module-API command. No Spring bean; no ThreadLocal. Required by ADR-0008 §3 to prevent the 179-scattered-`dayService`-call anti-pattern from recurring.
- `BusinessDay` — `business_days` table: `uid`, `businessDate LocalDate`, `openedAt`/`closedAt Instant`, `status OPEN/CLOSED`. `BusinessDayService.currentUid()` throws `NoDayOpenException` (→ `ProblemDetail`, `urn:hmis:error:no-day-open`) when no open day exists. Every transactional entity in later increments will stamp `businessDayUid` from here.
- `CompanyProfile` — a trivial single-row reference entity (`name`, `address`, `phone`) used as the skeleton's vertical slice. The only entity that exercises the full stack (Flyway DDL → `AuditableEntity` → repository → service → controller → OpenAPI → Angular).

**Key REST endpoints (this increment):**
- `GET /api/v1/company-profile` — returns the seeded profile. Proves HTTP → service → DB round-trip. `@PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")`.
- `GET /actuator/health` — public, proves container health checks.
- `POST /auth/token` — issues a JWT access token (Spring Security 6 Resource Server, HS256, `privileges` claim). Proves auth end-to-end.
- `POST /auth/token/refresh` — rotates the refresh token and reissues access token with `privileges` claim. Proves the defect fix from ADR-0006 is in place from day one.

**Process states / flows:** None from PROCESS.md. This increment has no clinical or financial process. The only "workflow" is the CI pipeline and the `BusinessDay` open/close operator action required before any future transactional write can proceed.

**Flyway migrations delivered:**
- `V1__schema.sql` — DDL for `audit_log` (ADR-0007), `business_days`, `company_profile`, `iam_user`, `iam_role`, `iam_privilege`, `iam_role_privilege`, `iam_user_role`, `iam_refresh_token`. All tables follow the `BIGINT GENERATED ALWAYS AS IDENTITY` + `CHAR(26) uid` pattern.
- `V2__seed_iam.sql` — all 177 `@PreAuthorize` privilege codes seeded into `iam_privilege`, default role definitions, and `iam_role_privilege` join rows. Non-negotiable; must run on every fresh deployment per ADR-0006.
- `V3__seed_company_profile.sql` — one placeholder company-profile row to support the trivial slice.

**Angular shell:** Angular 18 standalone + signals, Angular Material 3, OpenAPI-generator (`typescript-angular`) client generated from the committed `openapi.yaml`. A login page (calls `POST /auth/token`, stores tokens), a company-profile page (calls `GET /api/v1/company-profile`), and a health-status indicator. No business screens.

## Dependencies

None — this is increment 00, the root of the dependency graph. All later increments depend on this one for the shared kernel, `AuditableEntity`, `Money`, `TxAuditContext`, `BusinessDay`, the CI pipeline, and the Angular shell.

## Exact-process fidelity targets

This increment delivers no clinical or financial process, so PROCESS.md section references are minimal. The targets that ARE binding:

1. **ULID uid, hidden id (ADR-0003, ADR-0005, ADR-0014 §1):** An ArchUnit CI test must assert zero occurrences of `@PathVariable("id")`, zero `{id}` in any `@RequestMapping` pattern, and zero `Long id` (or `long id`) fields on any class inside a `dto` package. This gate must be green before the first PR merges.

2. **HMAC256 literal scan (ADR-0013 §3):** CI step `grep -r 'HMAC256' src/` must return zero matches. This is a required check that blocks promotion. Confirmed legacy defect: four HMAC256 literal sites existed in the legacy codebase (legacy-findings.md, security section); none survive in this repo.

3. **`privileges` claim on every token (ADR-0006):** Both `POST /auth/token` and `POST /auth/token/refresh` must emit a JWT with `privileges` (array of code strings), not `roles`. An integration test asserts the claim name on both endpoints. This corrects the legacy `"roles"`-claim defect confirmed at `UserResource:371` (prior-attempt-lessons.md, ADR-0006 context).

4. **No `id` in DTOs (ADR-0003, ADR-0005):** `CompanyProfileDto`, `MoneyDto`, and `BusinessDayDto` must not carry any `Long id` field. ArchUnit gate enforces this.

5. **Money precision (ADR-0009 §2):** `Money.amount` column DDL must be `NUMERIC(19,2)`; quantity columns must be `NUMERIC(19,6)`. A Flyway schema inspection test (via `AbstractIntegrationTest`) asserts column metadata matches these precisions. This is the single source of truth; no other ADR may redefine it.

6. **`BusinessDay` NoDayOpen gate (ADR-0009 §7):** Any endpoint that calls `BusinessDayService.currentUid()` when no open day exists must return HTTP 422 with `ProblemDetail` `type = "urn:hmis:error:no-day-open"`. A unit test covers this branch.

7. **`ApplicationModules.verify()` green (ADR-0001, ADR-0008):** The Spring Modulith boundary verification test must pass on every build. The 14 bounded-context module stubs (empty `package-info.java` with `@ApplicationModule`) must be present and the `shared` kernel must have zero circular dependencies.

## Prior-attempt pitfalls to avoid

- **No `id` in any DTO or cross-module surface (ADR-0014 §1, prior-attempt-lessons.md ADR-0003 note):** The prior build's `ClaimDto`/`ClaimLineDto` carried `Long id` fields. The ArchUnit gate is the hard-stop — it must be wired in CI before any business-logic increment begins. Failing to do this now means every later increment risks the same exposure.

- **No hand-coded mappers (ADR-0014 §3, prior-attempt-lessons.md ADR-0014 note):** The prior build used hand-coded `ClinicMapper`, `IamMapper`, `PatientMapper`. This increment introduces `MoneyMapper` as the canonical MapStruct example. Lombok must precede MapStruct in `annotationProcessorPaths` in `pom.xml`; document this in the repo's engineering notes so it is not accidentally reversed on any future dependency upgrade.

- **No `@Transactional` on controllers (ADR-0014 §5):** Legacy had 114 files carrying `@Transactional`, including controller classes. An ArchUnit rule asserting no `@Transactional` on `@RestController` classes is wired in this increment.

- **`"roles"` claim on refresh path (ADR-0006, prior-attempt-lessons.md security section):** The legacy `GET /token/refresh` at `UserResource:371` emitted claim `"roles"`, making every refreshed session grant zero authorities. This increment establishes `POST /auth/token/refresh` emitting `privileges` from the start. An integration test on both token-issuance endpoints verifies the claim name.

- **Secrets committed (ADR-0013):** `.env.example` is committed with placeholder values; `.env` is `.gitignore`d. The `JWT_SECRET` and `DB_PASSWORD` are consumed only via `${JWT_SECRET}` and `${DB_PASSWORD}` in `application.yml`. The HMAC256 grep gate in CI is the structural enforcement.

- **`ddl-auto=update` (legacy-findings.md):** `spring.jpa.hibernate.ddl-auto=validate` in all Spring profiles, never `update`. Flyway owns schema state. An integration test confirms the Flyway-applied schema passes Hibernate validation on startup.

- **Missing parity/golden-master harness scaffold (delivery strategy §2):** Even though no business process is delivered in increment 00, the `AbstractIntegrationTest` base class and the `*ParityIT.java` naming convention must be established now so every subsequent increment inherits the pattern rather than inventing its own.

## Lead & supporting agents

- **Lead:** solution-architect, devops-engineer
- **Supporting:** backend-engineer, frontend-engineer, data-architect, security-architect, qa-test-engineer, code-reviewer

## Definition of Done

- [ ] Maven multi-module Spring Modulith project compiles cleanly under Java 21 with `./mvnw verify`. All annotation processors (Lombok before MapStruct) are correctly ordered in `pom.xml`.
- [ ] `AuditableEntity` `@MappedSuperclass` present in `com.zana.hmis.shared`; `id` is private and has no getter visible outside the persistence layer; `uid` is populated in `@PrePersist` via `UlidCreator.getMonotonicUlid()`.
- [ ] `Money` value object compiles; `MoneyMapper` (MapStruct) generates correctly; `MoneyDto` carries no `id` field.
- [ ] `TxAuditContext` record is present in `shared`; no Spring bean; no ThreadLocal.
- [ ] `BusinessDay` entity persists to `business_days`; `BusinessDayService.currentUid()` throws `NoDayOpenException` when no OPEN day exists; that exception maps to `ProblemDetail` with `type = "urn:hmis:error:no-day-open"` via `GlobalExceptionHandler`.
- [ ] Flyway `V1`, `V2`, `V3` all apply cleanly to a fresh PostgreSQL 16 container. `spring.jpa.hibernate.ddl-auto=validate` passes after migration.
- [ ] All 177 privilege codes are present in the `iam_privilege` table after `V2` runs. An integration test counts them.
- [ ] `POST /auth/token` and `POST /auth/token/refresh` both return a JWT whose `privileges` claim is a non-empty string array. Integration tests assert this on both endpoints.
- [ ] `GET /api/v1/company-profile` returns the seeded profile when called with a valid token carrying `ADMIN-ACCESS`. Returns HTTP 403 without the privilege. Returns HTTP 401 without a token.
- [ ] ArchUnit tests green: (1) no `@PathVariable("id")`; (2) no `{id}` in route patterns; (3) no `Long id` field on any DTO class; (4) no `@Transactional` on `@RestController`; (5) no `HMAC256` literal in `src/`; (6) `GlobalExceptionHandler` is the single `@RestControllerAdvice`.
- [ ] `ApplicationModules.of(HmisApplication.class).verify()` passes; 14 bounded-context module stubs present with `@ApplicationModule` declarations.
- [ ] `AbstractIntegrationTest` base class established using Testcontainers `@ServiceConnection` with `postgres:16-alpine`; at least one `*IT.java` test exercises the full HTTP → service → DB → audit_log path for `GET /api/v1/company-profile`.
- [ ] `audit_log` table receives exactly one INSERT row on `POST /auth/token` user lookup and on `GET /api/v1/company-profile` access; integration test asserts `actor_username`, `action = 'READ'` (or `CREATE` on login), and non-null `checksum`.
- [ ] Docker Compose (`db`, `migrate`, `backend`, `frontend`) starts cleanly with `docker compose up`; `GET /actuator/health` returns `{"status":"UP"}`.
- [ ] `.env.example` committed; `.env` in `.gitignore`; `application.yml` has no secret literals; `grep -r 'HMAC256' src/` returns zero matches locally and in CI.
- [ ] GitHub Actions CI pipeline green on the PR: build, unit tests, Testcontainers ITs, ArchUnit gates, HMAC256 literal scan, OWASP Dependency-Check, Trivy image scan, `ApplicationModules.verify()`.
- [ ] Angular 18 shell compiles (`npm run build`); login page calls `POST /auth/token`, stores access and refresh tokens; company-profile page calls `GET /api/v1/company-profile` using the generated OpenAPI TypeScript client; both screens render without console errors against the running Docker Compose stack.
- [ ] OpenAPI `openapi.yaml` committed at `src/main/resources/`; springdoc-openapi renders it at `/v3/api-docs`; CI drift gate (`mvn verify -Pcontract-check`) confirms no diff between generated and committed spec.
- [ ] PR approved by code-reviewer; no open comments; branch merged to `main` via PR only (no direct push).
