-- =====================================================================================
-- Increment 04-P2 — Patient credit notes
--
-- PatientCreditNote is the refund instrument (legacy: PatientResource.java:643-654).
-- The PCN document number is generated from seq_pcn_no (V13).
-- status is always 'PENDING' in legacy (PatientCreditNoteServiceImpl.java:33-40);
-- the note is never auto-applied.
--
-- Legacy citations:
--   PatientCreditNote: domain/PatientCreditNote.java:35
--   PCN format: accessories/Formater.java:14-17  PCN{yyyyMMdd}-{nextval(seq_pcn_no)}
--   patient_bill_uid: net-new traceability field (no FK — avoids legacy PHI deletion risk)
-- =====================================================================================

CREATE TABLE patient_credit_notes (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)   NOT NULL,

    -- PCN document number: PCN{EAT-yyyyMMdd}-{nextval(seq_pcn_no)} (CR-09)
    no                  VARCHAR(60)   NOT NULL,

    -- loose cross-module ref (nullable — legacy patient nullable at PatientCreditNote.java:38)
    patient_uid         VARCHAR(26),

    -- full bill amount (positive) — signed-detail refund is NOT legacy (CR-03 rejected)
    amount              NUMERIC(19,2) NOT NULL,
    amount_currency     VARCHAR(3)    NOT NULL DEFAULT 'TZS',

    reference           VARCHAR(255),

    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',

    -- net-new traceability: link back to the cancelled bill uid (no FK — loose ref)
    patient_bill_uid    VARCHAR(26),

    -- loose cross-module ref
    business_day_uid    VARCHAR(26)   NOT NULL,

    -- audit columns
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT        NOT NULL,

    CONSTRAINT pk_patient_credit_notes PRIMARY KEY (id),
    CONSTRAINT uq_patient_credit_notes_uid UNIQUE (uid),
    CONSTRAINT uq_patient_credit_notes_no  UNIQUE (no),
    CONSTRAINT ck_patient_credit_notes_status CHECK (status IN ('PENDING'))
);

CREATE INDEX idx_patient_credit_notes_patient_uid ON patient_credit_notes (patient_uid);
CREATE INDEX idx_patient_credit_notes_bill_uid    ON patient_credit_notes (patient_bill_uid);
