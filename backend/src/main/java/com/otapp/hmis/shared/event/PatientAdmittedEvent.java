package com.otapp.hmis.shared.event;

/**
 * Cross-module event published by the {@code inpatient} module when a patient is admitted
 * (doAdmission — inc-07 07a).
 *
 * <p>The {@code registration} module's {@code PatientClosureListener} handles this event and
 * sets {@code Patient.type = INPATIENT} via {@code patient.changeType(PatientType.INPATIENT)}.
 *
 * <p><strong>Event seam design (inc-07 07a SEAM-A — no cycle):</strong>
 * <ul>
 *   <li>This record lives in {@code shared.event} — the {@code shared} module is OPEN
 *       (ADR-0014 §1), so every module can use it without an explicit allowed-dependency edge.</li>
 *   <li>{@code inpatient} imports only {@code shared} when publishing. It imports NOTHING from
 *       {@code registration}, so no inpatient→registration compile edge is created.</li>
 *   <li>{@code registration} imports only {@code shared} when consuming. No new registration→inpatient
 *       edge is added here.</li>
 *   <li>Result: no cycle. {@code ApplicationModules.verify()} stays green.</li>
 *   <li>The event is published synchronously via {@code ApplicationEventPublisher.publishEvent()}
 *       inside the same DB transaction as the doAdmission operation. The
 *       {@code @TransactionalEventListener(phase = BEFORE_COMMIT)} listener executes before the
 *       outer commit, so the Patient.type flip and the admission creation are fully atomic.</li>
 * </ul>
 *
 * <p><strong>PHI note:</strong>
 * The patientUid is a ULID (not a patient name, diagnosis, or financial identifier). It is safe
 * to include in structured log messages at DEBUG level.
 *
 * <p>Legacy citation: PatientServiceImpl.java:1785 — {@code p.setType("INPATIENT")} inline.
 * inc-07 07a SEAM-A splits this into an event to avoid an inpatient→registration compile cycle.
 *
 * @param patientUid    the ULID of the patient whose type must be changed to INPATIENT
 * @param actorUsername the username of the admitting principal (for audit attribution — SEC-01)
 */
public record PatientAdmittedEvent(String patientUid, String actorUsername) {
}
