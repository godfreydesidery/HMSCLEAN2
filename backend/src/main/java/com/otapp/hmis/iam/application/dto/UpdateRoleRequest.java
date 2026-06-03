package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to rename a role (build-spec §2 endpoint #9).
 * Reserved names rejected at the service layer; owner stays ORGANIZATION.
 */
public record UpdateRoleRequest(

        @NotBlank(message = "Role name is required")
        @Size(max = 80, message = "Role name must not exceed 80 characters")
        String name
) {
}
