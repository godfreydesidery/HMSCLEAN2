# Increment 01 — Identity & Access (IAM)

> **⚠️ SUPERSEDED IN PART — read the as-built record first.** This planning doc pre-dates the
> implementation and the legacy discovery, and drifted from the legacy source of truth on several
> points. The **authoritative, ratified, as-built** specification is in
> [`01-iam-discovery/`](01-iam-discovery/) — start with `00-build-spec.md` and `07-DECISIONS-RATIFIED.md`.
> Key corrections (legacy-verified): privilege codes are **35 distinct** (the "177" below counts
> `@PreAuthorize` *sites*, not codes; 26 are live gates, 9 commented-dead, tagged `ACTIVE/DEAD`);
> tables are **plural** (`users`/`roles`/`privileges`…), not `iam_*`; Flyway delta is **V4/V5**; the
> real gate codes are coarse (`USER-ALL`/`ROLE-ALL`/`ADMIN-ACCESS`), not the invented
> `USER-CREATE`/`ROLE-PRIVILEGE`/`PRIVILEGE-ALL`; user code is **`USR-NNN-NNN`**; **`ProviderProfile`
> does not exist** in the legacy — the six personnel extensions (Clinician/Nurse/Pharmacist/Cashier/
> StorePerson/Management) were modelled instead; new users are created **inactive**; the `'ALL'`
> privilege shortcut was **dropped**. The OpenAPI reference is committed at
> [`../../openapi/iam.yaml`](../../openapi/iam.yaml).

## Goal

Deliver a fully working, production-grade authentication and authorization spine — JWT login + refresh with the exact 177 legacy privilege codes seeded and enforced — so that every subsequent increment can gate its endpoints with `@PreAuthorize` from day one and the Angular shell can silently rotate tokens without re-login.

---

## Scope

**Bounded context:** `iam` module (`com.otapp.hmis.iam`), plus the `shared` kernel elements that underpin every other context.

**Key entities / aggregates:**

| Aggregate | Table | Notes |
|---|---|---|
| `User` | `iam_user` | Hidden `BIGINT GENERATED ALWAYS` id, ULID `uid` (`CHAR(26)`), `username` (unique), `BCrypt` password hash, `active` flag, `deceased` guard from later increments wired here |
| `Role` | `iam_role` | `uid`, `name`, M:N to `Privilege` |
| `Privilege` | `iam_privilege` | `uid`, `name` (the CODE string), `description`; 177 rows seeded by Flyway |
| `RefreshToken` | `iam_refresh_token` | `uid`, `user_uid`, SHA-256 hash of opaque token bytes, `expiresAt`, `revokedAt`, `replacedByUid`; one-time-use with reuse-detection |
| `ProviderProfile` | `iam_provider_profile` | Optional 1:1 extension of `iam_user`: specialty, registration number, licence number, licence expiry — used by clinical increments for clinician-affiliation checks |

**Key REST endpoints (all under `/api/v1`):**

| Method | Path | Authority required | Notes |
|---|---|---|---|
| `POST` | `/auth/token` | `permitAll` | Login; returns `{ accessToken, refreshToken, expiresIn }` |
| `POST` | `/auth/token/refresh` | `permitAll` | Rotates refresh token; returns same shape |
| `POST` | `/auth/token/revoke` | Authenticated | Revokes current refresh token |
| `GET` | `/iam/users/uid/{uid}` | `USER-ALL` | Fetch user by ULID |
| `POST` | `/iam/users` | `USER-CREATE` | Create user; returns `Location: /api/v1/iam/users/uid/{uid}` (HTTP 201) |
| `PUT` | `/iam/users/uid/{uid}` | `USER-UPDATE` | Update user (not password) |
| `PUT` | `/iam/users/uid/{uid}/password` | `USER-CHANGEPASSWORD` | Change password (BCrypt re-hash) |
| `PUT` | `/iam/users/uid/{uid}/activate` | `USER-ACTIVATE` | Enable / disable account |
| `GET` | `/iam/roles/uid/{uid}` | `ROLE-ALL` | Fetch role |
| `POST` | `/iam/roles` | `ROLE-CREATE` | Create role |
| `PUT` | `/iam/roles/uid/{uid}/privileges` | `ROLE-PRIVILEGE` | Assign/replace privileges on role |
| `PUT` | `/iam/users/uid/{uid}/roles` | `USER-ROLE` | Assign roles to user |
| `GET` | `/iam/privileges` | `PRIVILEGE-ALL` | List all 177 seeded privileges |
| `POST` | `/iam/provider-profiles` | `EMPLOYEE-ALL` | Create / upsert provider profile |

