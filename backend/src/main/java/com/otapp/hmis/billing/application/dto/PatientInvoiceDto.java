package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.billing.domain.InvoiceStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Wire representation of {@link com.otapp.hmis.billing.domain.PatientInvoice}.
 * No {@code id} field (ADR-0014 §1). Includes nested detail lines.
 */
public record PatientInvoiceDto(
        String uid,
        String patientUid,
        String planUid,
        InvoiceStatus status,
        BigDecimal amountPaid,
        String businessDayUid,
        List<PatientInvoiceDetailDto> details
) {
}
