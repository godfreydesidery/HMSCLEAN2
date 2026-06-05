package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link PharmacyToStoreRO} (inc-08b chunk 6). */
public interface PharmacyToStoreRORepository extends JpaRepository<PharmacyToStoreRO, Long> {
    Optional<PharmacyToStoreRO> findByUid(String uid);
}
