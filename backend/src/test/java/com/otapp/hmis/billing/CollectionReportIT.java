package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.application.CollectionReportService;
import com.otapp.hmis.billing.application.PaymentService;
import com.otapp.hmis.billing.application.ReceiptService;
import com.otapp.hmis.billing.application.dto.CollectionReportRow;
import com.otapp.hmis.billing.application.dto.ReceiptDto;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientPayment;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Golden-master EOD collections report + POS receipt tests (build-spec §5.1/§5.3, §7.2).
 *
 * <p>Uses unique {@code itemName} discriminators so the read-time {@code SUM GROUP BY} assertions
 * are isolated from collections written by other test classes that share the singleton container.
 */
class CollectionReportIT extends AbstractIntegrationTest {

    private static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired CollectionReportService collectionReportService;
    @Autowired ReceiptService receiptService;
    @Autowired PaymentService paymentService;
    @Autowired PatientBillRepository billRepository;
    @Autowired BusinessDayService businessDayService;

    private String dayUid;
    private TxAuditContext ctx;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        dayUid = ensureDayOpen();
        ctx = new TxAuditContext(dayUid, Instant.now(), "report-cashier");
        today = LocalDate.now(EAT);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
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
    // General cash-up: SUM(amount) GROUP BY (item_name, payment_channel) across all users
    // =========================================================================

    @Test
    @Transactional
    void generalReport_sumsByItemAndChannel() {
        // Two lines of the same item collapse into one summed bucket; a third item is its own row.
        PatientBill lab1 = makeBill("RPT-PAT-1", "RPTGEN-LAB", "5000.00");
        PatientBill lab2 = makeBill("RPT-PAT-1", "RPTGEN-LAB", "3000.00");
        pay(lab1, lab2);                          // 8000.00 under RPTGEN-LAB / Cash
        PatientBill cons = makeBill("RPT-PAT-1", "RPTGEN-CON", "2000.00");
        pay(cons);                                // 2000.00 under RPTGEN-CON / Cash

        List<CollectionReportRow> rows = collectionReportService.collectionsReport(today, today, null);

        assertThat(rowFor(rows, "RPTGEN-LAB"))
                .as("two RPTGEN-LAB lines summed")
                .isEqualByComparingTo("8000.00");
        assertThat(rowFor(rows, "RPTGEN-CON")).isEqualByComparingTo("2000.00");
        assertThat(rows.stream().allMatch(r -> "Cash".equals(r.paymentChannel())))
                .as("every legacy bucket is on the Cash channel").isTrue();
    }

    // =========================================================================
    // Per-cashier cash-up: filtered to the user who recorded the collection
    // =========================================================================

    @Test
    @Transactional
    void perCashierReport_filtersByRecordingUser() {
        // Alice records one collection; Bob another. createdBy is stamped from the security context.
        authenticateAs("rpt-alice");
        pay(makeBill("RPT-PAT-2", "RPTCASH-ALICE", "1500.00"));

        authenticateAs("rpt-bob");
        pay(makeBill("RPT-PAT-2", "RPTCASH-BOB", "2500.00"));
        SecurityContextHolder.clearContext();

        List<CollectionReportRow> alice = collectionReportService.collectionsReport(today, today, "rpt-alice");
        assertThat(rowFor(alice, "RPTCASH-ALICE")).isEqualByComparingTo("1500.00");
        assertThat(alice.stream().anyMatch(r -> "RPTCASH-BOB".equals(r.itemName())))
                .as("Bob's collection must NOT appear in Alice's report").isFalse();

        List<CollectionReportRow> bob = collectionReportService.collectionsReport(today, today, "rpt-bob");
        assertThat(rowFor(bob, "RPTCASH-BOB")).isEqualByComparingTo("2500.00");
        assertThat(bob.stream().anyMatch(r -> "RPTCASH-ALICE".equals(r.itemName()))).isFalse();
    }

