package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.WardCategory} (build-spec §1.1).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record WardCategoryDto(
        String uid,
        String code,
        String name,
        String description,
        boolean active) {
}
