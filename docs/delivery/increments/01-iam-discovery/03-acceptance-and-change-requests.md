The spec doc and the actual built state are confirmed. The migrations are V1/V2/V3 (next is V4+), tables are plural snake_case, and the spec's claims (177 codes, `iam_*` tables, `USR-{000000}`, invented gate codes, 11 default roles) are all drifted. I have everything I need. Here is the deliverable.

---

# PART 1 — ACCEPTANCE CRITERIA (Increment 01 — Identity & Access)

Conventions: every AC is independently verifiable by `qa-test-engineer` without legacy source access. Gate codes are the VERIFIED legacy codes only. Tags: `[SAFETY-CRITICAL]` (auth/financial integrity), `[PHI/PII]` (data-classification note required), `[NEW-SCOPE]` (deviation — see CR register).

---

## AC-IAM-01 — Login (issue access + refresh) `[SAFETY-CRITICAL]`
Endpoint: `POST /api/v1/auth/token` — `permitAll`. Data classification: credentials (PII); never log password or token values.

- **AC-01.1 (happy)** GIVEN a seeded active user with a known username/password WHEN `POST /auth/token` with valid credentials THEN `200` and body `{accessToken, refreshToken, expiresIn}`; access token is a JWT whose `privileges` claim is the flattened, deduplicated array of the user's roles' privilege codes; `sub`=username.
- **AC-01.2 (claim name)** THEN the authorities claim is named **`privileges`** (NOT `roles`). This is a verified deliberate divergence from legacy (legacy emitted defective `roles`); see CR-08.
- **AC-01.3 (TTL)** THEN access `exp` − `iat` = 15 minutes; refresh token lifetime = 8 hours. (Divergence from legacy 8h access / 24h refresh — see CR-09.)
- **AC-01.4 (bad credentials)** WHEN password is wrong THEN `401` `application/problem+json`, `type` ends in a structured error code (e.g. `urn:hmis:error:unauthenticated`); body contains no stack trace and no echo of the submitted password.
- **AC-01.5 (inactive user)** GIVEN `active=false` WHEN login THEN `401` ProblemDetail; no tokens issued. (Legacy `loadUserByUsername` builds UserDetails with the `active` flag; verify exact enabled-account semantics with `legacy-analyst` before asserting the precise message — see Query Q1.)
- **AC-01.6 (no-id)** THEN no response field named `id` of type numeric is present anywhere in the body.

## AC-IAM-02 — Refresh with rotation `[SAFETY-CRITICAL]`
Endpoint: `POST /api/v1/auth/token/refresh` — `permitAll`.

- **AC-02.1 (rotation)** GIVEN a valid unrevoked refresh token WHEN `POST /auth/token/refresh` THEN `200` with a NEW access token AND a NEW refresh token; the presented refresh token is revoked (single-use). The new access token carries a non-empty `privileges` claim (same converter path as login).
- **AC-02.2 (no legacy defect)** THEN the refreshed access token uses claim `privileges` and is accepted by `@PreAuthorize` gates (legacy refresh emitted empty-authority `roles`; this defect must NOT be reproduced — CR-08).
- **AC-02.3 (expired refresh)** GIVEN a refresh token past `expires_at` WHEN refresh THEN `401` ProblemDetail; no new tokens issued.

## AC-IAM-03 — Reuse detection `[SAFETY-CRITICAL]`
- **AC-03.1** GIVEN a refresh token already rotated (revoked) WHEN it is presented again to `/auth/token/refresh` THEN `401` `application/problem+json` with `type = urn:hmis:error:token-reuse-detected`.
- **AC-03.2** AND all currently-live refresh tokens for that user are revoked (verify: a previously-valid sibling refresh token now returns `401` on next use).
- **AC-03.3** This entire capability is `[NEW-SCOPE]` — legacy had no refresh-token store, no rotation, no reuse detection (CR-10). AC are written as net-new requirements, not legacy reproduction.

## AC-IAM-04 — Revoke / logout `[NEW-SCOPE]`
Endpoint: `POST /api/v1/auth/token/revoke` — Authenticated.
- **AC-04.1** GIVEN an authenticated caller with a valid refresh token WHEN `POST /auth/token/revoke` THEN `200`/`204` and the refresh token's `revoked=true`; subsequent refresh with it returns `401`.
- **AC-04.2** Legacy has NO revoke/logout endpoint (`hasRevokeEndpoint=false`); this is net-new (CR-10). AC must not claim legacy parity.

