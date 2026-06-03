package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.shared.application.dto.MoneyDto;
import java.time.LocalDate;

/**
 * POS receipt projection for a recorded payment (build-spec §5.3, NET-NEW BILL-1 hardening — legacy
 * has no receipt document). The receipt anchor is {@link com.otapp.hmis.billing.domain.PatientPayment}'s
 * uid; there is no separate receipt sequence. No {@code id} field (ADR-0014 §1).
 *
 * <p>Returned as structured JSON; the printable {@code @media print} rendering is a frontend
 * concern (deferred Angular print view), keeping presentation out of the backend. The patient MR
 * number is sourced by Registration (inc-03); until then the loose {@code patientUid} is carried.
 *
 * @param receiptNo      the payment uid (receipt anchor)
 * @param patientUid     loose ref to the patient
 * @param amount         amount paid
 * @param paymentMode    CASH | INSURANCE
 * @param status         payment header status (always RECEIVED)
 * @param cashier        the user who recorded the payment (created_by)
 * @param businessDayUid the business day the payment was stamped against
 * @param businessDate   the business-day calendar date (resolved from businessDayUid)
 */
public record ReceiptDto(
        String receiptNo,
        String patientUid,
        MoneyDto amount,
        String paymentMode,
        String status,
        String cashier,
        String businessDayUid,
        LocalDate businessDate
) {
}
