package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.ItemSupplier}.
 * FKs are supplied as uid strings; the service resolves them.
 */
public record ItemSupplierRequest(
        @NotBlank String itemUid,
        @NotBlank String supplierUid,
        @NotNull BigDecimal costPriceVatIncl,
        @NotNull BigDecimal costPriceVatExcl,
        boolean active) {
}