## AC-IAM-05 — Create user `[SAFETY-CRITICAL][PHI/PII]`
Endpoint: `POST /api/v1/iam/users`. **Gate: `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')`** (verified legacy; NOT `USER-CREATE`). Data classification: user PII (names, username).

- **AC-05.1 (gate, deny)** WHEN caller lacks both `USER-ALL` and `ADMIN-ACCESS` THEN `403` ProblemDetail `urn:hmis:error:forbidden`; no row created.
- **AC-05.2 (gate, allow)** WHEN caller has `USER-ALL` OR `ADMIN-ACCESS` AND body valid THEN `201` with `Location: /api/v1/iam/users/uid/{uid}`.
- **AC-05.3 (reserved username)** WHEN `username` = `root` (case-sensitive per legacy) THEN `400`/`422` ProblemDetail; no row created. (Legacy rejects `root` outright at controller.)
- **AC-05.4 (username length)** WHEN `username` length < 3 or > 50 (and username ≠ `root`) THEN `400` ProblemDetail listing the field. (Bounds 3..50 are the enforced legacy code, not the stale "6 and 16" comment — Query Q2 confirms canonical bounds before locking the message text.)
- **AC-05.5 (password required on create)** WHEN password blank/null on create THEN `400`. WHEN present, length must be 4..50 (enforced legacy bound) — confirm canonical bound + message via Q2.
- **AC-05.6 (password hashing)** THEN the stored password is a BCrypt hash (`$2[aby]$` prefix), never plaintext; the plaintext is not present in any response, log, or audit event.
- **AC-05.7 (user code)** THEN a `code` is assigned in format **`USR-NNN-NNN`** (see AC-IAM-13); immutable thereafter.
- **AC-05.8 (no-id)** Response DTO exposes `uid`, never numeric `id`.
- **AC-05.9 (audit)** THEN a `UserCreatedEvent` is emitted AFTER_COMMIT carrying `uid` and `performedBy` (username), NO PHI password, NO numeric id.
- **AC-05.10 (personnel side-effects — DEFER)** Legacy `saveUser` creates/deactivates personnel rows (Clinician/Nurse/etc.) per role membership. These entities are NOT in the increment-00 build. This side-effect is OUT OF SCOPE for increment 01 and tracked as a forward dependency (CR-11); do NOT author personnel-sync AC here until those aggregates exist.

## AC-IAM-06 — Update user (incl. implicit password change + active flag) `[SAFETY-CRITICAL][PHI/PII]`
Endpoint: `PUT /api/v1/iam/users/uid/{uid}`. **Gate: `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')`** (verified legacy — there is NO separate `USER-UPDATE`-only gate, NO `USER-CHANGEPASSWORD`, NO `USER-ACTIVATE` endpoint in legacy).

