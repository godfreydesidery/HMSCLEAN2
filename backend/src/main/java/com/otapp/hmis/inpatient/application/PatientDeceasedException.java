package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when a doAdmission request is rejected because the patient is DECEASED
 * (CR-07-deceased-guard, owner-approved net-new hardening — inc-07 07a, guard order step 2).
 *
 * <p>Maps to HTTP 422 via {@link ErrorCode#PATIENT_DECEASED}.
 *
 * <p>NET-NEW (inc-07): legacy had no admit-time deceased branch. This closes the safety gap
 * identified by the owner. The {@link ErrorCode#PATIENT_DECEASED} type URI is stable so the
 * Angular client can react without string-matching.
 */
public class PatientDeceasedException extends HmisException {

    public PatientDeceasedException() {
        super(ErrorCode.PATIENT_DECEASED, ErrorCode.PATIENT_DECEASED.title());
    }
}
