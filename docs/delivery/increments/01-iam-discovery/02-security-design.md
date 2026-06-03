I now have everything needed. I have confirmed all the load-bearing facts: the actual config property is `hmis.jwt.*` (not `security.jwt`), the `refresh_tokens` table has only a `revoked BOOLEAN` (no `revoked_at`/`replaced_by_uid`), the CI gate currently only greps `HMAC256` in `src/`, the encoder is already strength 12, the seed has all 35 codes, and `RefreshToken.isUsable()` collapses expired and revoked into one branch.

Here is the increment-01 security delta design.

---

# Increment-01 Security Delta Design — Authentication & IAM Authorization

Reconciles ADR-0006 (`docs/adr/0006-authentication-and-authorization.md`) with the already-built `SecurityConfig` / `AuthenticationService`. Design-only; no code written. Load-bearing file references are absolute.

## 0. State reconciliation (what ADR-0006 says vs. what increment-00 actually built)

Three ADR-0006 statements are STALE against the as-built code; design below targets the as-built reality, not the ADR text:

- ADR-0006 §Impl says config property `${security.jwt.secret}`. **As-built is `hmis.jwt.secret`** bound from `${JWT_SECRET}` (`backend/src/main/resources/application.yml:36-38`, `JwtProperties.java:14`). Use `hmis.*`.
- ADR-0006 §Impl says tables `iam_refresh_token`, `iam_privilege`, `iam_role_privilege`. **As-built tables are PLURAL: `refresh_tokens`, `privileges`, `role_privileges`** (`V1__schema.sql`). The `iam_*` names are stale (MEMORY confirms). Use plural.
- ADR-0006 §Impl says "all 177 `@PreAuthorize` gates / 177 codes". **Verified reality: 35 distinct string literals, of which only 26 are live gates, 9 are commented-only/dead.** The V2 seed already loads all 35 (`V2__seed_iam.sql:26-61`). This design seeds/gates per verified discovery, not the ADR's 177.

---

## 1. CORS lock-down

**Current defect** (`SecurityConfig.java:88-97`): `config.addAllowedOriginPattern("*")` — wildcard origin. With bearer-token auth and `allowCredentials` unset this is not a credential-leak vector, but it is an open-origin exposure and violates ADR-0006 §CORS ("locked to a configured allow-list").

**Design:**
- Add a typed config-properties record `CorsProperties` (prefix `hmis.cors`, field `allowedOrigins List<String>`), registered via `@EnableConfigurationProperties` alongside the existing `JwtProperties`. Keeping it under the existing `hmis.*` namespace is consistent with `hmis.jwt.*`. (The task names the property `security.cors.allowed-origins`; recommend `hmis.cors.allowed-origins` for namespace consistency with the as-built `hmis.jwt.*` — flagged as Deviation D-1.)
- `application.yml` adds:
  ```
  hmis:
    cors:
      allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:4200}
  ```
  Comma-separated env binds to `List<String>` natively in Spring Boot. Dev default `http://localhost:4200` (Angular shell). Real environments inject `CORS_ALLOWED_ORIGINS` per ADR-0013 secrets/config pattern.
- Rewrite the `corsConfigurationSource()` bean:
  - Replace `config.addAllowedOriginPattern("*")` with `config.setAllowedOrigins(corsProperties.allowedOrigins())` (use `setAllowedOrigins`, the exact-match list — NOT `setAllowedOriginPatterns`, which re-permits wildcard patterns).
  - Keep `config.addAllowedHeader("*")` and the explicit method list as-is (those are not the exposure).
  - Do NOT set `allowCredentials(true)` — bearer tokens travel in the `Authorization` header, not cookies; enabling credentials with an allow-list is unnecessary and would tighten preflight needlessly.
- The `.cors(Customizer.withDefaults())` in `securityFilterChain` (`SecurityConfig.java:104`) already picks up the `CorsConfigurationSource` bean — no filter-chain change needed.

