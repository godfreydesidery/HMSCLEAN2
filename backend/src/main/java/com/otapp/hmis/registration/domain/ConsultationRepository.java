package com.otapp.hmis.registration.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Consultation}.
 *
 * <p>Only uid-keyed and patient-keyed finders are exposed (ADR-0014 §1 — the hidden
 * {@code id} is never returned from any API or DTO layer).
 *
 * <p>The PENDING-consultation guard on {@code send-to-doctor} uses
 * {@link #existsByPatientAndStatusIn(Patient, List)} (build-spec §3.2 step 5 — no existing
 * PENDING/TRANSFERED/IN-PROCESS consultation allowed before booking another).
 */
public interface ConsultationRepository extends JpaRepository<Consultation, Long> {

    /**
     * Locate a consultation by ULID public identifier.
     */
    Optional<Consultation> findByUid(String uid);

    /**
     * All consultations for a patient, ordered by creation time descending.
     */
    List<Consultation> findByPatientOrderByCreatedAtDesc(Patient patient);

    /**
     * Check whether the patient has any consultation in one of the supplied statuses.
     * Used by the {@code send-to-doctor} guard (build-spec §3.2 step 5):
     * no existing PENDING/TRANSFERED/IN-PROCESS → proceed; else 422.
     */
    boolean existsByPatientAndStatusIn(Patient patient, List<ConsultationStatus> statuses);

    /**
     * Check whether the patient has a consultation with the exact supplied status.
     *
     * <p>Used by the {@code change_type} guard (PatientResource.java:485-488,
     * build-spec §8 C4): OUTPATIENT → OUTSIDER is blocked when the patient has any
     * PENDING consultation.
     *
     * <p>In inc-03 only {@link ConsultationStatus#PENDING} exists.  When inc-05 ships
     * IN-PROCESS and TRANSFERRED statuses, the {@code change_type} guard will call
     * {@link #existsByPatientAndStatusIn(Patient, List)} with the widened set instead.
     * <!-- inc-05 widens these statuses -->
     *
     * @param patient the patient aggregate root
     * @param status  the exact status to test for
     * @return {@code true} if at least one consultation in the given status exists
     */
    boolean existsByPatientAndStatus(Patient patient, ConsultationStatus status);
}
