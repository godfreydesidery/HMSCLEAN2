package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.RadiologyType}.
 */
public record RadiologyTypeRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotNull BigDecimal price,
        String uom,
        boolean active) {
}
