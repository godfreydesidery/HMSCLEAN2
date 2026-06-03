Confirmed: `formatSix("1")` → pad to `"000001"` → insert `-` at index 3 → `"000-001"`, so the full code is `USR-000-001`. The format is `USR-{3 digits}-{3 digits}` (6-digit zero-padded id with an embedded hyphen). I now have everything verified directly against the legacy source. Here is the complete design with exact DDL.

---

# Increment 01 IAM Schema Delta — Design + Exact DDL

All findings below were re-verified by direct read of the legacy source (`User.java`, `Clinician.java`, `Formater.java:36-52`, `UserServiceImpl.java:114,658-669`) and the increment-00 artefacts (`V1__schema.sql`, `V2__seed_iam.sql`, `AuditableEntity.java`, `RefreshToken.java`, `AuthenticationService.java`). Next migration number is **V4** (V1-V3 exist).

## Files to create

- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V4__schema_iam_delta.sql`
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V5__seed_iam_roles.sql`
- `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V6__seed_iam_privileges_reconcile.sql`

---

## 1. V4__schema_iam_delta.sql

```sql
-- =====================================================================================
-- Zana HMIS — Increment 01 IAM delta (ADR-0006). Builds ON TOP of V1 plural schema.
-- Additive-only: new columns are nullable or defaulted; new tables/sequences/indexes.
-- No destructive change; no V1-V3 edits. Conventions per V1 header.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1a. users — legacy identity columns absent from the increment-00 minimal users table.
--     Legacy User (domain/User.java) carries code, first/middle/last name, nickname.
--     Increment-00 users only has username/password_hash/enabled. Increment-01 needs the
--     full person identity + the immutable user code/number.
--     ADDITIVE: all new columns nullable (existing seeded 'admin' row has no values).
--     A follow-on data step (or the admin bootstrap) backfills the seeded admin row.
-- -------------------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN user_no     VARCHAR(11),               -- legacy 'code': USR-000-001 (see seq below)
    ADD COLUMN first_name  VARCHAR(80),               -- legacy firstName  (@NotBlank in legacy)
    ADD COLUMN middle_name VARCHAR(80),               -- legacy middleName (nullable in legacy)
    ADD COLUMN last_name   VARCHAR(80),               -- legacy lastName   (@NotBlank in legacy)
    ADD COLUMN nickname    VARCHAR(80);               -- legacy nickname/alias (@NotBlank, unique-ish)

-- user_no is immutable + unique in the legacy (@Column(unique=true, updatable=false)).
-- Enforced as UNIQUE here; NOT NULL is deferred until backfill (additive-only rule).
ALTER TABLE users
    ADD CONSTRAINT uq_users_user_no UNIQUE (user_no);

-- Length check encodes the EXACT legacy format USR-NNN-NNN (Formater.formatSix:36-52):
-- literal 'USR-' + 6-digit zero-padded id with a hyphen inserted between digit 3 and 4.
ALTER TABLE users
    ADD CONSTRAINT ck_users_user_no_format
        CHECK (user_no IS NULL OR user_no ~ '^USR-[0-9]{3}-[0-9]{3}$');

-- Legacy nickname is the visible alias and is @Column(unique=true) on the personnel
-- extensions; on User itself it is only @NotBlank (NOT unique). We keep it NON-unique
-- on users to match User.java exactly (do NOT invent a uniqueness constraint).
CREATE INDEX idx_users_nickname ON users (nickname);

-- -------------------------------------------------------------------------------------
-- 1b. User-code sequence. Legacy uses app-level MAX(id)+1 (race-prone, no DB sequence).
--     MODERN DEVIATION (ADR-0006): replace the race-prone MAX(id)+1 with a DB sequence
--     so concurrent user creation cannot collide. The FORMAT (USR-NNN-NNN) is preserved
--     exactly; only the unsafe id-derivation mechanism changes. The application formats
--     nextval('seq_usr_no') via the same zero-pad+embedded-hyphen rule as Formater.formatSix.
--     This is a mechanism change, not a process change — flagged in deviations list below.
-- -------------------------------------------------------------------------------------
CREATE SEQUENCE seq_usr_no
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE          -- format holds up to 999-999; guard in app, not schema
    NO CYCLE;

-- -------------------------------------------------------------------------------------
-- 1c. provider_profiles — 1:1 clinical-credential extension of users.
--     LEGACY HAS NO ProviderProfile and NO specialty/registration/licence fields
--     (verified: zero grep matches in com.orbix.api). The increment-01 spec's
--     ProviderProfile{specialty, registration, licence, licenceExpiry} is a NET-NEW
--     requirement, NOT a legacy reproduction. It is created here PER THE INCREMENT-01
--     SPEC, explicitly flagged as a spec-driven addition requiring an engagement-lead
--     change request for the new fields (deviation #4 below). The closest legacy analog
--     is the thin per-role personnel entities (Clinician/Nurse/...), which carry only a
--     free-text 'type' and (Clinician) a clinics M:N — NONE of the four spec fields.
--
--     Link is user_id BIGINT -> users.id (internal-id FK convention, ADR-0014 §1):
--     the FK never appears in any DTO; cross-module reads use the user's uid projection.
-- -------------------------------------------------------------------------------------
CREATE TABLE provider_profiles (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)  NOT NULL,
    user_id         BIGINT       NOT NULL,            -- 1:1 to users.id (internal id, never exposed)
    specialty       VARCHAR(120),                     -- spec field; nullable (not all users are providers)
    registration_no VARCHAR(60),                      -- spec field; professional registration number
    licence_no      VARCHAR(60),                      -- spec field; practising licence number
    licence_expiry  DATE,                             -- spec field; licence expiry date
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT       NOT NULL,
    CONSTRAINT pk_provider_profiles PRIMARY KEY (id),
    CONSTRAINT uq_provider_profiles_uid     UNIQUE (uid),
    -- 1:1 enforced: at most one provider profile per user.
    CONSTRAINT uq_provider_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_provider_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- registration_no / licence_no are organisation-unique when present (partial-unique below).
    -- A licence with an expiry must also have a number (data-quality rule, not legacy-derived).
    CONSTRAINT ck_provider_profiles_licence
        CHECK (licence_expiry IS NULL OR licence_no IS NOT NULL)
);

-- Partial-unique: registration / licence numbers unique among the rows that HAVE one
-- (NULLs allowed for non-clinical users). Encodes "a licence number identifies one provider".
CREATE UNIQUE INDEX uq_provider_profiles_registration_no
    ON provider_profiles (registration_no) WHERE registration_no IS NOT NULL;
CREATE UNIQUE INDEX uq_provider_profiles_licence_no
    ON provider_profiles (licence_no) WHERE licence_no IS NOT NULL;

-- Operational query: "find providers whose licence expires before X" (compliance sweep).
CREATE INDEX idx_provider_profiles_licence_expiry
    ON provider_profiles (licence_expiry) WHERE licence_expiry IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 1d. refresh_tokens — one-time-use rotation + reuse-detection enrichment (ADR-0006).
--     AuthenticationService.refresh() ALREADY: revokes the presented token on use,
--     and on reuse/expiry revokes ALL the user's live tokens. What is MISSING from the
--     physical row is (a) WHEN it was revoked, and (b) the rotation chain link. These two
--     columns make 'token-reuse-detected' forensically reconstructable without changing
--     the existing revoke-all behaviour. Minimal, additive.
--       revoked_at      : set when revoke() fires; NULL while live. (revoked BOOLEAN stays.)
--       replaced_by_uid : the uid of the refresh_tokens row that superseded this one on
--                         rotation (the new token issued in issue()). NULL for the live tail
--                         and for tokens revoked by reuse-detection rather than rotation.
-- -------------------------------------------------------------------------------------
ALTER TABLE refresh_tokens
    ADD COLUMN revoked_at      TIMESTAMPTZ,
    ADD COLUMN replaced_by_uid VARCHAR(26);

-- replaced_by_uid points at another refresh_tokens.uid (self-reference by public uid,
-- consistent with user_uid being a uid-FK rather than an id-FK on this table).
ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_uid) REFERENCES refresh_tokens (uid);

-- Consistency: a token is revoked IFF it has a revoked_at (keeps the boolean and the
-- timestamp from drifting apart).
ALTER TABLE refresh_tokens
    ADD CONSTRAINT ck_refresh_tokens_revoked_at
        CHECK ( (revoked = TRUE  AND revoked_at IS NOT NULL)
             OR (revoked = FALSE AND revoked_at IS NULL) );

-- Hot path: "find this user's LIVE tokens" (reuse-detection revoke-all + active-session
-- listing). Partial index keeps it tiny — only un-revoked rows are indexed.
CREATE INDEX idx_refresh_tokens_user_live
    ON refresh_tokens (user_uid) WHERE revoked = FALSE;
```

