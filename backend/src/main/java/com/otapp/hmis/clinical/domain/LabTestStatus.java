package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle statuses for a {@link LabTest} order (LabTest.java:55, V24 CHECK constraint).
 *
 * <p>All five values are valid Java identifiers (no hyphens, unlike ConsultationStatus),
 * so a plain {@code @Enumerated(EnumType.STRING)} is used — NO AttributeConverter needed.
 *
 * <p>State machine (legacy: PatientResource.java:3947-3980):
 * <ul>
 *   <li>{@code PENDING}   — initial state on order creation (save_lab_test).</li>
 *   <li>{@code ACCEPTED}  — lab tech accepts the order (from PENDING or REJECTED).</li>
 *   <li>{@code REJECTED}  — lab tech rejects the order (from PENDING or ACCEPTED).</li>
 *   <li>{@code COLLECTED} — specimen collected (from ACCEPTED).</li>
 *   <li>{@code VERIFIED}  — result written and verified (from COLLECTED).</li>
 * </ul>
 *
 * <p>Additional transition:
 * <ul>
 *   <li>hold: ACCEPTED → PENDING (revert to pending, clears accepted audit fields).</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>LabTest.java:55 (status String field)</li>
 *   <li>V24 CHECK status IN ('PENDING','ACCEPTED','REJECTED','COLLECTED','VERIFIED')</li>
 *   <li>PatientResource.java:3947-3980 (transition guards)</li>
 * </ul>
 */
public enum LabTestStatus {

    /** Order placed; awaiting lab tech action. */
    PENDING,

    /** Lab tech has accepted the order; specimen not yet collected. */
    ACCEPTED,

    /** Lab tech rejected the order (rejectComment explains why). */
    REJECTED,

    /** Specimen has been collected from the patient. */
    COLLECTED,

    /** Result written and verified; order is finalized. */
    VERIFIED
}
