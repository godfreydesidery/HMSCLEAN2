package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.InsuranceProvider}
 * (build-spec §1.4). Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record InsuranceProviderDto(
        String uid,
        String code,
        String name,
        String address,
        String telephone,
        String email,
        String fax,
        String website,
        boolean active) {
}
