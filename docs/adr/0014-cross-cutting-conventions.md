# ADR-0014: Cross-cutting Engineering Conventions

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

---

## Context

This is a greenfield build in `d:/My_Works/HMS/HMSCLEAN2`. The prior modernization attempt (`hmis-engine-api`, `com.otapp.hmis.engine`) is reference material only. Some of its choices are kept (ULID for the public identifier was correct); others — `BIGSERIAL id` exposed in DTOs, hand-coded mappers, the custom `ApiError` body, and an `AuditableEntity` that serializes `id` to clients — are explicitly NOT adopted. Both kept and rejected choices are documented here so engineers know which to replicate.

Fourteen bounded contexts must be implemented in parallel. Without a shared baseline, teams will diverge on entity keying and exposure, mapping strategy, transaction placement, error shape, money precision, and test structure. The legacy system (`com.orbix.api`) exhibits every failure mode that results from the absence of such conventions:

- 115+ domain classes, no base class, no uid — every `Long id` PK serialized directly to clients via bare entity returns in 51 `*Resource` classes.
- `double` across 60+ monetary and quantity fields; zero `BigDecimal` in the codebase.
- 114 files carry `@Transactional`, including controller classes.
- `ReportResource` (1,748 lines) returns bare domain entities with full JPA-loaded object graphs.
- HTTP 201 returned for GET read queries in 266 places across 43 files.
- No exception-handling strategy; no stable error body contract.

The engagement-ratified decisions are authoritative: `id` is a hidden implementation detail — it MUST NOT appear in any DTO, response body, URL, or cross-module payload; `uid` (a ULID) is the only public identifier.

---

## Decision

All fourteen bounded contexts MUST conform to the following conventions. Each sub-decision is **mandatory** unless marked advisory.

### 1. Dual-identifier base entity — id hidden, uid public (MANDATORY)

Every persistent JPA entity MUST extend a `@MappedSuperclass` base entity that provides:

- **`id`** — `BIGINT GENERATED ALWAYS AS IDENTITY`, the internal database PK. Declared `private` with no DTO getter. Exposed ONLY within the persistence layer for JPA joins and FK navigation. It MUST NOT appear in any DTO, response body, URL, or cross-module payload.
- **`uid`** — a **ULID**: 26-character Crockford base32, stored as `CHAR(26)`, `NOT NULL`, `UNIQUE`, `updatable = false`. Generated in a `@PrePersist` callback via `ulid-creator`'s `UlidCreator.getMonotonicUlid()`. This is the ONLY identifier that appears in URLs, API payloads, events, or any cross-module surface. ULID is chosen because it is URL-friendly, lexicographically sortable, and matches the prior build's proven choice.
- **`createdAt`**, **`updatedAt`** — `@CreatedDate` / `@LastModifiedDate`, type `Instant`, stored as `timestamptz` in UTC.
- **`createdBy`**, **`updatedBy`** — `@CreatedBy` / `@LastModifiedBy`, principal username from `SecurityContextHolder`.
- **`version`** — optimistic locking, `@Version Long`.

**Identifier exposure rules — absolute and non-negotiable:**

- `id` MUST NOT appear in any DTO field, HTTP response body, event payload, or URL path segment. There are no exceptions. The prior build's `ClaimDto`/`ClaimLineDto` carrying `Long id` are rejected — do not replicate them.
- `uid` is the ONLY stable reference across all API surfaces: URL paths use `/{resources}/uid/{resourceUid}`; events carry `uid`; cross-module and client-side joins use `uid`.
- An ArchUnit CI test asserts: no `@PathVariable` named `"id"`; no request-mapping pattern containing `{id}`; no DTO field of type `Long` named `id`. This is a hard merge gate.

Repositories implement `findByUid(String uid): Optional<T>`.

### 2. Package-by-context layout (MANDATORY)

The package root is `com.otapp.hmis.<context>`. Within each context:

```
<context>/
  api/            -- @RestController classes only; no business logic
  application/    -- @Service, DTOs (Java records), MapStruct mapper interfaces
  domain/         -- @Entity, repositories, domain services, enums, value objects
  infrastructure/ -- generators, adapters, context-specific configuration
```

Shared cross-cutting code lives in `com.otapp.hmis.common.*`. The legacy `com.orbix.api` package-by-layer structure is retired. Module structural verification runs on every build and MUST remain green.

### 3. DTO mapping — MapStruct (MANDATORY)

**MapStruct `@Mapper` interfaces are the mandatory default for all contexts** (NOT hand-coded mappers). The prior build's hand-coded `ClinicMapper`/`IamMapper`/`PatientMapper` accumulated denormalisation logic and nullability bugs that MapStruct's compile-time generation prevents. Lombok must precede MapStruct in `annotationProcessorPaths`.

Each `@Mapper` is `package-private`, placed in `<context>/application/`, and uses `componentModel = "spring"`. **No business logic in mappers**: any lookup (e.g. resolving a username from a `userUid`) is performed by the service layer before invoking the mapper; the mapper receives a fully-resolved source object. **No entity object MUST ever leave the `application` or `domain` layer** — controllers receive and return DTOs only. Inbound DTOs reference other entities by `uid`; the service resolves uid-to-entity internally.

