package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.WardBed}.
 * {@code wardUid} is required on create; on update it is ignored (ward FK is updatable=false
 * per legacy WardBed.java:49 — the service enforces this).
 */
public record WardBedRequest(
        @NotBlank String no,
        String status,
        boolean active,
        @NotBlank String wardUid) {
}
