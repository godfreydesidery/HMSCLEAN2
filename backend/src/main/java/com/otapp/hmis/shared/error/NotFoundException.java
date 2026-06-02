package com.otapp.hmis.shared.error;

/** Thrown when an aggregate cannot be resolved by its uid (ADR-0005). Maps to HTTP 404. */
public class NotFoundException extends HmisException {

    public NotFoundException(String detail) {
        super(ErrorCode.NOT_FOUND, detail);
    }
}
