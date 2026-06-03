package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.Item} (build-spec §1.2).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record ItemDto(
        String uid,
        String code,
        String barcode,
        String name,
        String shortName,
        String commonName,
        BigDecimal vat,
        String uom,
        BigDecimal packSize,
        String category,
        BigDecimal costPriceVatIncl,
        BigDecimal sellingPriceVatIncl,
        boolean active,
        String ingredients) {
}
