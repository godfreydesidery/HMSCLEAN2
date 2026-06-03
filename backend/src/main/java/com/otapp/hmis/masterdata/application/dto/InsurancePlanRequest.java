package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for create/update of
 * {@link com.otapp.hmis.masterdata.domain.InsurancePlan} (build-spec §1.4).
 *
 * <p>The provider is referenced by uid (not by id — ADR-0014 §1).
 */
public record InsurancePlanRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        boolean active,
        @NotNull String insuranceProviderUid) {
}
