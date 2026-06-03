package com.otapp.hmis.billing.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown by {@link BillingChargeService} when a consultation charge is attempted for an
 * insurance patient whose plan has no covered consultation price for this clinic.
 *
 * <p>PARITY — verbatim legacy message: "Plan not available for this clinic. Please change
 * payment method" (PatientServiceImpl.java:599-601). The transaction rolls back and NO bill
 * persists.
 *
 * <p>HTTP 422 Unprocessable Entity via {@link ErrorCode#PLAN_NOT_AVAILABLE_FOR_CLINIC}.
 */
public class PlanNotAvailableForClinicException extends HmisException {

    public PlanNotAvailableForClinicException() {
        super(ErrorCode.PLAN_NOT_AVAILABLE_FOR_CLINIC,
              // Verbatim legacy message — must not be changed (parity contract)
              "Plan not available for this clinic. Please change payment method");
    }
}
