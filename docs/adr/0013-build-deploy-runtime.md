# ADR-0013: Build, Deploy, and Runtime Platform

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect, devops-engineer)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

---

## Context

The legacy system runs as a single Spring Boot 2.2.5 JAR deployed manually against MySQL 5, with all configuration — including security-critical secrets — baked into `application.properties`. Specifically:

- `spring.datasource.password = <REDACTED>` is committed in plaintext.
- `jwt.secret = <REDACTED>` is set in properties but **never read** by any filter or controller; all four JWT-handling sites use the hardcoded literal `Algorithm.HMAC256("<REDACTED>".getBytes())`. The operative secret is `"<REDACTED>"` — an 8-character, all-lowercase, trivially brutable HMAC key.
- `spring.jpa.hibernate.ddl-auto = update` means there is no versioned DDL and no repeatable build; schema state is whatever Hibernate decided to do last run.
- There is no CI/CD pipeline, no container, and no infrastructure-as-code. Deployments are manual and environment-specific configuration is hand-edited.
- The frontend (Angular 16) is a separate artefact with its own build, not co-deployed.

The new system is a **greenfield build** starting from an empty database. There is no MySQL-to-PostgreSQL data migration, no row or financial reconciliation against legacy data, and no ETL job at any pipeline stage. The legacy codebase is reference-only archaeology.

The target system must deliver: reproducible builds across environments; a secrets management posture suitable for PHI/PII; database migrations that are auditable and reversible (Flyway — confirmed by the client); and a developer experience that lets engineers spin up the full stack locally in under five minutes.

The 14 bounded contexts span backend and a significant frontend (client-side document rendering, 177 `@PreAuthorize`-gated endpoints), so the container strategy must cover both tiers. Testcontainers is chosen for integration testing because every cross-context transaction (e.g., patient registration spanning Registration, Billing, and Clinical in one `@Transactional` call) requires a real PostgreSQL 16 instance to verify.

A related open decision — flagged here for resolution before the first migration is authored — is **reference/master-data seeding**: the 177 `@PreAuthorize` privilege codes, clinic/ward/pharmacy/store masterdata, medicine and lab-test types, dosage/route/frequency picklists, and insurance plan structures are all required before the system is operationally usable. Because the system starts empty, these must be delivered as Flyway seed scripts (not a dev-only seeder and not an admin wizard), so that every environment — including CI — comes up with a consistent, complete reference dataset. The data-architect owns this decision and must confirm the seeding strategy before the first staging deployment.

---

## Decision

**Ratify the recommended default in full.** The platform is:

1. **Containerisation:** Multi-stage Docker builds for both the Spring Boot backend and the Angular frontend. Backend stage 1 uses `eclipse-temurin:21-jdk-alpine` to run `./mvnw package -DskipTests`; stage 2 uses `eclipse-temurin:21-jre-alpine` to produce a minimal runtime image. Frontend stage 1 uses `node:20-alpine` to run `npm ci && npm run build`; stage 2 uses `nginx:1.27-alpine` to serve static assets with a hardened `nginx.conf`. Image tags use the Git SHA for traceability.

2. **Local dev (Docker Compose):** A single `docker-compose.yml` starts PostgreSQL 16, a Flyway migration runner (which applies `V1__baseline.sql` and all subsequent scripts before the app boots), and the backend and frontend containers. A `docker-compose.override.yml` pattern allows developer-specific overrides without polluting the committed baseline. Target: `docker compose up` produces a fully seeded, running stack in under five minutes.

3. **CI/CD (GitHub Actions):** On every PR and every merge to `main`:
   - Build (Maven wrapper, Node/npm lockfile — both pinned for reproducibility)
   - Unit tests
   - Integration tests (Testcontainers — PostgreSQL 16 is started in-process; no external DB required in CI)
   - SAST: SpotBugs + Find Security Bugs on the backend; ESLint security rules on the frontend
   - Dependency scan: OWASP Dependency-Check (backend) and `npm audit` (frontend)
   - Container image scan: Trivy — critical and high CVEs block promotion
   - **Release gate — HMAC256 literal scan:** `grep -r 'HMAC256' src/` must return zero matches before any image is promoted to staging. This gate is owned by security-architect and must be added to `ci.yml` as a required check.
   - Package and push to container registry (e.g., GitHub Container Registry or cloud-native equivalent)

   There is **no data-migration job** in the pipeline at any stage. The system is greenfield; the only database operations the pipeline performs are Flyway DDL migrations and reference-data seed scripts.

   Branch protection rules enforce: all checks green, at least one code-reviewer approval, no direct push to `main` or `release/*`.

