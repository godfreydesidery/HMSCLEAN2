# ADR-0006: AuthN/AuthZ — Spring Security 6, JWT, Preserved Privilege RBAC

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh greenfield build in d:/My_Works/HMS/HMSCLEAN2, no data migration

## Context

Legacy auth (verified by reading `CustomAuthenticationFilter`, `CustomAuthorizationFilter`, `SecurityConfig`, `UserServiceImpl.loadUserByUsername`) is stateless JWT on Spring Security 5 (`WebSecurityConfigurerAdapter`, `EnableGlobalMethodSecurity`, javax.servlet, auth0 `java-jwt`). The model is **User -> Role -> Privilege**: `loadUserByUsername` flattens every role's privileges into `SimpleGrantedAuthority(privilege.getName())`, the login filter emits a JWT with a **`privileges` claim = array of privilege CODE strings**, and the authorization filter rebuilds those authorities from that claim. Access is gated by **177 `@PreAuthorize("hasAnyAuthority('CODE',...)")` annotations across 45 resource classes** (e.g. `GOODS_RECEIVED_NOTE-CREATE`, `BILL-A`, `ADMIN-ACCESS`, `DAY-ACCESS`, `EMPLOYEE-ALL`). `Privilege` stores only `id` + unique `name` (the code).

**Security defects to correct, not port — all four hardcoded HMAC secret sites must be eliminated:**

The HMAC256 signing key is the hardcoded literal `"secret"` in exactly four source locations (confirmed by reading the legacy files). None of the four reads the `jwt.secret=javainuse` application property — that property is dead:

1. `CustomAuthenticationFilter:76` — login token signing (`Algorithm.HMAC256("secret".getBytes())`)
2. `CustomAuthorizationFilter:73` — inbound token verification (`Algorithm.HMAC256("secret".getBytes())`)
3. `UserResource:352` — refresh-token verification inside the `GET /token/refresh` handler
4. `UserResource:516` — the `getUsernameFromAuthorizationHeader` private helper

**Latent claim-name inconsistency (normalize, do not reproduce):**

The legacy `GET /token/refresh` handler (`UserResource:371`) re-issues the access token with `.withClaim("roles", ...)` instead of `.withClaim("privileges", ...)`. Because `CustomAuthorizationFilter:77` reads only `decodedJWT.getClaim("privileges")`, every access token minted by the legacy refresh endpoint silently grants zero authorities — every `@PreAuthorize` gate denies. This means the refresh path was functionally broken in production. The new system standardizes on `privileges` as the canonical claim name on every token from every endpoint; the `"roles"` claim name is not reproduced anywhere.

Additional legacy facts: access token TTL = 8h, refresh = 24h, no rotation, refresh token carries no privileges. CORS is wide-open (`allowedOrigins("*")`, `allowedHeaders("*")`, `@CrossOrigin(origins="*")`). CSRF disabled; sessions `STATELESS`. The DB-bound `authorizationToken` check is commented out (dead) in both filter files. Device-binding is confirmed absent from the backend.

Per CLIENT MANDATE, the **process and authorization results** must be identical; data types and model may improve. Correcting the `"roles"`-claim defect is a defect fix, not a process change, and is explicitly recorded as such below.

## Decision

Adopt **Spring Security 6.3 (Spring Boot 3.3) as an OAuth2 Resource Server validating self-issued JWTs**, with **BCrypt** password hashing. We **preserve verbatim**: the User->Role->Privilege model, the `privileges` claim (array of privilege CODE strings) on **every access token including those issued by /auth/token/refresh**, and **all 177 `@PreAuthorize("hasAnyAuthority(...)")` gates with their exact codes**. A `JwtAuthenticationConverter` maps the `privileges` claim directly to `SimpleGrantedAuthority` with **no `SCOPE_`/`ROLE_` prefix**.

Token lifecycle: **access token 15 min, refresh token 8h, refresh-token rotation** with server-side revocation (one-time-use, reuse-detection). Dedicated `POST /auth/token` (login) and `POST /auth/token/refresh` replace the legacy filter and `GET /token/refresh` controller endpoint. Signing: **HS256 with a key sourced from a secrets store** (env var in dev, cloud secret manager in prod via devops-engineer) — never hardcoded; `kid` header added to enable rotation. All four hardcoded `"secret"` sites are eliminated: the two legacy filter classes and the `UserResource` refresh handler and private helper are deleted entirely; no hardcoded key survives into the new codebase. Sessions remain `STATELESS`. **CORS locked to a configured allow-list of origins**; CSRF stays disabled (bearer tokens only).

