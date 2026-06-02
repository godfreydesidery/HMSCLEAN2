package com.otapp.hmis.shared.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.shared.domain.Money} (ADR-0005, ADR-0009).
 * Carries no {@code id} field — DTOs never expose the internal surrogate key (ADR-0014 §1).
 */
public record MoneyDto(BigDecimal amount, String currency) {
}
