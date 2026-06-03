package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.domain.CoverageStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.shared.application.MoneyMapper;
import com.otapp.hmis.shared.domain.TxAuditContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link BillingCommands} (build-spec §4.1).
 *
 * <p>This class is intentionally package-private in {@code billing.application} — it must
 * never be referenced directly by callers outside the billing module. Callers depend on
 * the {@link BillingCommands} interface from {@code billing.api} only.
 *
 * <p>Propagation REQUIRED: the charge runs inside the caller's (Registration/Clinical)
 * transaction, ensuring atomicity with the clinical encounter. A consultation hard-fail
 * rolls back the entire encounter in the caller's tx.
 *
 * <p>NOT {@code @PreAuthorize}-gated — authz is enforced at the caller's REST edge.
 * NOT {@code @Async}, NOT {@code REQUIRES_NEW}.
 */
@Service
@RequiredArgsConstructor
class BillingCommandsImpl implements BillingCommands {

    private final BillingChargeService chargeService;
    private final MoneyMapper moneyMapper;

    /**
     * {@inheritDoc}
     *
     * <p>Propagation REQUIRED: executes inside the caller's active transaction.
     * If no transaction is active, Spring will throw {@code IllegalTransactionStateException}
     * at runtime — this is intentional (guards against accidental out-of-tx calls).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ChargeResult recordClinicalCharge(ChargeRequest req, TxAuditContext ctx) {
        PatientBill bill = chargeService.recordCharge(
                req.kind(),
                req.serviceUid(),
                req.patientUid(),
                req.planUid(),
                req.membershipNo(),
                req.paymentType(),
                req.qty() != null ? req.qty() : java.math.BigDecimal.ONE,
                req.inpatient(),
                req.followUp(),
                ctx);

        // Derive coverage status from bill status for the result record
        CoverageStatus coverage = switch (bill.getStatus()) {
            case COVERED  -> CoverageStatus.COVERED;
            case VERIFIED -> CoverageStatus.VERIFIED;
            default       -> CoverageStatus.UNPAID;
        };

        return new ChargeResult(
                bill.getUid(),
                bill.getStatus(),
                moneyMapper.toDto(bill.getAmount()),
                coverage);
    }
}
