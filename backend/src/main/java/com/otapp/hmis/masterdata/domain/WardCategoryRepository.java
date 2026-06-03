package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WardCategoryRepository extends JpaRepository<WardCategory, Long> {

    Optional<WardCategory> findByUid(String uid);

    Optional<WardCategory> findByCode(String code);

    List<WardCategory> findAllByOrderByNameAsc();
}
