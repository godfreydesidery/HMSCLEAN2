package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link MedicationAdministration} (inc-07 07d, CR-07-MAR).
 */
public interface MedicationAdministrationRepository
        extends JpaRepository<MedicationAdministration, Long> {

    Optional<MedicationAdministration> findByUid(String uid);

    /**
     * All MAR entries bound to a given admission (loose uid), oldest first — the inpatient
     * closed-loop MAR read surface.
     */
    List<MedicationAdministration> findByAdmissionUidOrderByCreatedAtAsc(String admissionUid);
}
