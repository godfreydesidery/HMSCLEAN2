I now have a complete, verified picture of the as-built foundation. I have everything needed to write the authoritative build spec. I will not author the audit-baseline assertion beyond confirmed reality (legacy Envers = 0 `@Audited`, confirmed by legacy-analyst), and I gate the open decisions on engagement-lead sign-off.

---

# INCREMENT-01 BUILD SPECIFICATION — Identity & Access (AUTHORITATIVE)

Scope: add the IAM lock-and-key management layer on top of the increment-00 walking skeleton. Legacy is source of truth; the increment-01 spec doc has confirmed drift (reconciled below). Module `com.otapp.hmis.iam`. Next Flyway = **V4**.

---

## 1. AUTHORITATIVE PRIVILEGE-CODE SET

**Verified distinct authority string-literals: 35** (= 26 live `@PreAuthorize` gates + 9 commented-only/dead). The "177" in the spec is annotation SITES, not codes — do not use it. The full reflection-generated catalogue from `security/Object_.java` + `security/Operation.java` (legacy-analyst Q5) is NOT the gate universe; only these 35 ever appear in `@PreAuthorize`, so 35 is the authoritative seed-and-gate set for IAM.

**V2 seed verdict: MATCHES — no privilege INSERT/DELETE delta needed.** `V2__seed_iam.sql:26-61` contains exactly these 35 codes (cross-checked line by line; none extra, none missing). `PrivilegeSeedIT` already parses V2 and asserts persistence. The only privilege-table change in increment-01 is the **additive `category` tag column** (V4, below) — no codes are added or removed.

**Live (26) — used by an active gate; these are the only codes any modern `@PreAuthorize` may reference:**
`ADMIN-ACCESS, DAY-ACCESS, EMPLOYEE-ALL, GOODS_RECEIVED_NOTE-ALL, GOODS_RECEIVED_NOTE-CREATE, GOODS_RECEIVED_NOTE-UPDATE, GOODS_RECEIVED_NOTE-APPROVE, LOCAL_PURCHASE_ORDER-ALL, LOCAL_PURCHASE_ORDER-CREATE, LOCAL_PURCHASE_ORDER-UPDATE, PHARMACY_ORDER-ALL, PHARMACY_ORDER-CREATE, PHARMACY_ORDER-UPDATE, STORE_ORDER-ALL, PATIENT-ALL, PATIENT-CREATE, PATIENT-UPDATE, PAYROLL-ALL, PAYROLL-CREATE, PAYROLL-UPDATE, SUPPLIER_PRICE_LIST-ALL, ITEM_STOCK-UPDATE, MEDICINE_STOCK-UPDATE, USER-ALL, USER-UPDATE, ROLE-ALL`

**Dead (9) — commented-only in legacy; seeded for catalogue parity, NEVER a live gate in the modern build:**
`BILL-A, GOO-ALL, PATIENT-A, PATIENT-C, PATIENT-U, PROCUREMENT-ACCESS, PRODUCT-CREATE, ROLE-CREATE, ROLE-U`

**`expected-privilege-codes.txt` fixture** — create at `D:\My_Works\HMS\HMSCLEAN2\backend\src\test\resources\fixtures\expected-privilege-codes.txt`, one code per line, 35 lines, sorted ascending (so the parity test does a simple set-equality against `privilegeRepository.findAllByOrderByCodeAsc()`):

```
ADMIN-ACCESS
BILL-A
DAY-ACCESS
EMPLOYEE-ALL
GOODS_RECEIVED_NOTE-ALL
GOODS_RECEIVED_NOTE-APPROVE
GOODS_RECEIVED_NOTE-CREATE
GOODS_RECEIVED_NOTE-UPDATE
GOO-ALL
ITEM_STOCK-UPDATE
LOCAL_PURCHASE_ORDER-ALL
LOCAL_PURCHASE_ORDER-CREATE
LOCAL_PURCHASE_ORDER-UPDATE
MEDICINE_STOCK-UPDATE
PATIENT-A
PATIENT-ALL
PATIENT-C
PATIENT-CREATE
PATIENT-U
PATIENT-UPDATE
PAYROLL-ALL
PAYROLL-CREATE
PAYROLL-UPDATE
PHARMACY_ORDER-ALL
PHARMACY_ORDER-CREATE
PHARMACY_ORDER-UPDATE
PRODUCT-CREATE
PROCUREMENT-ACCESS
ROLE-ALL
ROLE-CREATE
ROLE-U
STORE_ORDER-ALL
SUPPLIER_PRICE_LIST-ALL
USER-ALL
USER-UPDATE
```

(35 lines. A second fixture `expected-active-privilege-codes.txt` with the 26 live codes optionally backs the `category='ACTIVE'` assertion.)

---

## 2. EXACT-PROCESS ENDPOINT SURFACE

Modern controllers: `UserAdminController`, `RoleAdminController`, `PrivilegeQueryController`, `ProviderProfileController` (all `@RequestMapping("/api/v1/iam")`), plus the new revoke method on the existing `AuthController`. **No class-level `@PreAuthorize`** on any IAM controller — the legacy gates per-method and leaves all GETs ungated; a class-level gate would retroactively lock the legacy-ungated reads (exact-process violation). `ADMIN-ACCESS` stays as one alternative inside `hasAnyAuthority(...)` on mutating methods only. The authenticated baseline is enforced by `.anyRequest().authenticated()` in `SecurityConfig`.

