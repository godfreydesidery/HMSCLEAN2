package com.otapp.hmis.masterdata.application.dto;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for upsert of {@link com.otapp.hmis.masterdata.domain.ServicePrice}
 * (build-spec §2.1, §endpoints).
 *
 * <p>{@code planUid} is nullable — null means a cash row.
 * {@code serviceUid} is nullable — null is valid only for
 * {@link ServiceKind#REGISTRATION} (CR-18).
 * {@code minAmount}/{@code maxAmount}/{@code currency} are inert (CR-11).
 *
 * <p>Amount bounds are validated in the service layer (RF-2): {@code amount < 0} → 400;
 * {@code amount == 0} → {@code covered} forced to {@code false}; {@code amount > 0} →
 * caller-supplied {@code covered} used verbatim
 * (legacy InsurancePlanResource.java:274-279, mirrored across all 7 kinds).
 */
public record ServicePriceRequest(
        String planUid,
        @NotNull ServiceKind kind,
        String serviceUid,
        String currency,
        @NotNull BigDecimal amount,
        boolean covered,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        boolean active) {
}
