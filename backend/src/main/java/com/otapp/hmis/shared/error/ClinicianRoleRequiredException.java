package com.otapp.hmis.shared.error;

/**
 * Thrown when a user targeted for clinic–clinician affiliation does not hold the
 * {@code CLINICIAN} role (CR-08, build-spec §5.2, AC-4).
 *
 * <p>Maps to HTTP 403 with {@code type = "urn:hmis:error:clinician-role-required"} via
 * {@link GlobalExceptionHandler#handleHmis}. This is a BUSINESS gate (the user exists
 * and is authenticated, but is not a clinician) — distinct from Spring Security's
 * {@code AccessDeniedException} (which covers missing privilege codes).
 */
public class ClinicianRoleRequiredException extends HmisException {

    public ClinicianRoleRequiredException(String userUid) {
        super(ErrorCode.CLINICIAN_ROLE_REQUIRED,
                "User '" + userUid + "' does not hold the CLINICIAN role");
    }
}
