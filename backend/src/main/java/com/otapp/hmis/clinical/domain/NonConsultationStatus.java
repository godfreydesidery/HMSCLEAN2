package com.otapp.hmis.clinical.domain;

import java.util.stream.Stream;

/**
 * Lifecycle status of a {@link NonConsultation} (inc-05 C4 build-spec §1).
 *
 * <p><strong>EXACT legacy spellings preserved.</strong> The legacy {@code NonConsultation.status}
 * is a free-text String. The observed write sites produce exactly two values:
 * {@code IN-PROCESS} (PatientServiceImpl.java:791) and {@code SIGNED-OUT}
 * (PatientResource.java:350). Both are hyphenated and therefore NOT valid Java enum constant
 * names, so {@code @Enumerated(EnumType.STRING)} (which persists the constant name) would write
 * the wrong token and violate the V21 {@code ck_non_consultations_status} CHECK.
 * The enum is therefore mapped by {@link NonConsultationStatusConverter} (NOT @Enumerated)
 * — identical pattern to {@link ConsultationStatusConverter}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>{@link #IN_PROCESS} — written at walk-in order creation:
 *       PatientServiceImpl.java:791 (lab), :1039 (radiology), :1285 (procedure)</li>
 *   <li>{@link #SIGNED_OUT} — written on walk-in sign-out:
 *       PatientResource.java:350</li>
 * </ul>
 */
public enum NonConsultationStatus {

    /** Walk-in encounter is open; orders are still being placed. Hyphenated in the DB. */
    IN_PROCESS("IN-PROCESS"),

    /** Walk-in encounter is closed (signed out). Hyphenated in the DB. */
    SIGNED_OUT("SIGNED-OUT");

    private final String dbValue;

    NonConsultationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The EXACT string persisted to {@code non_consultations.status} (matches V21 CHECK). */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolve from the persisted DB string (used by {@link NonConsultationStatusConverter}).
     *
     * @throws IllegalArgumentException if the value is outside the V21 CHECK vocabulary
     */
    public static NonConsultationStatus fromDbValue(String value) {
        return Stream.of(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown non-consultation status: " + value));
    }
}
