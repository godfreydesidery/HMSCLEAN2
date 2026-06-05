package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.AdministrationRoute}
 * (inc-07 07d, CR-07-MAR). Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record AdministrationRouteDto(
        String uid,
        String code,
        String name,
        String description,
        boolean active) {
}
