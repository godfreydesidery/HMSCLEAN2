package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link StoreToPharmacyRN} (inc-08b chunk 6). */
public interface StoreToPharmacyRNRepository extends JpaRepository<StoreToPharmacyRN, Long> {

    Optional<StoreToPharmacyRN> findByUid(String uid);

    boolean existsByStoreToPharmacyTO(StoreToPharmacyTO to);
}