4. **Infrastructure as Code (Terraform):** All cloud/on-prem resources are provisioned via Terraform: compute, managed PostgreSQL 16 instance, container registry, networking, TLS, DNS, object storage for DB backups, and the secrets store. Terraform state is stored remotely with locking. Drift detection runs on a scheduled workflow. No manual console changes are permitted in staging or production.

5. **Staged environments:** `dev` → `staging` → `production`. Promotion from staging to production requires a manual approval gate in GitHub Actions. Each environment has its own PostgreSQL instance and its own secrets. Flyway migrations run automatically on deployment — the pipeline validates that the migration plan is forward-only before applying.

6. **Externalized configuration and secrets:**
   - All environment-specific values (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, SMTP credentials, insurance API tokens) are injected as environment variables at runtime, never baked into images or committed to source.
   - In dev: Docker Compose reads from a `.env` file that is `.gitignore`d; a `.env.example` with placeholder values is committed.
   - In CI: GitHub Actions secrets supply values to the runner environment.
   - In staging and production: a dedicated secrets store (HashiCorp Vault or cloud-native equivalent — **devops-engineer to select based on target infrastructure**) provides secrets via environment injection; the Terraform code provisions the store and defines access policies. Secrets are rotatable without redeployment.
   - Spring Boot reads all secrets via `${DB_PASSWORD}`, `${JWT_SECRET}` etc. in `application.yml`; no secret literal appears anywhere in the repository.

7. **Observability wiring:** Spring Actuator (`/actuator/health`, `/actuator/metrics`) + Micrometer + OpenTelemetry SDK are wired from day one. The pipeline provisions Prometheus scraping and a Grafana stack (or equivalent). All bounded-context modules emit named metrics. The Actuator management port is bound only on the internal network, never exposed publicly.

---

## Secrets — Critical Path

The legacy system has hardcoded secrets at exactly five locations. The new system must ensure none of these patterns survives into any commit.

**Complete inventory — verified by grep against the legacy codebase (`D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api`):**

| Secret | Legacy value | Legacy location | Migration action |
|--------|-------------|-----------------|-----------------|
| DB password | `<REDACTED>` | `application.properties` line 5 | Replace with `${DB_PASSWORD}` env var; provision a least-privilege PostgreSQL application user in Terraform; no MySQL root-privilege assumptions carry forward |
| JWT signing key | `"<REDACTED>"` (literal) | `CustomAuthenticationFilter.java:76` (token issuance at login) | Replace with `${JWT_SECRET}` env var; generate a cryptographically random 256-bit key per environment |
| JWT signing key | `"<REDACTED>"` (literal) | `CustomAuthorizationFilter.java:73` (token verification on every protected request) | Same `${JWT_SECRET}` env var — both filters and both UserResource paths must be replaced atomically |
| JWT signing key | `"<REDACTED>"` (literal) | `UserResource.java:352` (token refresh endpoint — verifies incoming refresh token) | Same `${JWT_SECRET}` env var |
| JWT signing key | `"<REDACTED>"` (literal) | `UserResource.java:516` (private helper `getUsernameFromAuthorizationHeader()` — called by user-lookup endpoints) | Same `${JWT_SECRET}` env var |

**Note:** The property value `<REDACTED>` in `application.properties` was never operative — none of these four JWT sites reads the property. The operative secret has always been the in-code literal `"<REDACTED>"`.

**Release gate:** security-architect must run `grep -r 'HMAC256' src/` against the new codebase and confirm zero matches as a mandatory step before the first staging deployment. This grep is also automated as a required CI check (see Decision §3 above).

