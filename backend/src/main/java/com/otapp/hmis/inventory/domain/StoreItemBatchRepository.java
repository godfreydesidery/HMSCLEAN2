package com.otapp.hmis.inventory.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link StoreItemBatch} (inc-08b).
 *
 * <p>FEFO selection reproduces the legacy {@code getEarlierBatch} null-expiry EXCLUSION (Q8 baseline):
 * dated lots first (excludes null-expiry when any dated lot exists); else lowest-id over null-expiry
 * lots. {@code id ASC} pinned secondary sort.
 */
public interface StoreItemBatchRepository extends JpaRepository<StoreItemBatch, Long> {

    Optional<StoreItemBatch> findByUid(String uid);

    List<StoreItemBatch> findByStoreItem(StoreItem storeItem);

    @Query("""
            SELECT b FROM StoreItemBatch b
            WHERE b.storeItem = :si AND b.remainingQty > 0 AND b.expiryDate IS NOT NULL
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<StoreItemBatch> findDatedForFefo(@Param("si") StoreItem storeItem);

    @Query("""
            SELECT b FROM StoreItemBatch b
            WHERE b.storeItem = :si AND b.remainingQty > 0 AND b.expiryDate IS NULL
            ORDER BY b.id ASC
            """)
    List<StoreItemBatch> findUndatedForFefo(@Param("si") StoreItem storeItem);
}
