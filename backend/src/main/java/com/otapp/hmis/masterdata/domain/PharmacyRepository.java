package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PharmacyRepository extends JpaRepository<Pharmacy, Long> {

    Optional<Pharmacy> findByUid(String uid);

    Optional<Pharmacy> findByCode(String code);

    List<Pharmacy> findAllByOrderByNameAsc();
}
