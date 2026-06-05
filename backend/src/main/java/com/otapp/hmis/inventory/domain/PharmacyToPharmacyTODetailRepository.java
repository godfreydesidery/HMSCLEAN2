package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link PharmacyToPharmacyTODetail} (inc-08b chunk 7). */
public interface PharmacyToPharmacyTODetailRepository
        extends JpaRepository<PharmacyToPharmacyTODetail, Long> {
    Optional<PharmacyToPharmacyTODetail> findByUid(String uid);
}
