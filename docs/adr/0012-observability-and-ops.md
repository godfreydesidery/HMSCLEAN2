# ADR-0012: Observability & Operations

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect, devops-engineer, qa-test-engineer)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"

---

## Context

The legacy Zana HMIS backend has **zero production observability**. Key findings from the legacy read:

- `application.properties` (lines 16–20) has `spring.jpa.show-sql=true`, `logging.level.org.hibernate.SQL=DEBUG`, and `logging.level.org.hibernate.type=TRACE`. The `org.hibernate.type` logger at TRACE emits **bound parameter values** — meaning actual PHI (patient names, MR numbers, diagnosis-related fields) reaches the log stream on every SQL execution. The SQL-statement logger at DEBUG is a secondary concern; both must be suppressed. These must be permanently disabled in every non-developer environment.
- There are no health probes, no metrics endpoints, no structured logs, and no distributed trace IDs. Incidents are diagnosed by reading raw MySQL logs or console output.
- The system processes **financial transactions** (billing, cashiering, insurance claims — all previously in floating-point double, now migrated to BigDecimal) and **clinical records** (prescriptions, lab orders, admissions) that require regulated audit visibility and SLO enforcement.
- The codebase spans **14 bounded contexts**, **51 resource classes**, **177 `@PreAuthorize` annotations**, and **29+ report endpoints**. `ReportResource` alone performs in-memory O(n×m) joins across all contexts. Without metrics, latency regressions in reports are invisible until a user complains.
- The `Day` entity workflow (business-day open/close) gates all `createdOn` stamping. A stuck or failed day transition affects every write across every context — a scenario that must be surfaced by a health indicator, not discovered manually.
- The JWT secret was hardcoded as the literal `"<REDACTED>"` (line 27). Security-architect's externalized-secrets mandate means all credentials — including observability sinks such as Grafana API keys and Prometheus remote-write tokens — must never appear in source or logs.

The target stack (Java 21, Spring Boot 3.3, PostgreSQL 16, Docker + GitHub Actions) has first-class support for the full observability stack recommended by the engagement.

---

## Decision

**Adopt the full recommended observability stack: Spring Boot Actuator + Micrometer + OpenTelemetry traces + Prometheus/Grafana, structured JSON logging with correlation IDs, health/readiness probes, and SLOs/alerts on financial and clinical endpoints. PHI-safe logging is mandatory and non-negotiable.**

All five pillars are ratified without revision:

1. **Metrics** — Micrometer with the Prometheus registry (`io.micrometer:micrometer-registry-prometheus`). Custom meters on billing (`bill.created`, `payment.applied`), pharmacy stock (`stock.dispensed`, `stock.low`), and insurance claim state transitions. Actuator `/actuator/prometheus` scraped by Prometheus; devops-engineer owns the scrape config and Grafana dashboards per bounded context.

2. **Traces** — OpenTelemetry Java agent (`opentelemetry-javaagent`) injected at container startup via `JAVA_TOOL_OPTIONS`. W3C TraceContext propagation. Every inbound HTTP request and every cross-context service call acquires a `traceId` + `spanId`. The same `traceId` flows into the structured log output (see below). devops-engineer wires the OTLP exporter to Grafana Tempo or Jaeger — that infrastructure decision is theirs.

3. **Structured JSON logging** — Logback with `logstash-logback-encoder`. Every log line is a JSON object containing: `timestamp`, `level`, `logger`, `message`, `traceId`, `spanId`, `userId` (the internal numeric id of the authenticated user — not their name, not their privilege list), and `requestId` (UUID generated at filter entry). **PHI fields are never logged**: patient name, MR number (`MRNO/{year}/{id}`), diagnosis codes, prescription contents, and insurance member numbers are classified as PHI by security-architect and must not appear in any log line at any level. `show-sql=false` and all Hibernate SQL and parameter-binding loggers at WARN in all non-dev profiles. Developer local profile may enable `show-sql` only via an explicit opt-in environment variable; this profile must be blocked from CI and production by Spring profile guards.

4. **Health and readiness probes** — Actuator `/actuator/health/liveness` and `/actuator/health/readiness`. Custom `HealthIndicator` beans for: (a) PostgreSQL connectivity, (b) current business-day status (`Day.status = STARTED` — a stuck day means all writes will fail), (c) Flyway migration state (must be `SUCCESS` before readiness returns UP). Container orchestrator (Docker or Kubernetes) uses these probes for rolling-deploy safety. devops-engineer wires them into the deployment descriptor.

