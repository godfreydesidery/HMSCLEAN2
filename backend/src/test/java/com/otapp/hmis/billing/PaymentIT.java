package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.application.BillNotPayableException;
import com.otapp.hmis.billing.application.PaymentAmountMismatchException;
import com.otapp.hmis.billing.application.PaymentService;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.CollectionRepository;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientInvoice;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.billing.domain.PatientPayment;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLogRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Golden-master payment tests (build-spec §7.2 item 4).
 *
 * <h2>Test plan coverage</h2>
 * <ul>
 *   <li>Pay exact total of UNPAID bills → bills PAID + Collection rows written.</li>
 *   <li>Pay VERIFIED bill → PAID.</li>
 *   <li>Status-not-payable → BILL_NOT_PAYABLE.</li>
 *   <li>Tendered != sum → PAYMENT_AMOUNT_MISMATCH.</li>
 *   <li>No id in JSON response (ADR-0014 §1).</li>
 *   <li>Audit row written for payment.</li>
 * </ul>
 *
 * <p>Bills are seeded directly via domain constructors (not via the REST API) to isolate
 * payment logic from the pricing engine — the two engines are tested separately.
 */
class PaymentIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired PaymentService paymentService;
    @Autowired PatientBillRepository billRepository;
    @Autowired PatientInvoiceRepository invoiceRepository;
    @Autowired CollectionRepository collectionRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired BusinessDayService businessDayService;

    private String dayUid;
    private TxAuditContext ctx;

    @BeforeEach
    void setUp() {
        dayUid = ensureDayOpen();
        ctx = new TxAuditContext(dayUid, Instant.now(), "cashier-test");
    }

    /**
     * Opens a business day if none is currently open.
     * Robust to the shared Testcontainer singleton: a prior test (or its rollback) may or
     * may not have left a day open. Rolled-back @Transactional tests close the DB state,
     * so a committed open day from a previous @BeforeEach call remains visible.
     */
    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (com.otapp.hmis.shared.domain.NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    // =========================================================================
    // Pay exact total of two UNPAID bills → both PAID + Collection rows written
    // PatientBillResource.java:269-393
    // =========================================================================

    @Test
    @Transactional
    void recordPayment_exactTotal_twoBills_paidAndCollectionWritten() {
        PatientBill bill1 = makeBill("PAY-PAT-001", BillStatus.UNPAID, "5000.00");
        PatientBill bill2 = makeBill("PAY-PAT-001", BillStatus.UNPAID, "3000.00");

        Money tendered = Money.of(new BigDecimal("8000.00"));
        PatientPayment payment = paymentService.recordPayment(
                List.of(bill1.getUid(), bill2.getUid()), tendered, PaymentMode.CASH, ctx);

        assertThat(payment.getUid()).isNotNull();
        assertThat(payment.getAmount().getAmount()).isEqualByComparingTo("8000.00");

        // Both bills are PAID
        PatientBill b1 = billRepository.findByUid(bill1.getUid()).orElseThrow();
        PatientBill b2 = billRepository.findByUid(bill2.getUid()).orElseThrow();
        assertThat(b1.getStatus()).isEqualTo(BillStatus.PAID);
        assertThat(b2.getStatus()).isEqualTo(BillStatus.PAID);
        assertThat(b1.getBalance().getAmount()).isEqualByComparingTo("0.00");
        assertThat(b2.getBalance().getAmount()).isEqualByComparingTo("0.00");

        // Two Collection rows written (PatientBillResource.java:327-337)
        var collections = collectionRepository.findAll();
        assertThat(collections).as("two collection rows").hasSizeGreaterThanOrEqualTo(2);
        assertThat(collections.stream()
                .filter(c -> c.getPatientUid().equals("PAY-PAT-001"))
                .count()).isEqualTo(2);

        // Audit row for payment (AuditLogRepository is narrow — no findAll)
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(payment.getUid()))
                .as("audit_logs must contain a CREATE row for the payment uid")
                .anyMatch(r -> r.getAction() == AuditAction.CREATE);
    }

    // =========================================================================
    // Pay a VERIFIED bill → PAID (VERIFIED is payable — PatientBillResource.java:295)
    // =========================================================================

    @Test
    @Transactional
    void recordPayment_verifiedBill_becomePaid() {
        PatientBill bill = makeBill("PAY-PAT-002", BillStatus.VERIFIED, "4500.00");

        PatientPayment payment = paymentService.recordPayment(
                List.of(bill.getUid()), Money.of(new BigDecimal("4500.00")), PaymentMode.CASH, ctx);

        PatientBill paid = billRepository.findByUid(bill.getUid()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(BillStatus.PAID);
        assertThat(payment).isNotNull();
    }

    // =========================================================================
    // Status-not-payable → BILL_NOT_PAYABLE (PatientBillResource.java:295-296)
    // =========================================================================

    @Test
    @Transactional
    void recordPayment_coveredBill_throwsBillNotPayable() {
        PatientBill bill = makeBill("PAY-PAT-003", BillStatus.COVERED, "3500.00");
        var billUids = List.of(bill.getUid());
        var tendered = Money.of(new BigDecimal("3500.00"));

        assertThatThrownBy(() -> paymentService.recordPayment(billUids, tendered, PaymentMode.CASH, ctx))
                .isInstanceOf(BillNotPayableException.class);
    }

    @Test
    @Transactional
    void recordPayment_paidBill_throwsBillNotPayable() {
        PatientBill bill = makeBill("PAY-PAT-004", BillStatus.PAID, "3000.00");
        var billUids = List.of(bill.getUid());
        var tendered = Money.of(new BigDecimal("3000.00"));

        assertThatThrownBy(() -> paymentService.recordPayment(billUids, tendered, PaymentMode.CASH, ctx))
                .isInstanceOf(BillNotPayableException.class);
    }

    @Test
    @Transactional
    void recordPayment_canceledBill_throwsBillNotPayable() {
        PatientBill bill = makeBill("PAY-PAT-005", BillStatus.CANCELED, "2000.00");
        var billUids = List.of(bill.getUid());
        var tendered = Money.of(new BigDecimal("2000.00"));

        assertThatThrownBy(() -> paymentService.recordPayment(billUids, tendered, PaymentMode.CASH, ctx))
                .isInstanceOf(BillNotPayableException.class);
    }

    // =========================================================================
    // Exact-tender guard CR-12: tendered != sum → PAYMENT_AMOUNT_MISMATCH
    // PatientBillResource.java:389-391 — BigDecimal.compareTo, not double ==
    // =========================================================================

    @Test
    @Transactional
    void recordPayment_tenderTooLow_throwsPaymentAmountMismatch() {
        PatientBill bill = makeBill("PAY-PAT-006", BillStatus.UNPAID, "5000.00");

        // Tendered 4999.99 ≠ 5000.00 → mismatch
        var billUids = List.of(bill.getUid());
        var tendered = Money.of(new BigDecimal("4999.99"));

        assertThatThrownBy(() -> paymentService.recordPayment(billUids, tendered, PaymentMode.CASH, ctx))
                .isInstanceOf(PaymentAmountMismatchException.class);
    }

    @Test
    @Transactional
    void recordPayment_tenderTooHigh_throwsPaymentAmountMismatch() {
        PatientBill bill = makeBill("PAY-PAT-007", BillStatus.UNPAID, "5000.00");

        // Tendered 5000.01 ≠ 5000.00 → mismatch (no change in PARITY)
        var billUids = List.of(bill.getUid());
        var tendered = Money.of(new BigDecimal("5000.01"));

        assertThatThrownBy(() -> paymentService.recordPayment(billUids, tendered, PaymentMode.CASH, ctx))
                .isInstanceOf(PaymentAmountMismatchException.class);
    }

    // =========================================================================
    // Invoice detail amountPaid accumulation (PatientBillResource.java:341-349)
    // =========================================================================

    @Test
    @Transactional
    void recordPayment_billOnInsuranceInvoice_invoiceAmountPaidIncremented() {
        // Test the invoice amountPaid path using a VERIFIED inpatient bill attached to a cash invoice
        PatientBill verifiedBill = makeBill("PAY-PAT-009", BillStatus.VERIFIED, "4000.00");
        attachBillToExistingCashInvoice(verifiedBill, "PAY-PAT-009");

        paymentService.recordPayment(
                List.of(verifiedBill.getUid()),
                Money.of(new BigDecimal("4000.00")), PaymentMode.CASH, ctx);

        // Invoice amountPaid incremented
        PatientInvoice invoice = invoiceRepository.findPendingCashInvoice("PAY-PAT-009").orElseThrow();
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("4000.00");
    }

    // =========================================================================
    // REST controller — no id in JSON response (ADR-0014 §1)
    // =========================================================================

    @Test
    void getInvoice_returns401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/billing/invoices/uid/SOMEUID"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getInvoice_returns403_withoutBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/billing/invoices/uid/SOMEUID")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getInvoice_notFound_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));
        mockMvc.perform(get("/api/v1/billing/invoices/uid/NONEXISTENT-UID-000000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:not-found"));
    }

    @Test
    @Transactional
    void recordPayment_viaRest_noIdInResponse() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));

        // Create an invoice and a payable bill
        PatientInvoice invoice = invoiceRepository.save(
                new PatientInvoice("PAY-PAT-REST-001", null, dayUid));
        PatientBill bill = makeBill("PAY-PAT-REST-001", BillStatus.UNPAID, "2000.00");

        String body = """
                {
                  "billUids": ["%s"],
                  "tenderedTotal": {"amount": 2000.00, "currency": "TZS"},
                  "paymentMode": "CASH"
                }
                """.formatted(bill.getUid());

        var result = mockMvc.perform(
                        post("/api/v1/billing/invoices/uid/" + invoice.getUid() + "/payments")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        // Response must NOT contain an "id" field (ADR-0014 §1)
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.has("id"))
                .as("Response must not contain internal id field (ADR-0014 §1)")
                .isFalse();
        assertThat(node.has("uid"))
                .as("Response must contain uid")
                .isTrue();
    }

    // =========================================================================
    // Helpers — direct domain constructors (no REST API needed for payment tests)
    // =========================================================================

    private PatientBill makeBill(String patientUid, BillStatus status, String amount) {
        PatientBill bill = new PatientBill(
                patientUid, com.otapp.hmis.masterdata.lookup.ServiceKind.LAB_TEST,
                "Lab Test", "Lab Test", BigDecimal.ONE,
                Money.of(new BigDecimal(amount)), dayUid);
        // Apply desired status
        switch (status) {
            case VERIFIED -> bill.markVerified();
            case COVERED  -> bill.overrideWithInsurance(Money.of(new BigDecimal(amount)),
                                                        "PLAN-X", "MEM-X");
            case PAID     -> bill.markPaid();
            case CANCELED -> bill.cancel();
            default       -> { /* UNPAID — already set by constructor */ }
        }
        return billRepository.save(bill);
    }

    private void attachBillToExistingCashInvoice(PatientBill bill, String patientUid) {
        PatientInvoice invoice = invoiceRepository.findPendingCashInvoice(patientUid)
                .orElseGet(() -> invoiceRepository.save(
                        new PatientInvoice(patientUid, null, dayUid)));
        var detail = new com.otapp.hmis.billing.domain.PatientInvoiceDetail(
                invoice, bill, com.otapp.hmis.billing.domain.CoverageStatus.VERIFIED);
        invoice.addDetail(detail);
        invoiceRepository.save(invoice);
    }
}
