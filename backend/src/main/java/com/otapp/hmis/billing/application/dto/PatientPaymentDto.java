package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.application.dto.MoneyDto;

/**
 * Wire representation of {@link com.otapp.hmis.billing.domain.PatientPayment}.
 * The uid is the receipt anchor (build-spec §1.2). No {@code id} field (ADR-0014 §1).
 */
public record PatientPaymentDto(
        String uid,
        String patientUid,
        MoneyDto amount,
        PaymentMode paymentType,
        String status,
        String businessDayUid
) {
}
