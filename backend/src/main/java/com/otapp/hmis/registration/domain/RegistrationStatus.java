package com.otapp.hmis.registration.domain;

/**
 * Lifecycle status of a {@link Registration} (build-spec §1.2, CR-18).
 *
 * <p>Legacy citation: Registration.java:44; PatientServiceImpl.java:293-302.
 * Only {@link #ACTIVE} is created in inc-03.  Later increments (e.g. inpatient/discharge)
 * that need additional statuses MUST widen the DB CHECK ({@code ck_registrations_status})
 * via an additive migration — silent relaxation is not permitted (ADR-0008-R2).
 *
 * <p>Stored via {@code @Enumerated(STRING)} as a VARCHAR(20) column.
 */
public enum RegistrationStatus {

    /**
     * The patient is actively registered.  Default and sole value in inc-03.
     */
    ACTIVE
}
