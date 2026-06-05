package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Read view for a {@code ReferralPlan} bound to an admission (clinical::api surface,
 * inc-07 07a-3).
 *
 * <p>Exposed in the {@code clinical::api} named interface so the {@code inpatient} module can
 * consume the result of {@link AdmissionDispositionPort} calls without importing any
 * {@code clinical.application} or {@code clinical.domain} type (ADR-0008 §1, ADR-0022 D5).
 *
 * <p>The {@code createdByUserUid} field is essential for the inpatient SoD second-approver gate
 * (CR-07-SoD): {@code DispositionService} reads the creator from this view to compare against
 * {@code ctx.actorUsername()} at approve time.
 *
 * <p>No {@code id} field — internal surrogate key never crosses the module boundary (ADR-0014 §1).
 *
 * @param uid                        the plan's ULID (public identifier)
 * @param admissionUid               loose uid of the owning admission
 * @param patientUid                 loose uid of the patient
 * @param externalMedicalProviderUid MANDATORY loose uid of the external provider
 * @param referringDiagnosis         narrative (nullable)
 * @param history                    narrative (nullable)
 * @param investigation              narrative (nullable)
 * @param management                 narrative (nullable)
 * @param operationNote              narrative (nullable)
 * @param icuAdmissionNote           narrative (nullable)
 * @param generalRecommendation      narrative (nullable)
 * @param status                     PENDING or APPROVED (String — no domain enum crossing boundary)
 * @param createdByUserUid           loose uid of the user who created this plan (SoD gate)
 * @param approvedByUserUid          loose uid of the approving user (null until approved)
 * @param approvedAt                 approval timestamp (null until approved)
 * @param createdAt                  creation timestamp
 */
public record ReferralPlanView(
        String uid,
        String admissionUid,
        String patientUid,
        String externalMedicalProviderUid,
        String referringDiagnosis,
        String history,
        String investigation,
        String management,
        String operationNote,
        String icuAdmissionNote,
        String generalRecommendation,
        String status,
        String createdByUserUid,
        String approvedByUserUid,
        Instant approvedAt,
        Instant createdAt
) {
}
