package com.otapp.hmis.registration.domain;

/**
 * Observable lifecycle types for a {@link Patient} (build-spec §1.2, CR-11).
 *
 * <p>Legacy citation: Patient.java:63-64.  The 4-value vocabulary is a HDE BLOCKER —
 * reducing it would break clinical workflows.  The {@code change_type} endpoint toggles
 * only {@link #OUTPATIENT} ↔ {@link #OUTSIDER}; {@link #INPATIENT} is set by the
 * admission flow (inc-06) and {@link #DECEASED} by the deceased-note flow (deferred).
 *
 * <p>Stored via {@code @Enumerated(STRING)} as a VARCHAR(20) column.
 * The DB CHECK constraint ({@code ck_patients_type}) enforces the same vocabulary.
 */
public enum PatientType {

    /**
     * Standard outpatient.  Default at registration (PatientResource.java:410-414).
     * Eligible for {@code send-to-doctor}; toggleable to {@link #OUTSIDER}.
     */
    OUTPATIENT,

    /**
     * External / outsider patient.  Toggleable to {@link #OUTPATIENT} via
     * {@code change_type} (PatientResource.java:421-495).
     */
    OUTSIDER,

    /**
     * Admitted inpatient.  Set by the admission flow (inc-06, deferred).
     * {@code send-to-doctor} is blocked when type is INPATIENT
     * (PatientResource.java:499-500 "This operation is not allowed for inpatients").
     */
    INPATIENT,

    /**
     * Deceased patient.  Set by the deceased-note flow (inpatient/discharge increment,
     * deferred).  {@code type='DECEASED'} is the sole "deceased" marker — no boolean flag
     * (CR-05).
     */
    DECEASED
}
