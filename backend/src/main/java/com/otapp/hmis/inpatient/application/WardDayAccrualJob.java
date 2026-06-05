package com.otapp.hmis.inpatient.application;

import java.time.Instant;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ward-day accrual scheduled job (ADR-0018 JOB-001, inc-07 07c-ii / CR-07-Q2).
 *
 * <p><strong>NET-NEW trigger MECHANISM</strong> (the accrual SEMANTICS are extracted legacy parity
 * in {@link WardAccrualService}). Replaces the legacy always-running 5-minute {@code UpdatePatient}
 * polling Thread with a calendar-night {@code @Scheduled} + ShedLock cron. The owner accepted the
 * documented midnight-vs-rolling-24h timing variance (CR-07-Q2); see {@link WardAccrualService} and
 * ADR-0018 §Exact-process-impact for the variance + golden-master anchor.
 *
 * <p>Trigger: {@code cron = "${hmis.jobs.ward-accrual.cron:0 5 0 * * *}"} — 00:05 daily,
 * externalized + configurable (ADR-0018 §Implementation-notes 5). ShedLock {@code lockAtMostFor =
 * "PT10M"}, {@code lockAtLeastFor = "PT1M"} (ADR-0018 §Decision JOB-001) — at most one node runs it.
 *
 * <p>Idempotent (ADR-0018 §6): {@link WardAccrualService#accrueWardDay} accrues at most once per
 * eligible admission per run (the {@code openedAt = now} reset is the key); a re-run the same day
 * no-ops. Each accrual runs in its own {@code REQUIRES_NEW} tx — one admission's failure does not
 * abort the rest. Every run writes a {@code job_run_log} row (ADR-0018 §5; plain JDBC, append-only).
 *
 * <p>Only registered when {@code hmis.scheduling.enabled=true} (default) via
 * {@link com.otapp.hmis.shared.config.SchedulingConfig} — tests disable it and call
 * {@link WardAccrualService#accrueWardDay} directly.
 */
@Component
@RequiredArgsConstructor
public class WardDayAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(WardDayAccrualJob.class);
    private static final String JOB_NAME = "ward-accrual";

    private final WardAccrualService wardAccrualService;
    private final DataSource dataSource;

    /**
     * Fire the ward-day accrual sweep. Cron-triggered + ShedLock-guarded; also invoked by the
     * manual ops trigger (POST /ops/jobs/ward-accrual/trigger) for operational recovery.
     */
    @Scheduled(cron = "${hmis.jobs.ward-accrual.cron:0 5 0 * * *}")
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        runOnce();
    }

    /**
     * Execute one accrual sweep, writing an APPEND-ONLY {@code job_run_log} row at completion
     * (ADR-0018 §5: no job may UPDATE/DELETE its own audit rows — so a single terminal-status row
     * is written, with both {@code started_at} and {@code finished_at}, rather than a
     * STARTED-then-UPDATE pattern). Shared by the scheduled trigger and the manual ops trigger.
     *
     * @return the number of admissions that accrued a new ward-day
     */
    public int runOnce() {
        Instant startedAt = Instant.now();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            int accrued = wardAccrualService.accrueWardDay(startedAt);
            writeRunLog(jdbc, startedAt, "COMPLETED", accrued, null);
            log.info("Ward-accrual job COMPLETED: {} admissions accrued", accrued);
            return accrued;
        } catch (RuntimeException ex) {
            writeRunLog(jdbc, startedAt, "FAILED", null, truncate(ex.toString()));
            log.error("Ward-accrual job FAILED: {}", ex.toString());
            throw ex;
        }
    }

    private void writeRunLog(JdbcTemplate jdbc, Instant startedAt, String status,
                             Integer recordsAffected, String error) {
        // id is BIGSERIAL (V50) — DB-generated; append-only single terminal row.
        jdbc.update(
                "INSERT INTO job_run_log (job_name, started_at, finished_at, status, records_affected, error_message)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                JOB_NAME,
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(Instant.now()),
                status, recordsAffected, error);
    }

    private static String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
