package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    Optional<Medicine> findByUid(String uid);

    Optional<Medicine> findByCode(String code);

    List<Medicine> findAllByOrderByNameAsc();
}
