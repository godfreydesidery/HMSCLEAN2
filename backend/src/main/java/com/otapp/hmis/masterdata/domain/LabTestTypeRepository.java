package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabTestTypeRepository extends JpaRepository<LabTestType, Long> {

    Optional<LabTestType> findByUid(String uid);

    Optional<LabTestType> findByCode(String code);

    List<LabTestType> findAllByOrderByNameAsc();
}
