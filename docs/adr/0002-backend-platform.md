# ADR-0002: Backend Platform & Language — Java 21 + Spring Boot 3.3

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"

---

## Context

The legacy Zana HMIS backend runs Java 11 + Spring Boot 2.2.5 with a cluster of end-of-life dependencies. The concrete migration gap is non-trivial and must be sized before committing to an in-place upgrade vs. a ground-up rebuild:

| Area | Legacy | Gap |
|---|---|---|
| Namespace | `javax.*` everywhere | All imports → `jakarta.*` (Spring Boot 3 hard requirement) |
| Security config | `WebSecurityConfigurerAdapter` subclass | Class removed in Spring Security 6; replace with `SecurityFilterChain` bean + lambda DSL |
| Hibernate | 5 (`MySQL5InnoDBDialect`, `ddl-auto=update`) | Hibernate 6 — dialect class renamed, DDL generation semantics changed; no authoritative schema exists |
| Swagger | springfox 2.7 | springfox incompatible with Spring Boot 3; replace with springdoc-openapi 3 |
| JWT | auth0 `java-jwt` 3.x (hardcoded secret `"secret"`) | Migrate to Spring Security OAuth2 Resource Server + `spring-security-oauth2-jose`; secret must be externalized |
| Lombok | `@Data` inconsistently applied (`User` deliberately avoids it) | Lombok 1.18.x required; `@Data` exclusion on `User` must be preserved |
| Money/Decimal | `private double` on 115+ entity files; zero `BigDecimal` | Pre-approved upgrade to `BigDecimal`; no source compatibility — requires field-by-field rewrite |
| Schema management | None (Hibernate auto-DDL, no migration tool) | Schema must be reverse-engineered from live MySQL DB + entities; Flyway baseline scripts authored from scratch |

In addition: 177 `@PreAuthorize` annotations across 45 resource classes use auth0-style `privileges` claim, `PatientServiceImpl` crosses all 14 bounded contexts, `ReportResource` (1,748 lines, 30+ injected repositories) performs in-memory O(n×m) joins, and the JWT secret is hardcoded — not read from properties. These are not upgrade tasks; they are rewrites.

An in-place upgrade of these interconnected breakages carries compounding risk with no architectural gain. A ground-up rebuild that reuses verified business logic (privilege codes, document-number formats, process flows) is the safer and architecturally superior path.

---

## Decision

**Ratify: Java 21 LTS + Spring Boot 3.3 as the backend platform for the ground-up rebuild.**

This is a rebuild, not an upgrade. Every source file is authored for the target stack. Proven business logic — the 177 privilege codes, document-number format strings, process sequencing — is ported verbatim. Internal mechanics (double → BigDecimal, Hibernate auto-DDL → Flyway, springfox → springdoc, javax → jakarta) are replaced wholesale, not patched.

---

## Considered Alternatives

| Option | Decision | Reason rejected |
|---|---|---|
| Java 17 LTS + Spring Boot 3.1 | Rejected | Java 21 is current LTS; virtual threads (Project Loom) benefit the report workload; no meaningful extra effort |
| In-place upgrade of Spring Boot 2.2.5 → 3.x | Rejected | `javax→jakarta`, `WebSecurityConfigurerAdapter` removal, Hibernate 6 dialect changes, springfox incompatibility, and zero-authoritative-schema combine to make this riskier than a rebuild with identical scope |
| Micronaut / Quarkus | Rejected | Team expertise is Spring; Spring Modulith provides the modular monolith target; ecosystem fit for Spring Data JPA projections, Envers, Actuator |
| Kotlin + Spring Boot 3.3 | Rejected | Legacy is Java; rebuild team familiarity not confirmed; risk of skill mismatch outweighs language ergonomics gains |

---

## Consequences

**Positive**
- Java 21 virtual threads (`spring.threads.virtual.enabled=true`) absorb the blocking JDBC workload in `ReportResource` without a reactive rewrite.
- Spring Security 6 `SecurityFilterChain` DSL is cleaner; `@PreAuthorize` method security is fully supported and the 177 privilege-code annotations are ported without semantic change.
- springdoc-openapi 3 enables contract-first API design, replacing the ad-hoc springfox-annotated controllers.
- Hibernate 6 + PostgreSQL 16 cleanly support the dual-identifier strategy: a hidden `BIGINT` identity `id` plus a public `CHAR(26)` ULID `uid`.
- `BigDecimal` adoption eliminates floating-point billing errors; client has pre-approved this improvement.
- Flyway baseline scripts give the project its first authoritative DDL — a prerequisite for safe future migrations.

**Negative / Risks**

