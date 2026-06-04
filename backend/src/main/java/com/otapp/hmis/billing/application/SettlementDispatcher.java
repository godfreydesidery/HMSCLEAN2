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
        // DEFERRED SEAM (inc-05 C2): when a CASH consultation bill is paid, the clinical
        // module's Consultation.settled flag must also be flipped to true in this same tx.
        //
        // The chosen design (ADR-0022 D5, clinical-Consultation.java deferred note):
        // billing publishes a Spring ApplicationEvent<ConsultationSettledEvent>(billUid)
        // and the clinical module's ConsultationSettlementListener consumes it in the
        // SAME transaction (TransactionPhase.BEFORE_COMMIT) to call
        // consultationRepository.findByPatientBillUid(billUid).ifPresent(Consultation::markSettled).
        //
        // This keeps the direction billing→clinical (event consumer is in clinical, not here),
        // avoids a billing→clinical method call (which would require billing to import clinical),
        // and avoids a reverse clinical→billing edge. Implementation is deferred to the
        // dedicated settlement-seam chunk (post-C2).
        //
        // Until then: INSURANCE/COVERED/NONE consultations work end-to-end (settled=true at
        // booking). CASH-OPD open is correctly blocked (422 PAY_BEFORE_SERVICE) — legacy parity.
    }
}