5. **SLOs and alerts** — Prometheus alerting rules (managed by devops-engineer in the Terraform-sourced Grafana config) covering:
   - P99 latency > 2 s on any endpoint tagged `domain=billing` or `domain=clinical` — page.
   - HTTP 5xx error rate > 1 % over 5 min on financial or clinical endpoints — page.
   - Business-day health indicator DOWN for > 5 min — page.
   - Low-stock threshold breached (pharmacy `stock.low` counter) — warn.
   - Report endpoint P99 latency > 10 s — warn (baseline for the known O(n×m) join replacement work).

Actuator management endpoints (health detail, metrics, Prometheus, info, env, loggers) are exposed **only on the internal management port (default 8081 management port, separate from the API port 8082)** and are never reachable from the public network interface. This is security-architect's PHI-safe surface requirement.

---

## Considered alternatives

| Alternative | Why rejected |
|---|---|
| ELK stack (Elasticsearch + Logstash + Kibana) for logs + metrics | Heavier operational footprint; adds two additional stateful services; Grafana already chosen for metrics dashboards — a unified Grafana + Loki + Tempo + Prometheus stack is operationally simpler for a single-team deployment. |
| Sentry for error tracking only | Point solution; does not provide latency SLOs, structured traces, or business-level metrics. Complements but does not replace Prometheus/Grafana. |
| Spring Sleuth (legacy) | Spring Sleuth is end-of-life as of Spring Boot 3.x; replaced by Micrometer Tracing + OpenTelemetry. Not applicable. |
| Custom MDC-only correlation without OTel | Achieves correlation IDs in logs but provides no distributed trace visualisation, no span-level latency breakdown, and no automatic instrumentation of JDBC and HTTP client calls. Insufficient for 14-context debugging. |
| Keep show-sql=true in staging | Rejected absolutely. Staging uses production-like data from the 3-year dataset migration. PHI in staging logs is a compliance violation identical to production. |

---

## Consequences

**Positive**
- Incident diagnosis changes from "read raw MySQL logs" to structured trace queries — MTTR drops significantly.
- Financial and clinical SLO breaches are detected proactively rather than after user complaints.
- The `Day` entity failure mode (stuck business day) surfaces within 5 minutes via the custom health indicator.
- PHI-safe logging provides a defensible compliance posture from day one; legacy's parameter-value TRACE logging defect (the primary PHI vector) and SQL DEBUG logging are both permanently closed.
- Correlation IDs (`traceId`) link every log line to the user request that caused it — essential for security-architect's audit investigations.

**Negative / costs**
- Two additional infrastructure components (Prometheus, Grafana; or Grafana Cloud) must be provisioned and maintained by devops-engineer.
- The OTel Java agent adds ~50–80 ms cold-start latency and ~5–10 % CPU overhead at high throughput — acceptable for an HMIS workload; to be re-evaluated if throughput exceeds projections.
- PHI exclusion in logging requires developers to actively annotate or scrub log statements — qa-test-engineer must include a PHI-in-logs check in the security acceptance suite.

**Risks and mitigations**
- **Risk:** Developer adds a debug log line that includes a patient name or diagnosis — not caught until audit. **Mitigation:** qa-test-engineer implements an automated log-scrubbing test (golden-path request + assert no PHI fields appear in captured log output) and security-architect includes this in the PR review checklist.
- **Risk:** Actuator management port is accidentally exposed via a misconfigured reverse proxy. **Mitigation:** devops-engineer applies network-policy allow-list (internal subnet only) at the infrastructure level, not just application config, per the defence-in-depth principle.
- **Risk:** `show-sql` re-enabled in a non-dev profile by accident. **Mitigation:** backend-engineer implements a `@Profile("!dev")` configuration assertion that fails application startup if `spring.jpa.show-sql=true` and active profile is not `dev`.
- **Risk:** Hibernate parameter-binding logger renamed in Hibernate 6 — using only the Hibernate 5 logger name (`org.hibernate.type.descriptor.sql`) leaves `org.hibernate.orm.jdbc.bind` unsuppressed, allowing bound PHI parameter values to reach logs. **Mitigation:** Both logger names are pinned to WARN explicitly (see implementation notes); this covers both Hibernate 5 and Hibernate 6 and is future-proof against a library downgrade.

---

## Exact-process impact

**Preserved:** None of the 14 bounded-context business processes are affected. Observability is entirely cross-cutting infrastructure; it does not alter billing calculations, prescription workflows, inventory state transitions, or document-number generation.

**Legacy defect closed (not a process change):** `show-sql=true`, `org.hibernate.SQL=DEBUG`, and `org.hibernate.type=TRACE` in production are a security defect, not a business feature. The TRACE level on `org.hibernate.type` is the critical vector — it emits bound SQL parameter values, i.e., raw PHI on every query. Disabling all three is a non-negotiable remediation, not a change request.