### 4. Input validation (MANDATORY)

All externally-supplied `@RequestBody`/`@PathVariable` parameters MUST be validated with Jakarta Bean Validation 3 annotations (`@NotBlank`, `@NotNull`, `@Size`, `@Pattern`, `@DecimalMin`, etc.) plus `@Valid` on the controller parameter. Constraint messages reference `messages.properties` keys via `{key}` syntax — no inline English strings. Business-rule violations are typed domain exceptions (`ConflictException`, `BusinessRuleException`, `NotFoundException`), each carrying an `ErrorCode` enum value (see §6).

### 5. Transaction boundaries (MANDATORY)

`@Transactional` is placed on **service methods only** — never on controllers or mappers. Every write method is `@Transactional`; read-only methods use `@Transactional(readOnly = true)`. Cross-module atomic operations use one of two patterns:

1. A single application-layer service method owning the transaction, invoking each context's domain service within the same boundary — acceptable only when contexts share a JVM module and the dependency direction is legal per ADR-0011.
2. `@TransactionalEventListener(phase = AFTER_COMMIT)` for seeding dependent aggregates downstream (registration-fee invoice after patient registered; consultation-fee invoice after consultation booked; settlement flag propagated billing→encounter).

The transaction MUST NOT be held open across an HTTP round-trip, scheduled-task boundary, or published-event boundary.

### 6. Centralized exception handling — RFC 7807 ProblemDetail (MANDATORY)

A single `@RestControllerAdvice` class `GlobalExceptionHandler` (in `common/api/`) is the ONLY exception handler; no `@ExceptionHandler` is permitted in controllers. The response body is Spring's `ProblemDetail` (RFC 7807 `application/problem+json`):

- `type` — a URI whose last path segment is the machine-readable `ErrorCode` enum value (e.g. `https://hmis.otapp.net/problems/patient-not-found`). This replaces the prior build's string-matched `ApiError.message` approach.
- `title` — human-readable summary, sourced from `messages.properties`.
- `detail` — debugging context (not user-facing in production).
- `errors[]` — for validation failures: array of `{field, code, message}`.

The prior build's custom `ApiError` record is **rejected**. HTTP status codes MUST be semantically correct: 200 GET/PUT, 201 POST with `Location: /api/v1/{resource}/uid/{uid}`, 204 DELETE, 400 validation, 401 unauthenticated, 403 forbidden, 404 not found, 409 conflict, 422 business rule, 500 unexpected. The legacy misuse of 201 for GET is prohibited.

### 7. Money and quantity types — single source: ADR-0003 and ADR-0009 (MANDATORY)

Precision is defined authoritatively in ADR-0003 (DDL) and ADR-0009 (Java types, rounding). This ADR mandates compliance only:

- **Money:** `Money` value object wrapping `BigDecimal`; `@Column(precision=19, scale=2)`; DDL `NUMERIC(19,2)`; `RoundingMode.HALF_UP`. See ADR-0009 §3–§4.
- **Quantity / coefficient:** `BigDecimal`; `@Column(precision=19, scale=6)`; DDL `NUMERIC(19,6)`. See ADR-0009 §2.
- Integer-only stock fields: `INTEGER`.

Any precision in this ADR that differs from ADR-0003/ADR-0009 is an error here; those ADRs take precedence. No `double` field is permitted anywhere.

### 8. i18n readiness — baseline only; detail deferred to ADR-0021 (MANDATORY baseline)

All user-facing validation and error messages MUST be externalized to `src/main/resources/messages.properties` (with locale variants, e.g. `messages_sw.properties`). `ProblemDetail.title` and validation messages are populated via `MessageSource`. No hard-coded English strings in code. Full localization, locale negotiation, and the Swahili workflow are deferred to ADR-0021.

### 9. Null safety (MANDATORY)

Public service interfaces, controller signatures, and DTO factory methods MUST document nullability via `@NonNull`/`@Nullable`. Query methods that may return nothing use `Optional` (`findByUid(String uid): Optional<T>`). Raw `null` returns are prohibited; use `Optional` or a typed exception.

### 10. Testing baseline (MANDATORY)

- **Unit tests** (`*Test.java`): plain JUnit 5, no Spring, no DB — domain logic, validators, MapStruct output, calculations.
- **Integration tests** (`*IT.java`): full Spring context via `AbstractIntegrationTest`, real PostgreSQL 16 via Testcontainers (`@ServiceConnection`); HTTP exercised through `MockMvc`/`TestRestTemplate`; state reset per test.
- **Parity / golden-master tests** (`*ParityIT.java`): assert new output matches legacy fixtures for billing, numbering, and reports; trailing-digit differences beyond scale=2 are allow-listed.

---

## Considered alternatives

