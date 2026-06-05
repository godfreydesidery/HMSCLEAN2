package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.pharmacy.domain.PharmacySaleOrder;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderDetail;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderDetailRepository;
import com.otapp.hmis.shared.event.BillSettledEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pharmacy-side OTC settlement listener (inc-08a chunk 4) — the PENDING→APPROVED seam.
 *
 * <p>When a CASH bill transitions to PAID, the billing module publishes a {@link BillSettledEvent}
 * (from {@code shared.event}). This listener handles it in the SAME transaction
 * ({@code BEFORE_COMMIT}); if the bill uid matches a {@link PharmacySaleOrderDetail}, it flips that
 * detail's {@code payStatus}→PAID and the parent order PENDING→APPROVED — reproducing the legacy
 * inline behaviour in {@code confirm_bills_payment} (PatientBillResource.java:369-387) without any
 * {@code billing → pharmacy} compile edge (the event is in the OPEN {@code shared} module — mirrors
 * the clinical {@code ConsultationSettlementListener}; {@code ApplicationModules.verify()} stays green).
 *
 * <p>If the bill uid matches no OTC detail (e.g. a clinical or registration bill), the listener
 * silently completes — not every bill is an OTC sale line.
 *
 * <p>Audit/timestamp: the event carries no actor, so the approve/sold audit is stamped with the
 * "settlement" system actor and the event's {@code settledAt} (mirrors how clinical's
 * {@code markSettled()} runs without a per-call actor on the event path).
 */
@Component
@RequiredArgsConstructor
public class OtcSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(OtcSettlementListener.class);
    private static final String SETTLEMENT_ACTOR = "settlement";

    private final PharmacySaleOrderDetailRepository detailRepository;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBillSettled(BillSettledEvent event) {
        String billUid = event.billUid();
        detailRepository.findByPatientBillUid(billUid).ifPresent(detail -> {
            // detail managed in this tx (dirty-checked on commit) — no explicit save needed.
            detail.markPaid(SETTLEMENT_ACTOR, null, event.settledAt());
            PharmacySaleOrder order = detail.getPharmacySaleOrder();
            order.approve(SETTLEMENT_ACTOR, null, event.settledAt());   // idempotent if already APPROVED
            log.debug("OtcSettlementListener: order {} APPROVED, detail {} PAID (billUid={})",
                    order.getUid(), detail.getUid(), billUid);
        });
    }
}
