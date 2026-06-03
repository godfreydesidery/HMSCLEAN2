package com.otapp.hmis.registration.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/patients/uid/{uid}/send-to-doctor}
 * (build-spec §3.2, CR-22 — endpoint equivalent to legacy {@code do_consultation}).
 *
 * <p>Legacy citation: PatientServiceImpl.java:425-475 (do_consultation parameter set).
 *
 * @param clinicUid          ULID of the target clinic (masterdata module, no FK)
 * @param clinicianUserUid   ULID of the assigned clinician user (iam module, no FK)
 * @param followUp           true when this is a follow-up consultation — no charge is raised;
 *                           the consultation-fee PatientBill carries status NONE
 *                           (PatientServiceImpl.java:467-469; CR-20)
 */
public record SendToDoctorRequest(
        @NotBlank String clinicUid,
        @NotBlank String clinicianUserUid,
        boolean followUp
) {
}
