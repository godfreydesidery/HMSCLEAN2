# ADR-0005: API Style & Contract — RESTful + OpenAPI 3, dual id/uid

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization — fresh build, no data migration

## Context

The legacy Zana HMIS API is RPC-over-HTTP, not REST. 575 HTTP-method annotations across 48 resource files use almost exclusively `@GetMapping` (reads) and `@PostMapping` (every mutation — create, update, approve, verify, state-transition, delete). `@PutMapping`/`@DeleteMapping` appear only in `UserResource` (4 annotations total). Routes are verb-in-path RPC calls: `/goods_received_notes/create`, `/goods_received_notes/approve`, `/goods_received_notes/verify_detail_qty`. HTTP status codes are misused pervasively — `ResponseEntity.created(...)` (HTTP 201) is returned from GET reads and search queries (266 uses across 43 files; confirmed in `GoodsReceivedNoteResource.getVisibleGoodsReceivedNotes()`). Many endpoints accept a full domain entity as `@RequestBody` only to read one field (e.g. a `Clinician` body used for its `id`). Reports and several endpoints serialize bare JPA entities (`List<Consultation>`, `List<LabTest>`) with their entire lazily/eagerly loaded object graph straight onto the wire. Error responses use a hand-rolled `ApiError` body whose only stable signal is a free-text message; an Angular client is forced to regex-match messages to branch on error kind.

This is a greenfield rebuild: modern design, exact process. The new API must be RESTful and contract-first while reproducing the legacy business behaviour unchanged. Identifiers need a public, opaque, URL-safe handle that is decoupled from the database primary key and from the human-facing business document number (the legacy `no`, reproduced verbatim per ADR-0009).

## Decision

**1. REST + OpenAPI 3, contract-first.** A hand-authored OpenAPI 3 contract (springdoc-openapi 2.x) is the single source of truth. The Angular typed client is generated from it (`typescript-angular` openapi-generator); the contract is committed and versioned, and CI fails on drift between contract and controllers.

**2. DTOs on the wire, never entities.** Every request and response body is a purpose-built DTO mapped via MapStruct. JPA entities are never serialized or deserialized at the API boundary. This closes the legacy object-graph-serialization hazard and the "entity-as-request-filter" surface.

**3. `uid` = ULID is the only identifier in URLs and payloads.** `uid` is a 26-character Crockford base32 ULID, stored as `CHAR(26)`. The internal surrogate key `id` (`BIGINT GENERATED ALWAYS AS IDENTITY`) is never serialized and never appears in any URL, request, or response. `uid` is distinct from the human-facing business document number `no` (per ADR-0009): `no` remains a normal response field; `uid` is the addressing key.

**4. URL & verb conventions.** Plural-noun collections; resources addressed as `/{resources}/uid/{ulid}`; sub-resources nested under their parent uid (e.g. `/patients/uid/{ulid}/visits`). Correct verbs and status codes: `GET` (200), `POST` (201 + `Location`), `PUT` (full replace, idempotent, 200), `PATCH` (partial, 200), `DELETE` (204). State transitions become `POST` to a noun action sub-resource (`/approval`, `/verification`) — never a verb path segment. Consistent pagination, filtering, and sorting across all collections; idempotent verbs are honoured as such.

**5. Base path & error model.** All routes live under `/api/v1`; the version segment bumps only on a breaking change. Errors use RFC 7807 `ProblemDetail` (Spring Boot 3 native). A stable `ErrorCode` enum maps onto the `type` URI (e.g. `urn:hmis:error:no-day-open`) so the Angular client branches on a code, not on message text. The legacy `ApiError` body is rejected.

## Considered alternatives

| Option | Verdict |
|---|---|
| ULID (`CHAR(26)`) as the URL/payload uid | **Chosen** — opaque, URL-safe, lexicographically sortable, decoupled from PK and from `no` |
| UUIDv7 as the uid | **Rejected** — time-ordered like ULID but the user directive mandates ULID; ULID keeps a single canonical handle and avoids a second identifier convention |
| Expose internal `id` in URLs/DTOs | **Rejected** — leaks DB internals, enumerable, couples the public contract to the persistence key |
| Keep RPC verb-in-path + POST-for-all | **Rejected** — the legacy anti-pattern; not a process requirement, an API-surface defect |
| Serialize entities directly | **Rejected** — object-graph leakage, no stable contract, N+1/lazy-load hazards |
| GraphQL / gRPC | **Rejected** — overkill for this domain; REST+OpenAPI yields the cleanest generated Angular client |
| Custom `ApiError` body | **Rejected** — forces regex matching on messages; `ProblemDetail` + `ErrorCode` is machine-stable |

## Consequences

**Positive:** A single OpenAPI contract drives a fully typed Angular client, eliminating hand-written HTTP plumbing and message-regex error handling. ULID uids are opaque, sortable, and index-friendly while keeping the internal `id` fully private. Correct verbs/status codes restore HTTP semantics (caching, idempotency, `Location` headers). DTO-only boundaries remove the serialization and over-binding hazards.

**Negative / risks:** Contract-first adds an authoring step and a CI drift-gate. MapStruct mapper coverage must be complete for every DTO; mappers must stay pure (no repository injection / business logic in `@AfterMapping`). The verb-in-path habit is the dominant legacy pattern and will recur in code review — an ArchUnit gate denying verb segments and `{id}`/`@PathVariable("id")` is required. Every legacy RPC route must be mapped to its new RESTful equivalent; the legacy-analyst must confirm the route map so no behaviour is dropped.

## Exact-process impact

The redesign changes the API surface only, not the business process. Verbs, status codes, RESTful paths, ULID addressing, DTO boundaries, and `ProblemDetail` errors are technical corrections with **zero** business-process effect. Every legacy operation — every create, approve, verify, transfer, and state transition — is preserved with identical semantics and identical privilege checks (legacy privilege codes reproduced verbatim in `@PreAuthorize`). The business document number `no` and its formats are unchanged (ADR-0009). What is *not* reproduced are implementation defects: 201-on-GET, POST-for-reads, entity request bodies, and entity serialization.

## Implementation notes

- Stack: springdoc-openapi 2.x, MapStruct 1.6, Bean Validation 3.x; Spring Boot 3 native `ProblemDetail`.
- ULID generation via `ulid-creator` (`UlidCreator.getMonotonicUlid()`); persisted as `CHAR(26)`, unique-indexed; `id` is `BIGINT GENERATED ALWAYS AS IDENTITY`, never mapped into any DTO.
- An `EntityResolver` loads an aggregate by `uid`, returning 404 (`ProblemDetail`) when absent.
- `ErrorCode` enum: each constant carries a stable `type` URI segment; a `@RestControllerAdvice` maps domain exceptions to `ProblemDetail` with the matching `ErrorCode`.
- Money serializes as `BigDecimal` string `{ amount, currency }` per ADR-0009; entities never cross the boundary.
- ArchUnit gates: (1) no `@PathVariable("id")`; (2) no `{id}` in mapping patterns; (3) no `id` field on any class in the `dto` package; (4) verb-segment denylist (`create`, `approve`, `verify`, `delete`, …) in route patterns; (5) MapStruct `@AfterMapping` may not inject repositories or call Spring beans.
- Scoped lookups are primary (e.g. `/clinics/uid/{clinicUid}/clinicians`); unscoped admin-wide listings are gated to admin privileges.
- Cross-ADR ownership: ADR-0009 owns money, document-number formats, and business-date policy; this ADR owns API style, identifier exposure (ULID uid, hidden `id`), and the error contract.
