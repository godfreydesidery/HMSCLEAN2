-- =====================================================================================
-- Increment 07a-3 — Inpatient dispositions — Chunk 07a-3 (SCHEMA)
-- discharge_plans table: inpatient-owned narrative plan for the discharge disposition.
--
-- ReferralPlan and DeceasedNote are REUSED clinical entities (V28 already has their
-- tables with admission_uid columns). Only DischargePlan is net-new here.
--
-- Re-models the legacy flat DischargePlan entity with:
--   * ULID uid CHAR(26) + BIGINT GENERATED ALWAYS AS IDENTITY (ADR-0003, ADR-0005)
--   * Six narrative TEXT columns (DischargePlan.java:39-44 — verbatim field names)
--   * Status PENDING/APPROVED (CHECK-constrained VARCHAR)
--   * Approval audit: approved_by, approved_on_day_uid, approved_at
--   * @Version optimistic-locking + Spring Data JPA audit columns
--   * Named constraints: pk_/uq_/ck_/idx_
--
-- Net-new vs legacy:
--   * approved_on_day_uid — business-day traceability (net-new; legacy had no day uid)
--   * audit columns (created_at, updated_at, created_by, updated_by, version) — net-new
--     append-only audit trail (security-architect forward requirement)
--   * ck_discharge_plans_status DB CHECK backstop
--
-- Cross-module refs: admission_uid is a loose VARCHAR(26) (NO physical FK — inpatient
-- module boundary, ADR-0008 §1). An index on admission_uid supports the idempotent-save
-- lookup (existsByAdmissionUid).
--
-- Conventions: identical to V44 (inpatient_admission_lifecycle).
--
-- Legacy citations:
--   domain/DischargePlan.java:39-44 (six narrative fields);
--   PatientResource.java:5342-5390 (get_discharge_summary save + approve).
-- =====================================================================================

CREATE TABLE discharge_plans (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                         VARCHAR(26)     NOT NULL,

    -- Loose intra-module ref to the owning admission (no physical FK — ADR-0008 §1)
    admission_uid               VARCHAR(26)     NOT NULL,

    -- Six narrative TEXT columns (DischargePlan.java:39-44 — verbatim)
    history                     TEXT,
    investigation               TEXT,
    management                  TEXT,
    operation_note              TEXT,
    icu_admission_note          TEXT,
    general_recommendation      TEXT,

    -- Lifecycle status: PENDING (at save) → APPROVED (at approve)
    status                      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- Approval audit (CR-07-SoD — real approver captured, NOT creator)
    approved_by                 VARCHAR(80),
    approved_on_day_uid         VARCHAR(26),
    approved_at                 TIMESTAMPTZ,

    -- Spring Data JPA audit columns (AuditableEntity)
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(80),
    updated_by                  VARCHAR(80),
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_discharge_plans           PRIMARY KEY (id),
    CONSTRAINT uq_discharge_plans_uid       UNIQUE (uid),

    -- DB CHECK backstop for the two-value status vocabulary
    CONSTRAINT ck_discharge_plans_status CHECK (
        status IN ('PENDING', 'APPROVED')
    )
);

-- Supports existsByAdmissionUid lookup (idempotent save) and findByAdmissionUid
CREATE INDEX idx_discharge_plans_admission_uid ON discharge_plans (admission_uid);
