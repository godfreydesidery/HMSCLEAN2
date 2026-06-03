package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicRepository extends JpaRepository<Clinic, Long> {

    Optional<Clinic> findByUid(String uid);

    Optional<Clinic> findByCode(String code);

    List<Clinic> findAllByOrderByNameAsc();
}
