package com.otapp.hmis.shared.error;

/**
 * Thrown when a refresh token that has already been rotated (revoked) is presented again —
 * indicating a potential token theft scenario (build-spec §4, CR-10).
 *
 * <p>Maps to HTTP 401 {@code urn:hmis:error:token-reuse-detected} via
 * {@link GlobalExceptionHandler#handleHmis}. The response body must NOT contain token hashes,
 * replaced_by_uid, or user_uid (security constraint).
 */
public class TokenReuseException extends HmisException {

    public TokenReuseException() {
        super(ErrorCode.TOKEN_REUSE_DETECTED, "Refresh token reuse detected — all sessions revoked");
    }
}
