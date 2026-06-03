package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.application.PlanNotAvailableForClinicException;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.CoverageStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
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
 * Golden-master pricing engine tests (build-spec §7.2 items 1-3).
 *
 * <h2>Test plan coverage</h2>
 * <ol>
 *   <li>Pricing two-step (§2.1): cash→COVERED override; medicine planPrice×qty HALF_UP.</li>
 *   <li>Fallback asymmetry (§2.2): consultation HARD-FAIL verbatim message + tx rollback;
 *       LAB inpatient→VERIFIED; LAB outpatient→UNPAID; REGISTRATION silent; regFee==0→VERIFIED.</li>
 *   <li>PriceLookup missing-both → 422 SERVICE_PRICE_NOT_FOUND.</li>
 * </ol>
 *
 * <p>Each test seeds its own prices via the masterdata REST API (unique codes to avoid
 * cross-test contamination). BillingCommands is called directly (in-process, REQUIRED tx)
 * via a @Transactional test method — the same tx that would be supplied by the caller
 * in inc-03/05.
 */
class BillingChargeIT extends AbstractIntegrationTest {

    private static final String PRICES_URL    = "/api/v1/masterdata/service-prices";
    private static final String PROVIDERS_URL = "/api/v1/masterdata/insurance-providers";

    @Autowired MockMvc mockMvc;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BillingCommands billingCommands;
    @Autowired PatientBillRepository billRepository;
    @Autowired PatientInvoiceRepository invoiceRepository;
    @Autowired BusinessDayService businessDayService;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS", "BILL-A"));
        dayUid = ensureDayOpen();
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
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    // =========================================================================
    // §2.1 Pricing two-step — LAB: cash 5000, plan-X covered 3500
    // PatientServiceImpl.java:821-849
    // =========================================================================

