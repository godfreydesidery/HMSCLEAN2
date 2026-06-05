package com.otapp.hmis.clinical.api;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Command DTO for saving a deceased note bound to an admission (inpatient path — inc-07 07a-3).
 *
 * <p>Carries the admissionUid (loose — no physical FK) and the death narrative fields.
 * The {@code consultation} side of the XOR is always null for this command.
 *
 * <p><strong>Mandatory fields:</strong>
 * Both {@code patientSummary} and {@code causeOfDeath} are MANDATORY at the service level.
 * The service validates these and throws 422 with the verbatim legacy message
 * "Summary and cause of death are missing" if either is blank
 * (PatientResource.java:5720-5730). This is enforced in the service, NOT via @NotBlank,
 * per the adversarial-review BLOCKING item.
 *
 * <p>Legacy citation: PatientResource.java:5693-5773 (save_deceased_note for the
 * admission path).
 *
 * @param admissionUid   loose uid of the owning admission (non-blank, enforced by caller)
 * @param patientUid     loose uid of the patient (denormalised for note creation)
 * @param patientSummary MANDATORY patient summary narrative — blank triggers 422
 * @param causeOfDeath   MANDATORY cause of death narrative — blank triggers 422
 * @param deathDate      date of death (client-supplied, nullable)
 * @param deathTime      time of death (client-supplied, nullable)
 */
public record RecordAdmissionDeceasedNoteCommand(
        String admissionUid,
        String patientUid,
        String patientSummary,
        String causeOfDeath,
        LocalDate deathDate,
        LocalTime deathTime
) {
}
