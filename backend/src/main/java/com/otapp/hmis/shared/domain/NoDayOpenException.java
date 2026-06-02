package com.otapp.hmis.shared.domain;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown by {@link BusinessDayService#currentUid()} when no OPEN business day exists
 * (increment-00 spec, ADR-0009 §7). Maps to HTTP 422 with {@code type =
 * urn:hmis:error:no-day-open} via the {@code GlobalExceptionHandler}.
 */
public class NoDayOpenException extends HmisException {

    public NoDayOpenException() {
        super(ErrorCode.NO_DAY_OPEN, "No business day is currently open; open a day before proceeding.");
    }
}
