package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.Item}.
 */
public record ItemRequest(
        @NotBlank String code,
        String barcode,
        @NotBlank String name,
        String shortName,
        String commonName,
        @NotNull BigDecimal vat,
        String uom,
        @NotNull BigDecimal packSize,
        String category,
        @NotNull BigDecimal costPriceVatIncl,
        @NotNull BigDecimal sellingPriceVatIncl,
        boolean active,
        String ingredients) {
}
