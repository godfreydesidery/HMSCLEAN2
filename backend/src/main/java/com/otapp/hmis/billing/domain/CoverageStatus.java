package com.otapp.hmis.billing.domain;

/**
 * Denormalized snapshot of bill coverage at the time a {@link PatientInvoiceDetail} is
 * attached to its invoice (build-spec §1.2, §12).
 *
 * <p>Mirrors the PatientBill status subset relevant to claim-line tracking.
 *
 * <p>Exposed via the {@code billing :: api} named interface — the published {@code ChargeResult}
 * record carries it for external callers.
 */
@org.springframework.modulith.NamedInterface("api")
public enum CoverageStatus {

    /** Bill is cash UNPAID (no coverage). */
    UNPAID,

    /** Bill status was COVERED at attach time (insurance plan row found). */
    COVERED,

    /**
     * Bill status was VERIFIED at attach time (inpatient cash fallback — insurance
     * patient with no covered row but admitted; PatientServiceImpl.java:912-918).
     */
    VERIFIED
}
