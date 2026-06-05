package com.otapp.hmis.inventory.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link GoodsReceivedNote} (inc-08b). */
public interface GoodsReceivedNoteRepository extends JpaRepository<GoodsReceivedNote, Long> {

    Optional<GoodsReceivedNote> findByUid(String uid);

    Optional<GoodsReceivedNote> findByNo(String no);

    /** One-GRN-per-LPO guard (legacy GoodsReceivedNoteServiceImpl.java:64-108). */
    boolean existsByLocalPurchaseOrder(LocalPurchaseOrder lpo);

    List<GoodsReceivedNote> findByStatusOrderByCreatedAtAsc(GrnStatus status);
}
