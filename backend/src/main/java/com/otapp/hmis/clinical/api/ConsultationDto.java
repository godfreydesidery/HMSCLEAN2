package com.otapp.hmis.clinical.api;

import com.otapp.hmis.billing.domain.PaymentMode;

/**
 * Response DTO for a {@link com.otapp.hmis.clinical.domain.Consultation} (ADR-0022 D5, CR-22).
 *
 * <p>Published in {@code clinical.api} so the {@code registration} module's
 * {@code PatientRegistrationProcess.sendToDoctor} can return it from the
 * {@code POST /api/v1/patients/uid/{uid}/send-to-doctor} endpoint (ADR-0022 D3).
 *
 * <p>No internal {@code id} field — only the ULID {@code uid} is exposed (ADR-0014 §1).
 * Cross-module refs (clinicUid, clinicianUserUid, patientBillUid) are opaque ULID strings.
 *
 * <p>Uses {@code PaymentMode} from {@code billing.domain} (via the {@code billing::api}
 * named interface — ADR-0022 D5) rather than {@code registration.domain.PaymentType} to
 * avoid a {@code clinical → registration} import edge.
 *
 * <p>Moved from {@code registration.application.dto.ConsultationDto} → {@code clinical.api}
 * per ADR-0022 D5/D6.
 *
 * @param uid                  ULID of the consultation
 * @param patientUid           ULID of the patient
 * @param visitUid             ULID of the associated visit (nullable)
 * @param clinicUid            loose uid of the target clinic (masterdata module)
 * @param clinicianUserUid     loose uid of the assigned clinician user (iam module)
 * @param patientBillUid       loose uid of the consultation-fee PatientBill (billing module)
 * @param paymentMode          payment mode copied from patient at booking time
 * @param followUp             true if this is a follow-up consultation (NONE bill — CR-20)
 * @param settled              clinical-local settlement flag (inc-05 §5)
 * @param membershipNo         insurance membership number (empty for CASH)
 * @param insurancePlanUid     loose uid of the insurance plan (null for CASH)
 * @param status               consultation lifecycle status db-value string (e.g. "PENDING", "IN-PROCESS")
 */
public record ConsultationDto(
        String uid,
        String patientUid,
        String visitUid,
        String clinicUid,
        String clinicianUserUid,
        String patientBillUid,
        PaymentMode paymentMode,
        boolean followUp,
        boolean settled,
        String membershipNo,
        String insurancePlanUid,
        String status
) {
}
