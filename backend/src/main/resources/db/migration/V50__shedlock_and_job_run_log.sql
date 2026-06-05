-- V50: ShedLock distributed-job-lock table + job_run_log audit table
-- ADR-0018 §1 (ShedLock JDBC-template provider) + §5 (job-run audit).
-- These are infrastructure tables; no ORM entity is generated for either.

-- ShedLock table (per ShedLock JdbcTemplateLockProvider documentation).
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

-- Job-run audit log (ADR-0018 §5).
-- Append-only: no job may UPDATE or DELETE its own rows.
-- status values: STARTED | COMPLETED | FAILED
CREATE TABLE IF NOT EXISTS job_run_log (
    id               BIGSERIAL    NOT NULL,
    job_name         TEXT         NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ,
    status           TEXT         NOT NULL,
    records_affected INT,
    error_message    TEXT,
    CONSTRAINT pk_job_run_log PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_job_run_log_job_name_started ON job_run_log (job_name, started_at DESC);
