package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for rejecting a lab test order (C7).
 *
 * <p>A reject comment is required when rejecting so the clinician understands why
 * the test was not accepted (legacy parity — rejectComment stored on the LabTest row).
 */
public record LabTestRejectRequest(
        /** Reason for rejection (stored as reject_comment on the lab test row). */
        String rejectComment
) {
}
