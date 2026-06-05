package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link StoreToPharmacyTO} (inc-08b chunk 6). */
public interface StoreToPharmacyTORepository extends JpaRepository<StoreToPharmacyTO, Long> {

    Optional<StoreToPharmacyTO> findByUid(String uid);

    boolean existsByPharmacyToStoreRO(PharmacyToStoreRO ro);
}
