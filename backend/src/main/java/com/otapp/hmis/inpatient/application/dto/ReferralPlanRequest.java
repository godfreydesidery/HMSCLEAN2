package com.otapp.hmis.inpatient.application.dto;

/**
 * Request body for saving an admission referral plan (POST …/referral-plan — inc-07 07a-3).
 *
 * <p>{@code externalMedicalProviderUid} is MANDATORY (CR-07-Q7); enforced in the service.
 * Remaining narrative fields are nullable.
 *
 * <p>Legacy citation: PatientResource.java:5593-5685 — get_referral_summary save body.
 *
 * @param externalMedicalProviderUid MANDATORY loose uid of the external provider
 * @param referringDiagnosis         referring diagnosis narrative
 * @param history                    patient history narrative
 * @param investigation              investigation summary
 * @param management                 management/treatment summary
 * @param operationNote              operation note
 * @param icuAdmissionNote           ICU admission note
 * @param generalRecommendation      general recommendation
 */
public record ReferralPlanRequest(
        String externalMedicalProviderUid,
        String referringDiagnosis,
        String history,
        String investigation,
        String management,
        String operationNote,
        String icuAdmissionNote,
        String generalRecommendation
) {
}
