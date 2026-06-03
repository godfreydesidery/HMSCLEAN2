package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WardRepository extends JpaRepository<Ward, Long> {

    Optional<Ward> findByUid(String uid);

    Optional<Ward> findByCode(String code);

    List<Ward> findAllByOrderByNameAsc();
}