Resources are addressed by **uid** (`/uid/{uid}`), never `{id}` (ArchUnit-enforced). All DTOs are `record`s with **no `id` field**.

| # | Method | /api/v1 path | `@PreAuthorize` (VERIFIED legacy) | Request DTO (no id) | Response DTO (no id) | Behaviour | Success |
|---|---|---|---|---|---|---|---|
| 1 | POST | `/iam/users` | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | `CreateUserRequest{firstName,middleName,lastName,nickname,username,password,roleNames[]}` | `UserResponse{uid,userNo,firstName,middleName,lastName,nickname,username,enabled,roleNames[],createdAt}` | Reject username `root`; assign `userNo` USR-NNN-NNN; BCrypt-encode password; persist. | 201 `Location: /api/v1/iam/users/uid/{uid}` |
| 2 | GET | `/iam/users` | (none — ungated in legacy; covered by `.authenticated()`) | — | `List<UserSummaryResponse>` | List users (no password hash). | 200 |
| 3 | GET | `/iam/users/uid/{uid}` | (none) | — | `UserResponse` | Get one user by uid. | 200 |
| 4 | PUT | `/iam/users/uid/{uid}` | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | `UpdateUserRequest{firstName,middleName,lastName,nickname,password?,enabled,roleNames[]}` | `UserResponse` | Update names/roles; blank password keeps hash, non-blank re-encodes; `userNo`/`username` immutable; self-toggle of `enabled` rejected; root-edit guard. **`enabled` persistence behaviour = CR-12 (BLOCKED).** | 200 |
| 5 | DELETE | `/iam/users/uid/{uid}` | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` | — | — | Delete user; reject `root`. **Net effect = CR-13 (BLOCKED): legacy always-blocked vs real/soft delete undecided.** | 204 (or 422 if reproducing legacy block) |
| 6 | POST | `/iam/roles` | `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` | `CreateRoleRequest{name}` | `RoleResponse{uid,name,owner,privilegeCodes[]}` | Reject reserved names (15-list); set `owner=ORGANIZATION`. | 201 `Location: /api/v1/iam/roles/uid/{uid}` |
| 7 | GET | `/iam/roles` | (none) | — | `List<RoleResponse>` | List roles. | 200 |
| 8 | GET | `/iam/roles/uid/{uid}` | (none) | — | `RoleResponse` | Get one role. | 200 |
| 9 | PUT | `/iam/roles/uid/{uid}` | `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` | `UpdateRoleRequest{name}` | `RoleResponse` | Reject reserved names; `owner=ORGANIZATION`. | 200 |
| 10 | DELETE | `/iam/roles/uid/{uid}` | `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` | — | — | Reject `ROOT`. **CR-13 (BLOCKED).** | 204 (or 422) |
| 11 | PUT | `/iam/roles/uid/{uid}/privileges` | **`hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')`** (security fix — legacy `ROLE-U` was commented out/ungated) | `ReplaceRolePrivilegesRequest{privilegeCodes[]}` | `RoleResponse` | Full-replace: clear existing, add submitted set; `ALL` shortcut semantics per legacy. **Gate = CR-15 (sign-off). `ALL` semantics = Q4.** | 200 |
| 12 | PUT | `/iam/users/uid/{uid}/roles` | `hasAnyAuthority('USER-ALL','USER-UPDATE','ROLE-ALL','ADMIN-ACCESS')` | `AssignRolesRequest{roleNames[]}` | `UserResponse` | Assign roles (idempotent add; legacy adds only if absent). | 200 |
| 13 | GET | `/iam/privileges` | (none — ungated; optionally `?roleName=` filter as legacy `/privileges?role=`) | — | `List<PrivilegeResponse{uid,code,category}>` | List privilege catalogue (35) or a role's privileges. | 200 |
| 14 | POST | `/iam/provider-profiles` | `hasAnyAuthority('USER-ALL','ADMIN-ACCESS')` (no legacy code — coarse user gate; **CR-17 BLOCKED**) | `ProviderProfileRequest{userUid,specialty?,registrationNo?,licenceNo?,licenceExpiry?}` | `ProviderProfileResponse{uid,userUid,specialty,registrationNo,licenceNo,licenceExpiry}` | Net-new entity; **entire story BLOCKED on CR-17.** | 201 `Location: .../provider-profiles/uid/{uid}` |
| 15 | POST | `/auth/token/revoke` | (none `@PreAuthorize`; authenticated via `.anyRequest().authenticated()`) | `RevokeRequest{refreshToken}` | — | Hash → lookup; ownership check (JWT sub == token's user) else 403; revoke; idempotent (unknown/already-revoked → 204, no oracle). **Net-new = CR-10.** | 204 |

**Reserved role-name guard list (15, verbatim legacy):** `ROOT, ADMIN, RECEPTION, CASHIER, HUMAN-RESOURCE, PROCUREMENT, MANAGER, ACCOUNTANT, STORE-PERSON, CLINICIAN, NURSE, PHARMACIST, LABORATORIST, RADIOGRAPHER, RADIOLOGIST`. `MANAGEMENT` is seeded but omitted from the legacy guard (CR-14 — reproduce the gap verbatim unless engagement-lead rules otherwise). Comparison is case-insensitive-trim per legacy behaviour (legacy-analyst to confirm exact comparison in Q-list if ambiguous; default: exact case-sensitive match as legacy string `.equals`).

**Validation bounds (Bean Validation, pending Q2 confirmation of canonical values vs stale legacy messages):** username 3..50 (`@Size`), `root` rejected; password create 4..50, update 6..50 (only validated when non-blank); firstName/lastName `@NotBlank`; nickname `@NotBlank`. Lock the exact bounds/messages only after legacy-analyst resolves Q2.

---

## 3. SCHEMA — V4+ MIGRATIONS + ENTITY/DTO/MAPPER PLAN

### V4__schema_iam_delta.sql
Create at `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V4__schema_iam_delta.sql`. Additive only; no edits to V1-V3.

```sql
-- 4a. users: legacy identity columns (User.java) absent from increment-00 minimal users table.
ALTER TABLE users
    ADD COLUMN user_no     VARCHAR(11),
    ADD COLUMN first_name  VARCHAR(80),
    ADD COLUMN middle_name VARCHAR(80),
    ADD COLUMN last_name   VARCHAR(80),
    ADD COLUMN nickname    VARCHAR(80);
ALTER TABLE users ADD CONSTRAINT uq_users_user_no UNIQUE (user_no);
ALTER TABLE users ADD CONSTRAINT ck_users_user_no_format
    CHECK (user_no IS NULL OR user_no ~ '^USR-[0-9]{3}-[0-9]{3}$');
CREATE INDEX idx_users_nickname ON users (nickname);   -- NOT unique (matches User.java)

-- 4b. user_no sequence (modern safe derivation; format preserved exactly — CR-06).
CREATE SEQUENCE seq_usr_no AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- 4c. roles.owner discriminator (legacy Role.owner; reserved-name guard depends on it).
ALTER TABLE roles ADD COLUMN owner VARCHAR(20) NOT NULL DEFAULT 'ORGANIZATION';
ALTER TABLE roles ADD CONSTRAINT ck_roles_owner CHECK (owner IN ('SYSTEM','ORGANIZATION'));
UPDATE roles SET owner = 'SYSTEM' WHERE name = 'ADMIN';

-- 4d. privileges.category tag (live vs dead; NO codes added/removed — V2 set intact).
ALTER TABLE privileges ADD COLUMN category VARCHAR(12) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE privileges ADD CONSTRAINT ck_privileges_category CHECK (category IN ('ACTIVE','DEAD'));
UPDATE privileges SET category = 'DEAD'
WHERE code IN ('BILL-A','GOO-ALL','PATIENT-A','PATIENT-C','PATIENT-U',
               'PROCUREMENT-ACCESS','PRODUCT-CREATE','ROLE-CREATE','ROLE-U');

-- 4e. refresh_tokens: reuse-detection forensics (D-2 / CR-10).
ALTER TABLE refresh_tokens
    ADD COLUMN revoked_at      TIMESTAMPTZ,
    ADD COLUMN replaced_by_uid VARCHAR(26);
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_replaced_by
    FOREIGN KEY (replaced_by_uid) REFERENCES refresh_tokens (uid);
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_revoked_at
    CHECK ((revoked = TRUE AND revoked_at IS NOT NULL) OR (revoked = FALSE AND revoked_at IS NULL));
CREATE INDEX idx_refresh_tokens_user_live ON refresh_tokens (user_uid) WHERE revoked = FALSE;

-- 4f. provider_profiles (NET-NEW per spec — CR-17; created here but story BLOCKED until ratified).
CREATE TABLE provider_profiles (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26) NOT NULL,
    user_id         BIGINT      NOT NULL,
    specialty       VARCHAR(120),
    registration_no VARCHAR(60),
    licence_no      VARCHAR(60),
    licence_expiry  DATE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT      NOT NULL,
    CONSTRAINT pk_provider_profiles PRIMARY KEY (id),
    CONSTRAINT uq_provider_profiles_uid     UNIQUE (uid),
    CONSTRAINT uq_provider_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_provider_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_provider_profiles_licence CHECK (licence_expiry IS NULL OR licence_no IS NOT NULL)
);
CREATE UNIQUE INDEX uq_provider_profiles_registration_no ON provider_profiles (registration_no) WHERE registration_no IS NOT NULL;
CREATE UNIQUE INDEX uq_provider_profiles_licence_no      ON provider_profiles (licence_no)      WHERE licence_no IS NOT NULL;
CREATE INDEX idx_provider_profiles_licence_expiry        ON provider_profiles (licence_expiry)  WHERE licence_expiry IS NOT NULL;
```

> **DDL-validate caveat for backend-engineer:** since `enabled` (V1) defaults TRUE and the new `users` columns are nullable, the seeded `admin` row stays valid. But once entities map `userNo/firstName/lastName` as `nullable=false`, `ddl-auto=validate` only checks types/presence (not nullability), so validation passes; however the seeded `admin` row will violate the entity's `@NotNull`/non-null column on next update. **V5 must backfill the admin row** (below) before any non-null entity mapping is enforced at the app layer.

### V5__backfill_admin_identity.sql
Backfill the seeded admin row so the new identity columns and the cost-12 re-hash (D-8) are consistent.

```sql
-- Backfill admin identity (admin row pre-dates the identity columns).
UPDATE users
SET user_no     = 'USR-000-001',
    first_name  = 'System',
    last_name   = 'Administrator',
    nickname    = 'admin'
WHERE username = 'admin' AND user_no IS NULL;

-- Re-hash the cost-10 dev seed to cost-12 (D-8). Guarded by the exact old hash → idempotent no-op
-- if already rotated. Both hashes are of the documented dev password "password"; nothing new leaks.
UPDATE users
SET password_hash = '$2a$12$<cost12-bcrypt-of-password>'   -- backend-engineer generates with BCryptPasswordEncoder(12)
WHERE username = 'admin'
  AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';

-- Align seq_usr_no past the seeded admin (id-derived legacy started at 1 → admin = 001).
SELECT setval('seq_usr_no', 1, true);
```

> The 15 additional legacy role names (ROOT, RECEPTION, … RADIOLOGIST) are **NOT seeded in this increment** unless engagement-lead ratifies CR-07. The walking skeleton seeds only ADMIN. Seeding the other 15 is a separate ratified step (`V6__seed_iam_roles.sql`) — do not commit it until CR-07 is approved. Keep V4/V5 free of unratified role seeds so the privilege-code sign-off (the non-negotiable gate) is not coupled to the role-catalogue decision.

### Entity / DTO / Mapper plan (Lombok + MapStruct, DIRECTIVE-compliant)

**Entities (extend existing; `com.otapp.hmis.iam.domain`):**
- `User` — add fields `userNo`, `firstName`, `middleName`, `lastName`, `nickname` (`@Column`, Lombok `@Getter`, no setters). Add domain methods: `rename(...)`, `setEnabled(boolean)` via explicit method (no Lombok setter), `assignUserNo(String)` (set-once, reject change — mirrors legacy immutable code). Keep `changePasswordHash` and `assign(Role)`.
- `Role` — add `owner` field; constructor `Role(String name, String owner)`; method `replacePrivileges(Set<Privilege>)`, `removeAllPrivileges()`.
- `Privilege` — add `category` field (enum-backed `String`); read-only.
- `ProviderProfile` (NEW) — `@Entity @Table(name="provider_profiles")` extends `AuditableEntity`; field `private Long userId` mapped to `user_id` (internal-id FK, **never exposed** — getter package-private or convert to userUid at mapper boundary via a lookup; simplest: store `userId` but the DTO carries `userUid`, resolved in the application service, never in the mapper). `RefreshToken` — add `revokedAt` (Instant) + `replacedByUid` (String); `revoke()` sets `revokedAt = now`; add `isExpired(Instant)` and keep `isUsable`.

**Repositories (add finder methods):**
- `UserRepository`: `findByUsername`, `findByUid` (exist) + nothing else needed (uid lookups).
- `RoleRepository`: add `Optional<Role> findByName(String)`, `Optional<Role> findByUid(String)`, `List<Role> findAllByOrderByNameAsc()`.
- `PrivilegeRepository`: has `findAllByOrderByCodeAsc()` + add `Optional<Privilege> findByCode(String)`, `List<Privilege> findByCategory(String)`.
- `ProviderProfileRepository` (NEW): `findByUid`, `findByUserId`.
- Add `nextUserNo()` via a `@Query(nativeQuery=true, value="SELECT nextval('seq_usr_no')")` on `UserRepository` (or a dedicated `SequenceRepository`); the service formats it as `USR-` + zero-pad-6 + hyphen-at-index-3.

**DTOs (records, package `com.otapp.hmis.iam.application.dto`, NO `id`):** as listed in §2. All request DTOs carry Bean Validation annotations.

**Mappers (MapStruct, package-private `@Mapper(componentModel="spring")` in `com.otapp.hmis.iam.application`, NO repo injected):**
- `UserMapper` — `UserResponse toResponse(User)`, `UserSummaryResponse toSummary(User)`. Maps `roleNames` from `user.getRoles()` via `@Named` helper or `default` method extracting `Role::getName`. Does NOT map `passwordHash`. Entity construction stays in the service (not the mapper) because password encoding + userNo generation are service concerns.
- `RoleMapper` — `RoleResponse toResponse(Role)` mapping `privilegeCodes` from `role.getPrivileges()`.
- `PrivilegeMapper` — `PrivilegeResponse toResponse(Privilege)`.
- `ProviderProfileMapper` — `ProviderProfileResponse toResponse(ProviderProfile)`; `userUid` is set by the service, not the mapper (mapper has no repo).
- Lombok BEFORE MapStruct in `annotationProcessorPaths` (already a directive; verify pom).

**Cross-cutting:** `ddl-auto=validate` stays; Flyway owns V4/V5. No `id` in any DTO (ArchUnit `noLongIdFieldOnDtoClasses` enforces). Money/qty types n/a here.

---

## 4. SECURITY DELTA

1. **CORS lock-down** — replace `config.addAllowedOriginPattern("*")` (`SecurityConfig.java:91`) with `config.setAllowedOrigins(corsProperties.allowedOrigins())`. Add `CorsProperties` record (`@ConfigurationProperties("hmis.cors")`, field `List<String> allowedOrigins`), register via `@EnableConfigurationProperties`. `application.yml`: `hmis.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:4200}`. Do NOT enable `allowCredentials` (bearer tokens, not cookies). Property namespace `hmis.cors.*` (not `security.cors.*`) for consistency with `hmis.jwt.*` — **D-1**.

2. **Reuse-detection error type** — add `ErrorCode.TOKEN_REUSE_DETECTED("urn:hmis:error:token-reuse-detected", HttpStatus.UNAUTHORIZED, "Refresh token reuse detected")`; new `TokenReuseException extends HmisException` carrying it. `GlobalExceptionHandler.handleHmis` already maps any `HmisException` — **no new handler**. Split `AuthenticationService.refresh()` into three branches:
   - unknown hash → `InvalidTokenException("Refresh token not recognised")` → `invalid-token` (current behaviour kept).
   - found + `revoked==true` → **reuse**: revoke-all the user's live tokens (current revoke-all logic), audit the event, throw `TokenReuseException`.
   - found + `!revoked && now >= expiresAt` → `InvalidTokenException("Refresh token is expired")` → `invalid-token`.
   - usable → rotate; on rotation set the old token's `replacedByUid` = new token's uid, set `revokedAt`.
   **The reuse 401 body must NOT contain token hashes, `replaced_by_uid`, or `user_uid`** — generic detail only.

3. **Revoke endpoint** — `POST /api/v1/auth/token/revoke` on `AuthController` (NOT in the `permitAll` list). `RevokeRequest{@NotBlank refreshToken}`. `AuthenticationService.revoke(rawToken, authenticatedUsername)`: hash → lookup; ownership check (token's user's username == JWT sub) else `AccessDeniedException` (403); `revoke()` + `revokedAt`; audit `AuditAction.DELETE`; **204** for found/owned, unknown, AND already-revoked (idempotent, non-enumerating). Net-new — **CR-10 / D-3** (ratified).

4. **BCrypt seed decision** — RE-HASH the cost-10 admin seed to cost-12 in V5 (above), guarded by the exact old hash (idempotent). The dev password "password" is already public in V2, so nothing new leaks. Auto-upgrade-on-login rejected for v1 (more moving parts). **D-8** (informational; strengthening only).

5. **SAST** — broaden the CI gate beyond the `HMAC256` grep. Add a Semgrep step to `.github/workflows/ci.yml` with rules at `backend/ci/semgrep-secrets.yml`: flag string-literal first-args to `new MACSigner(...)`, `Algorithm.HMAC256(...)`, `new SecretKeySpec("literal"...)`, and string-literal assignment to identifiers matching `(?i)(secret|signingKey|jwtKey|hmacKey)`. The legitimate `jwtProperties.secret()` is a method call → passes. Keep the existing `HMAC256` grep + the `NoHardcodedSecretTest` ArchUnit gate as belt-and-suspenders. Recommend (devops, out of scope here) repo secret-scanning + push-protection + dependency scan.

---

## 5. CROSS-MODULE READ PROJECTIONS

Spring Modulith: `iam` must expose ONLY read projections cross-module — no `@Entity` leak. Place named-interface API types in a dedicated exposed package.

- **Package:** `com.otapp.hmis.iam.api` is the controller package; the cross-module **read API** goes in `com.otapp.hmis.iam` as a Spring Modulith **named interface**. Create package `com.otapp.hmis.iam.spi` (service-provider interface) marked as a named interface via `@NamedInterface("read")` in its `package-info.java`, OR keep the public projection records + a query service in `com.otapp.hmis.iam` root (allowed package). Recommended: package `com.otapp.hmis.iam.lookup` annotated `@org.springframework.modulith.NamedInterface("lookup")`.

- **`UserSummary`** (record, public) — fields: `String uid, String username, String displayName, boolean enabled, List<String> roleNames`. `displayName` = `firstName + " " + lastName` (or nickname fallback). **No id, no passwordHash, no email** (legacy User has no email).

- **`ProviderSummary`** (record, public) — fields: `String uid, String userUid, String specialty, String registrationNo, String licenceNo, LocalDate licenceExpiry`. **BLOCKED behind CR-17** (the entity itself is net-new) — define the record shape now but do not wire it into other modules until ratified.

- **Exposure mechanism:** a public `IamLookupService` (interface) in the named-interface package with `Optional<UserSummary> findUser(String uid)`, `List<UserSummary> findUsers(Collection<String> uids)`, `Optional<ProviderSummary> findProvider(String userUid)`. Implementation `IamLookupServiceImpl` in `application` (package-private), returns projections only. Other modules depend on the named interface, never on `iam.domain.User`. `ModularityTest.verify()` + a new explicit test (§7) assert no `@Entity` is referenced cross-module.

- Update `com.otapp.hmis.iam.package-info.java` to declare the allowed dependents/exposure once the lookup package exists (`@ApplicationModule` with the named interface).

---

## 6. AUDIT EVENTS

Two layers, both already supported by the as-built code:

**(a) Synchronous audit row (in-transaction)** via the existing `AuditRecorder.record(entityType, entityUid, action, actor)` — call it inside each `@Transactional` service method on the PHI/identity-touching mutations. `entityType` uses the legacy-style `"iam.User"`, `"iam.Role"`, `"iam.RefreshToken"` convention already in `AuthenticationService`. This writes the append-only, checksummed `audit_logs` row.

**(b) Domain events** published via Spring's `ApplicationEventPublisher`, consumed by `@TransactionalEventListener(phase = AFTER_COMMIT)` listeners that log at INFO (and are the seam for future cross-module reactions). Events (all records, **uid + performedBy only — NO numeric id, NO password, NO PHI beyond uid/username**):

| Event | Emitted by | Payload |
|---|---|---|
| `UserCreatedEvent` | create user (after commit) | `{userUid, performedBy}` |
| `UserUpdatedEvent` | update user (non-password fields) | `{userUid, performedBy}` |
| `UserPasswordChangedEvent` | update user with non-blank password | `{userUid, performedBy}` |
| `UserDeletedEvent` | delete user (if CR-13 enables delete) | `{userUid, performedBy}` |
| `UserRolesAssignedEvent` | assign roles to user | `{userUid, roleNames, performedBy}` |
| `RoleCreatedEvent` / `RoleUpdatedEvent` | role create/update | `{roleUid, performedBy}` |
| `RolePrivilegesReplacedEvent` | replace role privileges | `{roleUid, privilegeCodes, performedBy}` |
| `RefreshTokenReuseDetectedEvent` | reuse branch in refresh | `{userUid, performedBy=username}` |
| `RefreshTokenRevokedEvent` | revoke endpoint | `{userUid, performedBy}` |

`performedBy` is the authenticated actor (Spring Security context). Listeners must log via SLF4J at INFO with structured key=value, never the token value or password. `AuditRecorder` (a) is the tamper-evident record; events (b) are the observability/reaction seam. Do NOT reintroduce Envers (phantom feature; legacy-analyst confirmed zero `@Audited`).

---

## 7. TEST PLAN (mapped to DoD)

All `*IT` extend `AbstractIntegrationTest` (Testcontainers PG16). ArchUnit/unit tests run in Surefire (no Docker). Authority on tokens via `TestJwtFactory.tokenWithPrivileges(username, privileges)`.

**Parity / seed:**
- `PrivilegeSeedIT` (exists) — extend to also assert exactly 35 codes match `expected-privilege-codes.txt`, and `category='DEAD'` for the 9, `'ACTIVE'` for the 26. → DoD: privilege-code set.
- `RolePrivilegeParityIT` — assert ADMIN role has all 35 (unchanged from V2).

**Integration (endpoint behaviour, §2):**
- `UserAdminIT`:
  - `create_requiresUserAllOrAdminAccess` — token lacking both → 403 `urn:hmis:error:forbidden`; with `USER-ALL` → 201 + `Location`.
  - `create_rejectsRootUsername` → 400/422.
  - `create_assignsUserNoInUsrNnnNnnFormat` — assert response `userNo` matches `^USR-\d{3}-\d{3}$` (golden: second created user → `USR-000-002`).
  - `create_bcryptHashesPassword` — login with the new user's password succeeds; stored hash starts `$2`.
  - `update_blankPasswordKeepsHash` / `update_nonBlankPasswordReEncodes`.
  - `update_codeImmutable` → 400/422 on userNo change attempt.
  - `update_selfToggleEnabledRejected`.
  - `response_hasNoNumericId` — assert JSON has no `id` field.
- `RoleAdminIT`:
  - `create_requiresRoleAllOrAdminAccess`.
  - `create_rejectsReservedNames` (parametrized over the 15-name list).
  - `create_setsOwnerOrganization`.
  - `replacePrivileges_requiresRoleAllOrAdminAccess` (CR-15 gate) — lacking gate → 403.
  - `replacePrivileges_fullReplaceSemantics` — clears then sets exactly the submitted codes.
- `AssignRolesIT` — `requiresAnyOfFourCodes`; `idempotentAdd`.
- `PrivilegeQueryIT` — `listReturns35Codes`; ungated-but-authenticated (401 without token, 200 with any token).
- `TokenEndpointsIT` (exists) — keep; covers login + refresh `privileges` claim.
- `RefreshReuseIT` (NEW):
  - `reusedToken_returns401TokenReuseDetected` — rotate once, present old token again → 401 `urn:hmis:error:token-reuse-detected` + all user tokens revoked.
  - `expiredToken_returns401InvalidToken` — distinct from reuse.
- `TokenRevokeIT` (NEW):
  - `revoke_returns204AndRevokes` → subsequent refresh of that token → 401 (reuse).
  - `revoke_crossUserToken_returns403`.
  - `revoke_unknownToken_returns204` (non-enumerating).
- `CorsIT` (NEW):
  - `disallowedOrigin_preflightRejected` — `OPTIONS` from `https://evil.example` → no `Access-Control-Allow-Origin: https://evil.example`.
  - `allowedOrigin_echoed` — `http://localhost:4200` echoed.
- `ProviderProfileIT` (NEW, **@Disabled until CR-17**) — placeholder.

**Unit:**
- `UserNoFormatterTest` — golden master: `format(1)=USR-000-001`, `format(2)=USR-000-002`, `format(1234)=USR-001-234`, `format(999999)=USR-999-999`. → DoD: numbering parity (CR-06).
- `AuthenticationServiceTest` — three-way refresh branch with a fixed `Clock`; assert reuse triggers revoke-all + `TokenReuseException`.
- `BCryptPasswordEncoderTest` (NEW) — `assertThat(((BCryptPasswordEncoder) passwordEncoder).getStrength()).isEqualTo(12)`; `encode("x")` cost segment `$12$`.

**ArchUnit / structural:**
- `ApiConventionsArchTest` (exists) — already enforces no `{id}`/no `@PathVariable("id")`/no DTO `id`/no `@Transactional` on controllers/single advice. New IAM controllers must pass it.
- `ModularityTest` (exists) — `ApplicationModules.verify()` must stay green after adding the `iam.lookup` named interface.
- `IamNoEntityLeakArchTest` (NEW) — assert no class outside `com.otapp.hmis.iam` references `com.otapp.hmis.iam.domain..` types (only `iam.lookup..` projections allowed cross-module).
- `PrivilegeGateArchTest` (NEW) — scan every `@PreAuthorize` string literal in `com.otapp.hmis`; assert each referenced code ∈ the 26 live codes (no invented code like `USER-CREATE`/`ROLE-PRIVILEGE`; no dead code activated). This is the contract enforcing "verified legacy codes only."

**DoD mapping:** privilege set → PrivilegeSeedIT + PrivilegeGateArchTest; endpoint surface → the *IT suite; numbering → UserNoFormatterTest + UserAdminIT; security delta → CorsIT/RefreshReuseIT/TokenRevokeIT/BCryptPasswordEncoderTest; cross-module → IamNoEntityLeakArchTest + ModularityTest; audit → assertions in UserAdminIT that an `audit_logs` row is written per mutation; no-id → ApiConventionsArchTest + UserAdminIT JSON assertion.

---

## 8. DEVIATION / SIGN-OFF REGISTER (engagement-lead must sign off BEFORE any V4+ seed migration is committed)

| ID | Item | Type | Status | Owner |
|---|---|---|---|---|
| **D-1** | CORS property `hmis.cors.allowed-origins` (not `security.cors.*`) | Naming | Recommend approve | solution-architect (self) |
| **D-2** | `refresh_tokens.revoked_at` + `replaced_by_uid` columns (V4) | Schema add | Recommend approve | data-architect |
| **D-3 / CR-10** | `POST /auth/token/revoke` + rotation/reuse-detection (net-new; legacy had none) | New feature | RATIFIED (security model) | engagement-lead |
| **D-8** | Re-hash cost-10 admin seed → cost-12 (V5) | Strengthening | Recommend approve | (informational) |
| **CR-01/04/07** | Privilege/role/gate-code corrections (35 codes, 26 live; legacy gate codes verbatim; legacy 16 role names) — **NON-NEGOTIABLE GATE** | Source-of-truth fix | **OPEN — blocks seed commit** | engagement-lead |
| **CR-06** | userNo format `USR-NNN-NNN` + DB-sequence derivation (vs spec `USR-000000`/MAX+1) | Format/mechanism | OPEN — awaits Q6 + format sign-off | engagement-lead + data-architect |
| **CR-07** | Seed the legacy 16 role names (V6, separate) — NOT in V4/V5 | Catalogue | OPEN | engagement-lead |
| **CR-12** | activate/deactivate: reproduce legacy no-op stub vs real toggle | Behaviour | **OPEN — AC-06.7 blocked** | engagement-lead + healthcare-domain-expert |
| **CR-13** | user/role delete: reproduce always-blocked vs real/soft delete | Behaviour | **OPEN — delete endpoints blocked** | engagement-lead |
| **CR-14** | reserved-role guard: reproduce 15-list (omitting MANAGEMENT) vs add MANAGEMENT | Behaviour | OPEN (default: reproduce 15) | engagement-lead |
| **CR-15** | gate `/roles/{uid}/privileges` with `ROLE-ALL`/`ADMIN-ACCESS` (legacy was ungated) — security fix | Security vs exact-process | **OPEN — strongly recommend gate** | engagement-lead + security-architect |
| **CR-16** | require auth on list endpoints (legacy ungated; user-list is PII) | Security/PHI | OPEN (recommend authenticated-only, no extra code) | engagement-lead + security-architect |
| **CR-17** | `ProviderProfile{specialty,registrationNo,licenceNo,licenceExpiry}` is net-new (zero legacy fields) | New scope | **OPEN — entire story blocked** | engagement-lead + healthcare-domain-expert |
| **CR-20** | bootstrap principal `admin`/`ADMIN` (increment-00) vs legacy `root`/`ROOT` | Divergence | OPEN — record + decide | engagement-lead |

**Open queries to legacy-analyst (resolve before flagged ACs go sprint-ready):** Q1 inactive-login message; Q2 username/password canonical bounds+messages; Q3 root-update handling; Q4 `ALL` privilege shortcut semantics; Q6 userNo generation mechanism.

---

## 9. BUILD WORK BREAKDOWN (ordered for backend-engineer)

**Gate 0 — sign-off (BLOCKING):** engagement-lead ratifies CR-01/04/07 (codes), CR-06 (numbering), and rules CR-12/13/15/16/17. No seed migration commits before this.

**Backend tasks (in order):**
1. `V4__schema_iam_delta.sql` (§3) — users identity cols + seq + roles.owner + privileges.category + refresh_tokens forensics + provider_profiles. `mvn -pl backend flyway:migrate` against a scratch PG to validate, then revert (Testcontainers re-runs it).
2. `V5__backfill_admin_identity.sql` — backfill admin row + cost-12 re-hash (generate the hash with `new BCryptPasswordEncoder(12).encode("password")`) + `setval`.
3. Extend entities: `User`, `Role`, `Privilege`, `RefreshToken` (+`revokedAt`/`replacedByUid`); add `ProviderProfile` (behind CR-17). Add repository finders + `nextUserNo()`.
4. `UserNoFormatter` (shared util in `iam.application`) — zero-pad-6 + hyphen-at-index-3.
5. DTOs (records, §2) + Bean Validation + MapStruct mappers (Lombok-before-MapStruct).
6. Application services: `UserAdminService`, `RoleAdminService`, `PrivilegeQueryService`, `IamLookupServiceImpl`, `ProviderProfileService` (behind CR-17). Wire `AuditRecorder` + event publishing.
7. Controllers (§2) — per-method `@PreAuthorize` verbatim; **no class-level gate**; 201 + `Location` on creates.
8. Security: `CorsProperties` + lock CORS; `ErrorCode.TOKEN_REUSE_DETECTED` + `TokenReuseException`; refresh three-way branch; `AuthController.revoke`.
9. Cross-module: `iam.lookup` named-interface package (`UserSummary`, `ProviderSummary`, `IamLookupService`) + update `package-info.java`.
10. `expected-privilege-codes.txt` fixture; all tests in §7.
11. CI: Semgrep step + `backend/ci/semgrep-secrets.yml`.

**Main-loop / CI must run:** `mvn -q -pl backend verify` (Surefire ArchUnit + Modulith + Failsafe Testcontainers ITs), with `JWT_SECRET`, `DB_*`, and `CORS_ALLOWED_ORIGINS` env set; Flyway applies V1→V5 in the Testcontainers PG16; `ddl-auto=validate` confirms entity/schema parity. The new Semgrep gate runs in the `security-scan` job. ArchUnit `PrivilegeGateArchTest` fails the build if any `@PreAuthorize` references a non-live or invented code.

**Frontend delta (Angular 18 shell, `frontend/`):** regenerate the OpenAPI client after the new endpoints exist; move the access token from `sessionStorage` to **in-memory** (a singleton token holder), keep the refresh token handling via the existing refresh interceptor; on `urn:hmis:error:token-reuse-detected` (401) clear all tokens and force re-login (do not retry); add a logout action that calls `POST /api/v1/auth/token/revoke` then clears in-memory token. CORS allow-list must include the dev origin `http://localhost:4200`.

**OpenAPI / docs updates:** springdoc auto-generates `/v3/api-docs`; verify every IAM schema has `uid`/`code`/`name` and **no `id: integer`** property. Update ADR-0006 to remove the stale `security.jwt.*` / `iam_*` table names / "177 codes" text and reference the verified 35-code reality. Author/refresh the increment-01 spec doc `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\01-identity-access.md` to reflect this build spec (replace invented gate codes, `USR-000000`, 11 default roles).

---

## Load-bearing files (absolute)
- As-built schema: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V1__schema.sql`, `V2__seed_iam.sql`, `V3__seed_company_profile.sql` (next = **V4**)
- As-built security: `...\backend\src\main\java\com\otapp\hmis\iam\config\SecurityConfig.java` (CORS wildcard :91; no class-gate; encoder strength 12 :75), `...\iam\config\JwtProperties.java` (prefix `hmis.jwt`)
- As-built auth: `...\iam\application\AuthenticationService.java` (refresh :67-83, issue :85-109), `...\iam\domain\RefreshToken.java` (`isUsable` :48-50, only `revoked` boolean), `...\iam\api\AuthController.java`, `...\iam\application\dto\TokenResponse.java`
- As-built domain: `...\iam\domain\User.java` (no identity cols yet), `Role.java` (no owner yet), `Privilege.java` (`code` UNIQUE), `...\shared\domain\AuditableEntity.java` (hidden id + uid)
- As-built audit: `...\shared\audit\AuditRecorder.java`, `AuditAction.java`; error: `...\shared\error\ErrorCode.java`, `GlobalExceptionHandler.java`
- As-built tests: `...\src\test\java\com\otapp\hmis\iam\PrivilegeSeedIT.java`, `TokenEndpointsIT.java`, `ModularityTest.java`, `arch\ApiConventionsArchTest.java`, `arch\NoHardcodedSecretTest.java`, `support\AbstractIntegrationTest.java`, `TestJwtFactory.java`, `AdminCredentialsFixture.java`
- CI: `D:\My_Works\HMS\HMSCLEAN2\.github\workflows\ci.yml`
- Config: `...\backend\src\main\resources\application.yml`
- Legacy source of truth (READ-ONLY): `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\resource\UserResource.java`, `service\UserServiceImpl.java`, `util\Formater.java`, `domain\User.java|Role.java|Privilege.java`, `MainApplication.java`

**To create:** `V4__schema_iam_delta.sql`, `V5__backfill_admin_identity.sql`, (V6 role seed only after CR-07), `backend\src\test\resources\fixtures\expected-privilege-codes.txt`, `backend\ci\semgrep-secrets.yml`, and the new entities/DTOs/mappers/services/controllers/tests under `com.otapp.hmis.iam`.