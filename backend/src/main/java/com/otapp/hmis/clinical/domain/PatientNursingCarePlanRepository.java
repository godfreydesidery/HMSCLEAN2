package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientNursingCarePlan} (inc-07 07b).
 *
 * <p>Legacy citation: PatientNursingCarePlan.java:38-41; PatientResource.java:3163-3179.
 * inc-07 07b / AC-07B-NCP-01.
 */
public interface PatientNursingCarePlanRepository
        extends JpaRepository<PatientNursingCarePlan, Long> {

    /** Find by ULID public uid. */
    Optional<PatientNursingCarePlan> findByUid(String uid);

    /** All care plans for a given admission, oldest first. */
    List<PatientNursingCarePlan> findByAdmissionUidOrderByCreatedAtAsc(String admissionUid);
}
