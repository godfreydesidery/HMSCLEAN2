package com.otapp.hmis.clinical.application.dto;

import com.otapp.hmis.clinical.domain.LabTestStatus;
import java.time.Instant;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.LabTest} response (C7, no id leak).
 *
 * <p>All internal {@code id} fields are EXCLUDED (ADR-0014 §1). Only the public {@code uid}
 * is exposed. Encounter binding is expressed as *Uid strings (loose refs).
 */
public record LabTestDto(
        String uid,
        LabTestStatus status,
        boolean settled,
        String labTestTypeUid,
        String patientUid,
        String patientBillUid,
        String paymentType,
        String membershipNo,
        String insurancePlanUid,
        String diagnosisTypeUid,
        String clinicianUserUid,
        // Encounter binding (exactly one non-null)
        String consultationUid,
        String nonConsultationUid,
        String admissionUid,
        // Result fields
        String result,
        String report,
        String description,
        String testRange,
        String level,
        String unit,
        // Lifecycle audit
        String orderedByUserUid,
        String orderedOnDayUid,
        Instant orderedAt,
        String acceptedByUserUid,
        String acceptedOnDayUid,
        Instant acceptedAt,
        String heldByUserUid,
        String heldOnDayUid,
        Instant heldAt,
        String collectedByUserUid,
        String collectedOnDayUid,
        Instant collectedAt,
        String verifiedByUserUid,
        String verifiedOnDayUid,
        Instant verifiedAt,
        String rejectedByUserUid,
        String rejectedOnDayUid,
        Instant rejectedAt,
        String rejectComment,
        String businessDayUid,
        Instant createdAt
) {
}
