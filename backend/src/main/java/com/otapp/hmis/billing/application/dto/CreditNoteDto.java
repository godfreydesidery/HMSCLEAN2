package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.shared.application.dto.MoneyDto;

/**
 * Wire representation of {@link com.otapp.hmis.billing.domain.PatientCreditNote} — the refund
 * instrument. No {@code id} field (ADR-0014 §1). {@code status} is always {@code PENDING} (PARITY).
 *
 * @param uid            opaque identifier
 * @param no             PCN document number, e.g. {@code PCN20260603-7}
 * @param patientUid     loose ref to the patient (nullable in legacy)
 * @param amount         full bill amount (positive)
 * @param reference      free-text cause label (e.g. "Canceled consultation")
 * @param status         credit-note status (always {@code PENDING})
 * @param patientBillUid net-new traceability ref to the cancelled bill
 * @param businessDayUid the business day the note was stamped against
 */
public record CreditNoteDto(
        String uid,
        String no,
        String patientUid,
        MoneyDto amount,
        String reference,
        String status,
        String patientBillUid,
        String businessDayUid
) {
}
