package com.otapp.hmis.billing.application.dto;

import java.math.BigDecimal;

/**
 * One row of the EOD collections (cash-up) report — a {@code (itemName, paymentChannel)} bucket and
 * its summed amount (build-spec §1.4, §5.1). Amounts are {@code NUMERIC(19,2)} HALF_UP for
 * bit-identical totals.
 *
 * @param itemName       revenue-category label (the bill item, e.g. "Consultation", "Lab Test")
 * @param paymentChannel payment channel (always "Cash" in legacy data; multi-mode is [GATED:CR-08])
 * @param amount         summed amount collected in the report window for this bucket
 */
public record CollectionReportRow(
        String itemName,
        String paymentChannel,
        BigDecimal amount
) {
}
