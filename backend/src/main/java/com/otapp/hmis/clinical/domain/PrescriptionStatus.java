package com.otapp.hmis.clinical.domain;

import java.util.stream.Stream;

/**
 * Lifecycle status of a {@link Prescription} (Prescription.java:50).
 *
 * <p>EXACTLY TWO values ever written. The DB column stores hyphenated strings
 * ({@code NOT-GIVEN}, {@code GIVEN}) that are NOT valid Java identifiers, so
 * {@code @Enumerated(EnumType.STRING)} would persist the wrong token and violate the
 * V27 {@code ck_prescriptions_status} CHECK. Mapped by {@link PrescriptionStatusConverter}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Two-status enum: Prescription.java:50</li>
 *   <li>Default NOT-GIVEN: V27 prescriptions.status DEFAULT 'NOT-GIVEN'</li>
 *   <li>Transition NOT-GIVEN → GIVEN: PatientResource.java:3217-3245 (issueMedicine)</li>
 * </ul>
 */
public enum PrescriptionStatus {

    /** Ordered but not yet dispensed. Default on save. DB value: {@code NOT-GIVEN}. */
    NOT_GIVEN("NOT-GIVEN"),

    /** Dispensed in full. Terminal status. DB value: {@code GIVEN}. */
    GIVEN("GIVEN");

    private final String dbValue;

    PrescriptionStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The EXACT string persisted to {@code prescriptions.status} (matches the V27 CHECK). */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolve from the persisted DB string (used by {@link PrescriptionStatusConverter}).
     *
     * @throws IllegalArgumentException if the value is outside the V27 CHECK vocabulary
     */
    public static PrescriptionStatus fromDbValue(String value) {
        return Stream.of(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown prescription status: " + value));
    }
}
