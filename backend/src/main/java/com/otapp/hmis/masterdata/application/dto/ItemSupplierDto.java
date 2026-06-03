package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.ItemSupplier} (build-spec §1.2).
 * FK entities are exposed as {@code uid} strings. No {@code id} field.
 */
public record ItemSupplierDto(
        String uid,
        String itemUid,
        String supplierUid,
        BigDecimal costPriceVatIncl,
        BigDecimal costPriceVatExcl,
        boolean active) {
}