The 177 privilege CODE strings are seeded as reference data by a Flyway migration on every fresh deployment (see Implementation notes). They are not dev-only seeds.

## Considered alternatives

| Option | AuthZ fidelity | Modern fit | Ops cost | Verdict |
|---|---|---|---|---|
| **Spring Security 6 Resource Server + self-issued JWT (chosen)** | Exact (`privileges`->authority) | High | Low | **Adopt** |
| Keep custom auth0 filters, port as-is | Exact | Low (re-implements framework) | Medium | Reject |
| External IdP (Keycloak/Auth0), privileges via token mapper | High but indirect | High | High | Reject for v1 |
| Opaque tokens + DB introspection per request | Exact | Medium | High (not stateless) | Reject |

- **Port custom filters:** reproduces all four hardcoded-key sites and the `"roles"`-claim defect; forgoes framework hardening. Rejected.
- **External IdP:** valuable later for SSO/MFA, but adds infrastructure and privilege-mapping indirection for zero process gain now; revisit post-migration. Rejected for v1.
- **Opaque/introspection tokens:** breaks the stateless model and adds a DB hit per request across 177 gates. Rejected.

## Consequences

**Positive:** Identical authorization decisions on every request; 177 gates compile unchanged. Framework-managed JWT validation replaces hand-rolled filters. Externalized signing key + rotation + short access TTL close all four hardcoded-`"secret"` sites and the 8h-token risk. Locked CORS removes the open-origin exposure. The `"roles"`-claim defect on the refresh path is eliminated: `/auth/token/refresh` now emits a correctly-shaped `privileges` claim, restoring intended RBAC behavior for refreshed sessions.

**Negative / risks:**

- *Migration-time mismatch* — a missing or renamed privilege code silently denies access. **Mitigation:** qa-test-engineer runs a generated authorization parity suite asserting all 177 code->endpoint mappings; Flyway seeds `privileges.name` values verbatim from the canonical list.
- *Shorter access TTL* increases refresh traffic and can surprise long clinical sessions. **Mitigation:** 8h refresh window + silent rotation; ux-ui-designer handles transparent renewal and expiry UX in Angular.
- *Refresh rotation needs server state* (a revocation/refresh-token store) — a slight departure from pure statelessness, confined to the refresh path only; access-token validation stays stateless.
- *`"roles"`-claim defect fix is a behavior change on the refresh path.* Any test or client that currently calls `GET /token/refresh` and then makes an authenticated request will begin receiving correct authorization (previously all `@PreAuthorize` gates denied). **Mitigation:** qa-test-engineer's authorization parity suite must NOT attempt to reproduce the broken `"roles"`-claim behavior; the suite must instead assert `privileges`-claim presence on tokens from both `/auth/token` and `/auth/token/refresh`. This is classified as a **defect fix** in the change-request log.

## Exact-process impact

**Preserves:** the User->Role->Privilege RBAC, the `privileges` claim shape (on every access token), every privilege CODE, and all 177 `@PreAuthorize` gates — authorization decisions are identical for all tokens issued via `/auth/token` and, after the defect fix, for `/auth/token/refresh` as well.

**Defect fix (behavior change vs. broken legacy behavior):** The legacy `GET /token/refresh` handler emitted claim `"roles"` at `UserResource:371` while the authorization filter read claim `"privileges"` at `CustomAuthorizationFilter:77` — meaning every legacy-refreshed access token granted zero authorities and every `@PreAuthorize` gate denied. The new `/auth/token/refresh` emits `"privileges"` consistently. This is a fix of pre-existing broken code; it does not violate the "exact process" mandate because the legacy refresh-then-authorize path was never functionally correct in the field.

**Retire the hardcoded `"secret"` — full scope (all four sites confirmed, none survive):**

1. `CustomAuthenticationFilter:76` — login token signing
2. `CustomAuthorizationFilter:73` — inbound token verification
3. `UserResource:352` — refresh-token verification in `GET /token/refresh` handler
4. `UserResource:516` — `getUsernameFromAuthorizationHeader` private helper

All four are superseded by the new `/auth/token` and `/auth/token/refresh` endpoints backed by the secrets-store key. The `GET /token/refresh` controller endpoint, both legacy filter classes, and the private helper are deleted entirely.

