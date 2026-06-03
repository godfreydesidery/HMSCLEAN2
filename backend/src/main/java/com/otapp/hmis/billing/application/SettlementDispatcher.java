package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.shared.domain.TxAuditContext;
import org.springframework.stereotype.Service;

/**
 * Propagates settlement on the cash PAID transition (CR-05, RATIFIED scoped; build-spec §4.2).
 *
 * <p>Called by {@link PaymentService} in the SAME transaction as the bill's PAID transition —
 * <strong>billing → encounter direction only</strong> (ADR-0008 §6): no {@code @Async}, no
 * {@code REQUIRES_NEW}, no reverse edge. Today it sets billing's own {@code settled} flag on the
 * bill (a managed entity — the change is dirty-checked into the caller's tx).
 *
 * <p><strong>inc-05/06 seam:</strong> when the clinical modules exist, this dispatcher will ALSO
 * write {@code settled=true} onto the downstream encounter/order's LOCAL projection in this same tx.
 * Downstream clinical modules read ONLY their local flag; they NEVER call {@code billing.api} to
 * check it (the pay-before-service decision is {@link com.otapp.hmis.billing.api.SettlementPolicy},
 * evaluated against that local flag at the clinical {@code accept()}). The propagation must remain
 * billing → encounter, enforced by {@code ApplicationModules.verify()} + ArchUnit.
 */
@Service
public class SettlementDispatcher {

    /**
     * Mark a just-paid bill settled within the caller's transaction.
     *
     * @param bill the bill that transitioned to PAID
     * @param ctx  the operation's audit context (supplies the settlement instant)
     */
    public void onBillPaid(PatientBill bill, TxAuditContext ctx) {
        bill.markSettled(ctx.timestamp());
        // inc-05/06: also set the downstream encounter/order LOCAL settled flag here
        // (billing -> encounter only; same tx; no async; no reverse edge).
    }
}