The JWT signing-key change means all tokens issued by the legacy system become invalid when the new system goes live. This is expected and acceptable at cutover — users will be required to re-authenticate. **security-architect must document all four replacement sites in the cutover runbook and confirm no silent session-carryover assumption exists in the Angular frontend.**

---

## Considered Alternatives

| Option | Why rejected |
|--------|-------------|
| Plain JAR + shell script deploy (legacy approach) | No reproducibility, no secrets management, no rollback; unacceptable for PHI/production |
| Single-stage Docker (no multi-stage) | Bloats image with JDK and build tools; increases attack surface and image size |
| Jenkins instead of GitHub Actions | Additional infrastructure to manage; GitHub Actions is sufficient and keeps CI co-located with source |
| ArgoCD / GitOps for deployment | Valid for Kubernetes-first shops; premature if target infra is VM-based or single-node; devops-engineer retains discretion to adopt if Kubernetes is the deployment target |
| Liquibase instead of Flyway | Client has explicitly confirmed Flyway as the migration tool; not reconsidered here |
| docker-compose for production | Not suitable for production workloads — no restart guarantees, no resource limits, no orchestration; Terraform-managed deployment is the production path |
| Data-migration ETL job in pipeline | Engagement decision is greenfield with empty DB; there is no legacy data to migrate, no sequence seeding, and no ETL step at any stage |

---

## Consequences

**Positive:**
- Secrets are externalized and auditable; the trivially-guessable `"<REDACTED>"` JWT key is replaced with a proper random key per environment across all four legacy sites.
- The automated HMAC256 literal grep gate in CI makes it structurally impossible for a residual hardcoded key to ship — not just a documentation promise.
- `docker compose up` gives every engineer a faithful local environment with real PostgreSQL 16 and Flyway-applied migrations — no more Hibernate `ddl-auto=update` surprises.
- CI enforces that every PR is buildable, testable, and scannable before merge; broken builds cannot reach staging.
- Testcontainers integration tests validate the cross-context `@Transactional` patterns (registration+billing+clinical, goods-received approval spanning inventory+procurement) against a real database engine, not an H2 approximation.
- Trivy and OWASP Dependency-Check catch vulnerable transitive dependencies early, before they reach a production healthcare system.
- Infrastructure is reproducible and reviewable; no environment is a snowflake.
- The greenfield starting point eliminates an entire class of risk (legacy schema carry-forward, sequence collision, orphaned FK data) at the cost of requiring complete reference-data seeding from scratch.

**Negative:**
- Multi-stage builds and Testcontainers add to CI run time. Parallel job splitting and layer caching (Docker BuildKit, GitHub Actions cache) will be required to keep PRs under 15 minutes.
- Flyway DDL and seed scripts must be authored before the first `docker compose up` works. The data-architect must deliver `V1__baseline.sql` and the reference-data seed migrations before any backend engineer can run integration tests locally. This is a sequencing dependency the engagement-lead must schedule.
- Secrets store introduces operational complexity in dev onboarding. The `.env.example` pattern and a brief setup guide in `README.md` (authored by devops-engineer) mitigate this.
- Reference/master-data seeding is a non-negotiable blocker: the 177 privilege codes must be present before any `@PreAuthorize`-gated endpoint is testable. The seeding strategy (Flyway scripts vs. first-run wizard) must be confirmed by the data-architect before work on V2+ migrations begins.

**Risks and mitigations:**

| Risk | Mitigation |
|------|-----------|
| Legacy DB password `<REDACTED>` committed to history | New repo has no legacy `application.properties`; the legacy repo is read-only archaeology. Not a live risk in the new codebase — confirm with security-architect. |
| JWT key migration misses one of the four HMAC256 sites, shipping a residual hardcoded key | Mandatory `grep -r 'HMAC256' src/` release gate in CI (zero-match required); security-architect verifies all four sites explicitly in cutover runbook |
| JWT key rotation invalidates in-flight sessions at cutover | Coordinated maintenance window; documented in cutover runbook; frontend must handle 401 gracefully with a re-login prompt |
| Fresh V1 DDL is large (14 bounded contexts, 100+ entities) and error-prone | data-architect delivers V1 in reviewable segments, validated against a clean PostgreSQL 16 instance before it is declared the baseline; no migration from legacy schema is involved |
| Reference/master-data missing at first boot breaks privilege checks and worklist queries | 177 privilege codes and clinic/ward/pharmacy/store masterdata are seeded in a dedicated Flyway script (e.g., `V2__seed_reference_data.sql`) that runs before the app accepts traffic; this script is a required CI artefact from day one |
| Trivy/OWASP false positives blocking PRs | Security-architect defines a suppression policy; suppressions are code-reviewed and time-bounded |
| Terraform state corruption | Remote state backend with locking (e.g., S3 + DynamoDB, or Terraform Cloud); state is backed up before every `apply` |

