package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to create a new user (build-spec §2 endpoint #1).
 *
 * <p>Validation bounds (AMB-6 — reproduce code behaviour, not stale legacy comments):
 * <ul>
 *   <li>username 3..50; {@code root} rejected at service layer.
 *   <li>password 4..50 on create (legacy: UserServiceImpl.java:375).
 *   <li>firstName, lastName, nickname @NotBlank (legacy: validateUser lines 386-395).
 * </ul>
 */
public record CreateUserRequest(

        @NotBlank
        @Size(min = 3, max = 50, message = "Username length should be between 3 and 50")
        String username,

        @NotBlank
        @Size(min = 4, max = 50, message = "Password length should be more than 3 and less than 51")
        String password,

        @NotBlank(message = "First name is required")
        String firstName,

        String middleName,

        @NotBlank(message = "Last name is required")
        String lastName,

        @NotBlank(message = "Nickname is required")
        String nickname,

        List<String> roleNames
) {
}
