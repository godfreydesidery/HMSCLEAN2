package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle statuses for a {@link Radiology} order (Radiology.java, V25 CHECK constraint).
 *
 * <p>All five values are valid Java identifiers — plain {@code @Enumerated(EnumType.STRING)};
 * no AttributeConverter needed.
 *
 * <p>State machine (legacy: PatientResource.java:4280-4292):
 * <ul>
 *   <li>{@code PENDING}   — initial state on order creation.</li>
 *   <li>{@code ACCEPTED}  — radiographer accepts the order (from PENDING or REJECTED).</li>
 *   <li>{@code REJECTED}  — radiographer rejects the order (from PENDING or ACCEPTED).</li>
 *   <li>{@code COLLECTED} — DEAD state. The {@code collect_radiology111} endpoint is a dead /
 *       malformed endpoint (PatientResource.java:4317, CR-INC05-14). COLLECTED is present in
 *       the DB CHECK constraint for data fidelity on legacy-migrated rows; there is NO live
 *       transition into it in the new system. Do NOT expose a collect endpoint.</li>
 *   <li>{@code VERIFIED}  — result written and verified (from ACCEPTED directly —
 *       PatientResource.java:4280-4281). The active path is ACCEPTED → VERIFIED.</li>
 * </ul>
 *
 * <p>Additional transitions:
 * <ul>
 *   <li>hold: ACCEPTED → PENDING (revert to pending, clears accepted audit fields).</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Radiology.java status field</li>
 *   <li>V25 CHECK status IN ('PENDING','ACCEPTED','REJECTED','COLLECTED','VERIFIED')</li>
 *   <li>PatientResource.java:4280-4292 (transition guards — verify from ACCEPTED)</li>
 *   <li>CR-INC05-14 (collect_radiology111 is a dead/malformed endpoint)</li>
 * </ul>
 */
public enum RadiologyStatus {

    /** Order placed; awaiting radiographer action. */
    PENDING,

    /** Radiographer has accepted the order. */
    ACCEPTED,

    /** Radiographer rejected the order (rejectComment explains why). */
    REJECTED,

    /**
     * DEAD state — retained in the enum for CHECK constraint fidelity on legacy-migrated rows.
     * There is NO live transition into COLLECTED in the new system.
     * The {@code collect_radiology111} endpoint (PatientResource.java:4317) is dead (CR-INC05-14)
     * and is NOT exposed.
     */
    COLLECTED,

    /**
     * Result written and verified; order is finalized.
     * Active verify path: ACCEPTED → VERIFIED (PatientResource.java:4280-4281).
     * NO collect step in the middle.
     */
    VERIFIED
}
