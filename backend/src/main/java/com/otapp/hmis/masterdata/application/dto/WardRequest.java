package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.Ward}.
 * FKs are supplied as uid strings; the service resolves them to entities.
 */
public record WardRequest(
        @NotBlank String code,
        @NotBlank String name,
        int noOfBeds,
        boolean active,
        @NotBlank String wardCategoryUid,
        @NotBlank String wardTypeUid) {
}
