package com.otapp.hmis.shared.application.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire representation of {@link com.otapp.hmis.shared.domain.BusinessDay}
 * (build-spec §5, P5 admin endpoints). No {@code id} field (ADR-0014 §1).
 */
public record BusinessDayDto(
        String uid,
        LocalDate businessDate,
        Instant openedAt,
        Instant closedAt,
        String status
) {
}
