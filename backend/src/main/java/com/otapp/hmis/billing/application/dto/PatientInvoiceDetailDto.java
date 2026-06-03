package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.billing.domain.CoverageStatus;
import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.billing.domain.PatientInvoiceDetail}.
 * No {@code id} field (ADR-0014 §1).
 */
public record PatientInvoiceDetailDto(
        String uid,
        String billUid,
        String description,
        BigDecimal qty,
        BigDecimal amount,
        String status,
        CoverageStatus coverageStatus
) {
}
