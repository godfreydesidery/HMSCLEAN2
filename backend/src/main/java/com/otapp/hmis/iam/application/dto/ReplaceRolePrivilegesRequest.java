package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request to full-replace the privilege set on a role (build-spec §2 endpoint #11, CR-15).
 *
 * <p>The special value {@code "ALL"} in {@code privilegeCodes} is a shortcut that grants every
 * privilege in the catalogue (legacy {@code UserResource.java:444-445} behaviour).
 */
public record ReplaceRolePrivilegesRequest(

        @NotNull(message = "privilegeCodes must not be null (use empty list to clear all)")
        List<String> privilegeCodes
) {
}
