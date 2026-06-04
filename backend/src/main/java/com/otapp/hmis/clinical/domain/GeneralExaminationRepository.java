package com.otapp.hmis.clinical.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link GeneralExamination} (inc-05 C5).
 *
 * <p>Finders keyed by uid or by the intra-module encounter associations.
 * The partial UNIQUE indexes on {@code consultation_id} and {@code non_consultation_id}
 * (V23) ensure at most one row per consultation / per non-consultation.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>load_general_examination_by_consultation_id: PatientResource.java (auto-create)</li>
 *   <li>load_general_examination_by_non_consultation_id: PatientResource.java</li>
 *   <li>saveCG UPSERT: PatientResource.java:1469-1598</li>
 *   <li>Vitals copy on request: PatientResource.java:1340</li>
 * </ul>
 */
public interface GeneralExaminationRepository extends JpaRepository<GeneralExamination, Long> {

    /**
     * Locate a general examination by ULID public identifier.
     */
    Optional<GeneralExamination> findByUid(String uid);

    /**
     * Find the existing general examination for a consultation (UPSERT lookup).
     *
     * @param consultation the consultation entity
     * @return the existing examination, or empty if none exists
     */
    Optional<GeneralExamination> findByConsultation(Consultation consultation);

    /**
     * Find the existing general examination for a non-consultation (UPSERT lookup).
     *
     * @param nonConsultation the non-consultation entity
     * @return the existing examination, or empty if none exists
     */
    Optional<GeneralExamination> findByNonConsultation(NonConsultation nonConsultation);
}
