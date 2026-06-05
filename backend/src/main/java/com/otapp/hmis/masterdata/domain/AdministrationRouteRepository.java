package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdministrationRouteRepository extends JpaRepository<AdministrationRoute, Long> {

    Optional<AdministrationRoute> findByUid(String uid);

    Optional<AdministrationRoute> findByCode(String code);

    List<AdministrationRoute> findAllByOrderByNameAsc();
}
