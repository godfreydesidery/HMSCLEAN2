package com.otapp.hmis.billing.domain;

/**
 * Observable lifecycle states of a {@link PatientBill} (PatientBill.java:55 — legacy free-text
 * values normalised into an enum; ALL SIX are behaviourally load-bearing per build-spec §1.3).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>{@code UNPAID}   — created default (PatientServiceImpl.java:274, :828, etc.)</li>
 *   <li>{@code VERIFIED} — regFee==0 (:276) OR inpatient-no-covered-row fallback (:917)</li>
 *   <li>{@code COVERED}  — insurance plan row found (:605, :845, etc.)</li>
 *   <li>{@code PAID}     — cashier collection (PatientBillResource.java:307)</li>
 *   <li>{@code NONE}     — follow-up consultation, no charge (:468)</li>
 *   <li>{@code CANCELED} — single-L, exact legacy spelling (PatientResource.java:627)</li>
 * </ul>
 *
 * <p>Cashier collection gate: only {@code UNPAID} or {@code VERIFIED} are payable
 * (PatientBillResource.java:295-296).
 *
 * <p>Exposed via the {@code billing :: api} named interface — the published {@code ChargeResult}
 * record carries it for external callers.
 */
@org.springframework.modulith.NamedInterface("api")
public enum BillStatus {

    /** Cash charge awaiting cashier collection. Default for all new charges. */
    UNPAID,

    /**
     * Inpatient cash charge with no insurance coverage; or registration when fee is zero.
     * Treated as collectable (equivalent to UNPAID for cashier gate).
     */
    VERIFIED,

    /**
     * Fully met by an insurance plan (paid=amount, balance=0).
     * Routed to the insurance claim accumulator invoice; NOT cash-collected.
     */
    COVERED,

    /** Set by the cashier on cash collection (PatientBillResource.java:307). */
    PAID,

    /** Follow-up consultation with no charge; amount/paid/balance all zero. */
    NONE,

    /**
     * Soft-cancelled (single-L — exact legacy spelling at PatientResource.java:627).
     * Bill is never hard-deleted; audit trail is preserved.
     */
    CANCELED
}
