package com.otapp.hmis.billing.domain;

/**
 * Status of a {@link PatientPaymentDetail} link (PatientPaymentDetail.java:42).
 *
 * <p>Legacy citations: PatientBillResource.java:314 (RECEIVED), PatientResource.java:636-639
 * (flip to REFUNDED on cancel — the soft-cancel pattern, CR-13).
 */
public enum PaymentDetailStatus {

    /** Payment detail is active (bill was collected). */
    RECEIVED,

    /**
     * Bill was cancelled after payment; the payment detail is soft-reversed.
     * No negative amount row is created — this flag IS the reversal signal (CR-13/CR-03).
     */
    REFUNDED
}
