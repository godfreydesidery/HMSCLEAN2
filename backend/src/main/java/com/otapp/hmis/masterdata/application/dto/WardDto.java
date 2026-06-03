package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.Ward} (build-spec §1.1).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 * FK entities are exposed as {@code uid} strings, not as nested objects.
 */
public record WardDto(
        String uid,
        String code,
        String name,
        int noOfBeds,
        boolean active,
        String wardCategoryUid,
        String wardTypeUid) {
}
