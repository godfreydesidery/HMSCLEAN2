package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.WardBed} (build-spec §1.1).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 * FK {@code ward} is exposed as {@code wardUid} string.
 */
public record WardBedDto(
        String uid,
        String no,
        String status,
        boolean active,
        String wardUid) {
}
