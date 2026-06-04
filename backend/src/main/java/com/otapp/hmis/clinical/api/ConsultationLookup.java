package com.otapp.hmis.clinical.api;

import java.util.Set;

/**
 * Cross-module read guard for open-work consultation state (ADR-0022 D5).
 *
 * <p>Published in {@code clinical.api} so the {@code registration} module's
 * {@code changePatientType} and {@code changePaymentType} guards can check for open
 * consultations without importing the internal {@code clinical.domain.ConsultationRepository}
 * or the domain {@code ConsultationStatus} enum.
 *
 * <p>This replaces the inc-03 {@code consultationRepository.existsByPatientAndStatus(patient,
 * PENDING)} calls after the ownership transfer (ADR-0022 D6).
 *
 * <p>Implementation is package-private in {@code clinical.application.ConsultationLookupImpl}.
 *
 * <p>Legacy citation: PatientResource.java:485-488 (change_type PENDING check) and
 * PatientResource.java:325-357 (change_payment_type open-work check).
 */
public interface ConsultationLookup {

    /**
     * Whether the given patient has at least one consultation in any of the supplied work statuses.
     *
     * <p>Used by registration's {@code changePatientType} and {@code changePaymentType} guards
     * to block operations when the patient has open clinical work.
     *
     * @param patientUid the ULID of the patient
     * @param statuses   the set of {@link ConsultationWorkStatus} values to check
     * @return {@code true} if at least one matching consultation exists
     */
    boolean hasOpenWork(String patientUid, Set<ConsultationWorkStatus> statuses);
}
