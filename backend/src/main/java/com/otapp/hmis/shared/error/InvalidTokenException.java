package com.otapp.hmis.shared.error;

/** Thrown when a refresh token is unknown, expired, or already used (ADR-0006). Maps to HTTP 401. */
public class InvalidTokenException extends HmisException {

    public InvalidTokenException(String detail) {
        super(ErrorCode.INVALID_TOKEN, detail);
    }
}
