package com.otapp.hmis.clinical.application.dto;

import java.time.Instant;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.RadiologyAttachment} response (C8, no id leak).
 *
 * <p>No internal {@code id} field (ADR-0014 §1). The radiology order is referenced by its uid only.
 */
public record RadiologyAttachmentDto(
        String uid,
        String name,
        String fileName,
        String radiologyUid,
        Instant createdAt
) {
}
