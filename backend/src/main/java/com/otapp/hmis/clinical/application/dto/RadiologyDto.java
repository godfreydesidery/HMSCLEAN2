package com.otapp.hmis.clinical.application.dto;

import com.otapp.hmis.clinical.domain.RadiologyStatus;
import java.time.Instant;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.Radiology} response (C8, no id leak).
 *
 * <p>All internal {@code id} fields are EXCLUDED (ADR-0014 §1). Only the public {@code uid}
 * is exposed. Encounter binding is expressed as *Uid strings (loose refs).
 *
 * <p>Result columns are radiology-specific: result + report (NO range/level/unit — those
 * are lab-specific). The inline {@code attachment} blob is excluded from the DTO — clients
 * that need the raw blob should use a dedicated download endpoint (not implemented; DEFERRED).
 * The presence of an attachment is indicated by {@code hasAttachment}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Radiology.java:42-150 (entity shape)</li>
 *   <li>PatientResource.java:4280-4292 (lifecycle — ACCEPTED → VERIFIED, no collect)</li>
 * </ul>
 */
public record RadiologyDto(
        String uid,
        RadiologyStatus status,
        boolean settled,
        String radiologyTypeUid,
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
        // Result fields (radiology-specific: result + report; NO range/level/unit)
        String result,
        String report,
        String description,
        // Report-amendment audit (inc-06A C6 / ITEM4)
        String priorReport,
        String reportAmendedByUserUid,
        String reportAmendedOnDayUid,
        Instant reportAmendedAt,
        /**
         * Indicates whether the inline attachment blob is set on this radiology row.
         * The raw bytes are not included in the DTO — use a dedicated download endpoint.
         */
        boolean hasAttachment,
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
        String verifiedByUserUid,
        String verifiedOnDayUid,
        Instant verifiedAt,
        String rejectedByUserUid,
        String rejectedOnDayUid,
        Instant rejectedAt,
        /**
         * Reject comment. NOTE: NOT cleared on accept (radiology asymmetry vs LabTest).
         * See Radiology.accept() for details.
         */
        String rejectComment,
        String businessDayUid,
        Instant createdAt
) {
}
