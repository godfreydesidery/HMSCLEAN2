package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.Medicine} (build-spec §1.2).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record MedicineDto(
        String uid,
        String code,
        String name,
        String description,
        String type,
        BigDecimal price,
        String uom,
        String category,
        boolean active) {
}
