package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link PharmacyToPharmacyTO} (inc-08b chunk 7). */
public interface PharmacyToPharmacyTORepository extends JpaRepository<PharmacyToPharmacyTO, Long> {
    Optional<PharmacyToPharmacyTO> findByUid(String uid);
    boolean existsByPharmacyToPharmacyRO(PharmacyToPharmacyRO ro);
}
