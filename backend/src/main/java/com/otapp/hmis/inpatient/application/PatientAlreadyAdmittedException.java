package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when a doAdmission request is rejected because the patient already has an open
 * admission (PENDING or IN-PROCESS) — guard order step 3 (inc-07 07a).
 *
 * <p>Maps to HTTP 422 via {@link ErrorCode#BUSINESS_RULE}.
 * The detail message is the VERBATIM legacy string (PatientResource.java:5199):
 * {@code "Could not process admission. The patient is already admitted"}.
 * This exact text is preserved so the Angular client and QA tooling can match on it.
 *
 * <p>Legacy citation: PatientResource.java:5199.
 */
public class PatientAlreadyAdmittedException extends HmisException {

    /** Verbatim legacy message (PatientResource.java:5199). */
    static final String MESSAGE =
            "Could not process admission. The patient is already admitted";

    public PatientAlreadyAdmittedException() {
        super(ErrorCode.BUSINESS_RULE, MESSAGE);
    }
}
