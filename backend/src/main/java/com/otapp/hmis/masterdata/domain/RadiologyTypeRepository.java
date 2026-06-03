package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RadiologyTypeRepository extends JpaRepository<RadiologyType, Long> {

    Optional<RadiologyType> findByUid(String uid);

    Optional<RadiologyType> findByCode(String code);

    List<RadiologyType> findAllByOrderByNameAsc();
}
