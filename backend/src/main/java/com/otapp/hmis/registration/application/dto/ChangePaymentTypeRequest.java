package com.otapp.hmis.registration.application.dto;

import com.otapp.hmis.registration.domain.PaymentType;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/patients/uid/{uid}/payment-type}.
 *
 * <p>Controls the patient's payment classification.  The service applies the following
 * logic (PatientResource.java:359-373, build-spec §8 C4, CR-03):
 * <ul>
 *   <li><b>Target {@code INSURANCE}</b>: {@code insurancePlanUid} must be non-null AND
 *       {@code membershipNo} must be non-blank — else
 *       {@code MissingInsuranceInformationException} (422,
 *       {@code urn:hmis:error:missing-insurance-information}).  Sets
 *       {@code plan + membership + paymentType=INSURANCE}.</li>
 *   <li><b>Any non-INSURANCE target (i.e. {@code CASH})</b>: collapses to CASH —
 *       {@code insurancePlanUid=null}, {@code membershipNo=""}, {@code paymentType=CASH}
 *       (PatientResource.java:368-373 verbatim).</li>
 * </ul>
 *
 * <p>The legacy admissions guard ("Could not change. Patient has an ongoing medical
 * operation", PatientResource.java:366-367) is a DEFERRED-ENFORCEMENT no-op stub in
 * inc-03 — admissions do not exist until inc-06.  See inline comment in
 * {@code PatientRegistrationProcess.changePaymentType}.
 *
 * <p>Legacy citation: PatientResource.java:359-373 (change_payment_type endpoint, ungated
 * in legacy; CR-03 adds {@code PATIENT-ALL / PATIENT-UPDATE} gate).
 */
public record ChangePaymentTypeRequest(

        /**
         * The desired payment classification.  Must be non-null.
         * {@code CASH} collapses plan+membership; {@code INSURANCE} requires both.
         */
        @NotNull PaymentType paymentType,

        /**
         * Loose uid of the insurance plan (masterdata module; no FK — ADR-0008).
         * Required (non-null) when {@code paymentType=INSURANCE}; ignored/nulled for CASH.
         */
        String insurancePlanUid,

        /**
         * Insurance membership number.  Required (non-blank) when
         * {@code paymentType=INSURANCE}; collapsed to empty-string for CASH.
         */
        String membershipNo
) {
}
