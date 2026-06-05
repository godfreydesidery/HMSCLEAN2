package com.otapp.hmis.masterdata.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Consumable} masterdata entity (inc-07 07c).
 *
 * <p>Legacy citation: ConsumableRepository.java; PatientServiceImpl.java:2259-2262.
 * inc-07 07c.
 */
public interface ConsumableRepository extends JpaRepository<Consumable, Long> {

    /**
     * Check whether a Medicine is registered as a consumable.
     *
     * @param medicineUid loose uid of the medicine
     * @return the Consumable entry if the medicine is listed as a consumable, else empty
     */
    Optional<Consumable> findByMedicineUid(String medicineUid);
}
