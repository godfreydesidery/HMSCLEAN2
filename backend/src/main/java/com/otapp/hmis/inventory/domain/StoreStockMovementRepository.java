package com.otapp.hmis.inventory.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link StoreStockMovement} (inc-08b). Append-only (no update/delete). */
public interface StoreStockMovementRepository extends JpaRepository<StoreStockMovement, Long> {

    List<StoreStockMovement> findByStoreUidAndItemUidOrderByOccurredAtAsc(String storeUid, String itemUid);

    List<StoreStockMovement> findByStoreItemOrderByOccurredAtAsc(StoreItem storeItem);
}
