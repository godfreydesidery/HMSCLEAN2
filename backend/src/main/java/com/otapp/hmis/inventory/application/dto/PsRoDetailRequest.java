package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Add RO detail (inc-08b chunk 6). */
public record PsRoDetailRequest(@NotBlank String medicineUid, @NotNull @Positive BigDecimal orderedQty) {
}
