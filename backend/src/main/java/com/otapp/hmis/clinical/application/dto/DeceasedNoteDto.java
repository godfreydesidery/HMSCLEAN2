package com.otapp.hmis.clinical.application.dto;

import com.otapp.hmis.clinical.domain.DeceasedNoteStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Read DTO for {@link com.otapp.hmis.clinical.domain.DeceasedNote} (inc-05 C12).
 *
 * <p>No {@code id} field — the internal surrogate key is never exposed (ADR-0014 §1).
 * The {@code consultationUid} carries the owning consultation's public uid.
 *
 * @param uid                   the note's ULID (public identifier)
 * @param patientUid            loose uid of the patient
 * @param consultationUid       uid of the owning consultation (OPD path); null for admission path
 * @param admissionUid          loose uid of the owning admission (DEFERRED); null for OPD path
 * @param patientSummary        patient summary narrative
 * @param causeOfDeath          cause of death narrative
 * @param deathDate             date of death (client-supplied)
 * @param deathTime             time of death (client-supplied)
 * @param status                PENDING / APPROVED / ARCHIVED
 * @param approvedByUserUid     loose uid of the approving user (null until approved)
 * @param approvedOnDayUid      loose uid of the approving business day (null until approved)
 * @param approvedAt            approval timestamp (null until approved)
 * @param createdAt             creation timestamp
 */
public record DeceasedNoteDto(
        String uid,
        String patientUid,
        String consultationUid,
        String admissionUid,
        String patientSummary,
        String causeOfDeath,
        LocalDate deathDate,
        LocalTime deathTime,
        DeceasedNoteStatus status,
        String approvedByUserUid,
        String approvedOnDayUid,
        Instant approvedAt,
        Instant createdAt
) {
}
