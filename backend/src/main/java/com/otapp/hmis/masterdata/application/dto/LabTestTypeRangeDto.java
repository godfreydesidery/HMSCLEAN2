package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.LabTestTypeRange}
 * (build-spec §1.3). FK entity exposed as {@code labTestTypeUid} string. No {@code id} field.
 */
public record LabTestTypeRangeDto(
        String uid,
        String labTestTypeUid,
        String name) {
}
