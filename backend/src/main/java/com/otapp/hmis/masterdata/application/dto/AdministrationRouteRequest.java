package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of
 * {@link com.otapp.hmis.masterdata.domain.AdministrationRoute} (inc-07 07d, CR-07-MAR).
 */
public record AdministrationRouteRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        boolean active) {
}
