package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Add-GRN-detail-batch request (inc-08b; legacy add_batch, qty must be > 0). */
public record GrnBatchRequest(
        @NotBlank String batchNo,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        @NotNull @Positive BigDecimal qty
) {
}