- **AC-06.1 (gate)** WHEN caller lacks `USER-ALL` and `ADMIN-ACCESS` THEN `403`.
- **AC-06.2 (code immutable)** WHEN body attempts to change `code` THEN `400`/`422`; original code preserved.
- **AC-06.3 (password keep)** WHEN password field blank/null THEN stored hash is unchanged.
- **AC-06.4 (password change)** WHEN password non-blank THEN it is re-BCrypt-encoded and replaces the stored hash; new hash differs; old password no longer authenticates. Length bound 6..50 on update (enforced legacy code) pending Q2 confirmation of canonical bound/message.
- **AC-06.5 (self-toggle guard)** WHEN the caller attempts to change the active flag of their own account THEN `400`/`403` ProblemDetail; flag unchanged. (Legacy controller guard, verified.)
- **AC-06.6 (root edit guard)** WHEN target username = `root` AND the request is anything other than an activate/deactivate toggle THEN reject the non-toggle fields. (Verify exact behaviour of the modern build's root handling vs legacy force-clear of root password — Query Q3.)
- **AC-06.7 (activate/deactivate behaviour) `[NEW-SCOPE — pending decision]`** Legacy `UserServiceImpl:126` hard-forces `setActive(true)` on every update, so deactivation never persists (unfinished stub). The modern build MUST NOT silently reproduce this defect nor silently implement real deactivation. The intended behaviour is an OPEN CHANGE REQUEST (CR-12); until `engagement-lead` rules, AC-06.7 reads: *deactivation behaviour is undecided and must not be marked done.* QA asserts only what the ratified CR-12 decision states.
- **AC-06.8 (audit)** `UserPasswordChangedEvent` emitted AFTER_COMMIT (uid + performedBy, no plaintext) when password changed; `UserUpdatedEvent` for other field changes.

## AC-IAM-07 — Delete user `[SAFETY-CRITICAL]`
Endpoint: `DELETE /api/v1/iam/users/...`. **Gate: `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')`** (verified legacy).
- **AC-07.1 (gate)** Lacking both codes THEN `403`.
- **AC-07.2 (root)** Deleting `root` is rejected.
- **AC-07.3 (legacy = always blocked)** Legacy `allowDeleteUser` always returns false, so ALL deletes throw "Deleting this user is not allowed." Whether the modern build reproduces this no-op or implements real/soft delete is an OPEN CHANGE REQUEST (CR-13). Do NOT author a "delete succeeds" AC until CR-13 is ratified.

## AC-IAM-08 — Role create / update / delete `[SAFETY-CRITICAL]`
Endpoints: `POST/PUT/DELETE /api/v1/iam/roles/...`. **Gate: `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')`** (verified legacy; NOT `ROLE-CREATE`).
- **AC-08.1 (gate)** Lacking both codes THEN `403`.
- **AC-08.2 (reserved names — create)** Creating a role whose name matches a reserved name is rejected. Reserved set to assert: the verified legacy 15-name guard list `[ROOT, ADMIN, RECEPTION, CASHIER, HUMAN-RESOURCE, PROCUREMENT, MANAGER, ACCOUNTANT, STORE-PERSON, CLINICIAN, NURSE, PHARMACIST, LABORATORIST, RADIOGRAPHER, RADIOLOGIST]`. NOTE: `MANAGEMENT` is seeded but absent from the legacy guard (gap) — whether to add it is CR-14; until ruled, assert the 15-name list verbatim.
- **AC-08.3 (owner)** A user-created role is persisted with `owner = ORGANIZATION`.
- **AC-08.4 (update reserved)** Editing a reserved-name role is rejected (same 15-name list).
- **AC-08.5 (delete root / dead code)** Deleting `ROOT` is rejected; legacy `allowDeleteRole` always false → all role deletes throw. Same open question as users (CR-13).

## AC-IAM-09 — Assign privileges to role `[SAFETY-CRITICAL]`
Endpoint: `POST /api/v1/iam/roles/.../privileges` (legacy `/privileges/addtorole`).
- **AC-09.1 (full replace)** WHEN a privilege set is submitted THEN the role's existing privileges are cleared and replaced by exactly the submitted set (full-replace semantics, verified legacy). `ALL` shortcut behaviour must be confirmed against legacy before asserting (Query Q4).
- **AC-09.2 (gate) `[NEW-SCOPE — security fix]`** Legacy gate is `ROLE-U` but **commented out → effectively UNGATED** (any authenticated user could rewrite any role's privileges). Reproducing this is a security hole. The modern build MUST gate this endpoint; the chosen gate (`ROLE-ALL` OR `ADMIN-ACCESS` recommended) is a ratified deviation under CR-15. QA asserts: caller lacking the CR-15 gate code receives `403`. This AC is explicitly net-new (security remediation), NOT legacy reproduction.

## AC-IAM-10 — Assign roles to user `[SAFETY-CRITICAL]`
Endpoint: `PUT /api/v1/iam/users/.../roles` (legacy `/roles/addtouser`). **Gate: `hasAnyAuthority('USER-ALL','USER-UPDATE','ROLE-ALL','ADMIN-ACCESS')`** (verified legacy 4-code OR set; NOT `USER-ROLE`).
- **AC-10.1 (gate)** Caller with NONE of the four codes THEN `403`; caller with any one THEN allowed.
- **AC-10.2 (idempotent add)** Assigning a role already held does not duplicate the link (legacy `addRoleToUser` adds only if absent).
- **AC-10.3 (audit)** `UserRoleAssignedEvent` emitted AFTER_COMMIT (uid + roleName + performedBy).

## AC-IAM-11 — List privileges / roles / users (read) `[PHI/PII on users]`
Legacy: all these GETs are **UNGATED**.
- **AC-11.1 (list privileges)** `GET /api/v1/iam/privileges` returns the seeded privilege catalogue. The count to assert is the **VERIFIED count, NOT 177**. The seeded set is the **35 distinct authority string-literals** (= 26 active gates + 9 commented-only). The legacy generates privileges by reflection over `security/Object_.java` + `security/Operation.java`; the authoritative full catalogue size must be read from those files before locking the exact integer — raise Query Q5. Until Q5 resolves, assert: the endpoint returns exactly the seeded set with no duplicates and includes all 26 active gate codes verbatim.
- **AC-11.2 (gate parity)** Legacy list endpoints are ungated. The modern build gating them (e.g. requiring authentication) is a deviation tracked under CR-16; QA asserts only the ratified gate.
- **AC-11.3 (no-id)** Each privilege/role/user item exposes `uid`/`code`/`name`, never numeric `id`.
- **AC-11.4 (PHI)** User-list responses are PII; the endpoint must require authentication in the modern build (CR-16) and must not expose password hashes.

## AC-IAM-12 — Provider profile upsert `[NEW-SCOPE]`
Endpoint: `POST /api/v1/iam/provider-profiles`.
- **AC-12.1** The spec's `ProviderProfile {specialty, registrationNumber, licenceNumber, licenceExpiry}` does NOT exist in legacy (verified: zero matches for specialty/registration/licence anywhere in `com.orbix.api`). This entire entity and its fields are **INVENTED / NEW SCOPE** (CR-17). Any AC for it must be labelled net-new and approved via change request BEFORE authoring field-level criteria.
- **AC-12.2 (legacy reproduction option)** The closest legacy reproduction of "clinician affiliation" is `Clinician.clinics` (M:N to Clinic) + a free-text `type` string on the personnel entity. If `engagement-lead` chooses legacy-faithful reproduction instead of the invented ProviderProfile, the story is re-scoped to model `Clinician.type` + `clinics` only. Decision pending CR-17.
- **AC-12.3** Until CR-17 is ratified, this story is BLOCKED, not "ready"; no field-level AC are written as if reproducing legacy behaviour.

## AC-IAM-13 — User code numbering `[SAFETY-CRITICAL]`
- **AC-13.1 (format)** The generated user code format is **`USR-NNN-NNN`** — literal `USR-` + the id zero-padded to 6 digits with a hyphen inserted between the 3rd and 4th digit. Verified golden master: id 1 → `USR-000-001`; id 1234 → `USR-001-234`. The spec's `USR-{000000}` (plain 6-digit, no embedded hyphen, via `String.format("USR-%06d")`) is WRONG (CR-06). QA asserts the embedded-hyphen form.
- **AC-13.2 (sequence source)** Legacy derives the number from `MAX(id)+1` at application level (no DB sequence, no fiscal reset). The increment-00 build uses ULID `uid` as the surrogate key and a hidden `BIGINT GENERATED ALWAYS id`. The numbering source for `code` in the modern build (app-level max vs DB sequence) must be confirmed by `data-architect`/`backend-engineer` because the legacy scheme is race-prone — raise Query Q6. The spec's `seq_usr_no` Flyway sequence is itself a deviation (CR-06) but may be the chosen safe implementation; the OUTPUT FORMAT (`USR-NNN-NNN`) is the non-negotiable parity target regardless of generation mechanism.
- **AC-13.3 (immutability)** Once assigned, `code` cannot be changed (AC-06.2).

## AC-IAM-14 — CORS lock-down `[SAFETY-CRITICAL]`
- **AC-14.1** GIVEN `${security.cors.allowed-origins}` allow-list WHEN a preflight `OPTIONS` arrives from an origin NOT on the list THEN the response does NOT carry permissive `Access-Control-Allow-Origin`, and the cross-origin request is rejected by the browser/preflight.
- **AC-14.2** WHEN origin IS on the allow-list THEN the appropriate `Access-Control-Allow-Origin` echo is returned. (Increment-00 currently uses wildcard `addAllowedOriginPattern("*")`; locking it is required — CR-18.)

## AC-IAM-15 — RFC 7807 on 400 / 401 / 403 `[SAFETY-CRITICAL]`
- **AC-15.1 (401)** Invalid/expired/absent token on a protected endpoint THEN `401` `application/problem+json` `type = urn:hmis:error:unauthenticated`.
- **AC-15.2 (403)** Authenticated but missing required privilege THEN `403` `type = urn:hmis:error:forbidden`.
- **AC-15.3 (400)** Validation failure (e.g. bad username length) THEN `400` `application/problem+json` with field-level detail; no legacy `error_message` string-map shape.
- **AC-15.4** No auth-failure response is `text/plain`; none leaks a stack trace or PHI.

## AC-IAM-16 — No `id` in any DTO `[SAFETY-CRITICAL]`
- **AC-16.1** No request or response DTO across `iam`/`auth` exposes a numeric field named `id`. Verifiable by the `DtoIdExposureArchTest` ArchUnit gate and by inspecting the OpenAPI schema (no `id: integer` property on any IAM schema). All identity exposure is via `uid` (ULID) or `code`/`name`.

## AC-IAM-17 — Audit events `[SAFETY-CRITICAL]`
- **AC-17.1** `UserCreatedEvent`, `UserUpdatedEvent`, `UserPasswordChangedEvent`, `UserRoleAssignedEvent` are published via `@TransactionalEventListener(AFTER_COMMIT)` and logged at INFO with `uid` + `performedBy` only.
- **AC-17.2 (no PHI/no-id)** No event payload or log line contains a plaintext password, a numeric `id`, or other PHI.
- **AC-17.3 (net-new note)** The audit trail is NET-NEW (legacy declared Envers but annotated zero entities; legacy had only hand-rolled `created_by/created_on/createdAt` columns). AC are written as net-new requirements, NOT as reproduction of a legacy audit baseline (CR-19). Do NOT reproduce Envers.

## AC-IAM-18 — Cross-module read projection `[NEW-SCOPE — architecture]`
- **AC-18.1** The `iam` module exposes ONLY `UserSummary` (uid, username, displayName, active, roles) and `ProviderSummary` as cross-module read types; no `@Entity` leaks across the module boundary. Verified by `ApplicationModules.verify()`. This is a Spring Modulith architectural requirement, net-new (no legacy modular boundary existed).

---

### Open queries to `legacy-analyst` (must resolve before the flagged AC are marked "ready")
- **Q1** — exact inactive-account login behaviour/message (AC-01.5).
- **Q2** — canonical username (3..50?) and password (4..50 create / 6..50 update?) bounds + canonical messages, given legacy code/comment mismatch (AC-05.4/05.5/06.4).
- **Q3** — exact root-update handling, incl. legacy force-clear of root password (AC-06.6).
- **Q4** — exact `ALL` shortcut semantics in privilege-to-role assignment (AC-09.1).
- **Q5** — authoritative full privilege-catalogue size from `security/Object_.java` + `security/Operation.java` (AC-11.1) — the seeded count integer is currently a working hypothesis, NOT confirmed 177 and NOT confirmed 35-only.
- **Q6** — chosen `code` generation mechanism (app-level MAX+1 vs DB sequence) given race condition (AC-13.2).

---

# PART 2 — CHANGE-REQUEST REGISTER (Increment 01)

Scope of register: every deviation of the BUILD from the increment-01 spec DOC. Each item: **SPEC SAYS / LEGACY REALITY / DECISION / JUSTIFICATION / SIGN-OFF OWNER / STATUS**. Approval authority at the phase gate is `engagement-lead` (I maintain and impact-assess only; I do not self-approve). The privilege-code list sign-off by `engagement-lead` is non-negotiable.

| CR | SPEC SAYS | LEGACY REALITY (source of truth) | DECISION | JUSTIFICATION | SIGN-OFF OWNER | STATUS |
|---|---|---|---|---|---|---|
| **CR-01** | "177 distinct privilege codes." | 177 = `@PreAuthorize` annotation *sites*. Distinct authority string-literals = **35** (26 live gates + 9 commented-only/dead). Full generated catalogue size to be read from `security/Object_.java`+`Operation.java` (Q5). | Replace "177 codes" with the verified count. Seed the verified catalogue; gate only with the **26 live** codes; the 9 dead codes are NOT live gates. | Adherence to legacy source of truth; "177" is a documented miscount. | engagement-lead (privilege-code list = non-negotiable gate) | OPEN — awaits Q5 + engagement-lead sign-off on final list |
| **CR-02** | Tables `iam_user`, `iam_role`, `iam_privilege`, `iam_user_role`, `iam_role_privilege`, `iam_refresh_token`, `iam_provider_profile`. | Increment-00 already built **plural snake_case**: `users, roles, privileges, role_privileges, user_roles, refresh_tokens` (ArchUnit-enforced; user directive). `iam_*` names are stale. | Use the existing plural tables; spec's `iam_*` names are void. | Ratified directive (plural snake_case, ArchUnit-enforced) + increment-00 is the real foundation. | engagement-lead | RATIFIED (directive already in force) |
| **CR-03** | New Flyway `V1__schema_iam.sql` + `V2__seed_iam_privileges_and_roles.sql`. | V1/V2/V3 ALREADY applied in increment-00. Next migration MUST be **V4+**. | All increment-01 DDL/seed starts at **V4**. | Migration history is append-only; reusing V1/V2 would corrupt the Flyway baseline. | engagement-lead | RATIFIED |
| **CR-04** | Per-method gate codes `USER-CREATE, USER-CHANGEPASSWORD, USER-ACTIVATE, ROLE-CREATE, ROLE-PRIVILEGE, USER-ROLE, PRIVILEGE-ALL`. | NONE of these exist in legacy. Verified gates: user C/U/D → `USER-ALL` OR `ADMIN-ACCESS`; add-role-to-user → `USER-ALL`/`USER-UPDATE`/`ROLE-ALL`/`ADMIN-ACCESS`; role C/U/D → `ROLE-ALL` OR `ADMIN-ACCESS`; add-privilege-to-role → legacy `ROLE-U` but commented out; list endpoints ungated. | Replace invented codes with verified legacy gate codes verbatim. | Adherence to legacy; invented codes would silently alter the authorization model. | engagement-lead (non-negotiable gate) | OPEN — awaits sign-off |
| **CR-05** | `Privilege.name` is "the CODE string." | Legacy `Privilege.name` IS the code; increment-00 built the column as **`code`** (UNIQUE) on `privileges`. | Use the `code` field per the built schema; semantics identical to legacy `name`. | Ratified directive (built schema uses `code`); behaviour-equivalent. | engagement-lead | RATIFIED |
| **CR-06** | User code = `USR-{000000}` via `seq_usr_no`, `String.format("USR-%06d")`; golden master `USR-000001/2/3`. | Verified format is **`USR-NNN-NNN`** (6-digit zero-pad with hyphen inserted at index 3): id 1 → `USR-000-001`, id 1234 → `USR-001-234`. Source: app-level `MAX(id)+1`, no DB sequence, no fiscal reset. | Output format MUST be `USR-NNN-NNN` (embedded hyphen). Generation mechanism (sequence vs MAX+1) pending Q6; a DB sequence is an acceptable safe deviation provided the OUTPUT format is exact. | Adherence to legacy output format; the spec's `USR-000001` is wrong by one embedded hyphen. Normalising to `USR-000000` would itself require a separate ratified CR. | engagement-lead + data-architect | OPEN — awaits Q6 + format sign-off |
| **CR-07** | 11 default roles: `ADMIN, DOCTOR, NURSE, PHARMACIST, LAB_TECHNICIAN, RADIOGRAPHER, CASHIER, RECEPTIONIST, STORE_KEEPER, PROCUREMENT_OFFICER, HR_MANAGER`. | Legacy seeds **16** verbatim: `ROOT, ADMIN, RECEPTION, CASHIER, HUMAN-RESOURCE, PROCUREMENT, MANAGER, ACCOUNTANT, STORE-PERSON, MANAGEMENT, CLINICIAN, NURSE, PHARMACIST, LABORATORIST, RADIOGRAPHER, RADIOLOGIST`. (Legacy uses `CLINICIAN` not `DOCTOR`; no `LAB_TECHNICIAN`/`RECEPTIONIST`/etc.) | Seed the legacy 16 role names verbatim; spec's invented set is void. | Adherence to legacy; the spec set is partially invented (DOCTOR, LAB_TECHNICIAN, etc. do not exist). | engagement-lead | OPEN — awaits sign-off |
| **CR-08** | Refresh emits `privileges` (and login emits `privileges`). | Legacy login emits `privileges` (correct); legacy `/token/refresh` emits defective `roles` claim → empty authorities. | Emit `privileges` on BOTH login and refresh. Do NOT reproduce the `roles` defect. | Deliberate fix of a confirmed legacy defect; already corrected in increment-00. | security-architect + engagement-lead | RATIFIED (fixed in increment-00) |
| **CR-09** | Access TTL 15 min; refresh TTL 8 h. | Legacy access TTL 8 h (login) / 8 h (refresh path); refresh-token life 24 h. | Adopt access 15 min / refresh 8 h. | Security hardening; legacy refresh path was broken in production, so no client depends on the 8h access TTL. | security-architect + engagement-lead | RATIFIED |
| **CR-10** | Refresh rotation + reuse-detection + `/auth/token/revoke`. | Legacy: NO refresh-token store, NO rotation, NO reuse-detection, NO revoke/logout endpoint (`hasRevokeEndpoint=false`); stateless tokens only. | Implement rotation, reuse-detection, revoke as **NEW SCOPE**. | Net-new security capability; AC must be labelled net-new, not legacy reproduction. | security-architect + engagement-lead | RATIFIED (NEW SCOPE) |
| **CR-11** | (Not addressed.) | Legacy `saveUser` creates/(de)activates 6 personnel rows (Clinician/Nurse/Pharmacist/Cashier/StorePerson/Management) per role membership, with asymmetry (CASHIER/MANAGEMENT have no deactivation branch). | DEFER personnel-sync side-effects to a later increment (those aggregates do not exist in increment-00). Track as forward dependency; do NOT author personnel AC now. | Faithful reproduction requires entities not yet built; deferring preserves exact-process intent without guessing. | engagement-lead + solution-architect | OPEN — scheduling decision |
| **CR-12** | `/iam/users/uid/{uid}/activate` (dedicated `USER-ACTIVATE` endpoint) toggles enable/disable. | No dedicated endpoint; activate flows through update. Legacy `UserServiceImpl:126` force-sets `active=true` on every update → deactivation NEVER persists (unfinished stub). Controller has self/root toggle guards that are largely moot. | Decision REQUIRED: (a) reproduce no-op stub (true legacy), or (b) implement real activate/deactivate (controller intent). AC-06.7 blocked until ruled. | Cannot guess; the legacy is internally contradictory (Ambiguity #1). | engagement-lead (with healthcare-domain-expert) | OPEN — decision required |
| **CR-13** | (Implies functional delete via CRUD.) | Legacy `allowDeleteUser`/`allowDeleteRole` ALWAYS return false → all deletes throw "not allowed." Delete is effectively dead code. | Decision REQUIRED: reproduce always-blocked behaviour, or implement real/soft delete. AC-07/AC-08.5 blocked until ruled. | Cannot guess; reproducing dead code vs enabling delete is a material behaviour change (Ambiguity #2). | engagement-lead | OPEN — decision required |
| **CR-14** | (Reserved-role guard not enumerated.) | Legacy guards 15 reserved names but OMITS `MANAGEMENT` (which is seeded) — a gap allowing a clashing user-created `MANAGEMENT` role. | Decision: reproduce the 15-name guard exactly (gap and all), or add `MANAGEMENT` to the guard. Default for now: reproduce 15 verbatim. | Adding MANAGEMENT is a deviation; reproducing the gap is exact-process. Needs explicit ruling (Ambiguity #3). | engagement-lead | OPEN — decision required |
| **CR-15** | `/iam/roles/.../privileges` gated by `ROLE-PRIVILEGE`. | Legacy `/privileges/addtorole` intended gate `ROLE-U` is **commented out → effectively UNGATED**; any authenticated user can rewrite any role's privileges (privilege-escalation hole). | GATE this endpoint as a security fix (recommend `ROLE-ALL` OR `ADMIN-ACCESS`, since `ROLE-U` is a dead/commented-only code). Treat as NEW SCOPE (security remediation), not invented `ROLE-PRIVILEGE`. | Reproducing the ungated legacy is an unacceptable security hole; remediation needs a ratified deviation (Ambiguity #6). | security-architect + engagement-lead (non-negotiable) | OPEN — decision required |
| **CR-16** | List endpoints gated (`USER-ALL`, `ROLE-ALL`, `PRIVILEGE-ALL`). | Legacy list-users/roles/privileges are ALL **UNGATED** (no `@PreAuthorize`). | Require authentication on list endpoints (especially user-list = PII) as a security/PHI deviation; exact gate codes per ratified decision. | Exposing PII user lists ungated violates PHI/PII obligations; minimal deviation = require authentication. | security-architect + engagement-lead | OPEN — decision required |
| **CR-17** | `ProviderProfile {specialty, registrationNumber, licenceNumber, licenceExpiry}`. | NO ProviderProfile and ZERO matches for specialty/registration/licence anywhere in legacy. Closest reproduction = `Clinician.type` (free-text) + `Clinician.clinics` (M:N). Personnel entities have only code/type/names/active. | Decision REQUIRED: (a) treat ProviderProfile as NEW SCOPE (approve invented fields via CR), or (b) re-scope to legacy-faithful `Clinician.type`+`clinics`. AC-12 blocked until ruled. | The spec fields are invented; authoring them as legacy reproduction is forbidden (Guardrail). | engagement-lead (with healthcare-domain-expert) | OPEN — decision required |
| **CR-18** | "CORS locked to allow-list." | Increment-00 currently uses wildcard `addAllowedOriginPattern("*")`. | Lock CORS to `${security.cors.allowed-origins}` allow-list; remove wildcard. | Security hardening; aligns build with the spec target and PHI obligations. | security-architect + engagement-lead | RATIFIED (hardening) |
| **CR-19** | Audit events emitted (`UserCreatedEvent` etc.). | Legacy declared Envers dependency but annotated ZERO entities; only hand-rolled `created_by/created_on/createdAt` columns. No effective audit trail. | Implement NET-NEW tamper-evident audit-event trail; do NOT reproduce Envers (phantom feature). | Audit is net-new per engagement principle; reproducing the unused Envers dep is forbidden (phantom-feature trap). | security-architect + engagement-lead | RATIFIED (NEW SCOPE) |
| **CR-20** | ROOT bootstrap principal. | Legacy bootstraps user `root`/`r00tpA55` with role `ROOT` granted `ADMIN-ACCESS,USER-ALL,ROLE-ALL`. Increment-00 instead seeds `admin`/`password` with role `ADMIN`. | Accept the `admin`/`ADMIN` bootstrap as a known intentional divergence; or align to legacy `root`/`ROOT`. | Divergence already exists in increment-00; needs explicit recording and a ruling on whether to align bootstrap to legacy. | engagement-lead | OPEN — record + decision |

---

### Traceability / status notes
- **RATIFIED** items (CR-02, 03, 05, 08, 09, 10, 18, 19) reflect already-in-force directives or increment-00 reality and may proceed; I have logged them so no deviation is silently absorbed.
- **OPEN** items (CR-01, 04, 06, 07, 11–17, 20) require `engagement-lead` sign-off at the phase gate BEFORE the corresponding stories are marked sprint-ready. The privilege-code list (CR-01, CR-04, CR-07) is the non-negotiable gate and blocks the V4 seed migration from being committed.
- No CR is self-approved; my role is maintenance + impact assessment only.

Relevant artifacts (absolute paths):
- Spec doc with drift: `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\01-identity-access.md`
- Applied migrations (next = V4+): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V1__schema.sql`, `V2__seed_iam.sql`, `V3__seed_company_profile.sql`
- Legacy source of truth (READ-ONLY): `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\service\UserServiceImpl.java`, `...\resource\UserResource.java`, `...\domain\User.java`, `...\domain\Role.java`, `...\domain\Privilege.java`, `...\MainApplication.java`, `...\Formater.java`