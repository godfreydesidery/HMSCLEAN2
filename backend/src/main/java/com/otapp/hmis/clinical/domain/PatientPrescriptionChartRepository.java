package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientPrescriptionChart}.
 *
 * <p>The write path (create chart) is DEFERRED — admissions do not exist yet
 * (C10 build-spec §5). This repository is present so that the entity is mapped and
 * {@code ddl-auto=validate} passes. Read finders are provided for future use.
 *
 * <p>Legacy citation: PatientPrescriptionChart.java:34-82.
 */
public interface PatientPrescriptionChartRepository
        extends JpaRepository<PatientPrescriptionChart, Long> {

    /**
     * Locate a chart entry by ULID public identifier.
     */
    Optional<PatientPrescriptionChart> findByUid(String uid);

    /**
     * All chart entries for a given prescription, ordered by creation time ascending.
     *
     * @param prescription the parent prescription
     * @return chart entries for this prescription, oldest first
     */
    List<PatientPrescriptionChart> findByPrescriptionOrderByCreatedAtAsc(
            Prescription prescription);

    /**
     * All chart entries for a patient (by uid), ordered by creation time descending.
     *
     * @param patientUid the ULID of the patient
     * @return chart entries for this patient, newest first
     */
    List<PatientPrescriptionChart> findByPatientUidOrderByCreatedAtDesc(String patientUid);
}
