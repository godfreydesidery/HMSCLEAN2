package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcedureTypeRepository extends JpaRepository<ProcedureType, Long> {

    Optional<ProcedureType> findByUid(String uid);

    Optional<ProcedureType> findByCode(String code);

    List<ProcedureType> findAllByOrderByNameAsc();
}
