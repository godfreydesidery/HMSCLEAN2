package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierItemPriceRepository extends JpaRepository<SupplierItemPrice, Long> {

    Optional<SupplierItemPrice> findByUid(String uid);

    /**
     * The price row for a (supplier, item) pair keyed by their public uids — used by the inc-08
     * {@code SupplierItemPriceLookup} cross-module seam (LPO detail price source; legacy
     * "Item not valid for this supplier" guard when absent — LocalPurchaseOrderServiceImpl.java:231-273).
     */
    @Query("""
            SELECT sip FROM SupplierItemPrice sip
            WHERE sip.supplier.uid = :supplierUid AND sip.item.uid = :itemUid
            """)
    Optional<SupplierItemPrice> findBySupplierUidAndItemUid(
            @Param("supplierUid") String supplierUid, @Param("itemUid") String itemUid);

    List<SupplierItemPrice> findAllBySupplier(Supplier supplier);

    List<SupplierItemPrice> findAllByItem(Item item);

    List<SupplierItemPrice> findAllByOrderBySupplierNameAsc();
}
