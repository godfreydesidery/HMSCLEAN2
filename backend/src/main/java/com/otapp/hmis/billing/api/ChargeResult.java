package com.otapp.hmis.billing.api;

import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.CoverageStatus;
import com.otapp.hmis.shared.application.dto.MoneyDto;

/**
 * Result returned by {@link BillingCommands#recordClinicalCharge} (build-spec §4.1).
 *
 * <p>Carries only the public uid and observable state — NO internal {@code id}
 * (ADR-0014 §1). Money is expressed as {@link MoneyDto} for wire compatibility.
 *
 * @param billUid        the uid of the created {@code PatientBill}
 * @param status         the final bill status after the pricing engine ran
 * @param amount         the effective charge amount (cash or plan price)
 * @param coverageStatus coverage snapshot (UNPAID = cash; COVERED = insurance hit;
 *                       VERIFIED = inpatient cash fallback)
 */
public record ChargeResult(
        String billUid,
        BillStatus status,
        MoneyDto amount,
        CoverageStatus coverageStatus
) {
}
