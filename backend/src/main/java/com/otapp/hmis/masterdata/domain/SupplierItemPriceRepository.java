package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierItemPriceRepository extends JpaRepository<SupplierItemPrice, Long> {

    Optional<SupplierItemPrice> findByUid(String uid);

    List<SupplierItemPrice> findAllBySupplier(Supplier supplier);

    List<SupplierItemPrice> findAllByItem(Item item);

    List<SupplierItemPrice> findAllByOrderBySupplierNameAsc();
}
