package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.event.BillSettledEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Propagates settlement on the cash PAID transition (CR-05, inc-05; build-spec §4.2).
 *
 * <p>Called by {@link PaymentService} in the SAME transaction as the bill's PAID transition —
 * <strong>billing → encounter direction only</strong> (ADR-0008 §6): no {@code @Async}, no
 * {@code REQUIRES_NEW}, no reverse edge.
 *
 * <p>This dispatcher:
 * <ol>
 *   <li>Sets billing's own {@code settled} flag on the bill (managed entity — dirty-checked into
 *       the caller's transaction).</li>
 *   <li>Publishes a {@link BillSettledEvent} via Spring's {@link ApplicationEventPublisher} in the
 *       SAME transaction. The event type is in {@code shared.event} (OPEN module) — no
 *       billing→clinical compile edge is introduced.</li>
 * </ol>
 *
 * <p><strong>Settlement seam design (ADR-0022 D5, inc-05 §5):</strong>
 * The {@link BillSettledEvent} is consumed by the clinical module's
 * {@code ConsultationSettlementListener} with
 * {@code @TransactionalEventListener(phase = BEFORE_COMMIT)}, which executes WITHIN the billing
 * transaction before commit. The listener flips the local {@code settled} flag on whichever
 * clinical row (Consultation, LabTest, Radiology, Procedure, or Prescription) references this
 * bill uid. Because both the billing PAID transition and the clinical flag flip execute in the
 * same transaction, they commit atomically — or both roll back.
 *
 * <p><strong>No cycle:</strong>
 * Billing publishes {@code BillSettledEvent} (from {@code shared.event}, an OPEN module).
 * Clinical consumes it. Billing never imports any clinical type. Clinical already depends on
 * {@code billing::api}; it does NOT depend on {@code billing} internals. The event in
 * {@code shared.event} introduces no new compile edge in either direction.
 * {@code ApplicationModules.verify()} stays green.
 */
@Service
@RequiredArgsConstructor
public class SettlementDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SettlementDispatcher.class);

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Mark a just-paid bill settled within the caller's transaction, and publish a
     * {@link BillSettledEvent} so the clinical module can flip its own local settled flag
     * in the same transaction.
     *
     * @param bill the bill that transitioned to PAID
     * @param ctx  the operation's audit context (supplies the settlement instant)
     */
    public void onBillPaid(PatientBill bill, TxAuditContext ctx) {
        // Step 1: Mark billing's own settled flag (managed entity — dirty-checked into caller's tx)
        bill.markSettled(ctx.timestamp());

        // Step 2: Publish the BillSettledEvent so the clinical module's
        // ConsultationSettlementListener can flip the clinical-local settled flag in this same tx.
        // The event type is in shared.event (OPEN module) — no billing→clinical compile edge.
        // The listener runs with @TransactionalEventListener(phase = BEFORE_COMMIT), i.e.
        // still inside this transaction (ADR-0022 D5, inc-05 §5).
        BillSettledEvent event = new BillSettledEvent(bill.getUid(), ctx.timestamp());
        log.debug("SettlementDispatcher: publishing BillSettledEvent for billUid={}", bill.getUid());
        eventPublisher.publishEvent(event);
    }
}
