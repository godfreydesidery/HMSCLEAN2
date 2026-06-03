package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.ProcedureType}
 * (build-spec §1.3). No {@code id} field.
 */
public record ProcedureTypeDto(
        String uid,
        String code,
        String name,
        String description,
        BigDecimal price,
        String uom,
        boolean active) {
}
