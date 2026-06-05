package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link PharmacyToPharmacyRN} (inc-08b chunk 7). */
public interface PharmacyToPharmacyRNRepository extends JpaRepository<PharmacyToPharmacyRN, Long> {
    Optional<PharmacyToPharmacyRN> findByUid(String uid);
    boolean existsByPharmacyToPharmacyTO(PharmacyToPharmacyTO to);
}
