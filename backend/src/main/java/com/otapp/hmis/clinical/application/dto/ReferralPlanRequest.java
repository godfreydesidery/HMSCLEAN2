package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code save_referral_plan}
 * (POST /consultations/uid/{uid}/referral, inc-05 C12).
 *
 * <p>{@code externalMedicalProviderUid} is MANDATORY (non-blank — Bean Validation).
 * All seven narrative fields are optional (nullable).
 *
 * <p><strong>IMPORTANT — no existence check on externalMedicalProviderUid:</strong>
 * The {@code referral.external_medical_providers} table is NOT built in C12. The uid is
 * accepted verbatim (ReferralPlan.java:49-52). Future increment must add lookup validation
 * when the referral module is built.
 *
 * @param externalMedicalProviderUid MANDATORY loose uid of the target external provider
 * @param referringDiagnosis         narrative (nullable)
 * @param history                    narrative (nullable)
 * @param investigation              narrative (nullable)
 * @param management                 narrative (nullable)
 * @param operationNote              narrative (nullable)
 * @param icuAdmissionNote           narrative (nullable)
 * @param generalRecommendation      narrative (nullable)
 */
public record ReferralPlanRequest(
        @NotBlank String externalMedicalProviderUid,
        String referringDiagnosis,
        String history,
        String investigation,
        String management,
        String operationNote,
        String icuAdmissionNote,
        String generalRecommendation
) {
}
