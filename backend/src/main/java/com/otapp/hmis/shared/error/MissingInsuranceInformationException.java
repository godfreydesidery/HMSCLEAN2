package com.otapp.hmis.shared.error;

/**
 * Thrown when a registration request specifies {@code paymentType=INSURANCE} but either
 * {@code insurancePlanUid} is null or {@code membershipNo} is blank.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@link ErrorCode#MISSING_INSURANCE_INFORMATION}.
 *
 * <p>Legacy citation: PatientResource.java:299-301 — the legacy guard checked that
 * insurance patients supply a valid membership number; the new build adds the plan-uid
 * check for full consistency with the DB constraint {@code ck_patients_insurance_consistency}.
 * Build-spec §2.3 step 1.
 */
public class MissingInsuranceInformationException extends HmisException {

    public MissingInsuranceInformationException() {
        super(ErrorCode.MISSING_INSURANCE_INFORMATION,
                "Insurance plan and membership number are required for insurance patients");
    }
}
