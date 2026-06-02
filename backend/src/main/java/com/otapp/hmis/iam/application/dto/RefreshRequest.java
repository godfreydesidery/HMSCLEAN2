package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Refresh-token payload for {@code POST /api/v1/auth/token/refresh} (ADR-0006). */
public record RefreshRequest(
        @NotBlank String refreshToken) {
}
