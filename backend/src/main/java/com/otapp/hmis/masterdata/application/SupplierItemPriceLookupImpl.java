package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.SupplierItemPrice;
import com.otapp.hmis.masterdata.domain.SupplierItemPriceRepository;
import com.otapp.hmis.masterdata.lookup.SupplierItemPriceLookup;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Package-private implementation of {@link SupplierItemPriceLookup} (inc-08b). */
@Service
@RequiredArgsConstructor
class SupplierItemPriceLookupImpl implements SupplierItemPriceLookup {

    private final SupplierItemPriceRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> priceFor(String supplierUid, String itemUid) {
        return repository.findBySupplierUidAndItemUid(supplierUid, itemUid)
                .map(SupplierItemPrice::getPrice);
    }
}
