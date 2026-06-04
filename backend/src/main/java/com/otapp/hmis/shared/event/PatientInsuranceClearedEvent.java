package com.otapp.hmis.shared.event;

/**
 * Cross-module event published by the {@code clinical} module when an OPD referral plan is
 * saved (save_referral_plan transition — inc-05 C12).
 *
 * <p>The {@code registration} module's {@code PatientClosureListener} handles this event and
 * calls {@code patient.changePaymentType(PaymentType.CASH, null, "")} to clear the insurance
 * plan association for a referred-out patient.
 *
 * <p><strong>Rationale for clearing insurance at referral-save (not referral-approve):</strong>
 * Legacy behaviour: the patient is referred out immediately on save (PENDING state).
 * The insurance is cleared in the same transaction so the patient is immediately CASH on
 * the registration side.  The approve step confirms the referral is complete but does not
 * re-mutate the patient (the Patient is already OUTPATIENT type — no type change is needed
 * because the patient was never changed from OUTPATIENT).
 *
 * <p><strong>Event seam design (C12 ADR):</strong>
 * Same pattern as {@link PatientDeceasedEvent} — both types live in {@code shared.event},
 * creating no compile-time edge between {@code clinical} and {@code registration}.
 * Published synchronously in the same transaction; handled
 * {@code @TransactionalEventListener(phase = BEFORE_COMMIT)} for atomic commit.
 *
 * @param patientUid     the ULID of the patient whose insurance must be cleared
 * @param actorUsername  the username of the approving principal (for audit attribution — SEC-01)
 */
public record PatientInsuranceClearedEvent(String patientUid, String actorUsername) {
}
