package com.otapp.hmis.registration.application.dto;

import com.otapp.hmis.registration.domain.PaymentType;

/**
 * Response DTO for a {@link com.otapp.hmis.registration.domain.Consultation} (build-spec §3.2,
 * CR-22).
 *
 * <p>No internal {@code id} field — only the ULID {@code uid} is exposed (ADR-0014 §1).
 * Cross-module refs (clinicUid, clinicianUserUid, patientBillUid) are opaque ULID strings.
 *
 * @param uid                  ULID of the consultation
 * @param patientUid           ULID of the patient (from {@code patient.uid})
 * @param clinicUid            loose uid of the target clinic (masterdata module)
 * @param clinicianUserUid     loose uid of the assigned clinician user (iam module)
 * @param patientBillUid       loose uid of the consultation-fee PatientBill (billing module)
 * @param paymentType          payment type copied from patient at booking time
 * @param followUp             true if this is a follow-up consultation (NONE bill — CR-20)
 * @param status               consultation lifecycle status name (e.g. "PENDING")
 */
public record ConsultationDto(
        String uid,
        String patientUid,
        String clinicUid,
        String clinicianUserUid,
        String patientBillUid,
        PaymentType paymentType,
        boolean followUp,
        String status
) {
}