    // =========================================================================
    // Date window is [from 00:00, (to+1) 00:00) EAT — out-of-range day excludes today's rows
    // =========================================================================

    @Test
    @Transactional
    void report_excludesCollectionsOutsideDateWindow() {
        pay(makeBill("RPT-PAT-3", "RPTWINDOW-X", "777.00"));

        // Query a window entirely BEFORE today → today's collection is excluded
        LocalDate yesterday = today.minusDays(1);
        List<CollectionReportRow> rows = collectionReportService.collectionsReport(yesterday, yesterday, null);

        assertThat(rows.stream().anyMatch(r -> "RPTWINDOW-X".equals(r.itemName())))
                .as("today's collection is outside the [yesterday, today) window").isFalse();
    }

    // =========================================================================
    // POS receipt — anchored on the payment uid, carries billing-owned fields + business date
    // =========================================================================

    @Test
    @Transactional
    void receipt_carriesPaymentFieldsAndBusinessDate() {
        PatientBill bill = makeBill("RPT-PAT-4", "RPTRCPT", "4200.00");
        PatientPayment payment = pay(bill);

        ReceiptDto receipt = receiptService.receiptForPayment(payment.getUid());

        assertThat(receipt.receiptNo()).isEqualTo(payment.getUid());
        assertThat(receipt.amount().amount()).isEqualByComparingTo("4200.00");
        assertThat(receipt.paymentMode()).isEqualTo("CASH");
        assertThat(receipt.status()).isEqualTo("RECEIVED");
        assertThat(receipt.businessDayUid()).isEqualTo(dayUid);
        assertThat(receipt.businessDate()).isEqualTo(businessDayService.currentDay().getBusinessDate());
    }

    // =========================================================================
    // REST — gates + shape
    // =========================================================================

    @Test
    void collectionsReport_returns403_withoutBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/billing/reports/collections?from=2026-06-01&to=2026-06-03")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void collectionsReport_returns200_array_withBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));
        mockMvc.perform(get("/api/v1/billing/reports/collections?from=2026-06-01&to=2026-06-03")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @Transactional
    void receipt_viaRest_noIdInJson_withBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));
        PatientBill bill = makeBill("RPT-PAT-REST", "RPTRESTRCPT", "1000.00");
        PatientPayment payment = pay(bill);

        var result = mockMvc.perform(get("/api/v1/billing/payments/uid/" + payment.getUid() + "/receipt")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNo").value(payment.getUid()))
                .andExpect(jsonPath("$.paymentMode").value("CASH"))
                .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.has("id")).as("no internal id in receipt (ADR-0014 §1)").isFalse();
    }

    @Test
    void receipt_unknownPayment_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("cashier", List.of("BILL-A"));
        mockMvc.perform(get("/api/v1/billing/payments/uid/NONEXISTENT-UID-000000000000/receipt")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:not-found"));
    }

    @Test
    void receipt_returns403_withoutBillA() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/billing/payments/uid/SOMEUID/receipt")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static BigDecimal rowFor(List<CollectionReportRow> rows, String itemName) {
        return rows.stream()
                .filter(r -> itemName.equals(r.itemName()))
                .map(CollectionReportRow::amount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no report row for item " + itemName));
    }

    private PatientBill makeBill(String patientUid, String itemName, String amount) {
        PatientBill bill = new PatientBill(
                patientUid, com.otapp.hmis.masterdata.lookup.ServiceKind.LAB_TEST,
                itemName, itemName, BigDecimal.ONE, Money.of(new BigDecimal(amount)), dayUid);
        return billRepository.save(bill);
    }

    private PatientPayment pay(PatientBill... bills) {
        BigDecimal total = BigDecimal.ZERO;
        for (PatientBill b : bills) {
            total = total.add(b.amountValue());
        }
        return paymentService.recordPayment(
                java.util.Arrays.stream(bills).map(PatientBill::getUid).toList(),
                Money.of(total), PaymentMode.CASH, ctx);
    }
}
