package com.otapp.hmis.inventory.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link LocalPurchaseOrder} (inc-08b). */
public interface LocalPurchaseOrderRepository extends JpaRepository<LocalPurchaseOrder, Long> {

    Optional<LocalPurchaseOrder> findByUid(String uid);

    Optional<LocalPurchaseOrder> findByNo(String no);

    List<LocalPurchaseOrder> findByStatusOrderByCreatedAtAsc(LpoStatus status);

    List<LocalPurchaseOrder> findByStoreUidOrderByCreatedAtDesc(String storeUid);
}
