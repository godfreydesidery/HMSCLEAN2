package com.otapp.hmis.iam.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full user representation returned by create/update/get-one (build-spec §2 endpoint #1/#4/#3).
 * No {@code id} field (ArchUnit gate enforces). Password hash is never included.
 */
public record UserResponse(
        String uid,
        String userNo,
        String firstName,
        String middleName,
        String lastName,
        String nickname,
        String username,
        boolean enabled,
        List<String> roleNames,
        Instant createdAt
) {
}
