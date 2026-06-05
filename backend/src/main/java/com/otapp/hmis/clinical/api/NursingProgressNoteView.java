package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code PatientNursingProgressNote} record
 * (inc-07 07b, ADR-0008 §1).
 *
 * <p>No entity type leaks across the module boundary.
 *
 * <p>Legacy citation: PatientNursingProgressNote.java:38.
 * inc-07 07b / AC-07B-NPR-01.
 *
 * @param uid          the note's public ULID
 * @param admissionUid loose uid of the owning admission
 * @param nurseUid     loose uid of the nurse
 * @param note         progress note free-text (nullable)
 * @param createdAt    audit creation instant
 */
public record NursingProgressNoteView(
        String uid,
        String admissionUid,
        String nurseUid,
        String note,
        Instant createdAt
) {
}