**legacy-analyst must confirm:** (1) device-binding is truly unused at the Angular layer (fingerprint libs bundled but unwired); (2) no deployed client depends on the literal 8h access lifetime; (3) the full canonical list of distinct privilege codes for the parity matrix and seed migration.

**Change-requests implied:** retire all four hardcoded-`"secret"` sites; record the `"roles"`-claim refresh defect fix in the change-request log. The `jwt.secret=javainuse` property is dead and should be removed from any configuration template carried forward.

## Implementation notes

- **Stack:** `spring-boot-starter-oauth2-resource-server` + `spring-boot-starter-security` (Security 6.3, jakarta.servlet). Replace `WebSecurityConfigurerAdapter` with a `SecurityFilterChain` bean; `EnableGlobalMethodSecurity` -> `@EnableMethodSecurity(prePostEnabled=true)`. Keep all `@PreAuthorize` strings byte-for-byte identical to the legacy codes.

- **Authorities mapping:** `JwtAuthenticationConverter` + `JwtGrantedAuthoritiesConverter` reading claim `privileges`, `setAuthorityPrefix("")` so `hasAnyAuthority('GOODS_RECEIVED_NOTE-CREATE')` resolves directly. The converter is applied identically to tokens from both `/auth/token` and `/auth/token/refresh` — no special-casing.

- **Token issue:** Nimbus `JwtEncoder`/`JwtDecoder` with `OctetSequenceKey` (HS256, `kid`), key bytes from `${security.jwt.secret}` bound to a secrets manager (never a hardcoded literal, never the dead `jwt.secret` property). Access token claims: `sub`=username, `iss`, `iat`/`exp`, `privileges` array. Refresh token = opaque random bytes, SHA-256 hashed at rest in a `iam_refresh_token` table keyed by user `uid`, rotated on use with reuse-detection triggering revoke-all. User references use the public `uid` (ULID, `CHAR(26)`); internal `id` is never serialized into any token payload or response body.

- **Endpoints:** `POST /auth/token`, `POST /auth/token/refresh` — `permitAll()`; everything else `authenticated()`. Delete `CustomAuthenticationFilter`, `CustomAuthorizationFilter`, and `UserResource.GET /token/refresh` plus `getUsernameFromAuthorizationHeader`. User/role/privilege admin resources addressed by **uid** per ADR-0005 conventions (`/iam/users/uid/{uid}`); internal `id` never exposed.

- **Privilege seeding (non-negotiable):** All 177 privilege CODE strings plus the default Role definitions must be seeded by a Flyway migration (e.g. `V2__seed_iam_privileges_and_roles.sql`) that runs on every fresh deployment. They are not dev-only data. The migration populates `iam_privilege(name)` and `iam_role_privilege` join rows. The qa-test-engineer's authorization parity suite loads the expected code list from this migration and validates it against the running application. Any privilege omitted from the migration will silently deny access to the corresponding `@PreAuthorize` gate.

- **ProviderProfile:** Clinician identity extensions (specialty, registration number, licence number, licence expiry) live in `iam_provider_profile`, an optional 1:1 extension of `iam_user` within the `iam` module. This keeps clinician identity co-located with the security principal and avoids a cross-module dependency from the `encounter` module into `iam` for basic user lookups.

- **Module boundaries:** The `iam` module is a dependency of `masterdata` (for clinic-clinician affiliation checks) but not vice versa. No other module may write to `iam` tables. The `iam` module publishes read-only views (`UserSummary`, `ProviderSummary`) for cross-module consumption via Spring Modulith's allowed-dependency graph, verified by `ApplicationModules.verify()` in the test suite from day one.

- **CORS:** `CorsConfigurationSource` bean with a configured origin allow-list from `${security.cors.allowed-origins}`; remove all `@CrossOrigin(origins="*")` from every class. **Passwords:** `BCryptPasswordEncoder` (strength 12). Do **not** revive the commented `authorizationToken` DB binding. **Error responses:** all auth failures return RFC 7807 `ProblemDetail` with a structured `type` URI (e.g. `https://hmis.otapp.net/problems/auth/invalid-token`) per ADR-0009, not a raw `error_message` map string.

- **QA guidance:** The authorization parity suite must assert `privileges` claim presence and correct authority grants for tokens from **both** `/auth/token` and `/auth/token/refresh`. It must not model the legacy `"roles"`-claim behavior — that path was broken and is corrected by this ADR. The suite derives its expected privilege list from the Flyway seed migration, not from a hardcoded array in test code, so any future privilege addition automatically extends coverage.
