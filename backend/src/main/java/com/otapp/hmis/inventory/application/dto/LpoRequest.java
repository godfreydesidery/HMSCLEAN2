package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** Create-LPO request (inc-08b). storeUid + supplierUid required, server-validated. */
public record LpoRequest(
        @NotBlank String storeUid,
        @NotBlank String supplierUid,
        LocalDate orderDate,
        LocalDate validUntil
) {
}
