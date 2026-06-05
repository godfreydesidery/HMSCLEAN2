package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Add a batch to a pharmacy→pharmacy TO detail (inc-08b chunk 7; 1:1 qty, no coefficient). */
public record PpToBatchRequest(
        @NotBlank String medicineUid,
        @NotBlank String batchNo,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        @NotNull @Positive BigDecimal qty
) {
}
