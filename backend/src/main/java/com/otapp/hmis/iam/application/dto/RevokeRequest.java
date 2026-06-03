package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/token/revoke} (build-spec §2 endpoint #15, CR-10).
 */
public record RevokeRequest(

        @NotBlank(message = "refreshToken must not be blank")
        String refreshToken
) {
}
