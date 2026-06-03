package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheatreRepository extends JpaRepository<Theatre, Long> {

    Optional<Theatre> findByUid(String uid);

    Optional<Theatre> findByCode(String code);

    List<Theatre> findAllByOrderByNameAsc();
}
