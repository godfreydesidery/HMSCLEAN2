package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.CancellationResultDto;
import com.otapp.hmis.billing.application.dto.CreditNoteDto;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientCreditNote;
import com.otapp.hmis.billing.domain.PatientCreditNoteRepository;
import com.otapp.hmis.billing.domain.PatientInvoice;
import com.otapp.hmis.billing.domain.PatientInvoiceDetail;
import com.otapp.hmis.billing.domain.PatientInvoiceDetailRepository;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.billing.domain.PatientPaymentDetail;
import com.otapp.hmis.billing.domain.PatientPaymentDetailRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Charge cancellation + credit-note (refund) service (build-spec §3.3, CR-13 FIX, CR-10 FIX).
 *
 * <p>Reproduces the legacy {@code cancelConsultation} flow (PatientResource.java:611-673) and
 * <strong>standardizes the consultation soft-flag pattern for ALL bill kinds</strong> — the legacy
 * lab/radiology/procedure variants hard-deleted the bill + payment detail, erasing the PHI/audit
 * evidence of an ordered-charged-refunded service (a regulatory defect; HDE BLOCKER-CLINICAL-4).
 * Here every cancellation:
 * <ol>
 *   <li>soft-cancels the bill ({@code status=CANCELED}; never deleted) — PatientResource.java:627;</li>
 *   <li>if a {@code RECEIVED} payment detail exists, flips it to {@code REFUNDED} (never deleted)
 *       and issues a full-amount {@code PENDING} {@link PatientCreditNote} numbered by
 *       {@link DocumentNumberService} — PatientResource.java:636-654;</li>
 *   <li>removes the bill's invoice-claim detail and deletes the parent invoice
 *       <strong>only when zero details remain</strong> (CR-10 FIX — the legacy {@code j=j++}
 *       no-op always deleted the parent, corrupting multi-line claims).</li>
 * </ol>
 *
 * <p>{@code PatientInvoice.amountPaid} is NOT decremented on refund — legacy never decrements it
 * ([GATED:CR-03b]; reproducing the decrement would be knowingly inventing behaviour). The credit
 * note is the durable, payer-presentable refund record; no negative-amount ledger row is created
 * (signed-detail refund is not legacy — CR-03 rejected).
 *
 * <p>This is an in-process application service. The billing REST edge (cashier) and any future
 * clinical-module caller (inc-05/06) both invoke {@link #cancelCharge} inside their own transaction.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    private final PatientBillRepository billRepository;
    private final PatientPaymentDetailRepository paymentDetailRepository;
    private final PatientInvoiceDetailRepository invoiceDetailRepository;
    private final PatientInvoiceRepository invoiceRepository;
    private final PatientCreditNoteRepository creditNoteRepository;
    private final PatientCreditNoteMapper creditNoteMapper;
    private final DocumentNumberService documentNumberService;
    private final AuditRecorder auditRecorder;

    /**
     * Cancel a charge: soft-cancel the bill, refund any received payment (issuing a credit note),
     * and detach it from its insurance claim. Idempotent on the refund side — a second call finds
     * no {@code RECEIVED} payment detail and so creates no second credit note.
     *
     * @param billUid   the bill to cancel
     * @param reference the cause label stamped onto the credit note
     * @param ctx       the transaction audit context (open business day + actor)
     * @return the created credit note, or empty if the bill had no received payment to refund
     */
    @Transactional
    public Optional<PatientCreditNote> cancelCharge(String billUid, String reference, TxAuditContext ctx) {
        return doCancel(billUid, reference, ctx);
    }

    /**
     * Controller-facing wrapper: cancel a charge and return the API result (keeps DTO mapping in
     * the application layer). The bill is always {@code CANCELED} on success; {@code creditNote}
     * is present only when a refund was due.
     *
     * @param billUid   the bill to cancel
     * @param reference the cause label stamped onto the credit note
     * @param ctx       the transaction audit context
     * @return the cancellation result (bill status + optional credit note)
     */
    @Transactional
    public CancellationResultDto cancel(String billUid, String reference, TxAuditContext ctx) {
        CreditNoteDto noteDto = doCancel(billUid, reference, ctx)
                .map(creditNoteMapper::toDto)
                .orElse(null);
        return new CancellationResultDto(billUid, BillStatus.CANCELED.name(), noteDto);
    }

    /**
     * The cancellation flow itself, run inside the caller's transaction (no own {@code @Transactional}
     * — both public entry points already open one, so this avoids a self-invoked proxy call).
     */
    private Optional<PatientCreditNote> doCancel(String billUid, String reference, TxAuditContext ctx) {
        PatientBill bill = billRepository.findByUid(billUid)
                .orElseThrow(() -> new NotFoundException("PatientBill not found: " + billUid));

        // 1) Soft-cancel the bill (never hard-delete) — PatientResource.java:627
        bill.cancel();
        billRepository.save(bill);
        auditRecorder.record("billing.PatientBill", bill.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        // 2) Refund + credit note ONLY when a RECEIVED payment detail exists
        //    (PatientResource.java:636 — guard pd.getStatus().equals("RECEIVED"))
        Optional<PatientCreditNote> creditNote = paymentDetailRepository.findReceivedByBillUid(billUid)
                .map(detail -> refundAndIssueCreditNote(detail, bill, reference, ctx));

        // 3) Detach from the insurance claim: remove the invoice detail, delete the parent invoice
        //    ONLY when zero details remain (CR-10 FIX) — PatientResource.java:659-673
        invoiceDetailRepository.findByBillUid(billUid)
                .ifPresent(detail -> detachInvoiceDetail(detail, ctx));

        return creditNote;
    }

    /** Cause-label list of a patient's credit notes, newest first. */
    @Transactional(readOnly = true)
    public List<CreditNoteDto> listByPatient(String patientUid) {
        return creditNoteMapper.toDtoList(
                creditNoteRepository.findByPatientUidOrderByCreatedAtDesc(patientUid));
    }

    /** Fetch a single credit note by uid. */
    @Transactional(readOnly = true)
    public CreditNoteDto getByUid(String uid) {
        PatientCreditNote note = creditNoteRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("PatientCreditNote not found: " + uid));
        return creditNoteMapper.toDto(note);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Flip the received payment detail to REFUNDED (soft) and create the full-amount PENDING
     * credit note. PatientResource.java:637-654.
     */
    private PatientCreditNote refundAndIssueCreditNote(PatientPaymentDetail detail, PatientBill bill,
                                                       String reference, TxAuditContext ctx) {
        // Soft-reverse the payment — the REFUNDED flag IS the reversal signal (no negative row)
        detail.markRefunded();
        paymentDetailRepository.save(detail);
        // Audit the money reversal (ADR-0007 — a refund is a financial mutation)
        auditRecorder.record("billing.PatientPaymentDetail", detail.getUid(),
                             AuditAction.UPDATE, ctx.actorUsername());

        // Full bill amount, PENDING, with a collision-free PCN number (CR-09)
        PatientCreditNote note = new PatientCreditNote(
                documentNumberService.next(DocumentType.PCN),
                bill.getPatientUid(),
                bill.getAmount(),
                reference,
                bill.getUid(),
                ctx.dayUid());
        creditNoteRepository.save(note);
        auditRecorder.record("billing.PatientCreditNote", note.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return note;
    }

    /**
     * Remove the claim detail from its parent invoice; delete the parent invoice only when it has
     * no remaining details (CR-10 FIX). Removing from the {@code orphanRemoval=true} collection
     * deletes the detail row on flush; {@link PatientInvoice#isEmpty()} then reflects the true
     * remaining-detail count (the collection is fully initialised by the removal). Audits the
     * invoice mutation — DELETE when the last line goes, UPDATE when the claim is merely trimmed
     * (ADR-0007 — the invoice is a mandatory-chain billing entity).
     */
    private void detachInvoiceDetail(PatientInvoiceDetail detail, TxAuditContext ctx) {
        PatientInvoice invoice = detail.getInvoice();
        invoice.removeDetail(detail);
        invoiceRepository.save(invoice);
        if (invoice.isEmpty()) {
            invoiceRepository.delete(invoice);
            auditRecorder.record("billing.PatientInvoice", invoice.getUid(),
                                 AuditAction.DELETE, ctx.actorUsername());
        } else {
            auditRecorder.record("billing.PatientInvoice", invoice.getUid(),
                                 AuditAction.UPDATE, ctx.actorUsername());
        }
    }
}
