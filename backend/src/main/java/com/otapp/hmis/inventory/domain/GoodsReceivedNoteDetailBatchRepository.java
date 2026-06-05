package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link GoodsReceivedNoteDetailBatch} (inc-08b). */
public interface GoodsReceivedNoteDetailBatchRepository
        extends JpaRepository<GoodsReceivedNoteDetailBatch, Long> {

    Optional<GoodsReceivedNoteDetailBatch> findByUid(String uid);
}
