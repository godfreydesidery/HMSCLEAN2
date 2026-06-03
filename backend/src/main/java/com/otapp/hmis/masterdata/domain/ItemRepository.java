package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByUid(String uid);

    Optional<Item> findByCode(String code);

    List<Item> findAllByOrderByNameAsc();
}
