package com.otapp.hmis.inpatient.application.dto;

import java.time.Instant;

/**
 * Read DTO for {@link com.otapp.hmis.inpatient.domain.DischargePlan} (inc-07 07a-3).
 *
 * <p>No {@code id} field — internal surrogate key never exposed (ADR-0014 §1).
 *
 * @param uid                  the plan's ULID (public identifier)
 * @param admissionUid         loose uid of the owning admission
 * @param history              patient history narrative (nullable)
 * @param investigation        investigation summary (nullable)
 * @param management           management/treatment summary (nullable)
 * @param operationNote        operation note (nullable)
 * @param icuAdmissionNote     ICU admission note (nullable)
 * @param generalRecommendation general recommendation (nullable)
 * @param status               PENDING or APPROVED
 * @param createdBy            username of the user who created the plan (SoD creator)
 * @param approvedBy           username of the approving user (null until approved)
 * @param approvedAt           approval timestamp (null until approved)
 * @param createdAt            creation timestamp
 */
public record DischargePlanDto(
        String uid,
        String admissionUid,
        String history,
        String investigation,
        String management,
        String operationNote,
        String icuAdmissionNote,
        String generalRecommendation,
        String status,
        String createdBy,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt
) {
}
