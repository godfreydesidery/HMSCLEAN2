package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.application.CreditNoteService;
import com.otapp.hmis.billing.application.PaymentService;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.CoverageStatus;
import com.otapp.hmis.billing.domain.CreditNoteStatus;
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
import com.otapp.hmis.billing.domain.PaymentDetailStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLogRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Golden-master credit-note / cancellation tests (build-spec §3.3, §7.2 item 6; CR-13, CR-10, CR-09).
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Soft-flag refund of a paid charge: bill→CANCELED (kept), payment-detail→REFUNDED (kept),
 *       full-amount PENDING credit note with a PCN number.</li>
 *   <li>Cancel of an unpaid charge: bill→CANCELED, NO credit note (PARITY — PCN only when a
 *       RECEIVED payment existed).</li>
 *   <li>CR-10 FIX: multi-line invoice — cancelling one line keeps the parent; cancelling the last
 *       line deletes the parent (legacy {@code j=j++} always-delete bug NOT reproduced).</li>
 *   <li>REST: cancellation endpoint (200, no id), credit-note read endpoints, 401/403 RBAC.</li>
 * </ul>
 */
class CreditNoteIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PaymentService paymentService;
    @Autowired PatientBillRepository billRepository;
    @Autowired PatientInvoiceRepository invoiceRepository;
    @Autowired PatientInvoiceDetailRepository invoiceDetailRepository;
    @Autowired PatientPaymentDetailRepository paymentDetailRepository;
    @Autowired PatientCreditNoteRepository creditNoteRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired BusinessDayService businessDayService;

    private String dayUid;
    private TxAuditContext ctx;

    @BeforeEach
    void setUp() {
        dayUid = ensureDayOpen();
        ctx = new TxAuditContext(dayUid, Instant.now(), "cashier-test");
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    // =========================================================================
    // Soft-flag refund of a PAID charge (PatientResource.java:627-654, CR-13 FIX)
    // =========================================================================

    @Test
    @Transactional
    void cancelPaidCharge_softFlags_andIssuesFullAmountPendingCreditNote() {
        // A bill that has been paid at the cashier (RECEIVED payment detail exists)
        PatientBill bill = makeBill("CN-PAT-001", BillStatus.UNPAID, "5000.00");
        paymentService.recordPayment(List.of(bill.getUid()),
                Money.of(new BigDecimal("5000.00")), PaymentMode.CASH, ctx);

        Optional<PatientCreditNote> note =
                creditNoteService.cancelCharge(bill.getUid(), "Canceled consultation", ctx);

        // Bill is soft-cancelled (kept, NOT deleted)
        PatientBill cancelled = billRepository.findByUid(bill.getUid()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BillStatus.CANCELED);

        // Payment detail flipped to REFUNDED (kept, NOT deleted — PHI/audit preserved)
        assertThat(paymentDetailRepository.findReceivedByBillUid(bill.getUid()))
                .as("no RECEIVED payment detail remains — it was refunded").isEmpty();
        PatientPaymentDetail pd = paymentDetailRepository.findByBillUid(bill.getUid())
                .orElseThrow(() -> new AssertionError("payment detail row was hard-deleted"));
        assertThat(pd.getStatus())
                .as("payment detail row KEPT and marked REFUNDED (no hard-delete of PHI/audit)")
                .isEqualTo(PaymentDetailStatus.REFUNDED);

        // Full-amount PENDING credit note with a PCN number
        assertThat(note).isPresent();
        PatientCreditNote cn = note.orElseThrow();
        assertThat(cn.getStatus()).isEqualTo(CreditNoteStatus.PENDING);
        assertThat(cn.getAmount().getAmount()).isEqualByComparingTo("5000.00");
        assertThat(cn.getReference()).isEqualTo("Canceled consultation");
        assertThat(cn.getPatientBillUid()).isEqualTo(bill.getUid());
        assertThat(cn.getNo()).matches("^PCN\\d{8}-\\d+$");

        // Audit: credit-note CREATE row written
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(cn.getUid()))
                .anyMatch(r -> r.getAction() == AuditAction.CREATE);
    }

    // =========================================================================
    // Cancel of an UNPAID charge → CANCELED, NO credit note (PARITY)
    // =========================================================================

    @Test
    @Transactional
    void cancelUnpaidCharge_cancelsBill_noCreditNote() {
        PatientBill bill = makeBill("CN-PAT-002", BillStatus.UNPAID, "1200.00");

        Optional<PatientCreditNote> note =
                creditNoteService.cancelCharge(bill.getUid(), "Canceled lab test", ctx);

        assertThat(note).as("no PCN when there was no received payment").isEmpty();
        assertThat(billRepository.findByUid(bill.getUid()).orElseThrow().getStatus())
                .isEqualTo(BillStatus.CANCELED);
        assertThat(creditNoteRepository.findByPatientUidOrderByCreatedAtDesc("CN-PAT-002"))
                .isEmpty();
    }

    // =========================================================================
    // CR-10 FIX: multi-line invoice — parent deleted ONLY when zero details remain
    // (legacy j=j++ no-op always deleted the parent — PatientResource.java:659-673)
    // =========================================================================

    @Test
    @Transactional
    void cancelOneOfTwoInvoiceLines_keepsParent_thenLastLineDeletesParent() {
        // One invoice with TWO detail lines (two distinct bills)
        PatientInvoice invoice = invoiceRepository.save(
                new PatientInvoice("CN-PAT-003", "PLAN-CR10", dayUid));
        PatientBill bill1 = makeBill("CN-PAT-003", BillStatus.COVERED, "2000.00");
        PatientBill bill2 = makeBill("CN-PAT-003", BillStatus.COVERED, "3000.00");
        invoice.addDetail(new PatientInvoiceDetail(invoice, bill1, CoverageStatus.COVERED));
        invoice.addDetail(new PatientInvoiceDetail(invoice, bill2, CoverageStatus.COVERED));
        invoiceRepository.save(invoice);
        String invoiceUid = invoice.getUid();

        // Cancel the FIRST line → its detail removed, parent KEPT (one detail remains)
        creditNoteService.cancelCharge(bill1.getUid(), "Canceled covered line 1", ctx);

        assertThat(invoiceRepository.findByUid(invoiceUid))
                .as("parent invoice kept while a second detail remains (CR-10 FIX)")
                .isPresent();
        assertThat(invoiceDetailRepository.findByBillUid(bill1.getUid())).isEmpty();
        assertThat(invoiceDetailRepository.findByBillUid(bill2.getUid())).isPresent();

        // Cancel the SECOND (last) line → parent invoice now empty → DELETED
        creditNoteService.cancelCharge(bill2.getUid(), "Canceled covered line 2", ctx);

        assertThat(invoiceRepository.findByUid(invoiceUid))
                .as("parent invoice deleted once the last detail is removed")
                .isEmpty();
        assertThat(invoiceDetailRepository.findByBillUid(bill2.getUid())).isEmpty();
    }

    // =========================================================================
    // CR-10 single-detail: cancelling the only COVERED line removes the detail AND
    // deletes the (now-empty) parent invoice — no PCN (never cash-paid). This exercises
    // the isEmpty()->delete branch on a single-detail invoice (the j=j++ bug's blast radius).
    // =========================================================================

    @Test
    @Transactional
    void cancelCoveredCharge_removesDetailAndDeletesInvoice_noCreditNote() {
        PatientInvoice invoice = invoiceRepository.save(
                new PatientInvoice("CN-PAT-COV", "PLAN-COV", dayUid));
        PatientBill bill = makeBill("CN-PAT-COV", BillStatus.COVERED, "9000.00");
        invoice.addDetail(new PatientInvoiceDetail(invoice, bill, CoverageStatus.COVERED));
        invoiceRepository.save(invoice);
        String invoiceUid = invoice.getUid();

        var note = creditNoteService.cancelCharge(bill.getUid(), "Canceled covered service", ctx);

        assertThat(note).as("COVERED bill was never cash-paid → no PCN").isEmpty();
        assertThat(billRepository.findByUid(bill.getUid()).orElseThrow().getStatus())
                .isEqualTo(BillStatus.CANCELED);
        assertThat(invoiceDetailRepository.findByBillUid(bill.getUid())).isEmpty();
        assertThat(invoiceRepository.findByUid(invoiceUid))
                .as("single-detail invoice deleted once its only line is removed (CR-10 isEmpty branch)")
                .isEmpty();
    }

    // =========================================================================
    // GATED:CR-03b parity — amountPaid is NEVER decremented on refund/cancel
    // (legacy only deletes the detail / parent; it never adjusts amount_paid)
    // =========================================================================

    @Test
    @Transactional
    void cancel_doesNotDecrementInvoiceAmountPaid() {
        // Two paid bills on one surviving invoice → amountPaid accumulates to 7000
        PatientInvoice invoice = invoiceRepository.save(
                new PatientInvoice("CN-PAT-AP", null, dayUid));
        PatientBill b1 = makeBill("CN-PAT-AP", BillStatus.VERIFIED, "4000.00");
        PatientBill b2 = makeBill("CN-PAT-AP", BillStatus.VERIFIED, "3000.00");
        invoice.addDetail(new PatientInvoiceDetail(invoice, b1, CoverageStatus.VERIFIED));
        invoice.addDetail(new PatientInvoiceDetail(invoice, b2, CoverageStatus.VERIFIED));
        invoiceRepository.save(invoice);
        String invoiceUid = invoice.getUid();

        paymentService.recordPayment(List.of(b1.getUid()),
                Money.of(new java.math.BigDecimal("4000.00")), PaymentMode.CASH, ctx);
        paymentService.recordPayment(List.of(b2.getUid()),
                Money.of(new java.math.BigDecimal("3000.00")), PaymentMode.CASH, ctx);

        // Cancel b1 — its detail is removed but the invoice survives (b2 remains)
        creditNoteService.cancelCharge(b1.getUid(), "Canceled one of two", ctx);

        PatientInvoice after = invoiceRepository.findByUid(invoiceUid).orElseThrow();
        assertThat(after.getAmountPaid())
                .as("amountPaid is NOT decremented on refund (GATED:CR-03b)")
                .isEqualByComparingTo("7000.00");
    }

    // =========================================================================
    // REST — cancellation endpoint (200, no id in JSON), gated BILL-A
    // =========================================================================

    @Test
    @Transactional
    void cancelCharge_viaRest_returnsResult_noIdInJson() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));
        PatientBill bill = makeBill("CN-PAT-REST-001", BillStatus.UNPAID, "2500.00");
        paymentService.recordPayment(List.of(bill.getUid()),
                Money.of(new BigDecimal("2500.00")), PaymentMode.CASH, ctx);

        String body = """
                { "reference": "Canceled procedure" }
                """;

        var result = mockMvc.perform(
                        post("/api/v1/billing/bills/uid/" + bill.getUid() + "/cancellation")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billStatus").value("CANCELED"))
                .andExpect(jsonPath("$.creditNote.status").value("PENDING"))
                .andExpect(jsonPath("$.creditNote.no").value(org.hamcrest.Matchers.matchesPattern("^PCN\\d{8}-\\d+$")))
                .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.has("id")).as("no internal id in response (ADR-0014 §1)").isFalse();
        assertThat(node.get("creditNote").has("id")).isFalse();
    }

    @Test
    void cancelCharge_returns401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/billing/bills/uid/SOMEUID/cancellation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelCharge_returns403_withoutBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(post("/api/v1/billing/bills/uid/SOMEUID/cancellation")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listCreditNotes_returns403_withoutBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/billing/credit-notes?patientUid=X")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listCreditNotes_returns401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/billing/credit-notes?patientUid=X"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCreditNote_returns401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/billing/credit-notes/uid/SOMEUID"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCreditNote_notFound_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));
        mockMvc.perform(get("/api/v1/billing/credit-notes/uid/NONEXISTENT-UID-000000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:not-found"));
    }

    // =========================================================================
    // Cancel of a missing bill → 404 (NotFoundException)
    // =========================================================================

    @Test
    @Transactional
    void cancelCharge_unknownBill_throwsNotFound() {
        assertThatThrownBy(() -> creditNoteService.cancelCharge(
                "NONEXISTENT-BILL-0000000000", "x", ctx))
                .isInstanceOf(com.otapp.hmis.shared.error.NotFoundException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PatientBill makeBill(String patientUid, BillStatus status, String amount) {
        PatientBill bill = new PatientBill(
                patientUid, com.otapp.hmis.masterdata.lookup.ServiceKind.LAB_TEST,
                "Lab Test", "Lab Test", BigDecimal.ONE,
                Money.of(new BigDecimal(amount)), dayUid);
        switch (status) {
            case VERIFIED -> bill.markVerified();
            case COVERED  -> bill.overrideWithInsurance(Money.of(new BigDecimal(amount)),
                                                        "PLAN-CR10", "MEM-X");
            case PAID     -> bill.markPaid();
            case CANCELED -> bill.cancel();
            default       -> { /* UNPAID */ }
        }
        return billRepository.save(bill);
    }
}