**Required test** (`@WebMvcTest`/`MockMvc` slice or full-context): issue an `OPTIONS` preflight with `Origin: https://evil.example` + `Access-Control-Request-Method: POST` and assert the response does NOT contain an `Access-Control-Allow-Origin: https://evil.example` header (Spring returns 403 `Invalid CORS request` for a disallowed origin). A second positive test: `Origin: http://localhost:4200` returns `Access-Control-Allow-Origin: http://localhost:4200`.

---

## 2. Refresh reuse-detection — distinct ProblemDetail type

**Current behavior** (`AuthenticationService.refresh()` `:67-83` + `RefreshToken.isUsable()` `:48-50`): `isUsable()` collapses *revoked* and *expired* into one boolean. On any unusable token the service revokes all the user's live tokens and throws a single `InvalidTokenException("Refresh token is expired or already used")` → maps to `ErrorCode.INVALID_TOKEN` / `urn:hmis:error:invalid-token` / 401. **The reuse case is therefore indistinguishable from plain expiry** — the client (and security monitoring) cannot tell a benign expiry from a token-theft signal.

**Design — make a REUSED (revoked-but-not-yet-expired) refresh token a distinct, separately-typed 401:**

1. **New `ErrorCode` constant** in `ErrorCode.java`:
   ```
   TOKEN_REUSE_DETECTED("urn:hmis:error:token-reuse-detected", HttpStatus.UNAUTHORIZED, "Refresh token reuse detected")
   ```
   (Keep existing `INVALID_TOKEN` for unknown/expired.)

2. **New exception** `TokenReuseException extends HmisException` (carrying `ErrorCode.TOKEN_REUSE_DETECTED`), parallel to `InvalidTokenException.java:4`. No new `@ExceptionHandler` needed — `GlobalExceptionHandler.handleHmis()` (`:28-31`) already maps any `HmisException` via its `errorCode()`. This is the entire `GlobalExceptionHandler` mapping change: nothing, because the base handler is generic. (Confirm: `handleHmis` is matched before the `Exception.class` fallback by Spring's most-specific-type resolution.)

3. **Distinguish the three cases in `refresh()`** by splitting `isUsable()`:
   - Unknown hash (`findByTokenHash` empty) → `InvalidTokenException("Refresh token not recognised")` → `INVALID_TOKEN`.
   - Found, `revoked == true` → **token reuse**: this is the theft signal (a token already rotated away is being presented again). Revoke-all the user's live tokens (existing behavior, `:75-76`), then throw `TokenReuseException`.
   - Found, `!revoked && now >= expiresAt` → expired: throw `InvalidTokenException("Refresh token is expired")` → `INVALID_TOKEN`. (Optionally revoke the single expired row; revoke-all is not warranted for benign expiry.)
   - Found, usable → rotate as today.
   `RefreshToken` gains two read predicates to support the branch (e.g. `isRevoked()` is already the Lombok getter; add `isExpired(Instant now)`), keeping the state machine in the entity.

4. **`replaced_by_uid` / `revoked_at` reference (task item 2).** The task asks the reuse response to "reference `revoked_at`/`replaced_by_uid`." **These columns DO NOT EXIST** in the as-built `refresh_tokens` table (`V1__schema.sql:133-149` has only `revoked BOOLEAN`). To support forensic linkage of a reuse event you need a schema delta:
   - **`V4__refresh_token_lineage.sql`** (next migration is V4+ per MEMORY): `ALTER TABLE refresh_tokens ADD COLUMN revoked_at TIMESTAMPTZ; ADD COLUMN replaced_by_uid VARCHAR(26);` — `replaced_by_uid` is the uid of the token that rotated this one out (nullable; set on rotation), `revoked_at` stamps the revoke. Add `idx_refresh_tokens_replaced_by` if lineage walking is needed.
   - `RefreshToken.revoke()` (`:44-46`) sets `revoked_at = now`; `issue()` (`AuthenticationService.java:85-109`) sets the old token's `replaced_by_uid` to the new token's uid before save.
   - **PHI/leak guard:** these values are for the audit/forensic record and server logs ONLY. They MUST NOT appear in the 401 ProblemDetail body returned to the client — the body stays `{type: urn:hmis:error:token-reuse-detected, title, status:401, detail:"Refresh token reuse detected"}` with no token identifiers. (Per guardrail: no internal identifiers in error responses.) This schema delta is **Deviation D-2** (adds columns not in increment-00; data-architect owns physical schema sign-off).

5. **Audit the reuse event.** On reuse, call `auditRecorder.record("iam.RefreshToken", stored.getUid(), AuditAction.DELETE, actor)` (or a dedicated security-event action) so the revoke-all is captured in the append-only `audit_logs`. This is a security-relevant identity mutation and must be audited (mandate: every identity-record mutation captured).

**Required test:** rotate a token (call refresh once), then call refresh AGAIN with the now-revoked original raw token; assert HTTP 401 with body `type == urn:hmis:error:token-reuse-detected` AND assert all the user's refresh tokens are now revoked. Separate test: an expired-but-never-used token yields `type == urn:hmis:error:invalid-token`.

---

## 3. `POST /api/v1/auth/token/revoke` (authenticated logout)

ADR-0006 establishes rotation + revocation but the legacy has NO revoke endpoint (`hasRevokeEndpoint=false`, verified). Adding one is a defect-closing modernization consistent with ADR-0006's revocation model, not a legacy-behavior change. **Deviation D-3** (new endpoint with no legacy equivalent; engagement-lead sign-off — low risk, supports clean logout).

**Design:**
- Route: `POST /api/v1/auth/token/revoke`, added to `AuthController.java`. NOT in the `permitAll()` list (`SecurityConfig.java:107-116`) → falls under `.anyRequest().authenticated()`, so a valid access token is required (the caller proves identity via the bearer JWT subject).
- Request body: `RevokeRequest(refreshToken: String, @NotBlank)` — the caller submits the refresh token to revoke (the access token authenticates *who*; the body says *which* refresh token).
- Behavior in `AuthenticationService.revoke(String rawRefreshToken, String authenticatedUsername)`:
  1. Hash the raw token (`sha256`), look up by `token_hash`.
  2. **Ownership check:** resolve the token's `user_uid` → user; assert the user's `username` equals the JWT subject (`authenticatedUsername`). If a caller submits someone else's refresh token, throw `AccessDeniedException` → 403 `urn:hmis:error:forbidden` (prevents a logged-in user revoking another user's session). Do NOT 404-vs-403 leak: an unknown token also returns 204 (idempotent) to avoid an oracle (see below).
  3. If found and owned: `revoke()` it (idempotent — already-revoked stays revoked), set `revoked_at`.
  4. Audit: `auditRecorder.record("iam.RefreshToken", token.getUid(), AuditAction.DELETE, username)`.
