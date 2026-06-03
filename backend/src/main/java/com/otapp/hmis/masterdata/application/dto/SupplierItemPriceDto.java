package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.SupplierItemPrice}
 * (build-spec §1.2). FK entities as uid strings. No {@code id} field.
 */
public record SupplierItemPriceDto(
        String uid,
        BigDecimal price,
        String terms,
        boolean active,
        String supplierUid,
        String itemUid) {
}
