package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.application.dto.MoneyDto;
import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.billing.domain.PatientBill}.
 * No {@code id} field (ADR-0014 §1). Money via {@link MoneyDto}.
 */
public record PatientBillDto(
        String uid,
        String patientUid,
        String billItem,
        String description,
        ServiceKind kind,
        BigDecimal qty,
        MoneyDto amount,
        MoneyDto paid,
        MoneyDto balance,
        BillStatus status,
        PaymentMode paymentType,
        String membershipNo,
        String planUid,
        String businessDayUid
) {
}
