package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound request body for {@code POST /api/v1/masterdata/currencies}
 * (build-spec §1.5). Field name {@code defaultCurrency} matches the entity property.
 */
public record MdCurrencyRequest(
        @NotBlank @Size(min = 3, max = 3)
        String code,
        @NotBlank
        String name,
        boolean defaultCurrency
) {
}
