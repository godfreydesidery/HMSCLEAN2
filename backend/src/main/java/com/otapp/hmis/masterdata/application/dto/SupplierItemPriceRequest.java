package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.SupplierItemPrice}.
 *
 * <p>{@code active} is boxed {@code Boolean} so that an omitted JSON field deserialises as
 * {@code null}; the service defaults {@code null} → {@code true}
 * (RF-4 — legacy SupplierItemPrice.java:42 {@code active=true}).
 */
public record SupplierItemPriceRequest(
        @NotNull BigDecimal price,
        String terms,
        Boolean active,
        @NotBlank String supplierUid,
        @NotBlank String itemUid) {
}
