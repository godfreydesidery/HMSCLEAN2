package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Add pharmacy→pharmacy RO detail (inc-08b chunk 7). */
public record PpRoDetailRequest(@NotBlank String medicineUid, @NotNull @Positive BigDecimal orderedQty) {
}
