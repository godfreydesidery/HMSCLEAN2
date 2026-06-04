package com.otapp.hmis.clinical.application.dto;

import java.time.Instant;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.LabTestAttachment} response (C7, no id leak).
 *
 * <p>No internal {@code id} field (ADR-0014 §1). The lab test is referenced by its uid only.
 */
public record LabTestAttachmentDto(
        String uid,
        String name,
        String fileName,
        String labTestUid,
        Instant createdAt
) {
}
