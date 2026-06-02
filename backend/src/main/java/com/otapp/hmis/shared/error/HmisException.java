package com.otapp.hmis.shared.error;

/**
 * Base for typed domain exceptions carrying an {@link ErrorCode} (ADR-0014 §4, §6).
 * The {@link GlobalExceptionHandler} maps these to RFC 7807 {@code ProblemDetail}.
 */
public abstract class HmisException extends RuntimeException {

    private final transient ErrorCode errorCode;

    protected HmisException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
