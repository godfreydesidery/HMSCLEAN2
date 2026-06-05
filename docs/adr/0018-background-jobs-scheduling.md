# ADR-0018: Background jobs and scheduling

- **Status:** Accepted — ratified for inc-07 (Inpatient & Nursing) on 2026-06-05 after the §Context correction below (see [../delivery/increments/07-inpatient-discovery/03b-OWNER-RULINGS.md](../delivery/increments/07-inpatient-discovery/03b-OWNER-RULINGS.md))
- **Date:** 2026-06-02 (proposed); 2026-06-05 (ratified, §Context corrected)
- **Deciders:** solution-architect (reviewed by security-architect, data-architect); ratified by product owner / engagement-lead
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

---

## Context

The legacy system (`com.orbix.api`) has **zero `@Scheduled` annotations** — confirmed by exhaustive grep across all 115+ Java files in the domain and service packages. Every timed or batch operation in the legacy is manual and user-triggered:

- **Day open/close:** `DayResource.GET /days/end_day` is an explicit user action protected by `DAY-ACCESS` privilege. There is no automated midnight rollover.
- **Ward-day billing:** `PatientServiceImpl.doAdmission()` creates an initial `PatientBill` for bed occupancy at admission time (qty=1, flat rate from `WardType.price`). **It does re-accrue thereafter** — corrected per the inc-07 discovery (this ADR's original premise that the legacy "creates exactly one PatientBill... [with] no nightly loop, no per-day charge accrual" was **factually wrong**). The legacy `UpdatePatient` class implements `Runnable`, is started via `new Thread(...).start()` at `MainApplication.java:160-184`, and loops `while(true){ sleep(300000) }` (a 5-minute poll, `UpdatePatient.java:110`). For each `IN-PROCESS`/`STOPPED` admission whose currently-`OPENED` `AdmissionBed` is ≥24h old (`ChronoUnit.HOURS.between(openedAt, now) >= 24`, `UpdatePatient.java:291-292`) it **CLOSES** that `AdmissionBed` and **OPENS a new one** with `openedAt = now` (`:294-334`), creating a new `VERIFIED` ward-bed `PatientBill` at `WardType.price` (`:304-325`). So the legacy **does re-accrue per CHAINED rolling-24h windows** (each accrual resets `openedAt = now`, `:333`), not via a calendar cron and not as a single upfront charge. The total at any point is `(1 + N) × WardType.price` where `N` = completed rolling-24h intervals while the thread observed the admission `IN-PROCESS`/`STOPPED`. Because the trigger is a 5-minute poll keyed on each bed's own `openedAt`, the chained count `N` can be **strictly lower** than `floor(elapsed / 24h)` for long stays (poll-latency drift). There is no counter at discharge.
- **Payroll:** `PayrollServiceImpl.save()` is invoked on demand by an authorised user; no cron trigger exists.
- **Reporting:** `ReportResource` runs all reports synchronously on request; no scheduled report generation.

The prior modernization attempt introduced one `@Scheduled` job — `AdmissionAccrualJob` (cron `0 0 1 * * *`) — to address process mismatch M23: the prior build redesigned the billing model so that a long inpatient stay accumulated a ward-day line nightly, and discharge was gated on clearance of the accrued total. That was the prior attempt's own architectural choice, not a reproduction of legacy behaviour. Critically, the prior attempt's `pom.xml` contained **no ShedLock or Quartz dependency**, meaning the accrual job would fire on every node in a multi-instance deployment and double-charge ward days — an unresolved defect at the time of the GAP_AUDIT_2026-06 review.

The fresh build must distinguish:

1. **Confirmed legacy behaviours** that happen to require a scheduled mechanism in the new architecture (e.g., ward-day accrual if we adopt the accrual billing model).
2. **Net-new operational jobs** required by the new system (e.g., audit-partition maintenance, token cleanup).
3. **Jobs to defer pending legacy-analyst confirmation** (e.g., automated payroll runs, automated business-day rollover, periodic insurance-claim resubmission).

**Legacy-analyst gate:** Before implementing any scheduled job, the legacy analyst must confirm whether the corresponding legacy process is manual-user-triggered or has any automated trigger. This ADR lists only jobs whose justification is already grounded.

---

## Decision

### 1. Scheduling mechanism: Spring `@Scheduled` + ShedLock

Use Spring's built-in `@Scheduled` (enabled by `@EnableScheduling` on the application class) as the scheduling mechanism. This is sufficient for the current single-JVM modular-monolith deployment and requires no additional infrastructure.

Add **ShedLock** (`net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-jdbc-template`) from day one. ShedLock guarantees that a scheduled method executes on **at most one node at a time** by acquiring a named lock row in a `shedlock` table before execution. This prevents the double-execution defect that was unresolved in the prior attempt. The ShedLock table is managed by a dedicated Flyway migration.

Quartz is **not adopted** at this stage. Quartz adds significant complexity (persistent job store, trigger management, clustering protocol) that is disproportionate to the number of jobs confirmed at this stage. The decision is revisited if: (a) a job requires dynamic rescheduling at runtime, (b) job progress tracking becomes a user-facing feature, or (c) the job count exceeds ten distinct scheduled tasks.

### 2. Job registry and categorisation

Every `@Scheduled` method must be annotated with `@SchedulerLock(name = "...", lockAtMostFor = "...", lockAtLeastFor = "...")` from ShedLock. The lock name is the canonical job identifier and is used in job-run audit records.

Three categories of scheduled work are defined:

| Category | Description | Lock required | Idempotency requirement |
|---|---|---|---|
| A — Business accrual | Creates or updates financial records on a calendar trigger | Mandatory | Strictly idempotent: re-run must produce the same aggregate state as a first run |
| B — Ops maintenance | Housekeeping: partition creation, token expiry cleanup, stale-lock removal | Mandatory | Idempotent by construction (DDL and DELETE WHERE) |
| C — Deferred (analyst gate) | Not implemented until legacy-analyst confirms existence | N/A | N/A |

### 3. Confirmed jobs for V1

**JOB-001 — Ward-day accrual (Category A)**

The legacy **already re-accrues** ward-bed charges (see the corrected §Context above: a 5-minute polling `Thread` in `UpdatePatient` that adds a new `VERIFIED` ward-bed bill every rolling-24h per occupied bed). The accrual *semantics* are therefore an **EXTRACTED legacy-parity fact**, not a net-new billing model: `(1 + N) × WardType.price`, the initial admission bill `UNPAID` and each accrued bill `VERIFIED` (insurance `COVERED`), accrual halting once the admission leaves `IN-PROCESS`/`STOPPED`. What is **net-new** is only the *trigger mechanism* and its idempotency model: the fresh build replaces the always-running 5-minute in-process `Thread` (keyed on each bed's own `openedAt`, producing chained rolling-24h windows) with a **calendar-night `@Scheduled` + ShedLock cron** keyed on a per-calendar-night idempotency key. This is a deliberate, owner-approved mechanism change (inc-07 ruling Q2, CR-07-Q2): the cron is operationally simpler and multi-node-safe, at the cost of a documented timing variance — the calendar-night model accrues on day boundaries rather than on each bed's rolling 24h-from-`openedAt`, so for a given long stay the cron's nightly count and the legacy chained count can differ. The owner accepted this midnight-vs-rolling-24h variance as a runbook-documented deviation rather than requiring a true elapsed-24h rolling-interval design.

> **Golden-master anchor (corrected):** JOB-001's parity test MUST reconcile against the legacy **elapsed-24h chained total + poll-latency mechanic** (chained `N` can be `< floor(elapsed/24h)`), NOT a naïve calendar-night count, when asserting legacy fidelity of the *amount*. The cron *mechanism* itself is validated as net-new behaviour (idempotent per calendar night), explicitly labelled non-parity.

Trigger: `cron = "${hmis.jobs.ward-accrual.cron:0 5 0 * * *}"` — 00:05 daily, configurable.
ShedLock: `lockAtMostFor = "PT10M"`, `lockAtLeastFor = "PT1M"`.
Logic: for each ADMITTED admission, invoke `InvoiceService.accrueWardDay(admissionUid)` in an independent transaction per admission. A failure for one admission must not abort the rest. The accrual method is idempotent: if a ward-day line already exists for today's business date it is a no-op.
Module: lives in the `billing` module application layer (`billing.invoice.application.WardDayAccrualJob`). It reads the set of active admission UIDs via a published Spring Modulith event-listener query or a dedicated `AdmissionAccrualPort` interface in the `encounter` module's SPI package — the billing module must not hold a direct Spring Data repository reference into the `encounter` module. The port returns only `(admissionUid, wardTypeUid, patientClass, paymentType)` — the minimum needed for pricing.

**JOB-002 — Audit-partition maintenance (Category B)**

If the `audit_event` table is range-partitioned by month (per ADR-0007), a partition for the upcoming month must exist before the first event of that month is written. A job runs on the 25th of each month to create the next month's partition if absent.

Trigger: `cron = "0 30 2 25 * *"` — 02:30 on the 25th of each month, configurable.
ShedLock: `lockAtMostFor = "PT5M"`, `lockAtLeastFor = "PT30S"`.
Logic: `CREATE TABLE IF NOT EXISTS audit_event_YYYY_MM PARTITION OF audit_event FOR VALUES FROM (...) TO (...)`. DDL is idempotent due to `IF NOT EXISTS`.
Module: lives in the `ops` module or a dedicated `maintenance` package in the infrastructure layer.

### 4. Deferred jobs (analyst confirmation required before implementation)

The following processes exist in the legacy but are triggered manually. They are documented here as candidates. No code is written until the legacy analyst confirms whether an automated trigger is required in the new system.

- **JOB-C01 — Business-day rollover:** The legacy `DayService.endDay()` is a user-triggered `GET /days/end_day` call. Analyst must confirm whether the new system should automate this at midnight or retain the manual trigger. Given that `endDay()` also advances the business date, automated rollover could surprise overnight-shift staff. Recommendation: keep manual until analyst confirms otherwise, with a future cron option behind a feature flag.
- **JOB-C02 — Payroll period generation:** Legacy `PayrollServiceImpl` is entirely manual. Analyst must confirm whether auto-creating draft payroll periods at period-start is a requirement. Payroll has segregation-of-duties controls (DRAFT → VERIFIED → APPROVED → PAID); auto-creation of DRAFT is low-risk but must be confirmed.
- **JOB-C03 — Insurance claim auto-submission:** The prior attempt's V70 insurance claim ledger added a SUBMIT → SETTLE/REJECT lifecycle that the legacy never had. If claims are to be auto-submitted to a payer on a schedule, this is a net-new feature that requires an explicit product decision, not legacy fidelity.
- **JOB-C04 — Stale-token/refresh-token cleanup:** If refresh tokens are persisted to the database (a security-arch decision), a periodic purge of expired tokens is required. Not confirmed as in-scope until the token-persistence strategy is settled.

### 5. Job-run audit table

Every execution of a Category A or B job must write a row to a `job_run_log` table:

```
job_name        text        NOT NULL
started_at      timestamptz NOT NULL
finished_at     timestamptz
status          text        NOT NULL  -- STARTED | COMPLETED | FAILED
records_affected int
error_message   text
```

The `job_name` must match the ShedLock lock name. A Flyway migration creates this table (no ORM entity needed — plain JDBC insert via `JdbcTemplate`). The table is append-only; no job is allowed to UPDATE or DELETE its own audit rows.

### 6. Idempotency contract

Every Category A job method must satisfy: executing the method twice for the same calendar day must produce the same aggregate financial state as executing it once. The ward-day accrual job enforces this with a unique constraint on `(admission_uid, business_date)` in the ward-day charge table. Any `INSERT` that would violate the constraint is swallowed as a no-op (PostgreSQL `ON CONFLICT DO NOTHING`).

---

## Considered alternatives

**Quartz Scheduler** — provides a persistent job store, dynamic reschedule, and job-progress visibility. Rejected at this stage because the confirmed job count is two and neither requires dynamic rescheduling. Quartz's clustering protocol also conflicts with the modular-monolith's intent to avoid distributed state. Revisit at >10 jobs or when a job needs user-facing progress tracking.

**Spring Batch** — appropriate for large-scale ETL-style processing (chunk-oriented, restart/retry, skip policies). The ward-day accrual iterates at most a few hundred admissions; Spring Batch overhead is not justified. Adopt if a payroll batch or insurance bulk-claim submission job is confirmed and involves tens of thousands of records.

**Database-native cron (pg_cron)** — runs SQL directly in PostgreSQL without an application process. Avoids JVM warm-up latency but is invisible to application logging, audit, and ShedLock coordination. Not adopted: operational visibility (job-run audit, structured logs) outweighs the latency benefit.

**No scheduling at all** — valid given that the legacy has zero scheduled jobs. Rejected because the accrual billing model (adopted to fix M23) structurally requires a daily trigger; without it, the ward-day line is never written and the discharge payment gate never arms.

---

## Consequences

**Positive:**
- ShedLock closes the double-execution defect that was open in the prior attempt at audit time.
- The analyst-gate pattern prevents premature implementation of behaviours that may be intentionally manual in the legacy.
- Job-run audit gives operations staff visibility into nightly runs without requiring APM tooling.
- The `accrueWardDay` idempotency contract means a failed run can be retried (manually or automatically) without financial duplication.

**Negative / trade-offs:**
- The ShedLock table is a deployment dependency: the Flyway migration creating it must run before `@EnableScheduling` triggers the first execution. Spring's context startup order ensures this when Flyway is configured with `spring.flyway.enabled=true` (default).
- Configuring `lockAtMostFor` conservatively (10 min for ward accrual) means a crashed node holds the lock for up to 10 minutes before a peer can take over. Operators must monitor the `job_run_log` table for STARTED rows older than 15 minutes.
- Ward-day accrual fires at 00:05, so on the final day of a stay the discharge-gate check will not see today's accrued charge until after midnight. Clinical staff must understand that a same-day discharge before 00:05 uses yesterday's accrued balance. This is acceptable operational behaviour; document it in the runbook.

---

## Exact-process impact

**Corrected (inc-07 discovery):** the original claim here — "the legacy charges one flat ward-bed bill at admission time and does not re-accrue" — was **factually wrong**. The legacy **does re-accrue** (the `UpdatePatient` rolling-24h polling `Thread`; see §Context). The accrual TOTAL and STATUS semantics are therefore **EXTRACTED legacy-parity facts**, not a net-new billing model: total `(1 + N) × WardType.price`, initial bill `UNPAID`, accrued bills `VERIFIED` (insurance `COVERED`), accrual halting on leaving `IN-PROCESS`/`STOPPED`. Only the **trigger mechanism** is a deliberate process change: the always-on 5-minute `Thread` producing *chained rolling-24h-from-`openedAt`* windows is replaced by a `@Scheduled` + ShedLock **calendar-night cron** with a per-calendar-night idempotency model (owner-approved, inc-07 ruling Q2 / CR-07-Q2). The business result is **not** unconditionally identical: the calendar-night cron and the legacy chained-rolling-24h count can diverge for a given long stay (a same-day admission and a near-midnight admission accrue differently under a calendar model than under a `openedAt`-keyed rolling model). The owner accepted this as a tolerated, runbook-documented timing variance. The golden-master must therefore assert (a) the legacy chained-rolling-24h elapsed total as the *amount*-parity oracle (per the §Decision JOB-001 anchor above), and (b) the calendar-night cron's idempotency as net-new mechanism behaviour — it must NOT assert that the cron reproduces the legacy chained count window-for-window.

The business-day open/close process remains **manual** in V1, exactly as in the legacy. The `DayResource` controller and `DAY-ACCESS` privilege are reproduced; no automated rollover is introduced without analyst confirmation.

---

## Implementation notes

1. Add to `pom.xml`:
   ```xml
   <dependency>
     <groupId>net.javacrumbs.shedlock</groupId>
     <artifactId>shedlock-spring</artifactId>
     <version>6.0.1</version>
   </dependency>
   <dependency>
     <groupId>net.javacrumbs.shedlock</groupId>
     <artifactId>shedlock-provider-jdbc-template</artifactId>
     <version>6.0.1</version>
   </dependency>
   ```

2. Flyway migration (e.g., `V_XXXX__add_shedlock_and_job_run_log.sql`) must create:
   - `shedlock` table per ShedLock documentation.
   - `job_run_log` table per the schema defined above.

3. Application class: `@EnableScheduling` + `@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")`.

4. `WardDayAccrualJob` must live in `billing.invoice.application` (package-private class, package-private method — not a public API). The `AdmissionAccrualPort` SPI in `encounter.admission.spi` is the only cross-module reference; it is the allowed direction (billing reads from encounter via port, not the reverse).

5. All `@Scheduled` cron expressions must be externalized to `application.properties` with a sensible default so QA environments can disable or re-time jobs without a code change. Convention: `hmis.jobs.<job-name>.cron`.

6. A `POST /ops/jobs/{jobName}/trigger` endpoint (admin-only, `ADMIN-ACCESS` privilege) allows manual triggering of any registered job for operational recovery. This endpoint calls the same service method the scheduler calls; it does not bypass ShedLock.
