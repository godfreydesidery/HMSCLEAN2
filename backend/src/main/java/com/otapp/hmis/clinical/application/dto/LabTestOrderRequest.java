package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for ordering (saving) a lab test on an encounter (C7).
 *
 * <p>The encounter uid comes from the URL path (consultation uid or non-consultation uid),
 * NOT from this body. Only the labTestTypeUid is mandatory per legacy parity.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientServiceImpl.java:820-849 (lab order create, labTestType mandatory)</li>
 *   <li>LabTest.java:80-83 (@NotNull labTestType ref)</li>
 * </ul>
 */
public record LabTestOrderRequest(
        /**
         * MANDATORY loose uid of the lab test type (masterdata).
         * Verified via LabTestTypeLookup before persist.
         */
        @NotBlank String labTestTypeUid,

        /**
         * Optional loose uid of the diagnosis type to associate with this test.
         * Nullable — legacy LabTest.diagnosisTypeUid is optional.
         */
        String diagnosisTypeUid,

        /**
         * Optional loose uid of the ordering clinician user.
         * Nullable — the lab tech may order without a specific clinician on record.
         */
        String clinicianUserUid,

        /**
         * For OUTSIDER (non-consultation) orders: patient uid is required so billing
         * can record the charge against the correct patient.
         *
         * <p>For consultation orders: patientUid is derived from the consultation entity
         * (consultation.getPatientUid()) — this field is IGNORED on the consultation path.
         *
         * <p>For non-consultation orders: REQUIRED to build the ChargeRequest correctly.
         * The WalkInService.getOrCreateInProcess also needs the patient uid.
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