---

## 2. V5__seed_iam_roles.sql

The legacy **does** seed roles beyond ADMIN. `MainApplication.java:208-249` seeds 16 system role names. Increment-00's V2 seeded **only** `ADMIN` (an intentional bootstrap divergence). To reach exact-process parity for the role catalogue, the remaining 15 legacy role names are seeded here as empty `owner=SYSTEM` roles (privileges are attached later via the UI/endpoints, exactly as legacy does). I do **not** invent the increment-01 spec's DOCTOR/etc. names — those are wrong; the legacy uses CLINICIAN, not DOCTOR.

```sql
-- =====================================================================================
-- Zana HMIS — Increment 01 IAM role seed (exact-process parity with legacy).
-- Legacy MainApplication.java:208-249 seeds 16 SYSTEM role names if absent. V2 seeded
-- only ADMIN (increment-00 bootstrap divergence). Here we seed the remaining 15 legacy
-- names verbatim, as EMPTY roles (owner=SYSTEM), matching legacy seed behaviour where
-- seeded roles carry no privileges until assigned via the UI.
--
-- NOTE: V1 'roles' has no 'owner' column. Legacy Role.owner ('SYSTEM'/'ORGANIZATION')
-- gates the reserved-name protection (UserResource create/update reject SYSTEM-owned
-- names). To reproduce that rule the modern roles table needs an 'owner' column; it is
-- added here (additive, defaulted) so the seed and the future guard can rely on it.
-- The increment-01 spec's invented role names (DOCTOR, etc.) are NOT used.
-- =====================================================================================

-- Add the legacy Role.owner discriminator (default ORGANIZATION = user-created;
-- SYSTEM = reserved/seeded). Existing ADMIN row is a system role -> backfilled to SYSTEM.
ALTER TABLE roles
    ADD COLUMN owner VARCHAR(20) NOT NULL DEFAULT 'ORGANIZATION';
ALTER TABLE roles
    ADD CONSTRAINT ck_roles_owner CHECK (owner IN ('SYSTEM','ORGANIZATION'));

UPDATE roles SET owner = 'SYSTEM' WHERE name = 'ADMIN';

-- Seed the remaining 15 legacy SYSTEM role names (ADMIN already exists from V2).
-- ROOT is included for catalogue completeness; the modern bootstrap principal is 'admin'
-- (increment-00 divergence) but the ROOT role NAME is part of the legacy reserved set and
-- the reserved-name guard must recognise it.
INSERT INTO roles (uid, name, owner, created_at, version) VALUES
  ('01J1SEEDROLE00000000000001', 'ROOT',           'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000002', 'RECEPTION',      'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000003', 'CASHIER',        'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000004', 'HUMAN-RESOURCE', 'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000005', 'PROCUREMENT',    'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000006', 'MANAGER',        'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000007', 'ACCOUNTANT',     'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000008', 'STORE-PERSON',   'SYSTEM', now(), 0),
  ('01J1SEEDROLE00000000000009', 'MANAGEMENT',     'SYSTEM', now(), 0),
  ('01J1SEEDROLE0000000000000A', 'CLINICIAN',      'SYSTEM', now(), 0),
  ('01J1SEEDROLE0000000000000B', 'NURSE',          'SYSTEM', now(), 0),
  ('01J1SEEDROLE0000000000000C', 'PHARMACIST',     'SYSTEM', now(), 0),
  ('01J1SEEDROLE0000000000000D', 'LABORATORIST',   'SYSTEM', now(), 0),
  ('01J1SEEDROLE0000000000000E', 'RADIOGRAPHER',   'SYSTEM', now(), 0),
  ('01J1SEEDROLE0000000000000F', 'RADIOLOGIST',    'SYSTEM', now(), 0);
```

