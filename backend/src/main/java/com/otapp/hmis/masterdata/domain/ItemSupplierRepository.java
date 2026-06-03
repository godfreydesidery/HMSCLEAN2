package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemSupplierRepository extends JpaRepository<ItemSupplier, Long> {

    Optional<ItemSupplier> findByUid(String uid);

    List<ItemSupplier> findAllByItem(Item item);

    List<ItemSupplier> findAllBySupplier(Supplier supplier);

    List<ItemSupplier> findAllByOrderByItemNameAsc();
}
