package com.otapp.hmis.clinical.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Read view for a {@code DeceasedNote} bound to an admission (clinical::api surface,
 * inc-07 07a-3).
 *
 * <p>Exposed in the {@code clinical::api} named interface so the {@code inpatient} module can
 * consume the result of {@link AdmissionDispositionPort} calls without importing any
 * {@code clinical.application} or {@code clinical.domain} type (ADR-0008 §1, ADR-0022 D5).
 *
 * <p>The {@code createdByUserUid} field is essential for the inpatient SoD second-approver gate
 * (CR-07-SoD): {@code DispositionService} reads the creator from this view to compare against
 * {@code ctx.actorUsername()} at approve time.
 *
 * <p>No {@code id} field — internal surrogate key never crosses the module boundary (ADR-0014 §1).
 *
 * @param uid               the note's ULID (public identifier)
 * @param admissionUid      loose uid of the owning admission
 * @param patientUid        loose uid of the patient
 * @param patientSummary    patient summary narrative
 * @param causeOfDeath      cause of death narrative
 * @param deathDate         date of death (client-supplied)
 * @param deathTime         time of death (client-supplied)
 * @param status            PENDING or APPROVED (String — no domain enum crossing boundary)
 * @param createdByUserUid  loose uid of the user who created this note (SoD gate)
 * @param approvedByUserUid loose uid of the approving user (null until approved)
 * @param approvedAt        approval timestamp (null until approved)
 * @param createdAt         creation timestamp
 */
public record DeceasedNoteView(
        String uid,
        String admissionUid,
        String patientUid,
        String patientSummary,
        String causeOfDeath,
        LocalDate deathDate,
        LocalTime deathTime,
        String status,
        String createdByUserUid,
        String approvedByUserUid,
        Instant approvedAt,
        Instant createdAt
) {
}
