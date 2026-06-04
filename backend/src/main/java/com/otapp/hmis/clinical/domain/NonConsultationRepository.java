package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link NonConsultation}.
 *
 * <p>All finders are uid-keyed (String) — no cross-module entity references (ADR-0008 §1,
 * ADR-0022 D2 Correction). The repository supports the get-or-create IN_PROCESS pattern used
 * by the order-save paths (C7-C9, deferred) and the sign-out path.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>get-or-create lookup: PatientServiceImpl.java:790-806 (lab), :1033-1048 (radiology),
 *       :1280-1296 (procedure)</li>
 *   <li>sign-out: PatientResource.java:350</li>
 * </ul>
 */
public interface NonConsultationRepository extends JpaRepository<NonConsultation, Long> {

    /**
     * Locate a non-consultation by ULID public identifier.
     */
    Optional<NonConsultation> findByUid(String uid);

    /**
     * Find the current open (IN_PROCESS) walk-in encounter for a patient.
     *
     * <p>Used by the get-or-create pattern in {@code WalkInService.getOrCreateInProcess}:
     * if a row is found, reuse it; otherwise create a new IN_PROCESS row.
     *
     * @param patientUid the ULID of the patient
     * @param status     {@link NonConsultationStatus#IN_PROCESS}
     * @return the patient's current open walk-in encounter, or empty if none exists
     */
    Optional<NonConsultation> findByPatientUidAndStatus(String patientUid,
                                                         NonConsultationStatus status);

    /**
     * All non-consultations for a patient, ordered by creation time descending.
     *
     * @param patientUid the ULID of the patient
     * @return non-consultations for this patient, newest first
     */
    List<NonConsultation> findByPatientUidOrderByCreatedAtDesc(String patientUid);
}
