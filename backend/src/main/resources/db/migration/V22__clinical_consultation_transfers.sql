-- =====================================================================================
-- Increment 05 — Clinical / OPD bounded context — Chunk C1 (SCHEMA FIRST)
-- consultation_transfers: a clinic-to-clinic transfer request on a consultation
-- (ConsultationTransfer.java:35-65).
--
-- The source consultation is left in status TRANSFERED; a transfer row records the
-- DESTINATION clinic and the PENDING/COMPLETED/CANCELED lifecycle of the hand-off.
-- (The source's eventual SIGNED-OUT close is driven by free_consultation,
-- PatientResource.java:764 — out of scope for this DDL.)
--
-- APP INVARIANT enforced in DB: at most ONE PENDING transfer per patient
-- (findByPatientAndStatus returns Optional, PatientServiceImpl.java:2764; doConsultation:431)
-- — encoded as a partial UNIQUE index. NOTE: legacy entity has NO @Version column; the
-- platform-standard version column is added for optimistic-locking uniformity (additive,
-- does not change behaviour).
--
-- Conventions: identical to V15/V19.
--   consultation_id / patient_id = real intra-schema FKs
--   destination_clinic_uid / business_day_uid = loose VARCHAR(26), NO FK (ADR-0008)
--   Named constraints: pk_/fk_/uq_/ck_/idx_
--
-- Legacy citations:
--   ConsultationTransfer.java:40-41 (status), :43 (reason free-text), :45-48 (patient FK),
--   :50-53 (consultation FK, updatable=false), :55-58 (destinationClinic loose, updatable=true).
--   PatientResource.java:599 (system-wide PENDING queue, unscoped findAllByStatus).
-- =====================================================================================

CREATE TABLE consultation_transfers (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                     VARCHAR(26)     NOT NULL,

    -- lifecycle: PENDING -> COMPLETED | CANCELED (single-L CANCELED, legacy spelling)
    status                  VARCHAR(20)     NOT NULL,

    -- free-text rationale, never validated (ConsultationTransfer.java:43)
    reason                  TEXT,

    -- Intra-module real FK to the source consultation (updatable=false)
    consultation_id         BIGINT          NOT NULL,

    -- Intra-schema real FK to patient
    patient_id              BIGINT          NOT NULL,

    -- Loose cross-module ref to the DESTINATION masterdata clinic (no FK, updatable=true)
    destination_clinic_uid  VARCHAR(26)     NOT NULL,

    -- Loose cross-module ref to the open business day (no FK)
    business_day_uid        VARCHAR(26)     NOT NULL,

    -- Audit columns (version added for platform uniformity; legacy entity has none)
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(80),
    updated_by              VARCHAR(80),
    version                 BIGINT          NOT NULL,

    CONSTRAINT pk_consultation_transfers        PRIMARY KEY (id),
    CONSTRAINT uq_consultation_transfers_uid    UNIQUE (uid),
    CONSTRAINT fk_consultation_transfers_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultations (id),
    CONSTRAINT fk_consultation_transfers_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_consultation_transfers_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED')
    )
);

CREATE INDEX idx_consultation_transfers_consultation
    ON consultation_transfers (consultation_id);
CREATE INDEX idx_consultation_transfers_patient
    ON consultation_transfers (patient_id);

-- Legacy invariant: at most one PENDING transfer per patient (partial unique).
CREATE UNIQUE INDEX uq_consultation_transfers_one_pending_per_patient
    ON consultation_transfers (patient_id) WHERE status = 'PENDING';

-- System-wide pending-transfer queue (get_consultation_transfers, unscoped, :599).
CREATE INDEX idx_consultation_transfers_pending
    ON consultation_transfers (status) WHERE status = 'PENDING';
