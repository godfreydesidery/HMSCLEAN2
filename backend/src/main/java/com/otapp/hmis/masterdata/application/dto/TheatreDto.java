package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.Theatre} (build-spec §1.1).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record TheatreDto(
        String uid,
        String code,
        String name,
        String description,
        String location,
        boolean active) {
}
