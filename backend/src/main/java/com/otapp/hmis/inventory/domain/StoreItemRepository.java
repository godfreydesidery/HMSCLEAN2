package com.otapp.hmis.inventory.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link StoreItem} (inc-08b). */
public interface StoreItemRepository extends JpaRepository<StoreItem, Long> {

    Optional<StoreItem> findByUid(String uid);

    Optional<StoreItem> findByStoreUidAndItemUid(String storeUid, String itemUid);

    List<StoreItem> findByStoreUidOrderByItemUidAsc(String storeUid);

    boolean existsByStoreUidAndItemUid(String storeUid, String itemUid);
}
