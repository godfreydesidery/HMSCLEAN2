package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link StoreToPharmacyTODetail} (inc-08b chunk 6; add_batch path). */
public interface StoreToPharmacyTODetailRepository extends JpaRepository<StoreToPharmacyTODetail, Long> {
    Optional<StoreToPharmacyTODetail> findByUid(String uid);
}
