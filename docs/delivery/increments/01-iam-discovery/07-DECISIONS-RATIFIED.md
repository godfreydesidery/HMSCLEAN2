# Increment 01 (IAM) — Ratified Decisions & Ambiguity Defaults

**Status:** RATIFIED by the engagement owner (user) on 2026-06-03. This addendum is binding over the
synthesized build spec ([00-build-spec.md](00-build-spec.md)) wherever they differ. It unblocks Gate 0.

Source-of-truth extractions: [00-build-spec.md](00-build-spec.md), [04-verification.md](04-verification.md),
[05-management-gates.json](05-management-gates.json), and the personnel-entity extraction (User + six
extensions) captured below.

---

## A. The four sign-off decisions

| # | Decision | Ruling |
|---|---|---|
| 1 | **Privilege codes** | **Seed all 35** legacy codes; add `privileges.category` (`ACTIVE`/`DEAD`) tagging the 9 commented-dead codes (`BILL-A, GOO-ALL, PATIENT-A, PATIENT-C, PATIENT-U, PROCUREMENT-ACCESS, PRODUCT-CREATE, ROLE-CREATE, ROLE-U`). `@PreAuthorize` may reference **only the 26 live codes** — enforced by `PrivilegeGateArchTest`. V2 seed unchanged (already 35); the only privilege change is the additive `category` column in V4. Parity fixture = 35 lines. |
| 2 | **ProviderProfile** | **DO NOT build the invented ProviderProfile.** Instead **model the real legacy structure**: the six personnel extensions (`Clinician, Nurse, Pharmacist, Cashier, StorePerson, Management`) as faithful entities — see §C. |
| 3 | **Broken behaviors** | **Implement properly.** Real activate/deactivate toggle (no no-op stub) and working user/role delete (preserving the `root`/`ROOT` guards). Documented deviation from the unfinished legacy behavior. |
| 4 | **Hardening (ADR-0006)** | **Harden.** Gate role-privilege-assignment with `hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')` (legacy gate was commented out); require authentication on all IAM reads (user list is PII). Mutations keep their exact legacy gate codes (`USER-ALL`/`ROLE-ALL`/`ADMIN-ACCESS`). |

## B. Minor-item defaults (applied; recorded in the change register)

- **CR-06 numbering:** preserve the exact legacy format **`USR-NNN-NNN`** (`USR-%03d-%03d`), but generate via a DB sequence `seq_usr_no` (not the legacy `MAX(id)+1`, which is race-prone and reuses codes after deletes). Output is identical; mechanism modernized (allowed under the "data model/mechanism may change, process identical" directive). Golden-master: `USR-000-001`, `USR-000-002`, `format(1234)=USR-001-234`.
- **CR-14 reserved-role guard:** reproduce the legacy 15-name list verbatim (`ROOT, ADMIN, RECEPTION, CASHIER, HUMAN-RESOURCE, PROCUREMENT, MANAGER, ACCOUNTANT, STORE-PERSON, CLINICIAN, NURSE, PHARMACIST, LABORATORIST, RADIOGRAPHER, RADIOLOGIST`).
- **CR-20 bootstrap principal:** keep increment-00's `admin`/`ADMIN` (already shipped); record divergence from legacy `root`/`ROOT`. No change.
- **D-1 / D-2 / D-8:** approved — CORS property `hmis.cors.allowed-origins`; `refresh_tokens.revoked_at` + `replaced_by_uid`; re-hash the cost-10 admin seed → cost-12 in V5.

## C. Personnel-extension modeling plan (decision #2)

Build all six as `AuditableEntity` subclasses in `com.otapp.hmis.iam.domain`, tables
`clinicians, nurses, pharmacists, cashiers, store_persons, managements` (plural, directive-compliant).

**Common columns (every extension):** hidden `id` + `uid` (ULID, from `AuditableEntity`); `code VARCHAR`
(copy of the user's `USR-NNN-NNN` code); `type VARCHAR` (free-text, nullable, **not populated on
auto-create** — matches legacy); `first_name`, `middle_name`, `last_name`, `nickname` (copied from User,
kept equal — service enforces); `active BOOLEAN`; plus the standard audit columns. `@OneToOne` to `users`
with FK **`user_id` on the extension table** (nullable, optional).

**Extension-specific (DEFERRED — target tables don't exist in IAM):**
- `Clinician.clinics` → `clinicians_clinics` join to `Clinic` (clinical/masterdata). **Defer** to the increment that creates `clinics`.
- `StorePerson.stores` → `store_persons_stores` join to `Store` (inventory). **Defer** to the increment that creates `stores`.

**Lifecycle (`UserAdminService.saveUser`, reproduce legacy intent):** within the user create/update
transaction, iterate the user's roles and create-or-reactivate the matching extension (copy code + names,
`active=true`), and deactivate the extension when its triggering role is removed.

**Ambiguity defaults (override the legacy bugs in line with decision #3 "implement properly"; all flagged in
the change register for BA/healthcare-domain-expert post-review):**
- **AMB-1 (no DB unique on `user_id`):** ADD `UNIQUE(user_id)` per extension table (enforces the
  app-only "one extension per user per type" invariant at the DB; data-model hardening).
- **AMB-2 (`MANAGEMENT` trigger vs `MANAGER` reserved name):** trigger the `Management` extension on the
  role name **`MANAGER`** (the role that actually exists in the reserved list), fixing the legacy
  dead-trigger bug — consistent with "implement properly." Flag for BA confirmation.
- **AMB-4 (`type`):** create the column (nullable free-text); do not populate on auto-create.
- **AMB-5 (asymmetric deactivation):** deactivate **all six** extensions symmetrically when their role is
  removed (legacy only deactivated four). Consistent with "implement properly."
- **AMB-6 (validation bounds):** reproduce the **code** behavior, not the stale comments — username 3..50,
  password 4..50 on create / 6..50 on update (validated only when non-blank).

**Cross-module exposure:** expose only read projections from `iam` — `UserSummary` now; the six personnel
extensions are NOT exposed cross-module in this increment (no consumer yet). `ProviderSummary` from the
synthesized spec is dropped (no ProviderProfile).

## D. Net effect on the synthesized build spec

- §2 endpoint table: **remove** endpoint #14 `POST /iam/provider-profiles` (no ProviderProfile). User
  create/update endpoints now also drive the six-extension lifecycle. **Add** real `activate`/`deactivate`
  semantics to the user-update path and enable user/role `DELETE` (with `root`/`ROOT` guards).
- §3 schema: **drop** the `provider_profiles` table (4f). **Add** the six extension tables + `users_roles`
  already exists (increment-00 used `user_roles`; keep the existing `user_roles` name — do NOT switch to
  the legacy default `users_roles`). Keep 4a–4e (users identity cols, seq, roles.owner, privileges.category,
  refresh_tokens forensics).
- §5 cross-module: `UserSummary` only; drop `ProviderSummary`.
- §7 tests: drop `ProviderProfileIT`; **add** `PersonnelExtensionLifecycleIT` (create user with CLINICIAN +
  CASHIER roles → both extensions created & active; remove a role → that extension deactivated; one-per-type
  enforced) and `ActivateDeactivateIT` / `UserDeleteIT` for decision #3.
