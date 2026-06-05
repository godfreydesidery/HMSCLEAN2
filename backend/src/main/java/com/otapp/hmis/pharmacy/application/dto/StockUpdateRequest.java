package com.otapp.hmis.pharmacy.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Manual stock OVERWRITE request (inc-08a, AC-STK-13) — verbatim legacy {@code update_stock}
 * (PharmacyResource.java:199-231): the {@code stock} value is an ABSOLUTE set (not a delta), rejects
 * negative, and writes an ADJUSTMENT stock-card row with NO batch effect.
 *
 * @param pharmacyUid the pharmacy (required, server-validated stock source)
 * @param medicineUid the medicine
 * @param stock       the absolute new stock value (non-negative)
 */
public record StockUpdateRequest(
        @NotBlank String pharmacyUid,
        @NotBlank String medicineUid,
        @NotNull @PositiveOrZero BigDecimal stock
) {
}
