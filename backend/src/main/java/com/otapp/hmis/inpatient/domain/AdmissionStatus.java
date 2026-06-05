package com.otapp.hmis.inpatient.domain;

import java.util.stream.Stream;

/**
 * Lifecycle status of an {@link Admission} (inc-07 07a).
 *
 * <p><strong>EXACT legacy spellings preserved.</strong> The legacy {@code Admission.status} is a
 * free-text String (Admission.java:45); inc-07 normalises it into this enum WITHOUT cleaning up
 * the values. Two legacy values are hyphenated ({@code IN-PROCESS}, {@code SIGNED-OUT}), so each
 * constant carries its exact persisted {@link #dbValue} and is mapped by
 * {@link AdmissionStatusConverter} (NOT {@code @Enumerated}, which would write the constant name).
 *
 * <p><strong>Excluded values:</strong> {@code TRANSFERRED} is excluded per Q8 (owner-confirmed:
 * no admission transfer path in legacy, not a live state). This enum covers exactly the five
 * states that are written and read in the legacy admission lifecycle.
 *
 * <p>Legacy citations (the write sites that prove each value):
 * <ul>
 *   <li>{@link #PENDING}    — doAdmission creation (PatientServiceImpl.java:1719)</li>
 *   <li>{@link #IN_PROCESS} — payment-gated activation (PatientBillResource.java:356-357)</li>
 *   <li>{@link #STOPPED}    — admission stop flow (legacy Admission.status write site)</li>
 *   <li>{@link #HELD}       — admission hold flow</li>
 *   <li>{@link #SIGNED_OUT} — disposition sign-out (discharge/referral/deceased summary)</li>
 * </ul>
 */
public enum AdmissionStatus {

    /** Created by doAdmission; awaiting bed payment (CASH) or coverage confirmation. */
    PENDING("PENDING"),

    /** Activated once the ward-bed bill is paid (CASH path) or fully covered (INSURANCE path). */
    IN_PROCESS("IN-PROCESS"),

    /** Admission stopped before full activation — intermediate STOP state. */
    STOPPED("STOPPED"),

    /** Admission held pending further action. */
    HELD("HELD"),

    /** Fully signed out (discharge / referral / deceased summary approved). */
    SIGNED_OUT("SIGNED-OUT");

    private final String dbValue;

    AdmissionStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The EXACT string persisted to {@code admissions.status} (matches the V44 CHECK). */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolve from the persisted DB string (used by {@link AdmissionStatusConverter}).
     *
     * @throws IllegalArgumentException if the value is outside the V44 CHECK vocabulary
     */
    public static AdmissionStatus fromDbValue(String value) {
        return Stream.of(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown admission status: " + value));
    }
}
