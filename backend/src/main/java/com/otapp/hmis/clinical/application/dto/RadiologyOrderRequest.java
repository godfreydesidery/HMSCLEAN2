package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for ordering (saving) a radiology examination on an encounter (C8).
 *
 * <p>The encounter uid comes from the URL path (consultation uid or non-consultation uid),
 * NOT from this body. Only the radiologyTypeUid is mandatory per legacy parity.
 *
 * <p>Mirrors {@link LabTestOrderRequest} with the lab-specific field replaced by
 * {@code radiologyTypeUid}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Radiology.java:76-79 (radiologyType mandatory ref)</li>
 *   <li>PatientServiceImpl.java — radiology order create path</li>
 * </ul>
 */
public record RadiologyOrderRequest(
        /**
         * MANDATORY loose uid of the radiology type (masterdata).
         * Verified via RadiologyTypeLookup before persist.
         */
        @NotBlank String radiologyTypeUid,

        /**
         * Optional loose uid of the diagnosis type to associate with this order.
         * Nullable — legacy Radiology.diagnosisTypeUid is optional.
         */
        String diagnosisTypeUid,

        /**
         * Optional loose uid of the ordering clinician user.
         * Nullable — the radiographer may order without a specific clinician on record.
         */
        String clinicianUserUid,

        /**
         * For OUTSIDER (non-consultation) orders: patient uid required for billing.
         *
         * <p>For consultation orders: patientUid is derived from the consultation entity
         * (consultation.getPatientUid()) — this field is IGNORED on the consultation path.
         *
         * <p>For non-consultation orders: REQUIRED to build the ChargeRequest correctly.
         */
        String patientUid,

        /**
         * For OUTSIDER orders: visit uid for the walk-in encounter get-or-create.
         * Nullable — the visit may not be known for walk-in patients.
         */
        String visitUid,

        /**
         * Payment type override for the charge (CASH / INSURANCE).
         * Nullable — defaults to the encounter's payment type if not supplied.
         * For consultation path: derived from consultation.paymentMode.
         * For non-consultation path: this field is used to determine the billing mode.
         */
        String paymentType,

        /**
         * Insurance membership number (empty for CASH patients).
         * For consultation path: derived from consultation.membershipNo.
         * For non-consultation path: this field is used.
         */
        String membershipNo,

        /**
         * Insurance plan uid (null for CASH patients).
         * For consultation path: derived from consultation.insurancePlanUid.
         * For non-consultation path: this field is used.
         */
        String insurancePlanUid
) {
}
