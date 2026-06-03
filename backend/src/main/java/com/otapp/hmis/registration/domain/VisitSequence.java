package com.otapp.hmis.registration.domain;

/**
 * Classifies a {@link Visit} within the patient's care journey (build-spec §1.2).
 *
 * <p>Legacy citation: Visit.java:42-43 — "FIRST or SUBSEQUENT visit".
 * The data-architect sketch uses the hyphenated token {@code SUBSEQUENT-FOR-ADMISSION};
 * this enum uses the underscore form {@code SUBSEQUENT_FOR_ADMISSION} to comply with Java
 * enum identifier rules.  The DB CHECK constraint ({@code ck_visits_sequence}) likewise
 * stores the underscore form.  The 3-value set is unchanged; only the token spelling is
 * normalised.  (Build-spec §7 C1 enum note; no legacy data-migration concern since this
 * is a greenfield build — ADR-0009/0011.)
 *
 * <p>In inc-03 only {@link #FIRST} and {@link #SUBSEQUENT} are actually produced.
 * {@link #SUBSEQUENT_FOR_ADMISSION} is reserved for the admission flow (inc-06).
 *
 * <p>Stored via {@code @Enumerated(STRING)} as a VARCHAR(30) column.
 */
public enum VisitSequence {

    /**
     * First visit at the time of patient registration.
     * Created unconditionally by {@code PatientRegistrationProcess.register()}
     * (PatientServiceImpl.java:409-419).
     */
    FIRST,

    /**
     * Subsequent visit created on each {@code send-to-doctor} call.
     * Created unconditionally — no same-day dedup (PatientServiceImpl.java:499,
     * build-spec §3.2 step 8).
     */
    SUBSEQUENT,

    /**
     * Subsequent visit linked to an admission booking (inc-06, deferred).
     * Token stored as {@code SUBSEQUENT_FOR_ADMISSION} (underscore form — see class javadoc).
     */
    SUBSEQUENT_FOR_ADMISSION
}
