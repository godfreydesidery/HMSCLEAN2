package com.otapp.hmis.billing.domain;

/**
 * PARITY lifecycle states for {@link PatientInvoice} (PatientInvoice.java:50).
 *
 * <p>Legacy reality: the header only ever takes these two values (build-spec §3.1,
 * 01-extract-invoice-payment-core.md §2). The richer lifecycle PENDING/PARTIALLY_PAID/
 * PAID/CANCELLED is [GATED:CR-01] and must NOT be built until ratified.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>{@code PENDING} — set at creation (PatientServiceImpl.java:342, :631, :871, :940)</li>
 *   <li>{@code APPROVED} — batch-applied to prior PENDING invoices at start of next charge tx
 *       (PatientServiceImpl.java:586-590). Claim-batch close; NOT a payment event.</li>
 * </ul>
 */
public enum InvoiceStatus {

    /** Accumulating claim lines. Initial state at creation. */
    PENDING,

    /**
     * Prior claim batch closed at start of a new charge transaction.
     * Not a payment event — just a batch boundary marker.
     */
    APPROVED
}
