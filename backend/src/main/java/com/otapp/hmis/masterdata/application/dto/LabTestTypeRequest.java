package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.LabTestType}.
 *
 * <p>Note: on UPDATE, the {@code code} field in this request is IGNORED by the service —
 * {@code code} is immutable after creation (AC-9.4 / LabTestTypeServiceImpl.java:47-48).
 */
public record LabTestTypeRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotNull BigDecimal price,
        String uom,
        boolean active) {
}