    @Test
    @Transactional
    void pricingTwoStep_labTest_insurancePatient_billCoveredAtPlanPrice() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-LAB-INS-PROV", "BILL-IT-LAB-INS-PLAN");
        String svcUid  = createLabTestTypeAndGetUid("LTT-BILL-IT-001", "Lab Bill IT 001");
        seedPrice(null,    "LAB_TEST", svcUid, "5000.00", true);
        seedPrice(planUid, "LAB_TEST", svcUid, "3500.00", true);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-001", planUid, "MEM-001",
                ServiceKind.LAB_TEST, svcUid,
                BigDecimal.ONE, PaymentMode.INSURANCE, false, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        assertThat(result.status()).isEqualTo(BillStatus.COVERED);
        assertThat(result.amount().amount()).isEqualByComparingTo("3500.00");
        assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COVERED);

        // Verify the bill was persisted with the plan price
        PatientBill bill = billRepository.findByUid(result.billUid()).orElseThrow();
        assertThat(bill.amountValue()).isEqualByComparingTo("3500.00");
        assertThat(bill.getPaid().getAmount()).isEqualByComparingTo("3500.00");
        assertThat(bill.getBalance().getAmount()).isEqualByComparingTo("0.00");
        assertThat(bill.getPlanUid()).isEqualTo(planUid);
        assertThat(bill.getMembershipNo()).isEqualTo("MEM-001");

        // Verify PENDING invoice accumulator created
        assertThat(invoiceRepository.findPendingInsuranceInvoice("PATIENT-UID-001", planUid))
                .isPresent();
    }

    @Test
    @Transactional
    void pricingTwoStep_labTest_cashPatient_billUnpaidAtCashPrice() throws Exception {
        String svcUid = createLabTestTypeAndGetUid("LTT-BILL-IT-002", "Lab Bill IT 002");
        seedPrice(null, "LAB_TEST", svcUid, "5000.00", true);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-002", null, null,
                ServiceKind.LAB_TEST, svcUid,
                BigDecimal.ONE, PaymentMode.CASH, false, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        assertThat(result.status()).isEqualTo(BillStatus.UNPAID);
        assertThat(result.amount().amount()).isEqualByComparingTo("5000.00");
        assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.UNPAID);
    }

    // =========================================================================
    // §2.1 Medicine: plan price × qty HALF_UP (PatientServiceImpl.java:1552-1553)
    // =========================================================================

    @Test
    @Transactional
    void pricingTwoStep_medicine_qty3_planPrice_multiplied_halfUp() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-MED-PROV", "BILL-IT-MED-PLAN");
        String svcUid  = createMedicineAndGetUid("MED-BILL-IT-001", "Medicine Bill IT 001");
        seedPrice(null,    "MEDICINE", svcUid, "100.00", true);
        seedPrice(planUid, "MEDICINE", svcUid, "66.67", true);  // plan price per unit

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        // qty=3 → plan total = 66.67 × 3 = 200.01 (HALF_UP)
        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-003", planUid, "MEM-003",
                ServiceKind.MEDICINE, svcUid,
                new BigDecimal("3"), PaymentMode.INSURANCE, false, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        assertThat(result.status()).isEqualTo(BillStatus.COVERED);
        // 66.67 × 3 = 200.01 (HALF_UP at scale 2)
        assertThat(result.amount().amount()).isEqualByComparingTo("200.01");
    }

    // =========================================================================
    // §2.2 Fallback asymmetry — CONSULTATION HARD FAIL
    // PatientServiceImpl.java:599-601
    // =========================================================================

    @Test
    @Transactional
    void fallback_consultation_noCoveredRow_throws422_verbatimMessage_noBillPersisted() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-CON-PROV", "BILL-IT-CON-PLAN");
        String svcUid  = createClinicAndGetUid("BILL-IT-CLINIC-001", "Billing IT Clinic 001");
        // Seed only cash row — NO covered row for this plan+clinic
        seedPrice(null, "CONSULTATION", svcUid, "3000.00", true);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-004", planUid, "MEM-004",
                ServiceKind.CONSULTATION, svcUid,
                BigDecimal.ONE, PaymentMode.INSURANCE, false, false);

        // Verbatim legacy message (PatientServiceImpl.java:599-601); tx rolls back → no bill persists
        assertThatThrownBy(() -> billingCommands.recordClinicalCharge(req, ctx))
                .isInstanceOf(PlanNotAvailableForClinicException.class)
                .hasMessage("Plan not available for this clinic. Please change payment method");
    }

    // =========================================================================
    // §2.2 Fallback asymmetry — LAB inpatient → VERIFIED
    // PatientServiceImpl.java:912-918
    // =========================================================================

    @Test
    @Transactional
    void fallback_lab_inpatient_noCoveredRow_billVerified_attachedToCashInvoice() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-LAB-VER-PROV", "BILL-IT-LAB-VER-PLAN");
        String svcUid  = createLabTestTypeAndGetUid("LTT-BILL-IT-003", "Lab Bill IT 003");
        // Seed only cash row — NO covered row for this plan+lab
        seedPrice(null, "LAB_TEST", svcUid, "4500.00", true);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-005", planUid, "MEM-005",
                ServiceKind.LAB_TEST, svcUid,
                BigDecimal.ONE, PaymentMode.INSURANCE, true /* inpatient */, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        // Inpatient + no covered row → VERIFIED (PatientServiceImpl.java:917)
        assertThat(result.status()).isEqualTo(BillStatus.VERIFIED);
        assertThat(result.amount().amount()).isEqualByComparingTo("4500.00");
        assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.VERIFIED);

        // Bill is at cash price
        PatientBill bill = billRepository.findByUid(result.billUid()).orElseThrow();
        assertThat(bill.amountValue()).isEqualByComparingTo("4500.00");

        // Attached to a NULL-plan PENDING cash invoice
        assertThat(invoiceRepository.findPendingCashInvoice("PATIENT-UID-005")).isPresent();
    }

    // =========================================================================
    // §2.2 Fallback asymmetry — LAB outpatient insured, no covered row → UNPAID cash (silent)
    // PatientServiceImpl.java:821-835 (step 1 stays)
    // =========================================================================

    @Test
    @Transactional
    void fallback_lab_outpatient_noCoveredRow_billUnpaidCash_noInvoice() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-LAB-OPD-PROV", "BILL-IT-LAB-OPD-PLAN");
        String svcUid  = createLabTestTypeAndGetUid("LTT-BILL-IT-004", "Lab Bill IT 004");
        // Seed only cash row — NO covered row
        seedPrice(null, "LAB_TEST", svcUid, "4000.00", true);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-006", planUid, "MEM-006",
                ServiceKind.LAB_TEST, svcUid,
                BigDecimal.ONE, PaymentMode.INSURANCE, false /* outpatient */, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        // Non-admitted insured + no covered row → silent UNPAID cash
        assertThat(result.status()).isEqualTo(BillStatus.UNPAID);
        assertThat(result.amount().amount()).isEqualByComparingTo("4000.00");

        // No invoice attached (silent path)
        assertThat(invoiceRepository.findPendingInsuranceInvoice("PATIENT-UID-006", planUid))
                .isEmpty();
        assertThat(invoiceRepository.findPendingCashInvoice("PATIENT-UID-006"))
                .isEmpty();
    }

    // =========================================================================
    // §2.2 Fallback — REGISTRATION silent, regFee==0 → VERIFIED
    // PatientServiceImpl.java:273-277, :321
    // =========================================================================

    @Test
    @Transactional
    void fallback_registration_regFeeZero_billVerified() throws Exception {
        // Seed cash row with amount=0 → covered=false stored (RF-2), fee==0 → VERIFIED
        seedPrice(null, "REGISTRATION", null, "0.00", false);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-007", null, null,
                ServiceKind.REGISTRATION, null,
                BigDecimal.ONE, PaymentMode.CASH, false, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        // regFee==0 → VERIFIED (PatientServiceImpl.java:276)
        assertThat(result.status()).isEqualTo(BillStatus.VERIFIED);
        assertThat(result.amount().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    @Transactional
    void fallback_registration_insured_noCoveredRow_silentUnpaid() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-REG-PROV", "BILL-IT-REG-PLAN");
        // Seed cash reg fee
        seedPrice(null, "REGISTRATION", null, "500.00", true);
        // NO covered row for this plan → silent

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-008", planUid, "MEM-008",
                ServiceKind.REGISTRATION, null,
                BigDecimal.ONE, PaymentMode.INSURANCE, false, false);

        ChargeResult result = billingCommands.recordClinicalCharge(req, ctx);

        // Silent: stays UNPAID at cash price (PatientServiceImpl.java:321 — no-op)
        assertThat(result.status()).isEqualTo(BillStatus.UNPAID);
        assertThat(result.amount().amount()).isEqualByComparingTo("500.00");
    }

    // =========================================================================
    // §2.1 PriceLookup missing-both → 422 SERVICE_PRICE_NOT_FOUND
    // PriceLookupImpl.java step 3
    // =========================================================================

    @Test
    @Transactional
    void priceLookup_missingBoth_throws422ServicePriceNotFound() {
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        // serviceUid that has NO price rows at all
        ChargeRequest req = new ChargeRequest(
                "PATIENT-UID-009", null, null,
                ServiceKind.LAB_TEST, "NONEXISTENT-SVC-UID-00000000",
                BigDecimal.ONE, PaymentMode.CASH, false, false);

        // PriceLookupImpl throws ServicePriceNotFoundException (maps to HTTP 422
        // SERVICE_PRICE_NOT_FOUND). In tests we verify via the shared HmisException base.
        assertThatThrownBy(() -> billingCommands.recordClinicalCharge(req, ctx))
                .isInstanceOf(com.otapp.hmis.shared.error.HmisException.class)
                .satisfies(ex -> assertThat(
                        ((com.otapp.hmis.shared.error.HmisException) ex).errorCode())
                        .isEqualTo(com.otapp.hmis.shared.error.ErrorCode.SERVICE_PRICE_NOT_FOUND));
    }

    // =========================================================================
    // §2.1 Verified invoice accumulator: second COVERED charge for same patient+plan
    //      attaches to the EXISTING PENDING invoice (not a new one)
    // =========================================================================

    @Test
    @Transactional
    void pendingInvoiceAccumulator_secondCharge_attachesToExistingInvoice() throws Exception {
        String planUid = createPlanAndGetUid("BILL-IT-ACC-PROV", "BILL-IT-ACC-PLAN");
        String svc1 = createLabTestTypeAndGetUid("LTT-BILL-IT-ACC1", "Lab Acc 1");
        String svc2 = createLabTestTypeAndGetUid("LTT-BILL-IT-ACC2", "Lab Acc 2");
        seedPrice(null, "LAB_TEST", svc1, "5000.00", true);
        seedPrice(planUid, "LAB_TEST", svc1, "3000.00", true);
        seedPrice(null, "LAB_TEST", svc2, "6000.00", true);
        seedPrice(planUid, "LAB_TEST", svc2, "4000.00", true);

        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "cashier1");

        ChargeRequest req1 = new ChargeRequest("PATIENT-ACC-001", planUid, "MEM-ACC",
                ServiceKind.LAB_TEST, svc1, BigDecimal.ONE, PaymentMode.INSURANCE, false, false);
        ChargeRequest req2 = new ChargeRequest("PATIENT-ACC-001", planUid, "MEM-ACC",
                ServiceKind.LAB_TEST, svc2, BigDecimal.ONE, PaymentMode.INSURANCE, false, false);

        billingCommands.recordClinicalCharge(req1, ctx);
        billingCommands.recordClinicalCharge(req2, ctx);

        // Only ONE pending insurance invoice for (patient, plan)
        var invoices = invoiceRepository.findAllPendingForPatient("PATIENT-ACC-001");
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getDetails()).hasSize(2);
    }

    // =========================================================================
    // Helpers — seed via masterdata REST API
    // =========================================================================

    private void seedPrice(String planUid, String kind, String serviceUid,
                            String amount, boolean covered) throws Exception {
        String planVal = planUid    != null ? "\"" + planUid    + "\"" : "null";
        String svcVal  = serviceUid != null ? "\"" + serviceUid + "\"" : "null";
        String body = """
                {"planUid":%s,"kind":"%s","serviceUid":%s,"currency":"TZS",
                 "amount":%s,"covered":%b,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(planVal, kind, svcVal, amount, covered);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());
    }

    private String createPlanAndGetUid(String provCode, String planCode) throws Exception {
        String provBody = """
                {"code":"%s","name":"Provider %s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":false}
                """.formatted(provCode, provCode);
        var provResult = mockMvc.perform(post(PROVIDERS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provBody))
                .andExpect(status().isCreated())
                .andReturn();
        String provUid = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(provResult.getResponse().getContentAsString()).get("uid").asText();

        String planBody = """
                {"code":"%s","name":"Plan %s","description":null,
                 "active":false,"insuranceProviderUid":"%s"}
                """.formatted(planCode, planCode, provUid);
        var planResult = mockMvc.perform(
                        post(PROVIDERS_URL + "/uid/" + provUid + "/plans")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planBody))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(planResult.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createLabTestTypeAndGetUid(String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"price":5000.00,"active":false}
                """.formatted(code, name);
        var result = mockMvc.perform(post("/api/v1/masterdata/lab-test-types")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    /**
     * Builds a valid medicine create request. MedicineRequest has four non-blank required
     * fields: code, name, type, and category, plus a non-null price. The original test body
     * omitted type and category, causing HTTP 400. The optional unit-of-measure field is
     * named "uom" in the record (the old body used the unknown key "unit").
     */
    private String createMedicineAndGetUid(String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"type":"TABLET",\
"category":"ANALGESIC","price":100.00,"uom":"TAB","active":false}
                """.formatted(code, name);
        var result = mockMvc.perform(post("/api/v1/masterdata/medicines")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createClinicAndGetUid(String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"consultationFee":3000.00,"active":false}
                """.formatted(code, name);
        var result = mockMvc.perform(post("/api/v1/masterdata/clinics")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }
}
