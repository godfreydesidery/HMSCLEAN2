package com.otapp.hmis.billing.domain;

/**
 * Live payment modes (PatientBill.java:57 — only CASH and INSURANCE are live in legacy;
 * DEBIT_CARD/CREDIT_CARD/MOBILE appear only in a source comment, not in any code path).
 *
 * <p>PARITY: two values only. [GATED:CR-08] extends to DEBIT_CARD, CREDIT_CARD, MOBILE.
 *
 * <p>Legacy citations: PatientServiceImpl.java:391, :550, :566, :609, :846, :1086, :1815
 */
public enum PaymentMode {

    /** Self-pay / cash collection at cashier. */
    CASH,

    /** Insurance-covered charge; balance settled via claim. */
    INSURANCE
}