---

## Exact-Process Impact

**What this ADR preserves:** The platform layer is transparent to business process. The `@PreAuthorize` privilege codes, document numbering, billing calculations, insurance plan lookups, report outputs, and workflow state machines are unaffected by how the application is containerised or how its secrets are injected. No business rule changes.

**Confirmed scheduled jobs required in the new environment (not questions — confirmed from prior-attempt lessons):**
- A **daily ward-day accrual job** (`@Scheduled`) is a hard requirement for inpatient billing correctness. The prior build omitted this and CASH inpatients could be discharged with zero ward-days billed. The devops-engineer must ensure the container entrypoint does not suppress Spring's task scheduler, and the CI pipeline must include at least one integration test that fires the scheduler and verifies a ward-day charge is posted.
- Any other periodic jobs (e.g., insurance claim submission reminders, daily cash-up triggers) are the backend-engineer's responsibility to enumerate and register; the platform must not block `@Scheduled` execution.

**What the frontend engineer must confirm before devops-engineer finalises the nginx configuration:**
- Whether the Angular frontend has any hardcoded API base URL that assumes `localhost:8081` and will require an `$API_BASE_URL` environment variable substitution at the nginx layer (likely yes — must be resolved before the first staging deployment).

**Change requests implied:**
- The JWT signing key change at cutover constitutes a forced re-authentication event for all users. This is a security improvement, not a process change, but the product owner should communicate it to end users in advance. The four sites that must change atomically are: `CustomAuthenticationFilter.java:76`, `CustomAuthorizationFilter.java:73`, `UserResource.java:352`, and `UserResource.java:516`.
- The new system uses a least-privilege PostgreSQL application user rather than a root-equivalent account. The data-architect must confirm that no application query relies on elevated database privileges; in a fresh build this is inherently satisfied by designing queries from scratch.

---

## Implementation Notes

**devops-engineer owns all artifacts below; this section provides the architectural constraints, not the implementation.**

