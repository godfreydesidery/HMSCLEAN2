package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.InsurancePlan}
 * (build-spec §1.4). Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 * The parent provider is referenced by {@code insuranceProviderUid} (a uid string, not a
 * nested object) so the response does not leak internal entity graphs.
 */
public record InsurancePlanDto(
        String uid,
        String code,
        String name,
        String description,
        boolean active,
        String insuranceProviderUid) {
}