- Response: `204 No Content`. **Idempotent and non-enumerating** — an unknown or already-revoked token also returns 204 (do not return 404 for unknown tokens; that would be a token-existence oracle).
- Access token is NOT revoked (stateless, validates by signature+exp); it self-expires within ≤15 min. The frontend discards the access token on logout. This is the standard tradeoff for stateless JWT; documented, not a defect.

**Required test:** login → call revoke with the issued refresh token + valid access token → 204; then attempt refresh with that token → 401 (`invalid-token`, since now revoked-then-expired-path... note: revoking then immediately refreshing the SAME token hits the revoked branch = **`token-reuse-detected`** by the §2 logic). Decide and document: a self-revoked-then-reused token is classified as reuse (acceptable — it IS reuse of a dead token). Second test: user B's access token revoking user A's refresh token → 403.

---

## 4. `@PreAuthorize` gates for new IAM management endpoints — VERIFIED LEGACY CODES ONLY

Increment-01 adds the IAM management endpoints (user/role/privilege CRUD) on top of increment-00. Per the verified discovery (Codes report §D/E), the legacy spec codes `USER-CREATE / USER-CHANGEPASSWORD / USER-ACTIVATE / ROLE-PRIVILEGE / USER-ROLE / PRIVILEGE-ALL` **DO NOT EXIST** and must not be used. The legacy gates ALL user/role/privilege management through coarse codes in `UserResource.java`. Canonical mapping for the modern endpoints:

