# ADR-0003: Database & Persistence

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization — fresh build, no data migration

## Context

The legacy Zana HMIS runs on MySQL 5 (`MySQL5InnoDBDialect`, port 3306, `zana_hmis_db_test`) with Hibernate `ddl-auto=update` and **no migration tool** on the classpath — the schema has no authoritative DDL; it is whatever Hibernate last reflected from the ~115 entity files in `com.orbix.api.domain`. Every monetary, quantity, stock, and balance field is a Java `double` (96 declarations across 52 entities; zero `BigDecimal` matches). Identity is a bare `BIGINT` auto-increment PK, exposed directly in URLs and embedded in business numbers (`MRNO/{year}/{rawId}`). Attachments (`LabTestAttachment`, `RadiologyAttachment`) are flat relational rows holding a file `name` and a unique `fileName` reference, not blobs.

This is a **greenfield build** in `d:\My_Works\HMS\HMSCLEAN2` under the mandate "modern design, exact process": only the business process is held identical; data types and the data model are free to change. There is no production data to migrate and no MySQL schema to translate. This ADR fixes the database engine, the identifier strategy, the canonical numeric types, and the migration discipline. Money and document-numbering policy is governed jointly with ADR-0009 (this ADR's numeric decision is identical to and single-sourced with ADR-0009). File bytes are governed by ADR-0015.

## Decision

**Engine.** PostgreSQL 16. Chosen over MySQL 8 for native `NUMERIC` fidelity, partial/expression indexes, rich `timestamptz`, and identity-column support. Local and CI tests run against `postgres:16-alpine` via Testcontainers.

**Identifiers (dual-key).** Every table carries two keys:
- `id BIGINT GENERATED ALWAYS AS IDENTITY` — the internal surrogate PK. `GENERATED ALWAYS` forbids any application-supplied value at the database level. It is **never** serialized into a DTO, response body, log, or URL.
- `uid CHAR(26) NOT NULL UNIQUE` — a **ULID** (26-character Crockford base32, lexicographically sortable, URL-safe). The `uid` is the **only** identifier exposed externally; all addressable endpoints use `/{resources}/uid/{ulid}`. Generated in the application with `com.github.f4b6a3:ulid-creator` via `UlidCreator.getMonotonicUlid()`.

**Numeric types (single source — identical to ADR-0009).**
- Money: `NUMERIC(19,2)`.
- Quantity / coefficient / stock balance: `NUMERIC(19,6)`.

Scale 6 on quantity is required, not cosmetic: legacy `ItemMedicineCoefficient.coefficient` is a `double` holding values such as 1/3; `NUMERIC(19,4)` would truncate that and compound the error across stock-card running balances. `15,6` is also rejected — `19` integer-plus-fraction digits is the uniform precision. No `19,4` anywhere.

**Schema management.** Flyway is net-new (legacy had none). `V1__schema.sql` is authored directly as PostgreSQL 16 DDL — no mysqldump, no type translation, no row backfill, because there is no source data. `V2__seed_privileges.sql` seeds the 177 `@PreAuthorize` privilege codes on every fresh deployment. `spring.jpa.hibernate.ddl-auto=validate` in **all** environments; Hibernate may never alter the schema. `baseline-on-migrate=false` (there is no pre-existing schema to baseline over).

**Attachments.** `LabTestAttachment` and `RadiologyAttachment` are FLAT relational tables: `id` (identity), `uid`, `name`, `file_name` (UNIQUE), a parent FK (`lab_test_id` / `radiology_id`), and audit columns. `file_name` references a stored file whose bytes live in the backend defined by ADR-0015. They are **not** JSONB — there is no semi-structured payload.

**Timestamps.** Persist as `timestamptz`; Java type `Instant`/`OffsetDateTime`; JVM runs `-Duser.timezone=UTC`. The legacy hardcoded `+3h` offset (`DayServiceImpl.getTimeStamp()`) is not reproduced and needs no normalization (no data to migrate); EAT (Africa/Dar_es_Salaam) is applied only at display and for local-calendar document dates.

## Considered alternatives

- **ULID vs UUIDv7 (chosen: ULID).** UUIDv7 is the rejected alternative. Both are time-ordered and clustering-friendly, but ULID is chosen because it **matches the prior build's ratified choice** (continuity for any reused code and operators), is natively **URL-safe** without hyphen/encoding concerns, and is **lexicographically sortable as plain text** — its `CHAR(26)` string ordering is the chronological ordering, so ordinary B-tree and `ORDER BY uid` give creation order for free. UUIDv7 as native `uuid` would save 10 bytes per row but reintroduces a hyphenated, less URL-clean external token and diverges from the established codebase convention.
- **Native `uuid` column type.** Rejected as a consequence of choosing ULID — ULIDs are stored as `CHAR(26)` text.
- **Single shared `bigserial`-style exposed PK (no uid).** Rejected — leaks row counts and enables enumeration; the whole point of the dual key is to keep `id` internal.
- **MySQL 8.** Rejected in favour of PostgreSQL 16 (numeric/index/identity strengths above).
- **Hibernate `ddl-auto=update` (legacy behaviour).** Rejected — non-reproducible, undocumented schema drift; replaced by Flyway + `validate`.

## Consequences

Positive: external identifiers are opaque, unguessable, sortable, and URL-clean; internal `id` stays a fast 8-byte join key fully hidden from clients; one canonical numeric policy removes cross-ADR precision drift and protects stock-ledger accuracy; the schema is versioned, reviewable DDL from day one and CI can `validate` every entity against it.

Negative / cost: `CHAR(26)` indexes are larger than an 8-byte `uuid` and use string comparison (accepted for the URL/sortability/continuity benefits); ULID generation is an application responsibility (`ulid-creator` must be on the classpath and used for every insert); `ddl-auto=validate` means every schema change is a deliberate Flyway migration — no silent entity-driven evolution.

## Exact-process impact

None to business process. Identifiers, engine, and numeric types are technical substrate. The 177 privilege codes are reproduced verbatim by the seed migration so `@PreAuthorize` behaviour is unchanged. Document-number **formats** and the SPT collision are out of scope here and resolved in ADR-0009 (SPTO / PPTO) — this ADR does not redefine them.

## Implementation notes

- Base mapped superclass `AuditableEntity` with `@Id @GeneratedValue(strategy = IDENTITY) Long id` (mark `insertable=false, updatable=false`) and `@Column(length = 26, unique = true, nullable = false, updatable = false) String uid`, populated in a `@PrePersist` hook via `UlidCreator.getMonotonicUlid().toString()`.
- Column mappings: money `@Column(precision = 19, scale = 2)`; quantity/coefficient/balance `@Column(precision = 19, scale = 6)`.
- DDL conventions in `V1__schema.sql`: every table `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `uid CHAR(26) NOT NULL`, plus `CONSTRAINT uq_<table>_uid UNIQUE (uid)`. Repository lookups are by `uid`; never expose `id`.
- Attachment DDL (`lab_test_attachment`, `radiology_attachment`): `id`, `uid`, `name TEXT`, `file_name TEXT UNIQUE`, parent FK, audit columns. No JSONB column.
- Dependency: `com.github.f4b6a3:ulid-creator`. Cross-references: ADR-0009 (money/quantity scale single source; document numbering and SPT → SPTO/PPTO), ADR-0015 (attachment file-byte storage).
