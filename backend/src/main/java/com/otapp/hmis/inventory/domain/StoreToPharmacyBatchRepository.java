package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link StoreToPharmacyBatch} (inc-08b chunk 6). */
public interface StoreToPharmacyBatchRepository extends JpaRepository<StoreToPharmacyBatch, Long> {
    Optional<StoreToPharmacyBatch> findByUid(String uid);
}
