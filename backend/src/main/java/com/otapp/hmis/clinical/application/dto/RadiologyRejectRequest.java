package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for rejecting a radiology order (C8).
 *
 * <p>A reject comment is stored when rejecting so the clinician understands why
 * the examination was not accepted (legacy parity — rejectComment stored on the Radiology row).
 *
 * <p>NOTE on asymmetry: the rejectComment is NOT cleared when the order is subsequently
 * accepted. See {@link com.otapp.hmis.clinical.domain.Radiology#accept} for details.
 */
public record RadiologyRejectRequest(
        /** Reason for rejection (stored as reject_comment on the radiology row). */
        String rejectComment
) {
}