All admin-user endpoints (`/iam/users`, `/iam/roles`, `/iam/privileges`) are additionally gated by `ADMIN-ACCESS` at the controller class level, layering over per-method privilege codes verbatim from the legacy.

**Process flows implemented (PROCESS.md §12, §14):**

- `§14 User & access` — user account creation, role assignment, privilege wiring; the admin "user-and-access" pages map directly to the CRUD endpoints above.
- `§12 HR / Personnel` — `ProviderProfile` (specialty, registration number, licence) is seeded here so `§3 Doctor` and `§14 Medical units` (clinic-clinician affiliation) can reference it without crossing module boundaries.
- No clinical workflow is in scope; this increment builds the lock-and-key layer only.

**Flyway migrations in this increment:**

| Script | Content |
|---|---|
| `V1__schema_iam.sql` | DDL for `iam_user`, `iam_role`, `iam_privilege`, `iam_user_role`, `iam_role_privilege`, `iam_refresh_token`, `iam_provider_profile`; `shared` kernel tables (`business_days`); `seq_usr_no` for `USR-{000000}` user codes |
| `V2__seed_iam_privileges_and_roles.sql` | All 177 privilege CODE strings inserted into `iam_privilege`; default role definitions (`ADMIN`, `DOCTOR`, `NURSE`, `PHARMACIST`, `LAB_TECHNICIAN`, `RADIOGRAPHER`, `CASHIER`, `RECEPTIONIST`, `STORE_KEEPER`, `PROCUREMENT_OFFICER`, `HR_MANAGER`) seeded with role-privilege join rows |

---

## Dependencies

**Increment 00 (Walking Skeleton) must be complete first.** Increment 00 delivers: the Spring Modulith deployable compiling and running, the `shared` kernel (`AuditableEntity` with hidden id + ULID uid, `Money`, `TxAuditContext`, `BusinessDay`), Flyway baseline (`V1`) with `ddl-auto=validate`, `ApplicationModules.verify()` + ArchUnit gates green, and the CI pipeline (Testcontainers, Modulith verify, Flyway validate, SAST) green. Increment 01 adds its own `V1__schema_iam.sql` on top of that baseline.

No other increments depend on partial IAM. Every increment from 02 onward depends on Increment 01 being complete, because every `@PreAuthorize` gate, every JWT-authenticated request, and every audit `createdBy` stamp requires a working `iam` module.

---

## Exact-process fidelity targets

1. **177 privilege codes — verbatim, every one.** The Flyway `V2` seed must contain the exact CODE strings used in the 45 legacy resource classes (e.g. `GOODS_RECEIVED_NOTE-CREATE`, `BILL-A`, `ADMIN-ACCESS`, `DAY-ACCESS`, `EMPLOYEE-ALL`, `USER-ALL`, `USER-CREATE`, `USER-CHANGEPASSWORD`, `USER-ACTIVATE`, `ROLE-ALL`, `ROLE-CREATE`, `ROLE-PRIVILEGE`, `USER-ROLE`, `PRIVILEGE-ALL`). The parity test (`IamPrivilegeSeedParityIT`) reads the expected list from a fixture file at `src/test/resources/parity/iam/expected-privilege-codes.txt` (177 lines, one code per line, sourced from a grep of the legacy `@PreAuthorize` annotations) and asserts `GET /api/v1/iam/privileges` returns exactly those 177 codes — no more, no fewer.

