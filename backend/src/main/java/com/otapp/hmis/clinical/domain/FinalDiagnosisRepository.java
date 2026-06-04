package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link FinalDiagnosis} (inc-05 C6).
 *
 * <p>Byte-for-byte structural twin of {@link WorkingDiagnosisRepository} operating on the
 * separate {@code final_diagnoses} table. The duplicate-guard query
 * {@link #existsByConsultationAndDiagnosisTypeUid} reproduces the consultation-path guard
 * at PatientResource.java:1782.
 *
 * <p>There is intentionally NO unique DB constraint on (admission_uid, diagnosis_type_uid) —
 * the admission path is unguarded by design (CR-INC05-07 asymmetry).
 *
 * <p>Note: the same {@code diagnosisTypeUid} may appear in BOTH {@code working_diagnoses}
 * and {@code final_diagnoses} for the same consultation — the dup-guard is per-table only,
 * not cross-table. This is legacy parity: they are completely independent entities.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Duplicate guard: PatientResource.java:1782 (existsByConsultationAndDiagnosisType)</li>
 *   <li>List by consultation: PatientResource.java:load_consultation_final_diagnosis</li>
 *   <li>Delete: PatientResource.java:1917-1929 (delete_final_diagnosis)</li>
 * </ul>
 */
public interface FinalDiagnosisRepository extends JpaRepository<FinalDiagnosis, Long> {

    /**
     * Locate a final diagnosis by its ULID public identifier.
     *
     * @param uid the ULID of the final diagnosis
     * @return the entity, or empty if not found
     */
    Optional<FinalDiagnosis> findByUid(String uid);

    /**
     * Duplicate guard: check whether a FinalDiagnosis already exists for the given
     * (consultation, diagnosisTypeUid) pair (PatientResource.java:1782).
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
     * List all final diagnoses for a consultation, ordered by creation time ascending.
     *
     * <p>APPEND semantics: multiple distinct diagnosis types per consultation are allowed;
     * only the same type is blocked by the duplicate guard.
     *
     * @param consultation the owning consultation
     * @return the list, ordered by creation time ascending
     */
    List<FinalDiagnosis> findByConsultationOrderByCreatedAtAsc(Consultation consultation);
}