| Modern endpoint (proposed, uid-addressed) | Legacy operation | EXACT `@PreAuthorize` (verbatim legacy codes) | Legacy cite | Notes / deviation |
|---|---|---|---|---|
| `POST /api/v1/iam/users` | create user | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | UserResource.java:146 | exact |
| `PUT /api/v1/iam/users/{uid}` | update user | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | UserResource.java:158 | exact |
| change-password | (no dedicated legacy endpoint) | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | — | folds into update; **no separate code exists** — coarse `USER-ALL`/`ADMIN-ACCESS` governs. If increment-01 exposes a dedicated `PATCH .../password`, it MUST carry the same coarse gate. Inventing `USER-CHANGEPASSWORD` is prohibited. (See D-4.) |
| activate / deactivate | (no dedicated legacy endpoint) | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | — | folds into update; legacy service stubs deactivate (`setActive(true)` forced, `UserServiceImpl.java:126`). A real activate/deactivate is **Ambiguity #1 → change request** (D-5). Gate stays coarse `USER-ALL`/`ADMIN-ACCESS`. |
| `DELETE /api/v1/iam/users/{uid}` | delete user | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | UserResource.java:193 | exact. Legacy `allowDeleteUser` always false → delete always throws (dead). Reproducing-vs-enabling = Ambiguity #2 (D-6). |
| `POST /api/v1/iam/roles` | create role | `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` | UserResource.java:213 | exact |
| `PUT /api/v1/iam/roles/{uid}` | update role | `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` | UserResource.java:269 | exact |
| `DELETE /api/v1/iam/roles/{uid}` | delete role | `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` | UserResource.java:323 | exact (delete also dead in legacy) |
| `POST /api/v1/iam/users/{uid}/roles` | assign role to user | `hasAnyAuthority('USER-ALL','USER-UPDATE','ROLE-ALL','ADMIN-ACCESS')` | UserResource.java:336 | exact — preserve all four codes verbatim |
| `PUT /api/v1/iam/roles/{uid}/privileges` | assign/replace role privileges | legacy code is `ROLE-U` but **COMMENTED OUT** → effectively UNGATED | UserResource.java:444-445 | **SECURITY GAP — see D-7.** Recommend applying `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')`, NOT reproducing the ungated hole. Requires sign-off. |
| `GET /api/v1/iam/privileges` (list, incl. for a role) | list privileges | (no gate — UNGATED in legacy) | UserResource.java:414 | exact: leave ungated (any authenticated user). Already covered by `.anyRequest().authenticated()`. |
| `GET /api/v1/iam/roles`, `/roles/{uid}`, `/users`, `/users/{uid}` | list/get roles & users | (no gate — UNGATED) | UserResource.java (multiple) | exact: authenticated-only, no `@PreAuthorize`. |

**Class-level `ADMIN-ACCESS` layering (task item 4):** The legacy does NOT apply a class-level `@PreAuthorize` to `UserResource`; each method is gated (or not) individually, and `ADMIN-ACCESS` appears as one of the `hasAnyAuthority(...)` alternatives on the *mutating* methods only — the GET endpoints are ungated. Therefore **do NOT add a class-level `@PreAuthorize("hasAuthority('ADMIN-ACCESS')")`** on the modern IAM controller: a class-level gate would retroactively lock the legacy-UNGATED list/get endpoints, changing the authorization result (an exact-process violation). `ADMIN-ACCESS` stays method-level, as one alternative in `hasAnyAuthority(...)`, exactly per the legacy per-method gates. Cross-cutting authentication (must be logged in) is enforced by `.anyRequest().authenticated()` in `SecurityConfig.java:117`, which is the correct layer for the "must be authenticated" baseline.

**Seed alignment:** All 35 codes are already seeded (`V2__seed_iam.sql`). The 9 dead codes (`BILL-A, GOO-ALL, PATIENT-A/-C/-U, PROCUREMENT-ACCESS, PRODUCT-CREATE, ROLE-CREATE, ROLE-U`) are seeded but used by NO live `@PreAuthorize` in the modern build — that matches legacy (they gate nothing there either). Retain for parity; do NOT promote any to a live gate without a change request. **Specifically: `ROLE-U` must NOT be silently activated** on the privileges-assignment endpoint — see D-7.

---

