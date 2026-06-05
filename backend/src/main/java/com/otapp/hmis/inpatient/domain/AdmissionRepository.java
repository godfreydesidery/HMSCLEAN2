package com.otapp.hmis.inpatient.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Admission}.
 *
 * <p>Only uid-keyed finders are exposed externally (ADR-0014 §1 — internal {@code id} is never
 * serialised or returned from any API or DTO layer).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>findAllByPatientUidAndStatusIn: PatientResource.java:5197
 *       {@code admissionRepository.findAllByPatientAndStatusIn(p.get(), statuses)} — used in the
 *       "already admitted" guard during doAdmission (inc-07 07a guard order step 3).</li>
 *   <li>findByPatientBillUid: inpatient settlement listener matching pattern
 *       (mirrors ConsultationSettlementListener — inc-07 07a AdmissionSettlementListener).</li>
 * </ul>
 */
public interface AdmissionRepository extends JpaRepository<Admission, Long> {

    /** Locate an admission by its public ULID (primary lookup). */
    Optional<Admission> findByUid(String uid);

    /**
     * Find all admissions for a patient with status in the given list.
     *
     * <p>Used by the "already admitted" guard in doAdmission (PatientResource.java:5197):
     * if any PENDING or IN-PROCESS admission exists for this patient, reject with 422.
     *
     * @param patientUid the patient's loose uid
     * @param statuses   the status values to match (typically [PENDING, IN_PROCESS])
     * @return matching admissions (empty if none — admission is safe to proceed)
     */
    List<Admission> findAllByPatientUidAndStatusIn(String patientUid, List<AdmissionStatus> statuses);
}
