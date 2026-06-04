package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle status for the {@link Procedure} clinical procedure order aggregate.
 *
 * <p>Values are stored as VARCHAR(20) via {@code @Enumerated(EnumType.STRING)}.
 *
 * <p><strong>V26 CHECK constraint (exact reproduction):</strong>
 * {@code status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'VERIFIED')}
 *
 * <p><strong>NO APPROVED, NO COLLECTED states</strong>:
 * The planning-doc M14 "approve" step was fabricated (CR drift correction).
 * The legacy {@code Procedure.java} status field carries only these four values.
 *
 * <p><strong>REJECTED is theoretically reachable</strong> by legacy design (the accept guard
 * allows PENDING|REJECTED → ACCEPTED), but there is no reject_procedure endpoint in the
 * legacy system, so REJECTED is unreachable at runtime. It is retained as a valid enum
 * value for entity and constraint fidelity. The vestigial {@code held_*} columns and the
 * {@code accepted from REJECTED} guard are reproduced verbatim.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Procedure.java:54 — status field with these four values</li>
 *   <li>V26 CHECK: status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'VERIFIED')</li>
 *   <li>PatientResource.java:3408-3414 — add_note transition (ACCEPTED → VERIFIED)</li>
 * </ul>
 */
public enum ProcedureStatus {

    /** Initial state when a procedure order is created. */
    PENDING,

    /** Order has been accepted by theatre/clinical staff. */
    ACCEPTED,

    /**
     * Order was rejected.
     *
     * <p>UNREACHABLE at runtime — there is no reject_procedure endpoint in the legacy system.
     * Retained as a valid enum value for entity/constraint fidelity and the accept-from-REJECTED
     * guard (Procedure.accept() allows PENDING|REJECTED → ACCEPTED).
     */
    REJECTED,

    /**
     * Final state: procedure note has been added, order is complete.
     *
     * <p>Reached via add_note (PatientResource.java:3408-3414):
     * ACCEPTED + settled=true + note non-empty → VERIFIED.
     */
    VERIFIED
}
