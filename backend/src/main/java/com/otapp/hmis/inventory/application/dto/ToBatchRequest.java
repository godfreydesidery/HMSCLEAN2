package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Add a batch to a TO detail (inc-08b chunk 6). The pharmacy-SKU qty is derived server-side via the
 * coefficient ({@code storeSkuQty * coefficient}); the caller supplies only the store-SKU qty + lot
 * identity. {@code itemUid}/{@code medicineUid} identify the (store SKU, pharmacy SKU) pair.
 */
public record ToBatchRequest(
        @NotBlank String itemUid,
        @NotBlank String medicineUid,
        @NotBlank String batchNo,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        @NotNull @Positive BigDecimal storeSkuQty
) {
}
