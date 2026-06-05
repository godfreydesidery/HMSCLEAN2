package com.otapp.hmis.inpatient.application.dto;

import java.time.Instant;

/**
 * Response DTO for an {@link com.otapp.hmis.inpatient.domain.Admission} (inc-07 07a).
 *
 * <p>All identifiers are public uids — NO internal {@code id} field (ADR-0014 §1).
 *
 * @param uid              the admission's public ULID
 * @param patientUid       loose uid of the patient
 * @param wardBedUid       loose uid of the assigned bed
 * @param paymentType      CASH or INSURANCE
 * @param insurancePlanUid loose uid of the insurance plan (null for CASH)
 * @param membershipNo     insurance membership number (empty for CASH)
 * @param status           admission lifecycle status (PENDING / IN-PROCESS / etc.)
 * @param admittedAt       the instant of admission
 * @param dischargedAt     the instant of discharge (null while still admitted)
 */
public record AdmissionDto(
        String uid,
        String patientUid,
        String wardBedUid,
        String paymentType,
        String insurancePlanUid,
        String membershipNo,
        String status,
        Instant admittedAt,
        Instant dischargedAt
) {
}