## 5. BCrypt — re-hash decision for the cost-10 admin seed

**Facts:** Encoder is already `BCryptPasswordEncoder(12)` (`SecurityConfig.java:75`). The seeded admin hash `$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy` (`V2__seed_iam.sql:78`) is **cost-10** (the `$10$` segment), a well-known public BCrypt-of-"password". BCrypt verification reads the cost from the stored hash, so login still WORKS at cost-10 — but every admin login runs at 2^10 rounds, not 2^12, until the password is changed.

**Recommendation: RE-HASH the seed to cost-12 via a new migration `V5__rehash_admin_seed.sql`.** Justification:
- The whole point of strength 12 (ADR-0006) is undermined if the bootstrap principal sits at cost-10 indefinitely.
- It is a one-line, deterministic `UPDATE users SET password_hash = '<cost-12 hash of "password">' WHERE username='admin' AND password_hash = '<the cost-10 seed hash>'` — guarded by the exact old hash so it is idempotent and a no-op if already rotated.
- The seed password is the documented dev default "password" (V2 header) — not a real secret — so re-hashing it in a committed migration leaks nothing new (it is already public in V2). Production bootstrap rotates the admin password per-env regardless.
- Alternative (auto-upgrade-on-login via `DelegatingPasswordEncoder` + `upgradeEncoding`): more moving parts, only upgrades on next login, and changes the encoder bean. Rejected for v1 — the static re-hash is simpler and deterministic. Recorded as the chosen approach; no change request needed (it strengthens, does not alter, behavior). This migration is **Deviation-free** (strengthening a dev seed) but listed as informational D-8.

**Test (task item 5):** `BCryptPasswordEncoderTest` must assert `((BCryptPasswordEncoder) passwordEncoder).getStrength() == 12`. As-built there is NO such test (Glob found none) — **this test is a NEW required deliverable for increment-01.** Spec it: load the `passwordEncoder()` bean (or `new BCryptPasswordEncoder(12)` mirror), assert `getStrength()` returns 12; and assert `encode("x")` produces a hash whose cost segment is `$12$`. Add a parity assertion that the seeded admin hash, post-V5, begins `$2a$12$`.

---

## 6. SAST — CI gate failing on any hardcoded JWT/HMAC secret literal

**Current gate** (`ci.yml:26-35`) greps only the literal token `HMAC256` in `src/`. That catches the legacy auth0 pattern but NOT: a raw Nimbus `MACSigner("literal")`, a `SecretKeySpec("literal".getBytes(), "HmacSHA256")`, a `new BCryptPasswordEncoder` seeded with an inline key, or any `secret = "..."` literal. Broaden it.

**Design — add a Semgrep step to the `build-and-test` job (or a dedicated `security-scan` job running in parallel):**
- Add `semgrep` via `returntocorp/semgrep-action` (or `pip install semgrep` + `semgrep ci`). Pin the version.
- Custom rule set committed at `backend/ci/semgrep-secrets.yml` (config, NOT source — allowed; it contains rules, no secrets):
  - Rule `hmis-no-hmac-literal`: pattern matches `new MACSigner("...")`, `Algorithm.HMAC256("...")`, `new SecretKeySpec("...".getBytes(...), ...)` with a string-literal first arg → ERROR.
  - Rule `hmis-no-jwt-secret-literal`: pattern matches assignment of a string literal to any identifier matching `(?i)(secret|signingKey|jwtKey|hmacKey)` → ERROR. The legitimate path (`jwtProperties.secret()` from `${JWT_SECRET}`) is a method call, not a literal, so it passes.
  - Allowlist: test fixtures may use an env-injected secret; the CI already injects `JWT_SECRET` env (`ci.yml:43`), so NO secret literal is needed even in tests. Add a `paths: exclude:` only for generated/`target/` if needed — do NOT exclude `src/test`.
- Keep the existing `HMAC256` grep gate as a fast cheap belt-and-suspenders pre-check; Semgrep is the comprehensive gate.
- Also recommend (devops-engineer): enable GitHub **secret scanning + push protection** at the repo level, and add a dependency-scan step (`mvn org.owasp:dependency-check-maven:check` or Dependabot) and a frontend `npm audit --audit-level=high` — these belong in the secure-SDLC checklist but are out of scope for this specific gate. The hardcoded-secret gate above is the item-6 deliverable.

