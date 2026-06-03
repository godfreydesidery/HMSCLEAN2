package com.otapp.hmis.billing.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;
import java.math.BigDecimal;

/**
 * Thrown when the tendered total does not exactly equal the sum of selected bill amounts.
 *
 * <p>CR-12: replaces legacy {@code double !=} with {@code BigDecimal.compareTo == 0} on
 * scaled NUMERIC(19,2). PatientBillResource.java:389-391.
 *
 * <p>HTTP 422 Unprocessable Entity via {@link ErrorCode#PAYMENT_AMOUNT_MISMATCH}.
 */
public class PaymentAmountMismatchException extends HmisException {

    public PaymentAmountMismatchException(BigDecimal tendered, BigDecimal computed) {
        super(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
              "Tendered amount " + tendered + " does not match total bill amount " + computed
              + ". Insufficient payment");
    }
}