**Legacy-analyst must confirm:** Whether any existing integration (e.g., an external insurance portal or the Angular frontend) polls any Spring Actuator-style URL or health endpoint on the legacy backend. If so, the probe URL mapping must be preserved or the consuming system updated. Currently assessed as absent — legacy has no Actuator on the classpath.

**Change requests implied:** None. All decisions are within the solution-architect's pre-approved scope.

---

## Implementation notes

**Dependencies (add to `pom.xml`):**
```xml
<!-- Metrics -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<!-- Tracing via Micrometer + OTel bridge -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<!-- Structured JSON logging -->
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```

**Spring Boot application properties (non-dev profile):**
```properties
# Disable PHI-leaking SQL logging — mandatory in all non-dev profiles
spring.jpa.show-sql=false

# org.hibernate.SQL: suppresses SQL statement logging (was DEBUG in legacy)
logging.level.org.hibernate.SQL=WARN

# org.hibernate.orm.jdbc.bind: Hibernate 6 parameter-binding logger
# (logs bound parameter VALUES — the primary PHI vector; was org.hibernate.type=TRACE in legacy)
logging.level.org.hibernate.orm.jdbc.bind=WARN

# org.hibernate.type.descriptor.sql: Hibernate 5 equivalent — kept for defence-in-depth
# in case the dependency tree ever pulls in a Hibernate 5 artefact
logging.level.org.hibernate.type.descriptor.sql=WARN

# Actuator: expose only on management port, no public access
management.server.port=8081
management.endpoints.web.exposure.include=health,info,prometheus,metrics,loggers
management.endpoint.health.show-details=when-authorized
management.health.probes.enabled=true

# Tracing: sample 10 % in production, 100 % in staging
management.tracing.sampling.probability=0.1
```

**Why both Hibernate logger names:** The target stack runs Hibernate 6 (shipped with Spring Boot 3.x), which renamed the parameter-binding logger from `org.hibernate.type` / `org.hibernate.type.descriptor.sql` to `org.hibernate.orm.jdbc.bind`. Pinning only the Hibernate 5 name would leave bound parameter values — raw PHI — visible in logs on the actual target stack. Both names are included so the configuration is correct on Hibernate 6 and remains safe if the dependency is ever downgraded.

**Logback configuration (`logback-spring.xml`, non-dev profile):**
Use `LogstashEncoder` with a `MdcJsonProvider` to embed `traceId`, `spanId`, `userId`, `requestId` from MDC. Explicitly configure `SensitiveDataMaskingConverter` (custom class) to reject log messages matching PHI patterns (MR number regex `MRNO/\d{4}/\d+`, NIC/passport patterns). security-architect to supply the complete PHI field pattern list.

**Custom health indicators (backend-engineer implements):**
- `BusinessDayHealthIndicator`: queries `SELECT status FROM days ORDER BY id DESC LIMIT 1`; returns DOWN if no row or status != 'STARTED'.
- `FlywayMigrationHealthIndicator`: reads `Flyway` bean state; returns DOWN if pending migrations exist.

**SLO alert rules:** devops-engineer authors Prometheus `rules.yml` referencing `http_server_requests_seconds` (auto-provided by Micrometer) filtered by `uri` tag patterns matching billing (`/invoices/**`, `/bills/**`, `/payments/**`) and clinical (`/consultations/**`, `/admissions/**`, `/prescriptions/**`) paths. Alert thresholds above are starting values; qa-test-engineer validates alert firing in the staging environment before go-live.

**PHI-in-logs test (qa-test-engineer implements):** Integration test using Testcontainers that executes a full patient registration + bill creation flow, captures the Logback `ListAppender` output, and asserts zero occurrences of any string matching the PHI regex patterns supplied by security-architect. This test runs in every CI build and blocks merge on failure.

**Peer handoffs:**
- devops-engineer: Prometheus scrape config, Grafana dashboard templates per bounded context, OTLP collector wiring, network policy for management port, alerting rules in Terraform.
- security-architect: PHI field pattern list for log masking, Actuator endpoint authentication requirement (confirm whether `management.endpoint.health.show-details=when-authorized` requires a separate management security filter chain), PR review checklist item for PHI-in-logs.
- data-architect: `BusinessDayHealthIndicator` SQL query must align with the finalised `days` table schema.
- qa-test-engineer: PHI-in-logs integration test, alert-firing validation in staging, Actuator endpoint access-control verification.
- backend-engineer: implement startup assertion blocking `show-sql=true` outside dev profile; implement custom health indicators; add `userId` to MDC on every authenticated request (use the internal `User.id` — never the username string or any PHI field).
