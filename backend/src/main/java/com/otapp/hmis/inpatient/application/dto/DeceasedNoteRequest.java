package com.otapp.hmis.inpatient.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for saving an admission deceased note (POST …/deceased-note — inc-07 07a-3).
 *
 * <p>{@code patientSummary} and {@code causeOfDeath} are MANDATORY; enforced in the service
 * (NOT via @NotBlank — adversarial-review BLOCKING item). Both blank → 422
 * "Summary and cause of death are missing" (PatientResource.java:5720-5730).
 *
 * @param patientSummary MANDATORY patient summary narrative
 * @param causeOfDeath   MANDATORY cause of death narrative
 * @param deathDate      date of death (nullable)
 * @param deathTime      time of death (nullable)
 */
public record DeceasedNoteRequest(
        String patientSummary,
        String causeOfDeath,
        LocalDate deathDate,
        LocalTime deathTime
) {
}