---

## 3. V2 35-code seed — verification + reconciliation migration

**Verification result:** V2's INSERT contains exactly the 35 distinct string literals. I cross-checked every row against the verified set: all 35 present, none extra, none missing. The literal membership is **correct and complete**.

**However**, adversarial verification established that **9 of the 35 are commented-only DEAD codes** that gate nothing at runtime: `BILL-A, GOO-ALL, PATIENT-A, PATIENT-C, PATIENT-U, PROCUREMENT-ACCESS, PRODUCT-CREATE, ROLE-CREATE, ROLE-U`. Under "exact process," dead gates gate nothing — but pruning them is a catalogue-policy decision, not a defect fix. Per the guardrail "never silently activate/prune," I do **not** delete them. Instead V6 records the active/dead classification as a `category` column so the 26 live gates are distinguishable, without changing what is seeded (parity preserved). This is the conservative, reversible choice; pruning would require an engagement-lead change request.

```sql
-- =====================================================================================
-- Zana HMIS — Increment 01 privilege-catalogue reconciliation (NEVER edits V2).
-- V2 seeded all 35 distinct legacy authority string-literals — verified complete/correct.
-- Adversarial analysis: only 26 are ACTIVE gates; 9 are commented-only DEAD codes.
-- We do NOT prune (exact-process / no-silent-change). We TAG each code so live vs dead
-- is queryable, keeping all 35 seeded for full historical parity.
-- =====================================================================================

ALTER TABLE privileges
    ADD COLUMN category VARCHAR(12) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE privileges
    ADD CONSTRAINT ck_privileges_category CHECK (category IN ('ACTIVE','DEAD'));

-- The 9 commented-only / never-enforced codes (verified file:line in discovery report).
UPDATE privileges SET category = 'DEAD'
WHERE code IN (
    'BILL-A', 'GOO-ALL', 'PATIENT-A', 'PATIENT-C', 'PATIENT-U',
    'PROCUREMENT-ACCESS', 'PRODUCT-CREATE', 'ROLE-CREATE', 'ROLE-U'
);
-- The remaining 26 stay ACTIVE (the default). No INSERTs, no DELETEs: V2's set is intact.
```

