package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for adding a named file attachment to a radiology order (C8).
 *
 * <p>Only the fileName (storage reference) is mandatory. Name is optional.
 * The actual file bytes are out of scope — the legacy stores filename references only
 * for the named attachment child table. The inline blob is stored separately at verify time.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>RadiologyAttachment.java:28-50 (name, fileName fields)</li>
 *   <li>PatientServiceImpl.java:2928-2933 (attachment rules: max 5, ACCEPTED gate)</li>
 * </ul>
 */
public record RadiologyAttachmentRequest(
        /** Optional display name for the attachment (e.g., "Chest X-Ray"). */
        String name,

        /**
         * File name / storage reference (globally unique per DB constraint).
         * Mandatory: the attachment must have a fileName to be stored.
         */
        @NotBlank String fileName
) {
}
