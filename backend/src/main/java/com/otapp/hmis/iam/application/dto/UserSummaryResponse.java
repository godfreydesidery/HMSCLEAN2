package com.otapp.hmis.iam.application.dto;

import java.util.List;

/**
 * Lightweight user summary for list endpoints (build-spec §2 endpoint #2).
 * No {@code id} field; no password hash.
 */
public record UserSummaryResponse(
        String uid,
        String userNo,
        String username,
        String displayName,
        boolean enabled,
        List<String> roleNames
) {
}