No additions or removals to the 35-code set are warranted — the set is an exact 35:35 match with the legacy gate-code universe.

---

## 4. Canonical entity-table mapping (new/changed for increment 01)

| New/changed entity | Table | Column (type) | Legacy source | Constraint |
|---|---|---|---|---|
| User (extend) | `users` | `user_no VARCHAR(11)` | `User.code` (`User.java:52`) | `uq_users_user_no`, `ck_users_user_no_format` |
| | | `first_name VARCHAR(80)` | `User.firstName` (`:54`) | — |
| | | `middle_name VARCHAR(80)` | `User.middleName` (`:56`) | — |
| | | `last_name VARCHAR(80)` | `User.lastName` (`:57`) | — |
| | | `nickname VARCHAR(80)` | `User.nickname` (`:59`) | `idx_users_nickname` (NOT unique — matches legacy) |
| Role (extend) | `roles` | `owner VARCHAR(20)` | `Role.owner` (`Role.java`) | `ck_roles_owner` |
| Privilege (extend) | `privileges` | `category VARCHAR(12)` | derived (active/dead classification) | `ck_privileges_category` |
| ProviderProfile (NEW) | `provider_profiles` | `uid VARCHAR(26)` | — (spec-new entity) | `uq_provider_profiles_uid` |
| | | `user_id BIGINT` | 1:1 → `users.id` | `fk_provider_profiles_user`, `uq_provider_profiles_user_id` |
| | | `specialty VARCHAR(120)` | spec (no legacy field) | — |
| | | `registration_no VARCHAR(60)` | spec (no legacy field) | `uq_provider_profiles_registration_no` (partial) |
| | | `licence_no VARCHAR(60)` | spec (no legacy field) | `uq_provider_profiles_licence_no` (partial), `ck_provider_profiles_licence` |
| | | `licence_expiry DATE` | spec (no legacy field) | `idx_provider_profiles_licence_expiry` (partial) |
| RefreshToken (extend) | `refresh_tokens` | `revoked_at TIMESTAMPTZ` | ADR-0006 (no legacy refresh store) | `ck_refresh_tokens_revoked_at` |
| | | `replaced_by_uid VARCHAR(26)` | ADR-0006 rotation chain | `fk_refresh_tokens_replaced_by` |

Sequence: `seq_usr_no` (BIGINT, START 1, NO CYCLE) — drives `user_no` formatting.

---

## 5. Every deviation from the increment-01 spec doc + why

