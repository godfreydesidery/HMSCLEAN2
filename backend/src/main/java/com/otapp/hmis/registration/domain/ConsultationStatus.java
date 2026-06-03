package com.otapp.hmis.registration.domain;

/**
 * Lifecycle status of a {@link Consultation} (build-spec §3, CR-18, CR-21).
 *
 * <p>Legacy citation: Consultation.java:56.  Only {@link #PENDING} is written by
 * inc-03's minimal stub.  The full consultation status machine (OPEN, FREE, TRANSFERRED,
 * IN-PROCESS, etc.) lives in inc-05 (clinical module), which will carry the
 * Consultation-aggregate ownership-transfer plan (ADR-0008-R1, CR-21).
 * Any new status values MUST be added via an additive migration (ADR-0008-R2).
 *
 * <p>Stored via {@code @Enumerated(STRING)} as a VARCHAR(20) column.
 */
public enum ConsultationStatus {

    /**
     * Consultation booking has been created; patient is queued for the doctor.
     * Default and sole value written in inc-03.
     */
    PENDING
}
