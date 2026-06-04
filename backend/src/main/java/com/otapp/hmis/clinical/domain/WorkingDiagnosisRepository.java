package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WorkingDiagnosis} (inc-05 C6).
 *
 * <p>Finders are keyed by uid or by the intra-module encounter association (consultation).
 * No cross-module entity references (ADR-0008 §1).
 *
 * <p>The duplicate-guard query {@link #existsByConsultationAndDiagnosisTypeUid} reproduces
 * the consultation-path guard at PatientResource.java:1662 — the service throws
 * {@link com.otapp.hmis.shared.error.InvalidPatientOperationException} on a positive result.
 *
 * <p>There is intentionally NO unique DB constraint on (admission_uid, diagnosis_type_uid) —
 * the admission path is unguarded by design (CR-INC05-07 asymmetry).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Duplicate guard: PatientResource.java:1662 (existsByConsultationAndDiagnosisType)</li>
 *   <li>List by consultation: PatientResource.java:load_consultation_working_diagnosis</li>
 *   <li>Delete: PatientResource.java:1917-1929 (deleteById — we expose via uid)</li>
 * </ul>
 */
public interface WorkingDiagnosisRepository extends JpaRepository<WorkingDiagnosis, Long> {

    /**
     * Locate a working diagnosis by its ULID public identifier.
     *
     * @param uid the ULID of the working diagnosis
     * @return the entity, or empty if not found
     */
    Optional<WorkingDiagnosis> findByUid(String uid);

    /**
     * Duplicate guard: check whether a WorkingDiagnosis already exists for the given
     * (consultation, diagnosisTypeUid) pair (PatientResource.java:1662).
     *
     * <p>Only the consultation path is guarded. The admission path has NO duplicate guard
     * (CR-INC05-07 asymmetry — 11-DECISIONS-RATIFIED.md §2).
     *
     * @param consultation     the owning consultation
     * @param diagnosisTypeUid the loose uid of the diagnosis type
     * @return {@code true} if a duplicate row exists
     */
    boolean existsByConsultationAndDiagnosisTypeUid(Consultation consultation,
                                                     String diagnosisTypeUid);

    /**
     * List all working diagnoses for a consultation, ordered by creation time ascending.
     *
     * <p>APPEND semantics: multiple distinct diagnosis types per consultation are allowed;
     * only the same type is blocked by the duplicate guard.
     *
     * @param consultation the owning consultation
     * @return the list, ordered by creation time ascending
     */
    List<WorkingDiagnosis> findByConsultationOrderByCreatedAtAsc(Consultation consultation);
}
