package com.otapp.hmis.clinical.application.dto;

/**
 * Response DTO for a {@link com.otapp.hmis.clinical.domain.ClinicalNote} (inc-05 C5).
 *
 * <p>No internal {@code id} field — only the ULID {@code uid} is exposed (ADR-0014 §1).
 * Encounter references are opaque ULID strings (no cross-module entity refs).
 * All 8 SOAP fields are nullable (legacy parity — no @NotBlank on any SOAP field).
 *
 * @param uid                        ULID of the clinical note
 * @param consultationUid            ULID of the owning consultation (null for non-consultation or admission)
 * @param nonConsultationUid         ULID of the owning non-consultation (null for consultation or admission)
 * @param admissionUid               loose uid of the owning admission (null for consultation or non-consultation; DEFERRED)
 * @param businessDayUid             loose uid of the open business day at creation
 * @param mainComplain               chief complaint (max 500 chars)
 * @param presentIllnessHistory      history of present illness
 * @param pastMedicalHistory         past medical history
 * @param familyAndSocialHistory     family and social history
 * @param drugsAndAllergyHistory     drugs and allergy history
 * @param reviewOfOtherSystems       review of other systems
 * @param physicalExamination        physical examination findings
 * @param managementPlan             management / treatment plan
 */
public record ClinicalNoteDto(
        String uid,
        String consultationUid,
        String nonConsultationUid,
        String admissionUid,
        String businessDayUid,
        String mainComplain,
        String presentIllnessHistory,
        String pastMedicalHistory,
        String familyAndSocialHistory,
        String drugsAndAllergyHistory,
        String reviewOfOtherSystems,
        String physicalExamination,
        String managementPlan
) {
}
