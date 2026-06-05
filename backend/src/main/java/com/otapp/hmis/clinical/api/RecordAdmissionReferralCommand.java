package com.otapp.hmis.clinical.api;

/**
 * Command DTO for saving a referral plan bound to an admission (inpatient path — inc-07 07a-3).
 *
 * <p>Carries the admissionUid (loose — no physical FK) and the referral narrative fields.
 * The {@code consultation} side of the XOR is always null for this command.
 *
 * <p>Legacy citation: PatientResource.java:5593-5685 (get_referral_summary request body
 * fields for the admission path).
 *
 * @param admissionUid               loose uid of the owning admission (non-blank, enforced by caller)
 * @param patientUid                 loose uid of the patient (denormalised for note creation)
 * @param externalMedicalProviderUid MANDATORY loose uid of the external provider (CR-07-Q7)
 * @param referringDiagnosis         narrative (nullable)
 * @param history                    narrative (nullable)
 * @param investigation              narrative (nullable)
 * @param management                 narrative (nullable)
 * @param operationNote              narrative (nullable)
 * @param icuAdmissionNote           narrative (nullable)
 * @param generalRecommendation      narrative (nullable)
 */
public record RecordAdmissionReferralCommand(
        String admissionUid,
        String patientUid,
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
