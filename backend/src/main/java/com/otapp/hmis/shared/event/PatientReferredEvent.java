package com.otapp.hmis.shared.event;

/**
 * Cross-module event published by the {@code inpatient} module when an inpatient admission
 * is closed via the referral disposition (inc-07 07a-3).
 *
 * <p>The {@code registration} module's {@code PatientClosureListener} handles this event and
 * sets {@code Patient.type = OUTPATIENT} ONLY — payment type and insurance plan are NOT
 * cleared. This is the legacy asymmetry between discharge and referral:
 * <ul>
 *   <li><strong>Discharge</strong> (PatientResource.java:5378-5381): sets type=OUTPATIENT AND
 *       clears paymentType=CASH + insurancePlan=null. Uses {@link PatientDischargedEvent}.</li>
 *   <li><strong>Referral</strong> (PatientResource.java:5626): sets type=OUTPATIENT ONLY; does
 *       NOT clear insurance plan or payment type. Uses this {@code PatientReferredEvent}.</li>
 * </ul>
 *
 * <p><strong>Event seam design (inc-07 07a SEAM-A — no cycle):</strong>
 * Same seam design as {@link PatientAdmittedEvent} and {@link PatientDischargedEvent}: lives
 * in {@code shared.event}, consumed by {@code registration} with no compile edge to
 * {@code inpatient}.
 *
 * <p><strong>PHI note:</strong>
 * The patientUid is a ULID. Safe to log at DEBUG level.
 *
 * <p>Legacy citation: PatientResource.java:5626 — referral sign-out sets patient type=OUTPATIENT
 * only; no insurance clearing. Asymmetry vs discharge at :5378-5381 (discharge full reset).
 *
 * @param patientUid    the ULID of the patient whose type must be changed to OUTPATIENT
 * @param actorUsername the username of the approving principal (for audit attribution — SEC-01)
 */
public record PatientReferredEvent(String patientUid, String actorUsername) {
}
