package com.otapp.hmis.billing.application.dto;

/**
 * Result of cancelling a charge. The bill is always soft-cancelled; a {@link CreditNoteDto} is
 * present only when the bill had a {@code RECEIVED} payment to refund (PARITY — the legacy PCN is
 * created only inside the {@code pd.getStatus().equals("RECEIVED")} branch,
 * PatientResource.java:636-654). For an unpaid/covered bill, {@code creditNote} is {@code null}.
 *
 * @param billUid    the cancelled bill's uid
 * @param billStatus the bill's status after cancellation (always {@code CANCELED})
 * @param creditNote the refund credit note, or {@code null} if no refund was due
 */
public record CancellationResultDto(
        String billUid,
        String billStatus,
        CreditNoteDto creditNote
) {
}
