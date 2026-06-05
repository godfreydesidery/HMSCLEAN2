package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientNursingChart} (inc-07 07b).
 *
 * <p>Legacy citation: PatientNursingChart.java:38-45; PatientResource.java:3135-3138.
 * inc-07 07b / AC-07B-NCA-01.
 */
public interface PatientNursingChartRepository extends JpaRepository<PatientNursingChart, Long> {

    /** Find by ULID public uid. */
    Optional<PatientNursingChart> findByUid(String uid);

    /** All charts for a given admission, oldest first. */
    List<PatientNursingChart> findByAdmissionUidOrderByCreatedAtAsc(String admissionUid);
}
