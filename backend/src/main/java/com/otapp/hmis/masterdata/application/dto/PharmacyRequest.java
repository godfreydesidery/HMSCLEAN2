package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.Pharmacy}.
 */
public record PharmacyRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        String location,
        String category,
        boolean active) {
}
