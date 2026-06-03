package com.otapp.hmis.billing.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when a bill is not in a payable state ({@code UNPAID} or {@code VERIFIED}).
 *
 * <p>PARITY — legacy message: "One or more bills have been paid/covered/canceled..."
 * (PatientBillResource.java:295-296).
 *
 * <p>HTTP 422 Unprocessable Entity via {@link ErrorCode#BILL_NOT_PAYABLE}.
 */
public class BillNotPayableException extends HmisException {

    public BillNotPayableException(String billUid) {
        super(ErrorCode.BILL_NOT_PAYABLE,
              "One or more bills have been paid/covered/canceled and cannot be paid again"
              + (billUid != null ? " [bill: " + billUid + "]" : ""));
    }
}
