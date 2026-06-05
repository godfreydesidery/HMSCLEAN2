package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientDressingChart} (inc-07 07b).
 *
 * <p>Legacy citation: PatientDressingChart.java:40-95; PatientServiceImpl.java:2078-2245.
 * inc-07 07b / AC-07B-DRS-01.
 */
public interface PatientDressingChartRepository
        extends JpaRepository<PatientDressingChart, Long> {

    /** Find by ULID public uid. */
    Optional<PatientDressingChart> findByUid(String uid);

    /** All dressing charts for a given admission, oldest first. */
    List<PatientDressingChart> findByAdmissionUidOrderByCreatedAtAsc(String admissionUid);

    /** Find by the associated bill uid (for delete reversal). */
    Optional<PatientDressingChart> findByPatientBillUid(String patientBillUid);
}
