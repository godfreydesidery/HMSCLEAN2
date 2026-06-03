package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.ItemSupplier}.
 * FKs are supplied as uid strings; the service resolves them.
 *
 * <p>{@code active} is boxed {@code Boolean} so that an omitted JSON field deserialises as
 * {@code null}; the service defaults {@code null} → {@code true}
 * (RF-4 — legacy ItemSupplier.java:49 {@code active=true}).
 */
public record ItemSupplierRequest(
        @NotBlank String itemUid,
        @NotBlank String supplierUid,
        @NotNull BigDecimal costPriceVatIncl,
        @NotNull BigDecimal costPriceVatExcl,
        Boolean active) {
}