| Risk | Mitigation |
|---|---|
| Hibernate 6 breaking changes in JPQL and `@OneToMany` fetch semantics | All 21 report projection interfaces must be regression-tested against PostgreSQL 16 before go-live; assign to qa-test-engineer |
| `jakarta.*` namespace sweep is mechanical but large (~115 entity files, 45 resource files) | Automated IDE migration + compile-time verification; not a logic risk |
| `BigDecimal` arithmetic changes trailing digits vs. legacy `double` | Parity tests compare business totals with a defined tolerance; any trailing-digit difference is documented as an accepted improvement, not a defect. qa-test-engineer owns golden-master report tests |
| Spring Security 6 DSL — `WebSecurityConfigurerAdapter` removal | security-architect owns `SecurityFilterChain` bean design; the `CustomAuthorizationFilter` privilege-claim logic is preserved verbatim |
| Flyway baseline requires a live MySQL 5 introspection | data-migration-engineer must dump the live `zana_hmis_db_test` schema before any source entity is touched; this is the authoritative schema baseline |

---

## Exact-Process Impact

**Preserved verbatim:**
- All 177 `@PreAuthorize("hasAnyAuthority('CODE',...)")` privilege codes and their endpoint assignments.
- Document-number format strings: `GRN{yyyyMMdd}-{id}`, `LPO{yyyyMMdd}-{id}`, `MRNO/{year}/{rawId}`, `USR-{000000}`, etc.
- Business-day open/close workflow (`Day` entity, `DayService`) — this is a confirmed business-process artifact, not an implementation detail. legacy-analyst must confirm with the product owner whether the Day workflow is mandatory before it is modelled in the new bounded context.
- JWT `privileges` claim name and array structure; BCrypt password hashing.
- 8-hour access / 24-hour refresh token expiry.
- UTC+3 timestamp offset — data-migration-engineer must normalize all legacy `createdAt` timestamps to UTC during Flyway migration; API layer renders in local time. legacy-analyst must confirm the timezone with the product owner.

**Accepted improvements (client pre-approved, not change-requests):**
- `double` → `BigDecimal` on all monetary and quantity fields.
- Atomic document-number generation via PostgreSQL `SEQUENCE` objects (one per document type), replacing the racy `SELECT MAX(id)+1` pattern. The "SPT" prefix collision between StoreToPharmacy-TO and PharmacyToPharmacy-TO must be resolved by the product owner before sequences are defined — legacy-analyst to raise.
- JWT secret externalized to environment variable; hardcoded `"secret"` eliminated (security-architect to specify secret rotation policy).

---

## Implementation Notes

**Stack versions (locked for this ADR):**

```
Java                  21 (LTS, Temurin distribution)
Spring Boot           3.3.x (latest patch at project start)
Spring Security       6.3.x (via Boot BOM)
Spring Data JPA       3.3.x / Hibernate 6.5.x
springdoc-openapi     2.5.x  (springdoc-openapi-starter-webmvc-ui)
Flyway                10.x   (flyway-core + flyway-database-postgresql)
MapStruct             1.5.x  (annotation processor; replaces manual entity→DTO mapping)
Lombok                1.18.32+ (annotation processor; @Data excluded on User — preserve)
jjwt / spring-security-oauth2-jose  (replace auth0 java-jwt 3.x)
JUnit 5 + Testcontainers  (PostgreSQL 16 container for integration tests)
```

**Security migration (security-architect):**
Replace `WebSecurityConfigurerAdapter` with a `@Bean SecurityFilterChain`. Configure Spring Security OAuth2 Resource Server with a symmetric `SecretKeySpec` loaded from `JWT_SECRET` env var. Port `CustomAuthorizationFilter` privilege-claim extraction into a `JwtAuthenticationConverter`. All 177 `@PreAuthorize` annotations are copied verbatim into the new resource classes.

**Persistence (data-architect):**
Use `@Column(columnDefinition = "char(26)")` for `uid` fields on all entities, generated in a `@PrePersist` via `ulid-creator` `UlidCreator.getMonotonicUlid()` (ULID, per ADR-0003). Define one PostgreSQL `SEQUENCE` per document type in the Flyway V1 migration. Set `spring.jpa.hibernate.ddl-auto=validate` — never `update` — in all environments; Flyway owns schema state.

**Decimal migration:**
All `private double` fields become `private BigDecimal`. JPA column mapping uses `@Column(precision=19, scale=2)` for monetary fields and `@Column(precision=19, scale=6)` for quantities — single-sourced to ADR-0003 / ADR-0009. Arithmetic uses `BigDecimal.setScale(2, RoundingMode.HALF_UP)` for billing totals. qa-test-engineer defines golden-master tests against the 29 confirmed report endpoints before cutover.

**Report layer:**
`ReportResource` in-memory joins are replaced with JPQL projections or native SQL views in PostgreSQL. The 21 existing projection interfaces in `reports/models` are the direct migration targets. devops-engineer provisions a read replica or dedicated reporting datasource to isolate report query load.

**Package structure:**
Adopt package-by-feature aligned to the 14 bounded contexts (e.g., `com.zana.hmis.inventory`, `com.zana.hmis.billing`) using Spring Modulith module boundaries. Cross-context calls go through published application-event or facade interfaces — breaking the `PatientServiceImpl` god-object is the first and highest-risk decomposition task.
