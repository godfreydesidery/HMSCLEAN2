-- =====================================================================================
-- Increment 02 — P4b: Clinician–Clinic affiliation join table (CR-08)
--
-- Design: affiliation is owned by the iam module as an @ElementCollection of opaque
-- clinic-uid strings on the Clinician entity. This faithfully reproduces the legacy
-- Clinician.clinics @ManyToMany (Clinician.java:69-71) while keeping iam independent
-- of the masterdata module (no FK to masterdata.clinics — just loose VARCHAR(26) refs).
--
-- The masterdata module orchestrates affiliation via the iam.lookup named interface;
-- no masterdata.clinic_clinician table exists (CR-08 / build-spec §5.2).
--
-- Index idx_clinician_clinic_uids_clinic supports the reverse lookup:
-- "which clinicians are affiliated with clinic X?" (ClinicianAffiliationService).
-- =====================================================================================

CREATE TABLE clinician_clinic_uids (
    clinician_id BIGINT       NOT NULL,
    clinic_uid   VARCHAR(26)  NOT NULL,
    CONSTRAINT pk_clinician_clinic_uids
        PRIMARY KEY (clinician_id, clinic_uid),
    CONSTRAINT fk_clinician_clinic_uids_clinician
        FOREIGN KEY (clinician_id) REFERENCES clinicians (id) ON DELETE CASCADE
);

CREATE INDEX idx_clinician_clinic_uids_clinic ON clinician_clinic_uids (clinic_uid);
