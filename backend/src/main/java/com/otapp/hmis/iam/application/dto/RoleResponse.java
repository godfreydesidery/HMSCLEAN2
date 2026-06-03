package com.otapp.hmis.iam.application.dto;

import java.util.List;

/**
 * Role representation (build-spec §2 endpoints #6/#7/#8/#9/#11).
 * No {@code id} field.
 */
public record RoleResponse(
        String uid,
        String name,
        String owner,
        List<String> privilegeCodes
) {
}
