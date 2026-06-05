-- =====================================================================================
-- Increment 07d — Medication Administration Record (MAR) — CR-07-MAR
--
-- New table:
--   medication_administrations — clinical-owned closed-loop MAR aggregate. Captures the
--                                structured who/what/when/how of an actual administration:
--                                route (FK-less ref to administration_routes masterdata),
--                                administered_at, dose_given, patient_response.
--
-- NET-NEW — there is NO legacy MAR. This is ADDITIVE over the free-text dosing-note path
-- (patient_prescription_charts, V27/07b) — both coexist; MAR does NOT replace the dosing note.
-- Owner-APPROVED CR-07-MAR; prerequisites (route masterdata + standard PHI/audit) ruled
-- 2026-06-05. MAR ACs are net-new acceptance tests, NOT golden-master parity.
--
-- Design rules:
--   * ULID uid VARCHAR(26) + BIGINT GENERATED ALWAYS AS IDENTITY (ADR-0003, ADR-0005)
--   * prescription_id is an intra-clinical real FK (MAR is clinical-owned, like
--     patient_prescription_charts) → references prescriptions(id)
--   * all CROSS-module refs are loose *_uid VARCHAR(26) (NO physical FK — ADR-0008 §1):
--     admission_uid, patient_uid, nurse_uid, route_uid, business_day_uid
--   * route_uid + administered_at are NOT NULL (a closed-loop entry must name both)
--   * audit columns: created_at/updated_at/created_by/updated_by/version (AuditableEntity)
--
-- inc-07 07d / CR-07-MAR
-- =====================================================================================

CREATE TABLE medication_administrations (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)     NOT NULL,

    -- Intra-clinical real FK to the parent prescription (clinical owns this aggregate)
    prescription_id     BIGINT          NOT NULL,

    -- Cross-module loose refs (no physical FK — ADR-0008 §1)
    admission_uid       VARCHAR(26),
    patient_uid         VARCHAR(26),
    nurse_uid           VARCHAR(26),
    route_uid           VARCHAR(26)     NOT NULL,

    -- Structured administration payload
    administered_at     TIMESTAMPTZ     NOT NULL,
    dose_given          VARCHAR(200),
    patient_response    TEXT,

    business_day_uid    VARCHAR(26),

    -- Audit columns
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT          NOT NULL,

    CONSTRAINT pk_medication_administrations         PRIMARY KEY (id),
    CONSTRAINT uq_medication_administrations_uid     UNIQUE (uid),
    CONSTRAINT fk_med_admin_prescription
        FOREIGN KEY (prescription_id) REFERENCES prescriptions (id)
);

CREATE INDEX idx_med_admin_admission_uid   ON medication_administrations (admission_uid);
CREATE INDEX idx_med_admin_prescription_id ON medication_administrations (prescription_id);
