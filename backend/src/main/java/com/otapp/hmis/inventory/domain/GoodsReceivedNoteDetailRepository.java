package com.otapp.hmis.inventory.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link GoodsReceivedNoteDetail} (inc-08b). */
public interface GoodsReceivedNoteDetailRepository extends JpaRepository<GoodsReceivedNoteDetail, Long> {

    Optional<GoodsReceivedNoteDetail> findByUid(String uid);
}
