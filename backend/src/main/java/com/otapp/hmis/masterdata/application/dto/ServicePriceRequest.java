package com.otapp.hmis.masterdata.application.dto;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import jakarta.validation.constraints.DecimalMin;
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
 */
public record ServicePriceRequest(
        String planUid,
        @NotNull ServiceKind kind,
        String serviceUid,
        String currency,
        @NotNull @DecimalMin(value = "0.00") BigDecimal amount,
        boolean covered,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        boolean active) {
}