| Convention | Alternative considered | Rejected because |
|---|---|---|
| **ULID (`CHAR(26)`) for uid** | UUIDv7 / native `uuid` | ULID is URL-friendly, lexicographically sortable, and matches the prior build's proven choice. UUIDv7/native `uuid` is the rejected alternative for this build. |
| `id` hidden everywhere | `id` permitted in intra-module DTOs | The prior build's `ClaimDto`/`ClaimLineDto` "client-side row-keying" pattern is rejected. An authenticated client has no legitimate need for sequential ids; `uid` is the stable reference and avoids insert-order enumeration leakage. |
| MapStruct as mandatory default | Hand-coded static mappers | Hand-coded `ClinicMapper`/`IamMapper`/`PatientMapper` grew denormalisation bugs and drifted silently when entities evolved. MapStruct's compile-time generation is auditable and drift-free. |
| ProblemDetail (RFC 7807) | Custom `ApiError` record | `ApiError` (the prior build's choice) is rejected. `ProblemDetail` is Spring Boot 3.x native, integrates with springdoc, and exposes a structured `type` URI for programmatic frontend handling without string matching. |
| Service-level `@Transactional` | Unit-of-work / explicit transaction manager | Spring declarative transactions are idiomatic and sufficient at this scale; UoW adds complexity with no benefit. |
| Jakarta Validation on request records | Fluent validator objects | Bean Validation integrates with Spring MVC automatically and is the established idiom. |
| messages.properties i18n baseline | Inline strings, localize later | "Localize later" means never; externalization is a one-time structural choice, free upfront and impractical to retrofit across 115+ entities. |

---

## Consequences

### Positive

- Every engineer orients within any context immediately: layout, base entity, error handling, mapper pattern, and tests are identical everywhere.
- The uid-only rule eliminates ALL legacy id-enumeration vulnerabilities with no carve-outs; ULID's lexicographic ordering keeps `uid` indexes range-friendly.
- `ProblemDetail` with typed `ErrorCode` URIs lets the Angular frontend branch on codes programmatically, eliminating the prior `if (error.message.match(/registration fee/i))` pattern.
- MapStruct's compile-time generation guarantees mapper fidelity at build time.
- Single-sourcing money/quantity precision to ADR-0003/ADR-0009 eliminates the prior cross-ADR conflict.
- Service-level `@Transactional` makes boundaries explicit and removes the legacy controller-transaction anti-pattern.
- The three-tier test baseline means every context ships integration-tested and parity-verified from its first merge.

### Negative / Risks

- **MapStruct processor ordering must be maintained.** Lombok must precede MapStruct in `annotationProcessorPaths`; a misorder yields silent compilation failures. Review `pom.xml` on any dependency upgrade.
- **Cross-context transactions remain complex.** Decomposing the legacy `PatientServiceImpl` (one transaction spanning every context) relies on the `@TransactionalEventListener` after-commit seam; a seeded aggregate will not roll back if the listener fails after the outer commit, so idempotent retries / compensating actions are required.
- **ArchUnit id-in-DTO rule** must match field name `"id"` precisely, not `Long` type alone, to avoid false positives on legitimate `Long` fields.
- **messages.properties discipline** erodes without tooling; an ArchUnit rule flagging user-facing literals in `throw new` outside `messages.properties` is recommended.
- **Testcontainers startup time:** mitigate with `withReuse(true)` locally and TC Cloud in CI.

---

## Implementation notes

**Ratified stack:** Spring Boot `3.3.x`, Java `21`, Spring Modulith `1.2.x`, MapStruct `1.6.x` (`componentModel = "spring"`, no hand-coded mappers), Testcontainers `1.20.x` (`postgres:16-alpine`), springdoc-openapi `2.6.x`.

**Identifier generation:** `uid` is a ULID via `ulid-creator` `UlidCreator.getMonotonicUlid()`, stored `CHAR(26) NOT NULL UNIQUE`, `updatable = false`, generated in `@PrePersist` if null. `id` is `BIGINT GENERATED ALWAYS AS IDENTITY` and is never serialized. Do NOT use native `uuid`/UUIDv7 — that is the rejected alternative.

**Enforced structural rules:**
1. The dual-identifier base entity is the only `@MappedSuperclass`; no entity extends anything else.
2. `id` MUST NOT appear in any DTO field, event payload, response body, or URL path segment — the ArchUnit test is the gate.
3. `GlobalExceptionHandler` is the single `@RestControllerAdvice`.
4. `AbstractIntegrationTest` is the single base class for all `*IT` tests; no IT may use a non-containerized DB.
5. A `messages.properties` entry is required for every user-facing string.
6. All `@Mapper` interfaces are `package-private`, in `<context>/application/`, `componentModel = "spring"`, with no business logic.

**For devops:** CI runs `./mvnw verify` including ITs (Docker socket required); the ArchUnit tests (`ApiContractArchTest`, `DtoIdExposureArchTest`) run in the Surefire phase and fail the build on violation.

**For qa:** parity tests are `<Context><Scenario>ParityIT.java`; fixtures in `src/test/resources/parity/<context>/`; monetary assertions use `assertMoneyEquals` rounding both to scale=2; the allow-list lives in `src/test/resources/parity/allow-list.json`.
