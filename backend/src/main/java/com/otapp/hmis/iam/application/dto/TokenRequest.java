package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Login credentials for {@code POST /api/v1/auth/token} (ADR-0006, ADR-0014 §4). */
public record TokenRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