**Acceptance:** introduce a temporary commit with `String secret = "abc";` in `src/main` → CI fails on the Semgrep step. Remove → passes. (Verification step for qa/devops, not a committed test.)

---

## 7. ProblemDetail coverage — type URIs for 400 / 401 / 403

Confirming the full auth error surface. As-built `ErrorCode.java` already defines all the URNs; the converters in `SecurityConfig.writeProblem` (`:134-141`) and `GlobalExceptionHandler` agree on the same URNs.

| Scenario | Trigger | ErrorCode | `type` URI | HTTP | Where mapped |
|---|---|---|---|---|---|
| Bad credentials (wrong user/pass) | `login()` throws `BadCredentialsException` (`AuthenticationService.java:58,60`) | `INVALID_CREDENTIALS` | `urn:hmis:error:invalid-credentials` | 401 | `GlobalExceptionHandler.handleBadCredentials` `:33-36` |
| Malformed login body (blank username/password) | `@Valid` on `TokenRequest` | `VALIDATION` | `urn:hmis:error:validation` | 400 | `handleValidation` `:48-63` (this is the 400 path) |
| Invalid/unknown/expired refresh token | `InvalidTokenException` | `INVALID_TOKEN` | `urn:hmis:error:invalid-token` | 401 | `handleHmis` `:28-31` |
| **Refresh REUSE (new, §2)** | `TokenReuseException` | `TOKEN_REUSE_DETECTED` | `urn:hmis:error:token-reuse-detected` | 401 | `handleHmis` `:28-31` (no new handler) |
| Missing/garbled bearer on a protected route | OAuth2 resource-server entry point | `UNAUTHENTICATED` | `urn:hmis:error:unauthenticated` | 401 | `SecurityConfig` entry point `:120-128` |
| Authenticated but lacks the privilege | `@PreAuthorize` denies → `AccessDeniedException` | `FORBIDDEN` | `urn:hmis:error:forbidden` | 403 | `SecurityConfig` accessDeniedHandler `:123-130` + `GlobalExceptionHandler.handleAccessDenied` `:43-46` |
| Revoke another user's token (§3) | `AccessDeniedException` | `FORBIDDEN` | `urn:hmis:error:forbidden` | 403 | same as above |

