package com.otapp.hmis.shared.domain;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when {@code POST /api/v1/shared/business-days/open} is attempted but a
 * business day is already OPEN (build-spec §5, P5 BusinessDay admin endpoints).
 * Maps to HTTP 409 Conflict via {@link ErrorCode#CONFLICT}.
 */
public class BusinessDayAlreadyOpenException extends HmisException {

    public BusinessDayAlreadyOpenException() {
        super(ErrorCode.CONFLICT,
                "A business day is already open. Close the current day before opening a new one.");
    }
}
