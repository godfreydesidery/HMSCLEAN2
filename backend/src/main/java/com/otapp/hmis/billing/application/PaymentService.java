package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.Collection;
import com.otapp.hmis.billing.domain.CollectionRepository;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientInvoiceDetailRepository;
import com.otapp.hmis.billing.domain.PatientPayment;
import com.otapp.hmis.billing.domain.PatientPaymentDetail;
import com.otapp.hmis.billing.domain.PatientPaymentDetailRepository;
import com.otapp.hmis.billing.domain.PatientPaymentRepository;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-bill payment recording (PARITY — build-spec §3.2).
 *
 * <p>Implements the legacy per-bill, full-payment-only cashier collection flow
 * (PatientBillResource.java:269-393). Key contracts:
 * <ul>
 *   <li>One {@link PatientPayment} header per call (receipt anchor).</li>
 *   <li>Each bill must be in state UNPAID or VERIFIED — else {@link BillNotPayableException}.</li>
 *   <li>Each bill is marked PAID (paid=amount, balance=0).</li>
 *   <li>One {@link PatientPaymentDetail} per bill (no amount column — PARITY).</li>
 *   <li>One {@link Collection} row per bill (cashier reconciliation ledger).</li>
 *   <li>If bill has an invoice detail: detail.status=PAID + invoice.amountPaid incremented.</li>
 *   <li>Exact-tender guard (CR-12): tendered total must {@code compareTo==0} the sum of bill amounts.</li>
 * </ul>
 *
 * <p>Side-effects to downstream clinical modules (admissions→IN-PROCESS, consult→SIGNED-OUT,
 * pharmacy→APPROVED) are DEFERRED to inc-05/06. A documented seam is left via
 * {@link #applyPaymentSideEffects(PatientBill)} — do not call downstream from P1.
 *
 * <p>CashierShift gate is [GATED:CR-04 — DEFERRED]. Payment is NOT gated on an open shift.
 * On the PAID transition the {@link SettlementDispatcher} marks the bill settled in this same tx
 * (CR-05; billing → encounter only).
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PatientBillRepository billRepository;
    private final PatientPaymentRepository paymentRepository;
    private final PatientPaymentDetailRepository paymentDetailRepository;
    private final PatientInvoiceDetailRepository invoiceDetailRepository;
    private final CollectionRepository collectionRepository;
    private final SettlementDispatcher settlementDispatcher;
    private final AuditRecorder auditRecorder;

    /**
     * Record a full payment for one or more selected bills.
     *
     * @param billUids      ordered list of bill uids to pay
     * @param tenderedTotal the total amount tendered by the patient
     * @param mode          payment mode (always CASH for this path in PARITY)
     * @param ctx           transaction audit context
     * @return the persisted PatientPayment (receipt anchor)
     */
    @Transactional
    public PatientPayment recordPayment(List<String> billUids, Money tenderedTotal,
                                        PaymentMode mode, TxAuditContext ctx) {

        // [GATED:CR-04] CashierShift open-shift check — NOT implemented in P1

        // Create the payment header (PatientBillResource.java:277-285)
        PatientPayment payment = new PatientPayment(
                null,           // patientUid nullable — not yet known at this call level in legacy
                tenderedTotal,
                mode,
                ctx.dayUid());
        paymentRepository.save(payment);

        BigDecimal running = BigDecimal.ZERO;

        for (String billUid : billUids) {
            // Load or 404 (PatientBillResource.java:291-293)
            PatientBill bill = billRepository.findByUid(billUid)
                    .orElseThrow(() -> new NotFoundException("PatientBill not found: " + billUid));

            // Payable-status gate: only UNPAID or VERIFIED (PatientBillResource.java:295-296)
            if (bill.getStatus() != BillStatus.UNPAID && bill.getStatus() != BillStatus.VERIFIED) {
                throw new BillNotPayableException(billUid);
            }

            // Mark bill PAID — paid=amount, balance=0 (PatientBillResource.java:305-307)
            bill.markPaid();
            // CR-05: propagate settlement in THIS tx (billing → encounter; no async, no reverse edge)
            settlementDispatcher.onBillPaid(bill, ctx);
            billRepository.save(bill);

            // Create payment detail — no amount column (PARITY, PatientBillResource.java:310-320)
            PatientPaymentDetail detail = new PatientPaymentDetail(payment, bill);
            paymentDetailRepository.save(detail);

            // Write collection row (PatientBillResource.java:327-337)
            Collection collection = new Collection(
                    bill.getPatientUid(),
                    bill,
                    bill.getAmount(),
                    bill.getBillItem(),
                    "Cash",     // hard-coded PARITY
                    "NA",       // hard-coded PARITY
                    ctx.dayUid());
            collectionRepository.save(collection);

            // If bill is on an insurance invoice: detail.status=PAID + invoice.amountPaid++
            // (PatientBillResource.java:341-349)
            updateInvoiceDetailOnPayment(billUid, bill.amountValue());

            running = running.add(bill.amountValue()).setScale(2, RoundingMode.HALF_UP);

            // Documented seam for inc-05/06 clinical side-effects
            // (PatientBillResource.java:352-387 — admissions, consultations, pharmacy SO)
            applyPaymentSideEffects(bill);
        }

        // Exact-tender guard (CR-12): compareTo==0 on NUMERIC(19,2) scaled values
        // (PatientBillResource.java:389-391 — replaces legacy double != )
        BigDecimal tenderedScaled = tenderedTotal.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (tenderedScaled.compareTo(running) != 0) {
            throw new PaymentAmountMismatchException(tenderedScaled, running);
        }

        // Audit the payment header
        auditRecorder.record("billing.PatientPayment", payment.getUid(),
                             AuditAction.CREATE, ctx.actorUsername());

        // CR-05 settlement is dispatched per-bill above (settlementDispatcher.onBillPaid),
        // in this same tx — no batched post-loop dispatch needed.

        return payment;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * If the bill has an associated invoice detail, mark it PAID and increment
     * the invoice running amountPaid (PatientBillResource.java:341-349).
     */
    private void updateInvoiceDetailOnPayment(String billUid, BigDecimal billAmount) {
        invoiceDetailRepository.findByBillUid(billUid).ifPresent(invoiceDetail -> {
            invoiceDetail.markPaid();
            invoiceDetail.getInvoice().addToPaidAmount(billAmount);
        });
    }

    /**
     * Documented seam for inc-05/06 clinical side-effects on payment
     * (PatientBillResource.java:352-387):
     * <ul>
     *   <li>Admissions → IN-PROCESS + bed → OCCUPIED</li>
     *   <li>In-process consultations → SIGNED-OUT</li>
     *   <li>Pharmacy sale orders → APPROVED + details paid/sold</li>
     * </ul>
     *
     * <p>NOT implemented in P1. Wiring is inc-05/06 scope.
     * This method intentionally does nothing in this increment.
     *
     * @param bill the bill that was just paid
     */
    @SuppressWarnings("unused")
    private void applyPaymentSideEffects(PatientBill bill) {
        // INC-05/06: wire SettlementDispatcher or clinical-module events here
        // PatientBillResource.java:352-387
    }
}
