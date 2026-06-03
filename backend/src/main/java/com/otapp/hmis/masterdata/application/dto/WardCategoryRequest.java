package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.WardCategory}.
 */
public record WardCategoryRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        boolean active) {
}
