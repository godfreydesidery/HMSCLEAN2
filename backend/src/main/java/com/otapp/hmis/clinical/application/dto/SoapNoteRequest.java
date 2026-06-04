package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for saving (upserting) a SOAP clinical note (inc-05 C5).
 *
 * <p>All 8 SOAP fields are nullable — the legacy has no @NotBlank / @NotNull on any SOAP
 * field (ClinicalNote.java:34-75). The entire note may be submitted with only some fields
 * populated; the rest are stored as null (which is legal per V23).
 *
 * <p>Legacy citation: PatientResource.java:1469-1598 (saveCG UPSERT — ClinicalNote fields).
 *
 * @param mainComplain               chief complaint (max 500 chars per V23)
 * @param presentIllnessHistory      history of present illness
 * @param pastMedicalHistory         past medical history
 * @param familyAndSocialHistory     family and social history
 * @param drugsAndAllergyHistory     drugs and allergy history
 * @param reviewOfOtherSystems       review of other systems
 * @param physicalExamination        physical examination findings
 * @param managementPlan             management / treatment plan
 */
public record SoapNoteRequest(
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
