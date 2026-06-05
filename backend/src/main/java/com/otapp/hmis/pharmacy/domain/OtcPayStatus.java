package com.otapp.hmis.pharmacy.domain;

/**
 * OTC sale-order DETAIL payment status (inc-08a chunk 4; PharmacySaleOrderDetail default 'UNPAID' →
 * 'PAID' on bill payment, PatientBillResource.java:369-387). Independent of the line's
 * {@link OtcFulfilmentStatus}. Valid Java identifiers → {@code @Enumerated(STRING)}.
 */
public enum OtcPayStatus {

    /** Bill not yet paid. */
    UNPAID,

    /** Linked bill paid (set via the BillSettledEvent seam). */
    PAID
}
