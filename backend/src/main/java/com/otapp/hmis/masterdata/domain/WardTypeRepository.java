package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WardTypeRepository extends JpaRepository<WardType, Long> {

    Optional<WardType> findByUid(String uid);

    Optional<WardType> findByCode(String code);

    List<WardType> findAllByOrderByNameAsc();
}
