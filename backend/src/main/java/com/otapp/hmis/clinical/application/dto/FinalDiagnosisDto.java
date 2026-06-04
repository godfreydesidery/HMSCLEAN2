package com.otapp.hmis.clinical.application.dto;

/**
 * Response DTO for a {@link com.otapp.hmis.clinical.domain.FinalDiagnosis} (inc-05 C6).
 *
 * <p>Byte-for-byte structural twin of {@link WorkingDiagnosisDto} for the {@code final_diagnoses}
 * table. No internal {@code id} field — only the ULID {@code uid} is exposed (ADR-0014 §1).
 *
 * @param uid               ULID of the final diagnosis
 * @param diagnosisTypeUid  loose uid of the diagnosis type (masterdata reference)
 * @param description       optional free-text description (nullable)
 * @param consultationUid   ULID of the owning consultation (null if admission-bound)
 * @param admissionUid      loose uid of the owning admission (null if consultation-bound; DEFERRED)
 * @param patientUid        loose uid of the patient (copied from consultation at save time)
 * @param businessDayUid    loose uid of the open business day at creation
 */
public record FinalDiagnosisDto(
        String uid,
        String diagnosisTypeUid,
        String description,
        String consultationUid,
        String admissionUid,
        String patientUid,
        String businessDayUid
) {
}
