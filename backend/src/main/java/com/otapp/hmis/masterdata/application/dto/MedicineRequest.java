package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.Medicine}.
 */
public record MedicineRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotBlank String type,
        @NotNull BigDecimal price,
        String uom,
        @NotBlank String category,
        boolean active) {
}
