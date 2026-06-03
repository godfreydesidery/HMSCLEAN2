package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.LabTestTypeRange}.
 * The {@code labTestTypeUid} is a path variable, not a body field (nested under the parent endpoint).
 */
public record LabTestTypeRangeRequest(
        @NotBlank String name) {
}
