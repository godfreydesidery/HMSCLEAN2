package com.otapp.hmis.inpatient.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for {@code POST /api/v1/inpatient/admissions} (doAdmission — inc-07 07a).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * Insurance fields are nullable for CASH patients.
 *
 * <p>Legacy citation: LAdmission request body sent to PatientResource.java:5187.
 *
 * @param patientUid       the ULID of the patient to admit
 * @param wardBedUid       the ULID of the target bed (must be active and EMPTY)
 * @param paymentType      {@code "CASH"} or {@code "INSURANCE"} — copied from patient
 *                         but supplied explicitly so the UI can confirm the patient's current
 *                         payment mode before admission
 * @param insurancePlanUid loose uid of the insurance plan (null/omitted for CASH patients)
 * @param membershipNo     insurance membership number (null/omitted for CASH patients)
 */
public record AdmissionRequest(
        @NotBlank String patientUid,
        @NotBlank String wardBedUid,
        @NotBlank String paymentType,
        String insurancePlanUid,
        String membershipNo
) {
}
