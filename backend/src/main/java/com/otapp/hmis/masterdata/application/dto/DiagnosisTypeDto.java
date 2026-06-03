package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.DiagnosisType}
 * (build-spec §1.3). No price/uom (legacy DiagnosisType.java:37-55 has none — CR-06).
 * No {@code id} field (ADR-0014 §1).
 */
public record DiagnosisTypeDto(
        String uid,
        String code,
        String name,
        String description,
        boolean active) {
}
