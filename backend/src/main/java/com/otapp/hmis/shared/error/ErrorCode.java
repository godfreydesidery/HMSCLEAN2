package com.otapp.hmis.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error catalogue (ADR-0005 §5, ADR-0014 §6).
 *
 * <p>Each constant maps onto the RFC 7807 {@code type} URI. The Angular client branches on the
 * {@code type} (a {@code urn:hmis:error:*} URN), never on free-text messages.
 */
public enum ErrorCode {

    NO_DAY_OPEN("urn:hmis:error:no-day-open", HttpStatus.UNPROCESSABLE_ENTITY, "No business day is open"),
    NOT_FOUND("urn:hmis:error:not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT("urn:hmis:error:conflict", HttpStatus.CONFLICT, "Conflict"),
    BUSINESS_RULE("urn:hmis:error:business-rule", HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation"),
    VALIDATION("urn:hmis:error:validation", HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_CREDENTIALS("urn:hmis:error:invalid-credentials", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    INVALID_TOKEN("urn:hmis:error:invalid-token", HttpStatus.UNAUTHORIZED, "Invalid or expired token"),
    TOKEN_REUSE_DETECTED("urn:hmis:error:token-reuse-detected", HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
    UNAUTHENTICATED("urn:hmis:error:unauthenticated", HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN("urn:hmis:error:forbidden", HttpStatus.FORBIDDEN, "Access denied"),
    INTERNAL("urn:hmis:error:internal", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final String type;
    private final HttpStatus status;
    private final String title;

    ErrorCode(String type, HttpStatus status, String title) {
        this.type = type;
        this.status = status;
        this.title = title;
    }

    public String type() {
        return type;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
