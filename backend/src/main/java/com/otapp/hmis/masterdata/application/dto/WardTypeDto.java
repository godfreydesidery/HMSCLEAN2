package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.WardType} (build-spec §1.1).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record WardTypeDto(
        String uid,
        String code,
        String name,
        String description,
        BigDecimal price,
        boolean active) {
}
