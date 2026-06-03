package com.otapp.hmis.billing.application.dto;

import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.application.dto.MoneyDto;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * HTTP request body for recording a payment (POST /billing/invoices/uid/{uid}/payments).
 *
 * <p>Build-spec §3.2: full-bill-only, exact-tender. No {@code id} fields.
 *
 * @param billUids      the uids of the bills to pay (must match those on the invoice)
 * @param tenderedTotal the total amount tendered (must equal sum of bill amounts — CR-12)
 * @param paymentMode   the payment mode (CASH in P1 PARITY)
 */
public record RecordPaymentRequest(
        @NotEmpty List<String> billUids,
        @NotNull MoneyDto tenderedTotal,
        @NotNull PaymentMode paymentMode
) {
}
