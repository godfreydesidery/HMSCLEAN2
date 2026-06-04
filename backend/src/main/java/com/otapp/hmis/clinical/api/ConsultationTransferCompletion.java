package com.otapp.hmis.clinical.api;

/**
 * Cross-module API for completing a pending consultation transfer at rebook time
 * (ADR-0022 D3/D5, inc-05 C3).
 *
 * <p>When a patient is re-sent to a doctor via the {@code sendToDoctor} endpoint
 * (registration's {@code PatientRegistrationProcess}), if a PENDING transfer exists for
 * the patient the target clinic MUST match {@code transfer.destinationClinicUid}. On match
 * the transfer is marked COMPLETED in the same transaction before the new consultation is
 * booked.
 *
 * <p>Called from {@code registration.PatientRegistrationProcess.sendToDoctor} inside the
 * registration's own {@code @Transactional} boundary — propagation MANDATORY (same tx).
 * The completion is atomic with the new consultation creation and the billing charge.
 *
 * <p>Implementation: package-private in
 * {@code clinical.application.ConsultationTransferService}.
 *
 * <p>Legacy citation: PatientServiceImpl.java:431-435
 * (doConsultation: findByPatientAndStatus PENDING → check destinationClinic == target → mark COMPLETED).
 */
public interface ConsultationTransferCompletion {

    /**
     * If a PENDING transfer exists for the patient, verify the target clinic matches the
     * transfer's destination and mark the transfer COMPLETED.
     *
     * <p>Guard: if a PENDING transfer exists but {@code targetClinicUid} does NOT match
     * {@code transfer.destinationClinicUid}, throws
     * {@link com.otapp.hmis.shared.error.InvalidPatientOperationException} (422) with the
     * verbatim legacy message naming the required destination clinic
     * (PatientServiceImpl.java:431-435).
     *
     * <p>If no PENDING transfer exists, this method is a no-op (returns silently).
     *
     * @param patientUid     loose uid of the patient being re-booked
     * @param targetClinicUid loose uid of the target clinic for the new consultation
     * @param actorUsername  username for audit attribution
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException (422) if a PENDING
     *         transfer exists and {@code targetClinicUid} != {@code transfer.destinationClinicUid}
     */
    void completePendingTransferOnRebook(String patientUid, String targetClinicUid,
                                          String actorUsername);
}
