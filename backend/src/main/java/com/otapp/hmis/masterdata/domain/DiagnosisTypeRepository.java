package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisTypeRepository extends JpaRepository<DiagnosisType, Long> {

    Optional<DiagnosisType> findByUid(String uid);

    Optional<DiagnosisType> findByCode(String code);

    List<DiagnosisType> findAllByOrderByNameAsc();
}
