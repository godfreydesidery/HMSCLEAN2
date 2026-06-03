package com.otapp.hmis.billing.api;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when a CASH outpatient/outsider service is requested before its charge is settled
 * (CR-05, RATIFIED scoped; build-spec §4.2). Part of the published {@code billing.api} contract so
 * clinical modules (inc-05/06) raise it at their {@code accept()} via {@link SettlementPolicy}.
 *
 * <p>HTTP 422 Unprocessable Entity via {@link ErrorCode#PAY_BEFORE_SERVICE}.
 */
public class PayBeforeServiceException extends HmisException {

    public PayBeforeServiceException(String billUid) {
        super(ErrorCode.PAY_BEFORE_SERVICE,
              "Payment is required before this service can be provided"
              + (billUid != null ? " [bill: " + billUid + "]" : ""));
    }
}
