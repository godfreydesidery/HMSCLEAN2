package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.MdDocumentType}
 * (build-spec §1.5, CR-09, CR-10). No {@code id} field (ADR-0014 §1).
 */
public record MdDocumentTypeDto(
        String uid,
        String kind,
        String prefix
) {
}
