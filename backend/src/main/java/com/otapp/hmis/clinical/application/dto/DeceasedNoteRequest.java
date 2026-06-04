package com.otapp.hmis.clinical.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for {@code save_deceased_note} (POST /consultations/uid/{uid}/deceased-note).
 *
 * <p>Both {@code patientSummary} and {@code causeOfDeath} are mandatory at the service layer
 * (not via Bean Validation — the legacy throws a business error "Summary and cause of death
 * are missing" when either is blank, which is reproduced verbatim as a 422 in the service).
 *
 * <p>Legacy citation: DeceasedNote.java:36-76 (field names verbatim from entity).
 *
 * @param patientSummary  patient summary at time of death (must be non-blank — service enforces)
 * @param causeOfDeath    cause of death narrative (must be non-blank — service enforces)
 * @param deathDate       date of death (client-supplied, verbatim; nullable)
 * @param deathTime       time of death (client-supplied, verbatim; nullable)
 */
public record DeceasedNoteRequest(
        String patientSummary,
        String causeOfDeath,
        LocalDate deathDate,
        LocalTime deathTime
) {
}
