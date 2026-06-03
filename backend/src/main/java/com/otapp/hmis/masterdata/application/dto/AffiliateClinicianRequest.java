package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/masterdata/clinics/uid/{clinicUid}/clinicians}
 * (CR-08, build-spec §5.2).
 *
 * <p>Contains only the target user's public ULID. The clinic uid comes from the path variable.
 */
public record AffiliateClinicianRequest(
        @NotBlank(message = "userUid must not be blank")
        String userUid
) {
}
