package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.CompanyProfile} (ADR-0005).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record CompanyProfileDto(
        String uid,
        String name,
        String address,
        String phone) {
}
