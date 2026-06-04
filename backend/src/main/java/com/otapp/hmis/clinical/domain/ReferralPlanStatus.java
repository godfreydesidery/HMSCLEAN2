package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle status for a {@link ReferralPlan} (ReferralPlan.java:47, inc-05 C12).
 *
 * <p>All three values are valid Java identifiers — plain {@code @Enumerated(STRING)}, no
 * custom AttributeConverter needed (mirrors {@link DeceasedNoteStatus}).
 *
 * <p>State machine (consultation OPD path):
 * <pre>
 *   save_referral_plan → PENDING
 *   get_referral_summary (approve) → APPROVED
 *   day-rollover ARCHIVED sweep → ARCHIVED  [DEFERRED — CR-INC05-11]
 * </pre>
 *
 * <p>Legacy citation: ReferralPlan.java:47 (status column values).
 */
public enum ReferralPlanStatus {

    /**
     * Plan has been created; the owning consultation is SIGNED_OUT; the patient's insurance
     * has been cleared (CASH) via the {@code PatientInsuranceClearedEvent} cross-module seam.
     */
    PENDING,

    /**
     * Plan has been approved (get_referral_summary transition completed).
     * The owning consultation status is re-confirmed SIGNED_OUT (unconditional reset).
     */
    APPROVED,

    /**
     * Archived by the day-rollover sweep.
     * Hidden from all list endpoints.
     *
     * <p><strong>DEFERRED (CR-INC05-11):</strong> Same as {@link DeceasedNoteStatus#ARCHIVED}.
     * Not produced by the current C12 implementation.
     */
    ARCHIVED
}