**Confirmed gaps/notes:**
- The "400 bad-credentials" wording in the task is a category mismatch: **bad credentials is 401, not 400** (correctly so — `INVALID_CREDENTIALS` is `HttpStatus.UNAUTHORIZED`). The only auth 400 is malformed-body validation (`VALIDATION`). Confirm with the caller that "400" referred to the validation path.
- **Two error-rendering paths exist** (the filter-level `SecurityConfig.writeProblem` for pre-controller failures, and `GlobalExceptionHandler` for in-controller exceptions). They MUST agree on URNs for the same condition. They do today for unauthenticated/forbidden. **Add `TOKEN_REUSE_DETECTED` only to `ErrorCode`** — it is thrown in-controller (service layer), so it flows through `GlobalExceptionHandler`, not the filter; no `SecurityConfig` change needed for it.
- **PHI/leak guard on all of the above:** every ProblemDetail `detail` must stay generic. `BadCredentialsException` message is the constant `"Invalid username or password"` (`:58,60`) — does NOT echo the submitted username (good; keep it). The reuse 401 must not echo token hashes, `replaced_by_uid`, or `user_uid`. The `instance` is set to `request.getRequestURI()` (`GlobalExceptionHandler.java:77`) — confirm no IAM route puts a username/uid in the path for an *unauthenticated* request (login/refresh paths don't). PASS.

---

## DEVIATION REGISTER (engagement-lead sign-off required)

| ID | Deviation | Type | Recommendation | Sign-off owner |
|---|---|---|---|---|
| **D-1** | CORS property namespaced `hmis.cors.allowed-origins` not the task's `security.cors.allowed-origins` | Naming alignment to as-built `hmis.jwt.*` | Approve `hmis.cors.*` for consistency | solution-architect |
| **D-2** | Add `revoked_at` + `replaced_by_uid` columns to `refresh_tokens` (V4) — not in increment-00 schema | Schema add (forensics for reuse) | Approve; data-architect owns physical schema | data-architect + engagement-lead |
| **D-3** | New `POST /auth/token/revoke` endpoint — no legacy equivalent (`hasRevokeEndpoint=false`) | New feature (clean logout) | Approve; consistent with ADR-0006 revocation model, low risk | engagement-lead |
| **D-4** | If a dedicated change-password endpoint is exposed, it carries coarse `USER-ALL`/`ADMIN-ACCESS` (no fine-grained legacy code exists; `USER-CHANGEPASSWORD` is invented and prohibited) | Exact-process preservation | Approve coarse gate; do not invent code | security-architect (self) + engagement-lead |
| **D-5** | Real activate/deactivate vs. legacy forced-`setActive(true)` stub (Ambiguity #1) | Behavior decision | Needs decision: faithful stub or real toggle | engagement-lead + healthcare-domain-expert |
| **D-6** | User/Role delete is dead code in legacy (`allowDelete*`→false). Reproduce-as-disabled vs. enable real/soft-delete (Ambiguity #2) | Behavior decision | Needs decision | engagement-lead |
| **D-7** | `/roles/{uid}/privileges` is UNGATED in legacy (gate commented out). **Recommend gating `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` as an approved security fix** — reproducing the ungated hole lets any authenticated user rewrite any role's privileges (privilege-escalation). | Security fix vs. exact-process | **Strongly recommend gate.** Requires explicit sign-off as a deliberate deviation. | engagement-lead + security-architect |
| **D-8** | Re-hash cost-10 admin seed → cost-12 (V5) | Strengthening (informational) | Approve; no behavior change | (informational) |

## New deliverables this increment owes downstream
- **data-architect:** V4 `refresh_tokens` lineage columns spec (D-2); V5 admin re-hash migration (D-8).
- **backend-engineer:** `CorsProperties`, `TokenReuseException`, `ErrorCode.TOKEN_REUSE_DETECTED`, `refresh()` three-way branch, revoke endpoint + ownership check, `@PreAuthorize` table §4 verbatim, no class-level `ADMIN-ACCESS`.
- **devops-engineer:** Semgrep secret-scan gate (`backend/ci/semgrep-secrets.yml`), repo secret-scanning/push-protection, dependency scan.
- **qa-test-engineer:** CORS reject test, reuse-vs-expiry 401-type tests, revoke 204 + cross-user 403 test, `BCryptPasswordEncoderTest.getStrength()==12`, ProblemDetail type-URI assertions for the §7 matrix.
- **legacy-analyst:** confirm resolution of Ambiguities #1/#2 (D-5/D-6) and the §4 gate map against extracted legacy data before RBAC model finalization.

## Key file references (absolute)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\iam\config\SecurityConfig.java` (CORS bean :88-97; filter chain; encoder :75; entry/denied handlers :120-130)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\iam\application\AuthenticationService.java` (refresh :67-83; issue :85-109)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\iam\domain\RefreshToken.java` (`isUsable` :48-50)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\shared\error\ErrorCode.java` (add `TOKEN_REUSE_DETECTED`)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\shared\error\GlobalExceptionHandler.java` (`handleHmis` :28-31 — no change needed)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\java\com\otapp\hmis\iam\api\AuthController.java` (add revoke)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\application.yml` (add `hmis.cors.allowed-origins` :36)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V1__schema.sql` (`refresh_tokens` :133-149 — lacks `revoked_at`/`replaced_by_uid`)
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V2__seed_iam.sql` (admin hash cost-10 :78; all 35 codes :26-61)
- `D:\My_Works\HMS\HMSCLEAN2\.github\workflows\ci.yml` (HMAC256 grep gate :26-35 — broaden with Semgrep)
- `D:\My_Works\HMS\HMSCLEAN2\docs\adr\0006-authentication-and-authorization.md` (stale `security.jwt.*` / `iam_*` / 177-codes references — reconciled above)