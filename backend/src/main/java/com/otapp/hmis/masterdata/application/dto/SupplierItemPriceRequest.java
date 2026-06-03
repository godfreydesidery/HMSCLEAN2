package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.SupplierItemPrice}.
 */
public record SupplierItemPriceRequest(
        @NotNull BigDecimal price,
        String terms,
        boolean active,
        @NotBlank String supplierUid,
        @NotBlank String itemUid) {
}
