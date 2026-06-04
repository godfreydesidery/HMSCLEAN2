package com.otapp.hmis.registration.domain;

import java.util.stream.Stream;

/**
 * Lifecycle status of a {@link Consultation} (inc-05 build-spec §1; CR-21 ownership transfer to
 * the clinical context).
 *
 * <p><strong>EXACT legacy spellings preserved.</strong> The legacy {@code Consultation.status} is a
 * free-text String (Consultation.java:55-56); inc-05 normalises it into this enum WITHOUT
 * "cleaning up" the values. Several legacy values are NOT valid Java identifiers (hyphenated
 * {@code IN-PROCESS}, {@code SIGNED-OUT}), so each constant carries its exact persisted
 * {@link #dbValue} and is mapped by {@link ConsultationStatusConverter} (NOT {@code @Enumerated},
 * which would persist the constant name and break for the hyphenated values).
 *
 * <p>The planning-doc states {@code BOOKED / IN_PROGRESS / COMPLETED} are REJECTED inventions
 * (11-DECISIONS-RATIFIED.md §3). {@code STOPPED} is an Admission-only query ghost
 * (PatientResource.java:328) — never written onto a Consultation — and is EXCLUDED.
 *
 * <p>Legacy citations (the write sites that prove each value):
 * <ul>
 *   <li>{@link #PENDING}    — do_consultation booking (PatientServiceImpl.java:494)</li>
 *   <li>{@link #IN_PROCESS} — open_consultation / open_follow_up (PatientResource.java:886, :915)</li>
 *   <li>{@link #TRANSFERED} — create_consultation_transfer, single-R (PatientServiceImpl.java:2808)</li>
 *   <li>{@link #CANCELED}   — cancel_consultation, single-L (PatientResource.java:618)</li>
 *   <li>{@link #SIGNED_OUT} — free_consultation / closure (PatientResource.java:699, :764)</li>
 *   <li>{@link #HELD}       — save_deceased_note OUTPATIENT (PatientResource.java:5753)</li>
 * </ul>
 */
public enum ConsultationStatus {

    /** Booking created; patient queued for the doctor. Default on do_consultation. */
    PENDING("PENDING"),

    /** Doctor has opened the consultation (pay-before-service gate passed). Hyphenated in the DB. */
    IN_PROCESS("IN-PROCESS"),

    /** Raised for clinic-to-clinic transfer. Single-R legacy spelling, verbatim. */
    TRANSFERED("TRANSFERED"),

    /** Soft-cancelled before opening. Single-L legacy spelling, verbatim. */
    CANCELED("CANCELED"),

    /** Closed/freed (sign-out, referral, completion). Hyphenated in the DB. */
    SIGNED_OUT("SIGNED-OUT"),

    /** Held pending deceased-note approval (OPD death path). */
    HELD("HELD");

    private final String dbValue;

    ConsultationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The EXACT string persisted to {@code consultations.status} (matches the V20 CHECK). */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolve from the persisted DB string (used by {@link ConsultationStatusConverter}).
     *
     * @throws IllegalArgumentException if the value is outside the V20 CHECK vocabulary
     */
    public static ConsultationStatus fromDbValue(String value) {
        return Stream.of(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown consultation status: " + value));
    }
}
