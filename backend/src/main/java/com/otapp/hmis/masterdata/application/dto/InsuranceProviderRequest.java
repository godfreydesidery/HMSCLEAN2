package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of
 * {@link com.otapp.hmis.masterdata.domain.InsuranceProvider} (build-spec §1.4).
 */
public record InsuranceProviderRequest(
        @NotBlank String code,
        @NotBlank String name,
        String address,
        String telephone,
        String email,
        String fax,
        String website,
        boolean active) {
}
