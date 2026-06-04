package com.otapp.hmis.clinical.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientVital} (inc-05 C5).
 *
 * <p>Finders keyed by uid or by the intra-module encounter associations.
 * The partial UNIQUE index on {@code consultation_id} (V23) ensures at most one PatientVital
 * row per consultation — the GET load-or-create logic depends on this.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Auto-create EMPTY on GET: PatientResource.java:1298-1307</li>
 *   <li>Submitted vitals worklist: PatientResource.java:1321</li>
 *   <li>Request / ARCHIVED: PatientResource.java:1340</li>
 * </ul>
 */
public interface PatientVitalRepository extends JpaRepository<PatientVital, Long> {

    /**
     * Locate a patient vital by ULID public identifier.
     */
    Optional<PatientVital> findByUid(String uid);

    /**
     * Find the existing PatientVital for a consultation (load-or-create lookup).
     *
     * <p>The V23 partial UNIQUE index on {@code consultation_id} guarantees at most one row.
     *
     * @param consultation the consultation entity
     * @return the existing PatientVital, or empty if none exists
     */
    Optional<PatientVital> findByConsultation(Consultation consultation);

    /**
     * Find the existing PatientVital for a consultation with a specific status.
     *
     * <p>Used by the request-vital guard: the service verifies
     * {@code status == SUBMITTED} before archiving and copying into the exam.
     *
     * @param consultation the consultation entity
     * @param status       the required status
     * @return the PatientVital with the given status, or empty if none
     */
    Optional<PatientVital> findByConsultationAndStatus(Consultation consultation,
                                                        PatientVitalStatus status);
}
