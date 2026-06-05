package com.otapp.hmis.inventory.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for the {@link Purchase} ledger (inc-08b). */
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    List<Purchase> findByGoodsReceivedNote(GoodsReceivedNote grn);
}
