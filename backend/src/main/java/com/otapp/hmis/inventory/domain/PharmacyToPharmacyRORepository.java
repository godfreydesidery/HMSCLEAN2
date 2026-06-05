package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link PharmacyToPharmacyRO} (inc-08b chunk 7). */
public interface PharmacyToPharmacyRORepository extends JpaRepository<PharmacyToPharmacyRO, Long> {
    Optional<PharmacyToPharmacyRO> findByUid(String uid);
}
