package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientConsumableChart} (inc-07 07c-i).
 *
 * <p>Legacy citation: PatientConsumableChartRepository.java (the legacy equivalent).
 * inc-07 07c-i / CR-07-consumable-stock.
 */
public interface PatientConsumableChartRepository extends JpaRepository<PatientConsumableChart, Long> {

    /**
     * Find by public uid. Used for delete and bill-uid lookup.
     *
     * @param uid the ULID of the chart entry
     * @return the chart, or empty if not found
     */
    Optional<PatientConsumableChart> findByUid(String uid);

    /**
     * Find all consumable charts for a given admission uid, ordered by creation time ascending.
     *
     * @param admissionUid the loose admission uid
     * @return list of charts, oldest first
     */
    List<PatientConsumableChart> findByAdmissionUidOrderByCreatedAtAsc(String admissionUid);
}
