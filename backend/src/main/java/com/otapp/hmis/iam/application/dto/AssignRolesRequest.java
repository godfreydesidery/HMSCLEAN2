package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request to assign roles to a user (build-spec §2 endpoint #12).
 * The service performs an idempotent add — existing assignments are preserved.
 */
public record AssignRolesRequest(

        @NotNull(message = "roleNames must not be null")
        List<String> roleNames
) {
}
