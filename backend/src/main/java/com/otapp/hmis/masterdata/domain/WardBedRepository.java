package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WardBedRepository extends JpaRepository<WardBed, Long> {

    Optional<WardBed> findByUid(String uid);

    List<WardBed> findAllByWardOrderByNoAsc(Ward ward);

    List<WardBed> findAllByOrderByNoAsc();
}
