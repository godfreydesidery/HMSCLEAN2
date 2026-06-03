package com.otapp.hmis.iam.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to update an existing user (build-spec §2 endpoint #4).
 *
 * <p>Validation bounds (AMB-6):
 * <ul>
 *   <li>password — validated only when non-blank; update requires 6..50 (legacy: UserServiceImpl.java:379).
 *   <li>userNo and username are immutable — the service ignores any attempt to change them.
 *   <li>enabled field drives activate/deactivate; self-toggle is rejected at the controller.
 * </ul>
 */
public record UpdateUserRequest(

        @NotBlank(message = "First name is required")
        String firstName,

        String middleName,

        @NotBlank(message = "Last name is required")
        String lastName,

        @NotBlank(message = "Nickname is required")
        String nickname,

        /**
         * When blank/null: keep existing hash. When non-blank: re-encode (min 6 chars on update).
         */
        @Size(min = 0, max = 50, message = "Password length should be less than 51")
        String password,

        boolean enabled,

        List<String> roleNames
) {
}
