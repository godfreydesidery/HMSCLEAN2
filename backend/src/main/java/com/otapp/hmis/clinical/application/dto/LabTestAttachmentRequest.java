package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for adding an attachment to a lab test order (C7).
 *
 * <p>Only the fileName (storage reference) is mandatory. Name is optional.
 * The actual file bytes are out of scope — the legacy stores filename references only for lab.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>LabTestAttachment.java:35-57 (name, fileName fields)</li>
 *   <li>PatientServiceImpl.java:2828-2834 (attachment rules)</li>
 * </ul>
 */
public record LabTestAttachmentRequest(
        /** Optional display name for the attachment (e.g., "CBC Report"). */
        String name,

        /**
         * File name / storage reference (globally unique per DB constraint).
         * Mandatory: the attachment must have a fileName to be stored.
         */
        @NotBlank String fileName
) {
}