2. **`privileges` claim on every token, including refresh.** Access tokens issued by both `POST /auth/token` and `POST /auth/token/refresh` must carry `"privileges": [...]` (the flattened, deduplicated array of privilege CODE strings for the authenticated user's roles). The `JwtAuthenticationConverter` sets `authorityPrefix = ""` so `hasAnyAuthority('GOODS_RECEIVED_NOTE-CREATE')` resolves directly. An integration test (`IamTokenClaimsIT`) calls both endpoints and asserts the `privileges` claim is present and non-empty on both.

3. **Token TTL: access 15 min, refresh 8 h.** ADR-0006 tightens the legacy 8 h access to 15 min. The `exp` claim must be asserted in `IamTokenClaimsIT`. No client behaviour from PROCESS.md depends on a specific access TTL (the legacy refresh path was broken in production).

4. **User code format: `USR-{000000}`.** PROCESS.md §14 / legacy-findings.md confirm the format `USR-{000000}` (zero-padded six digits, hyphen-separated, no date). Generated via `seq_usr_no`; `DocumentNumberService` formats with `String.format("USR-%06d", seq)`. Golden-master test: create 3 users sequentially; assert codes are `USR-000001`, `USR-000002`, `USR-000003`.

5. **BCrypt strength 12.** The legacy stored plaintext-equivalent passwords via a weak scheme (legacy-findings.md §Security); ADR-0006 mandates BCrypt strength 12. A unit test (`BCryptPasswordEncoderTest`) asserts `encoder.getStrength() == 12` and that `matches()` returns true for a known pair.

6. **All 177 `@PreAuthorize` gates active from this increment.** The `SecurityFilterChain` must configure `@EnableMethodSecurity(prePostEnabled = true)`. An ArchUnit test (`AuthorizationArchTest`) asserts that every `@RestController` method in the `iam` module that modifies state carries a `@PreAuthorize` annotation, and that `@PreAuthorize` strings contain only codes present in the `expected-privilege-codes.txt` fixture.

7. **CORS locked to allow-list.** `CorsConfigurationSource` reads `${security.cors.allowed-origins}` (comma-separated). Integration test asserts a request from an unlisted origin receives a 403 preflight rejection.

8. **RFC 7807 `ProblemDetail` on all auth failures.** `401 Unauthorized` (invalid/expired token), `403 Forbidden` (missing privilege), and `400 Bad Request` (bad credentials) all return `application/problem+json` with a `type` URI ending in the structured `ErrorCode` value (e.g. `urn:hmis:error:invalid-token`, `urn:hmis:error:access-denied`). No plain-text `error_message` map.

---

## Prior-attempt pitfalls to avoid

- **M (legacy-findings.md §Security — hardcoded HMAC key at 4 sites).** The prior build's equivalent increment would have ported `CustomAuthenticationFilter` and `CustomAuthorizationFilter` verbatim, preserving the hardcoded literal `"<REDACTED>"` at all four confirmed sites. This increment must delete both filter classes in their entirety and use Spring Security 6's `JwtEncoder`/`JwtDecoder` with the key sourced exclusively from `${security.jwt.secret}` (env var in dev via `.env`; CI/prod via secrets store). CI must have a SAST step (e.g. Semgrep rule `detect-hardcoded-jwt-secret`) that fails the build if a hardcoded key literal is detected.

- **Legacy refresh defect (ADR-0006 §Exact-process impact).** The legacy `GET /token/refresh` handler (`UserResource:371`) emitted claim `"roles"` instead of `"privileges"`, silently granting zero authorities on every refreshed session. Do NOT reproduce this. The new `POST /auth/token/refresh` must emit `"privileges"` using the identical `JwtAuthenticationConverter` path as login. The parity test must explicitly assert `privileges` claim presence on refresh tokens and must NOT contain any assertion that models the broken `"roles"` behavior.

- **ADR-0014 §Identifier exposure — `id` must never be serialized.** The prior build's `ClaimDto` and `ClaimLineDto` (referenced in ADR-0014) carried `Long id` fields. All `IamUserDto`, `IamRoleDto`, `IamPrivilegeDto` records must expose only `uid`. The ArchUnit `DtoIdExposureArchTest` (gate: no DTO field of type `Long` named `id`) must be green before merge.

- **ADR-0014 §MapStruct — no hand-coded mappers.** The prior build's `IamMapper` was hand-coded and accumulated nullability bugs. This increment must define a `package-private` MapStruct `@Mapper(componentModel = "spring")` for `User ↔ IamUserDto` and `Role ↔ IamRoleDto`. The ArchUnit mapper rule (mappers package-private, in `application/`, no repository injected) must be green.

- **REG-1 / prior-attempt-lessons.md §Gaps (medium) — membership/insurance-card lookup.** Although patient lookup is not this increment's scope, the `iam_user.username` unique index and the `GET /iam/users/uid/{uid}` endpoint must be designed so Increment 02 (Registration) can do an exact `username` lookup without a cross-module entity import. The `shared` module must export a `UserSummary` read-only projection (uid, username, displayName, active, roles) as the only IAM surface for cross-module consumption.

- **PHARM-1 / prior-attempt-lessons.md — no "select working pharmacy" session.** This is not IAM scope, but the design principle applies here: do not put pharmacy-session state or any context-specific session attribute into the JWT or the `iam` module. The JWT carries only `sub` (username), `iss`, `iat`/`exp`, and `privileges`. All context-specific session selection (pharmacy, store) is a client-side concern carried in request parameters per the ratified architecture.

---

## Lead & supporting agents

| Role | Agent slug |
|---|---|
| **Lead** | backend-engineer |
| **Co-lead / reviewer** | security-architect |
| Supporting | engagement-lead |
| Supporting | solution-architect |
| Supporting | data-architect |
| Supporting | devops-engineer |
| Supporting | frontend-engineer |
| Supporting | qa-test-engineer |
| Gate | code-reviewer |

The engagement-lead signs off the final privilege-code list before `V2` is committed (non-negotiable gate per ADR-0006). The legacy-analyst confirms the 177 codes by grep of the legacy `@PreAuthorize` annotations and delivers the `expected-privilege-codes.txt` fixture.

---

## Definition of Done

- [ ] **Vertical slice end-to-end.** A user can POST to `/auth/token`, receive an access token with a non-empty `privileges` array, call `GET /iam/privileges` with that token and receive 177 codes, and call `POST /auth/token/refresh` and receive a new access token that also carries a `privileges` array — all against a live PostgreSQL 16 (Testcontainers) instance.
- [ ] **Flyway migrations green.** `V1__schema_iam.sql` and `V2__seed_iam_privileges_and_roles.sql` apply cleanly from an empty database; `ddl-auto=validate` passes against the resulting schema.
- [ ] **177 privilege codes seeded and parity-verified.** `IamPrivilegeSeedParityIT` passes: the running application returns exactly the 177 codes from the `expected-privilege-codes.txt` fixture.
- [ ] **Token claims correct on both endpoints.** `IamTokenClaimsIT` asserts `privileges` claim present (non-empty) on tokens from both `/auth/token` and `/auth/token/refresh`; `exp` reflects 15-min access / 8-h refresh TTLs.
- [ ] **Refresh-token rotation.** A second call to `/auth/token/refresh` with the same (already-rotated) refresh token returns `401` with `ProblemDetail type = urn:hmis:error:token-reuse-detected` and revokes all tokens for the user (reuse-detection pattern).
- [ ] **BCrypt strength-12 enforced.** `BCryptPasswordEncoderTest` green; `POST /auth/token` with wrong password returns `401 ProblemDetail`; correct password returns `200`.
- [ ] **`@PreAuthorize` gates enforced.** `POST /iam/users` with a token lacking `USER-CREATE` returns `403 ProblemDetail`; with the correct privilege returns `201`.
- [ ] **User code format.** Sequential create-user test asserts `USR-000001`, `USR-000002`, `USR-000003`.
- [ ] **No `id` exposed.** `DtoIdExposureArchTest` green; no `Long id` field in any DTO.
- [ ] **No hardcoded secret.** SAST step green; no literal secret in any committed file; `.env.example` contains `SECURITY_JWT_SECRET=<replace-with-generated-256-bit-key>` but no real value.
- [ ] **CORS locked.** Preflight from an unlisted origin returns `403`.
- [ ] **RFC 7807 on all auth failures.** `401`, `403`, and `400` responses carry `application/problem+json` with a structured `type` URI.
- [ ] **Spring Modulith boundaries green.** `ApplicationModules.verify()` passes; `iam` module exposes only `UserSummary`, `ProviderSummary` as cross-module read types; no other module's `@Entity` is imported inside `iam`.
- [ ] **ArchUnit gates green.** `DtoIdExposureArchTest`, `AuthorizationArchTest`, `MapperConventionArchTest` all pass.
- [ ] **Angular login shell.** A standalone Angular 18 `LoginComponent` (signal-based form) calls `POST /auth/token` via the OpenAPI-generated client, stores the access token in memory (not `localStorage`), silently calls `/auth/token/refresh` before expiry, and routes the user to a stub home screen. The shell demonstrates the full auth round-trip in the browser.
- [ ] **OpenAPI spec updated.** `springdoc` generates an OpenAPI 3 spec that documents all `iam` and `auth` endpoints with correct security schemes; spec committed to `docs/openapi/iam.yaml`.
- [ ] **Audit events emitted.** `UserCreatedEvent`, `UserPasswordChangedEvent`, `UserRoleAssignedEvent` published via `@TransactionalEventListener(phase = AFTER_COMMIT)` and logged at INFO level with `uid` and `performedBy` (no `id`).
- [ ] **PR merged.** Code-reviewer has approved the PR; all CI checks (build, Surefire, Failsafe / Testcontainers, ArchUnit, Modulith verify, Flyway validate, SAST, OpenAPI lint) are green on `main`.
