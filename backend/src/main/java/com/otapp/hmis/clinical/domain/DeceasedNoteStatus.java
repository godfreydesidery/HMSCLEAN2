package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle status for a {@link DeceasedNote} (DeceasedNote.java:48, inc-05 C12).
 *
 * <p>All three values are valid Java identifiers — plain {@code @Enumerated(STRING)}, no
 * custom AttributeConverter needed (unlike {@link ConsultationStatus} which has hyphenated values).
 *
 * <p>State machine (consultation OPD path):
 * <pre>
 *   save_deceased_note → PENDING
 *   get_deceased_summary (approve) → APPROVED
 *   day-rollover ARCHIVED sweep → ARCHIVED  [DEFERRED — CR-INC05-11]
 * </pre>
 *
 * <p>Legacy citation: DeceasedNote.java:48 (status column values).
 */
public enum DeceasedNoteStatus {

    /**
     * Note has been created but not yet approved by the approving user.
     * The owning consultation is HELD at this point.
     */
    PENDING,

    /**
     * Note has been approved (get_deceased_summary transition completed).
     * The owning consultation is SIGNED_OUT; Patient.type has been set to DECEASED
     * via the {@code PatientDeceasedEvent} cross-module seam.
     */
    APPROVED,

    /**
     * Archived by the day-rollover sweep (48 h grace period).
     * Hidden from all list endpoints.
     *
     * <p><strong>DEFERRED (CR-INC05-11):</strong> The 48-hour ARCHIVED sweep belongs to the
     * business-day bounded context and is not implemented in C12. ARCHIVED notes are never
     * created by the current implementation; the status value is reserved for the future sweep.
     */
    ARCHIVED
}
