package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Add-LPO-detail request (inc-08b). Price is NOT accepted — copied from SupplierItemPrice (Q legacy). */
public record LpoDetailRequest(
        @NotBlank String itemUid,
        @NotNull @Positive BigDecimal qty
) {
}
