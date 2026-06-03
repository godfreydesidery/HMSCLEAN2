package com.otapp.hmis.masterdata.application.dto;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.ServicePrice}
 * (build-spec §2.1). Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 *
 * <p>All fields map 1-to-1 with the entity. {@code minAmount}/{@code maxAmount}/{@code currency}
 * are included for completeness (they are NET-NEW inert fields — CR-11; they must not drive
 * any behaviour in consuming code).
 */
public record ServicePriceDto(
        String uid,
        String planUid,
        ServiceKind kind,
        String serviceUid,
        String currency,
        BigDecimal amount,
        boolean covered,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        boolean active) {
}
