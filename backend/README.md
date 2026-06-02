# Zana HMIS — Backend (Increment 00)

Spring Modulith modular monolith. Package root `com.otapp.hmis`, single Maven module,
Java 21 / Spring Boot 3.3 / Spring Modulith 1.2 / Flyway 10 / PostgreSQL 16.

## Prerequisites

- Java 21 (Temurin), Maven 3.9+, Docker (for Testcontainers integration tests).

## Generate the Maven wrapper (one-time)

The committed sources do not include the `.mvn/wrapper/` files (they were not generated
in the authoring environment). Generate them once:

```bash
cd backend
mvn -N wrapper:wrapper -Dmaven=3.9.11
```

This creates `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` so that
`./mvnw verify` works on any machine. Until then, use a locally installed `mvn`.

## Build & verify

```bash
cd backend
mvn -q -DskipTests compile    # compile only
mvn -q test                   # unit tests + ArchUnit gates + Spring Modulith verify()
mvn -q verify                 # adds Testcontainers integration tests (Docker required)
```

`JWT_SECRET` must be set for the app to start; tests use `application-test.yml`'s value.
For a local run: `export JWT_SECRET=...; export DB_PASSWORD=...` (see root `.env.example`).

## Engineering notes (do not regress)

- **Lombok over boilerplate (DIRECTIVE 1, ADR-0014):** entities use `@Getter`/`@Setter`/
  `@NoArgsConstructor` (+ explicit domain factories/mutators); services/config/controllers use
  `@RequiredArgsConstructor`. No hand-written getters/setters/constructors. `backend/lombok.config`
  sets `config.stopBubbling = true` and `lombok.addLombokGeneratedAnnotation = true`. On
  `AuditableEntity` the `id` field carries `@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)` —
  there is **no public getter for `id`** — and `uid` is getter-only (set in `@PrePersist`).
- **Plural table names (DIRECTIVE 2):** every DB table is PLURAL snake_case
  (`audit_logs`, `business_days`, `company_profiles`, `users`, `roles`, `privileges`,
  `role_privileges`, `user_roles`, `refresh_tokens`); the redundant `iam_` prefix is dropped.
  Columns stay snake_case singular. Constraints follow `pk_<table>` / `fk_<table>_<ref>` /
  `uq_<table>_<cols>` / `idx_<table>_<cols>`. JPA `@Table`/`@Column` names match the Flyway DDL
  exactly so Hibernate `ddl-auto=validate` passes.
- **Maven wrapper:** the committed repo does NOT include `./mvnw` / `.mvn/wrapper/` (those writes
  are blocked in the authoring sandbox). Run `mvn -N wrapper:wrapper` once locally to add the
  wrapper; until then (and in CI) the installed system `mvn` 3.9+ is used.
- **Annotation-processor order (ADR-0014 §3):** in `pom.xml`, `maven-compiler-plugin`
  `annotationProcessorPaths` lists **Lombok → lombok-mapstruct-binding → MapStruct**, in that
  order. Reversing it breaks MapStruct silently. Re-check on every dependency upgrade.
- **`id` is never exposed (ADR-0014 §1):** `AuditableEntity.id` has no public getter; DTOs are
  records without an `id`; routes use `/uid/{uid}`. `ApiConventionsArchTest` enforces this.
- **`ddl-auto=validate` everywhere (ADR-0003/0013):** Flyway owns the schema.
- **No `HMAC256` literal (ADR-0013 §3):** the signing key comes from `${JWT_SECRET}` via a
  `SecretKeySpec`; CI runs `grep -r 'HMAC256' src/` (zero-match) and `NoHardcodedSecretTest`
  mirrors it.
- **`privileges` claim on every token (ADR-0006):** both `/auth/token` and `/auth/token/refresh`
  emit `privileges` (never `roles`). `TokenEndpointsIT` asserts it on both.

## Dev admin credentials (seeded by `V2__seed_iam.sql`)

- username: `admin`
- password: `password`  *(documented dev default — rotate in every real environment)*

The stored value is a BCrypt hash of `password`. Production must replace this via a follow-on
migration or an admin bootstrap step.

## Privilege codes

`V2__seed_iam.sql` seeds the distinct privilege CODE strings extracted verbatim from the legacy
`@PreAuthorize` gates (`com.orbix.api`). `PrivilegeSeedIT` parses the expected set from the
migration and asserts every code is present after `flyway migrate`.
