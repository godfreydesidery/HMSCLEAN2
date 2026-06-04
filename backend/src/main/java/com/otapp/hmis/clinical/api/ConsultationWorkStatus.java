package com.otapp.hmis.clinical.api;

/**
 * API-level enum of guard-relevant consultation work statuses (ADR-0022 D5).
 *
 * <p>Published in {@code clinical.api} so the {@code registration} module can express the
 * open-work guard without importing the internal {@code clinical.domain.ConsultationStatus}.
 * This shields registration from the full domain status vocabulary — when clinical widens the
 * status set (e.g. adds HELD for deceased notes), registration is unaffected.
 *
 * <p>Maps 1:1 to the domain enum constants that represent "open work" for the send-to-doctor
 * and patient-type-flip guards:
 * <ul>
 *   <li>{@link #PENDING}   → {@code clinical.domain.ConsultationStatus.PENDING}</li>
 *   <li>{@link #IN_PROCESS} → {@code clinical.domain.ConsultationStatus.IN_PROCESS}</li>
 *   <li>{@link #TRANSFERED} → {@code clinical.domain.ConsultationStatus.TRANSFERED}</li>
 * </ul>
 *
 * <p>Legacy citation: PatientResource.java:485-488 (change_type guard) and :443-448
 * (do_consultation guard) — both reference PENDING; inc-05 widens to include IN-PROCESS
 * and TRANSFERED.
 *
 * <p>ADR-0022 D5: exposes only the API enum; {@code ConsultationWorkStatus} never imports
 * from {@code clinical.domain}.
 */
public enum ConsultationWorkStatus {

    /** Booking created; patient queued for the doctor. (≡ domain PENDING) */
    PENDING,

    /** Doctor has opened the consultation. (≡ domain IN_PROCESS) */
    IN_PROCESS,

    /** Raised for clinic-to-clinic transfer. Single-R legacy spelling. (≡ domain TRANSFERED) */
    TRANSFERED
}
