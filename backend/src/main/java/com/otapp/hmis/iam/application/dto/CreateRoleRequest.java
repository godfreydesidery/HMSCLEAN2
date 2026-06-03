package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to create a new organization role (build-spec §2 endpoint #6).
 * Reserved names (15-list) are rejected at the service layer.
 */
public record CreateRoleRequest(

        @NotBlank(message = "Role name is required")
        @Size(max = 80, message = "Role name must not exceed 80 characters")
        String name
) {
}
