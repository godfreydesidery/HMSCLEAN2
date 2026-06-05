package com.otapp.hmis.pharmacy.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Add-OTC-detail request (inc-08a chunk 4). The line is billed flat-CASH at Medicine.price×qty (Q9).
 */
public record SaleOrderDetailRequest(
        @NotBlank String medicineUid,
        @NotNull @Positive BigDecimal qty,
        String dosage,
        String frequency,
        String route,
        String days
) {
}
