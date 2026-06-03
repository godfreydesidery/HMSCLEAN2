package com.otapp.hmis.iam.application.dto;

/**
 * Privilege catalogue entry (build-spec §2 endpoint #13).
 * {@code category} is {@code ACTIVE} or {@code DEAD}.
 */
public record PrivilegeResponse(
        String uid,
        String code,
        String category
) {
}
