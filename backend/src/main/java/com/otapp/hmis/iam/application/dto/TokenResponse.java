package com.otapp.hmis.iam.application.dto;

import java.util.List;

/**
 * Issued token pair (ADR-0006). The {@code privileges} array mirrors the JWT {@code privileges}
 * claim so a client need not decode the token. No {@code id} is ever exposed.
 *
 * @param accessToken          signed HS256 access token
 * @param refreshToken         opaque rotating refresh token
 * @param tokenType            always {@code Bearer}
 * @param expiresInSeconds     access-token lifetime in seconds
 * @param privileges           the privilege CODE strings granted to the principal
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        List<String> privileges) {
}
