package com.otapp.hmis.shared.event;

/**
 * Cross-module event published by the {@code clinical} module when an OPD death note is
 * approved (get_deceased_summary transition — inc-05 C12).
 *
 * <p>The {@code registration} module's {@code PatientClosureListener} handles this event and
 * sets {@code Patient.type = DECEASED} via {@code patient.changeType(PatientType.DECEASED)}.
 *
 * <p><strong>Event seam design (C12 ADR):</strong>
 * <ul>
 *   <li>This record lives in {@code shared.event} — a package reachable by every module
 *       without creating a direct inter-module compile edge.</li>
 *   <li>{@code clinical} imports only {@code shared} when publishing (no import of
 *       {@code registration}).</li>
 *   <li>{@code registration} imports only {@code shared} when consuming (no import of
 *       {@code clinical}).</li>
 *   <li>Therefore no cycle is introduced and {@code ApplicationModules.verify()} stays green.</li>
 *   <li>The event is published synchronously via {@code ApplicationEventPublisher.publishEvent()}
 *       inside the same DB transaction as the clinical state change.  This keeps the Patient
 *       mutation atomic with the note approval.  A {@code @TransactionalEventListener(
 *       phase = BEFORE_COMMIT)} listener executes before the outer commit, so both the
 *       DeceasedNote approval AND the Patient.type flip commit together or roll back together.</li>
 * </ul>
 *
 * @param patientUid     the ULID of the patient whose type must be changed to DECEASED
 * @param actorUsername  the username of the approving principal (for audit attribution — SEC-01)
 */
public record PatientDeceasedEvent(String patientUid, String actorUsername) {
}