1. **`ProviderProfile{specialty, registration, licence, licenceExpiry}` is NOT a legacy reproduction.** Verified: zero matches for any of these fields in `com.orbix.api`. The legacy has no `ProviderProfile` entity — only six thin per-role personnel extensions (`Clinician`, `Nurse`, ...) whose only distinguishing field is a free-text `type` (Clinician adds a `clinics` M:N). I build `provider_profiles` per the spec, but it is a **net-new requirement**, and its four fields require an explicit engagement-lead change request before they are treated as authoritative. Flagging, not silently reproducing.

2. **User-code format is `USR-NNN-NNN`, not the spec's `USR-{000000}`.** Verified in `Formater.formatSix` (`Formater.java:36-52`): 6-digit zero-pad then `sb.insert(3,"-")`. The `ck_users_user_no_format` CHECK encodes `^USR-[0-9]{3}-[0-9]{3}$` (an embedded hyphen between digit 3 and 4). The spec's plain `USR-000000` is wrong by one embedded hyphen. `user_no` length is 11, not 10.

3. **User-code derivation: DB sequence instead of `MAX(id)+1`.** Legacy uses app-level `userRepository.getLastId()+1` (`UserServiceImpl.java:661`) — race-prone, no locking. I introduce `seq_usr_no` (ADR-0006) so concurrent creates can't collide. The **format is preserved exactly**; only the unsafe id-derivation mechanism changes (mechanism, not process). Approved-divergence candidate.

4. **Spec gate codes `USER-CREATE/USER-CHANGEPASSWORD/USER-ACTIVATE/ROLE-PRIVILEGE/USER-ROLE/PRIVILEGE-ALL` do not exist in the legacy and are NOT seeded.** No schema change carries them. Real gates are `USER-ALL`/`ADMIN-ACCESS` etc. (already in V2). There is no dedicated change-password/activate endpoint or code to model.

5. **Default roles are the legacy 16 (ROOT, ADMIN, RECEPTION, CASHIER, HUMAN-RESOURCE, PROCUREMENT, MANAGER, ACCOUNTANT, STORE-PERSON, MANAGEMENT, CLINICIAN, NURSE, PHARMACIST, LABORATORIST, RADIOGRAPHER, RADIOLOGIST)** — verbatim from `MainApplication.java:208-233`. The spec's DOCTOR/etc. set is partially invented and NOT used (legacy uses CLINICIAN, not DOCTOR).

6. **`roles.owner` column added** (legacy `Role.owner`, default `SYSTEM`/`ORGANIZATION`). V1 omitted it; the legacy reserved-name guard depends on it, so it is required for exact-process role protection. Additive, defaulted `ORGANIZATION`, seeded roles set to `SYSTEM`.

7. **`refresh_tokens` is enriched minimally** (`revoked_at`, `replaced_by_uid`) rather than redesigned. The existing `revoked BOOLEAN` and the existing revoke / revoke-all behaviour in `AuthenticationService` are untouched; the two columns only make the reuse-detection event forensically reconstructable (ADR-0006), as requested.

8. **35-code seed kept intact; not pruned.** V2's 35 literals are verified complete and correct. 9 are dead (commented-only) gates, but per no-silent-change I **tag** them (`privileges.category='DEAD'`) instead of deleting. Pruning to the 26 active codes would need an engagement-lead change request.

9. **`users.nickname` is NOT made unique.** Legacy `User.nickname` is only `@NotBlank` (`User.java:59`), not `@Column(unique=true)`. Only the personnel extensions make nickname unique. I add a plain index, not a unique constraint — faithful to `User.java`.

## Open items for other agents

- **engagement-lead:** approve (3) sequence-vs-MAX(id) mechanism change; confirm (1) the four net-new `provider_profiles` fields as an approved addition; decide (8) keep-vs-prune the 9 dead codes.
- **security-architect:** `provider_profiles` will hold professional-credential data (`registration_no`, `licence_no`) — confirm whether these are PHI-adjacent and whether `provider_profiles` and the new `users` name columns require audit-trail coverage before I author any audit DDL (I have authored none here, per the boundary rule).
- **backend-engineer:** `user_no` formatting must zero-pad `nextval('seq_usr_no')` to 6 digits and insert a hyphen at index 3 to satisfy `ck_users_user_no_format`; `NOT NULL` on `user_no`/`first_name`/`last_name` is deferred until the seeded `admin` row is backfilled (separate migration once bootstrap values are decided).

Files: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V4__schema_iam_delta.sql`, `V5__seed_iam_roles.sql`, `V6__seed_iam_privileges_reconcile.sql` (DDL above; not yet written to disk — say the word and I will create them).