- **Docker:**
  - Backend: `eclipse-temurin:21-jdk-alpine` build stage → `eclipse-temurin:21-jre-alpine` runtime. JVM flags: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`. Run as non-root UID 1000.
  - Frontend: `node:20-alpine` build stage → `nginx:1.27-alpine` runtime. Nginx serves on port 80; TLS termination is upstream (load balancer / reverse proxy). `$API_BASE_URL` injected via `nginx.conf` environment substitution at container startup.
  - Both images pinned to SHA digests in production.

- **Docker Compose (dev):**
  - Services: `db` (postgres:16-alpine), `migrate` (flyway/flyway:10, depends_on db, runs and exits), `backend` (depends_on migrate), `frontend` (depends_on backend, optional for pure API dev).
  - Volumes: named volume `pgdata` for the DB; bind-mount `./src/main/resources/db/migration` into the Flyway container.
  - Health checks on `db` before `migrate` starts; health check on `migrate` (exit 0) before `backend` starts.
  - No ETL container, no legacy-database container, no data-sync service at any stage of Compose or pipeline.

- **GitHub Actions:**
  - Workflows: `ci.yml` (PR gate), `release.yml` (main branch → push to registry), `terraform-plan.yml` (infra PR review), `terraform-apply.yml` (post-merge to `infra/main`).
  - Maven: `./mvnw -B verify` with `-Dtestcontainers.reuse.enable=false` in CI to guarantee clean state.
  - Trivy: `aquasecurity/trivy-action@v0.x` with `exit-code: 1` on CRITICAL/HIGH.
  - OWASP: `dependency-check/dependency-check-action@main`; fail on CVSS >= 7.
  - **HMAC256 literal gate (required check, blocks merge):**
    ```yaml
    - name: No hardcoded JWT secrets
      run: |
        count=$(grep -r 'HMAC256' src/ | wc -l)
        if [ "$count" -ne "0" ]; then
          echo "ERROR: $count hardcoded HMAC256 literal(s) found — externalize via JWT_SECRET env var"
          grep -rn 'HMAC256' src/
          exit 1
        fi
    ```
  - Image tag pattern: `ghcr.io/org/zana-hmis-api:${GITHUB_SHA}` and `:latest` on main.

- **Spring Boot configuration:**
  - `application.yml` (committed, no secrets): all values reference `${ENV_VAR:default_for_local}`.
  - `application-dev.yml`: developer-friendly defaults (SQL logging on, Flyway `clean` disabled, Actuator fully open on localhost only).
  - `application-prod.yml`: SQL logging off, Actuator management port restricted, HTTPS enforced.
  - `spring.flyway.locations=classpath:db/migration` — all Flyway scripts live under `src/main/resources/db/migration/`.
  - `spring.jpa.hibernate.ddl-auto=validate` in all profiles: Hibernate validates against the Flyway-managed schema but never mutates it.

- **JWT configuration in `application.yml`:**
  ```yaml
  jwt:
    secret: ${JWT_SECRET}
    access-token-expiry-hours: 8
    refresh-token-expiry-hours: 24
  ```
  All four legacy HMAC256 sites (`CustomAuthenticationFilter`, `CustomAuthorizationFilter`, `UserResource.refreshToken`, `UserResource.getUsernameFromAuthorizationHeader`) must be refactored to inject this value via `@Value("${jwt.secret}")`. No other approach is acceptable.

- **Secrets store integration:** devops-engineer selects the concrete store based on the target cloud/on-prem platform. The Spring Boot app consumes secrets exclusively via environment variables — no Vault SDK dependency in the application code. The secrets store injects env vars into the container at startup. This keeps the application portable and testable without a live secrets store in dev.

- **Key library versions (to align with ADR-0001 target stack):**
  - Flyway: 10.x (supports PostgreSQL 16; Java 21 compatible)
  - Testcontainers: 1.20.x with `postgresql` module
  - Trivy: latest stable at pipeline authoring time, pinned by SHA
  - Terraform: 1.8.x (pin provider versions in `versions.tf`)

- **Peer handoffs:**
  - **data-architect:** deliver `V1__baseline.sql` (fresh DDL for all 14 bounded contexts, ULID `CHAR(26)` identifiers, no legacy schema carry-forward) and `V2__seed_reference_data.sql` (177 privilege codes, clinic/ward/pharmacy/store masterdata, medicine/lab-test types, dosage/route/frequency picklists) to `src/main/resources/db/migration/` before devops-engineer finalises the Compose `migrate` service. This is a hard blocking dependency for all backend integration testing.
  - **security-architect:** (1) confirm the acceptable JWT key length and rotation policy; (2) provide the Vault/secrets store access-policy specification to devops-engineer; (3) verify all four HMAC256 sites are absent in the new codebase and document this confirmation in the cutover runbook; (4) own the `grep -r 'HMAC256' src/` CI gate as a required check before the first staging promotion.
  - **backend-engineer:** the four HMAC256 sites are a day-one remediation item — no other backend work proceeds until `CustomAuthenticationFilter`, `CustomAuthorizationFilter`, and both methods in `UserResource` are refactored to use `@Value("${jwt.secret}")`. Additionally, all `@Scheduled` jobs (ward-day accrual at minimum) must be registered and verified through the same CI Testcontainers pipeline.
  - **qa-test-engineer:** receives the Compose stack and GitHub Actions pipeline as the test execution environment; must confirm Testcontainers integration test coverage includes at least one cross-context `@Transactional` scenario per bounded context, and at least one `@Scheduled` ward-day accrual scenario for inpatient billing.
  - **backend-engineer + frontend-engineer:** must not commit any secret, credential, or environment-specific URL; the `.gitignore` and a pre-commit hook (configured by devops-engineer) enforce this.
