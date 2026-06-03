package com.otapp.hmis.billing.domain;

/**
 * Status of a {@link PatientCreditNote}.
 *
 * <p>PARITY: only {@code PENDING} exists in legacy — the note is never auto-applied
 * and never transitions (PatientCreditNoteServiceImpl.java:33-40).
 */
public enum CreditNoteStatus {

    /**
     * Credit note created; awaiting manual reconciliation.
     * The only observable state in legacy; never transitions.
     */
    PENDING
}
