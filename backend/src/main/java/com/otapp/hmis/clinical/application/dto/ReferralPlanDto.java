package com.otapp.hmis.clinical.application.dto;

import com.otapp.hmis.clinical.domain.ReferralPlanStatus;
import java.time.Instant;

/**
 * Read DTO for {@link com.otapp.hmis.clinical.domain.ReferralPlan} (inc-05 C12).
 *
 * <p>No {@code id} field — the internal surrogate key is never exposed (ADR-0014 §1).
 *
 * @param uid                        the plan's ULID (public identifier)
 * @param patientUid                 loose uid of the patient
 * @param consultationUid            uid of the owning consultation (OPD path); null for admission
 * @param admissionUid               loose uid of the owning admission (DEFERRED); null for OPD
 * @param externalMedicalProviderUid MANDATORY loose uid of the external provider
 * @param referringDiagnosis         narrative (nullable)
 * @param history                    narrative (nullable)
 * @param investigation              narrative (nullable)
 * @param management                 narrative (nullable)
 * @param operationNote              narrative (nullable)
 * @param icuAdmissionNote           narrative (nullable)
 * @param generalRecommendation      narrative (nullable)
 * @param status                     PENDING / APPROVED / ARCHIVED
 * @param approvedByUserUid          loose uid of the approving user (null until approved)
 * @param approvedOnDayUid           loose uid of the approving business day (null until approved)
 * @param approvedAt                 approval timestamp (null until approved)
 * @param createdAt                  creation timestamp
 */
public record ReferralPlanDto(
        String uid,
        String patientUid,
        String consultationUid,
        String admissionUid,
        String externalMedicalProviderUid,
        String referringDiagnosis,
        String history,
        String investigation,
        String management,
        String operationNote,
        String icuAdmissionNote,
        String generalRecommendation,
        ReferralPlanStatus status,
        String approvedByUserUid,
        String approvedOnDayUid,
        Instant approvedAt,
        Instant createdAt
) {
}
