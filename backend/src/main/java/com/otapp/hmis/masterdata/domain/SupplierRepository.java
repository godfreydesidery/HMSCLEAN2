package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByUid(String uid);

    Optional<Supplier> findByCode(String code);

    List<Supplier> findAllByOrderByNameAsc();
}
