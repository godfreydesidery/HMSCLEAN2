package com.otapp.hmis.shared.error;

/**
 * Thrown when a patient type or state change is rejected by a business guard.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@link ErrorCode#BUSINESS_RULE}, which is
 * the equivalent of the legacy {@code InvalidOperationException} (PatientResource.java:499,
 * :505, :485-488; build-spec §8 C4).
 *
 * <p>The exact legacy message is passed as {@code detail} so the Angular client and QA
 * tooling can match on the verbatim text.  The {@code type} URI
 * {@code urn:hmis:error:business-rule} is stable and must not change (ADR-0005 §5).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>INPATIENT guard: PatientResource.java:499-500
 *       "This operation is not allowed for inpatients"</li>
 *   <li>Active-consultation guard: PatientResource.java:485-488
 *       "Can not change patient type, the patient has an active consultation."</li>
 *   <li>Admissions guard on change_payment_type: PatientResource.java:366-367
 *       "Could not change. Patient has an ongoing medical operation"</li>
 *   <li>Generic catch-all: PatientResource.java:505
 *       "Patient type could not be changed."</li>
 * </ul>
 */
public class InvalidPatientOperationException extends HmisException {

    /**
     * Construct with the verbatim legacy message as the detail.
     *
     * @param detail the exact message string from the legacy system
     */
    public InvalidPatientOperationException(String detail) {
        super(ErrorCode.BUSINESS_RULE, detail);
    }
}
