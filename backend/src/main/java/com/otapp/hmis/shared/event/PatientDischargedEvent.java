package com.otapp.hmis.shared.event;

/**
 * Cross-module event published by the {@code inpatient} module when a patient is discharged
 * (disposition sign-out — inc-07 07a-3).
 *
 * <p>The {@code registration} module's {@code PatientClosureListener} handles this event and
 * sets {@code Patient.type = OUTPATIENT} and clears insurance
 * via {@code patient.changePaymentType(CASH, null, "")}.
 *
 * <p><strong>Event seam design (inc-07 07a SEAM-A — no cycle):</strong>
 * Same seam design as {@link PatientAdmittedEvent}: lives in {@code shared.event}, consumed
 * by {@code registration} with no compile edge to {@code inpatient}.
 *
 * <p><strong>PHI note:</strong>
 * The patientUid is a ULID. Safe to log at DEBUG level.
 *
 * <p>Legacy citation: get_*_summary discharge paths flip Patient.type back to OUTPATIENT
 * (PatientServiceImpl.java — inpatient disposition flows; exact cite resolved in 07a-3).
 * The handler is built now in PatientClosureListener; the publisher wiring lands in 07a-3.
 *
 * @param patientUid    the ULID of the patient whose type must be changed to OUTPATIENT
 * @param actorUsername the username of the discharging principal (for audit attribution — SEC-01)
 */
public record PatientDischargedEvent(String patientUid, String actorUsername) {
}
