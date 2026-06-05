package com.otapp.hmis.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-07 07a-2 — Insurance ward-billing path + top-up split.
 *
 * <p>Drives the INSURANCE admission path through the real vertical slice against
 * PostgreSQL 16 (Testcontainers via {@link AbstractIntegrationTest}).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li><strong>Scenario A (diff &gt; 0 — top-up):</strong> INSURANCE admit where covered price
 *       &lt; cash ward price → COVERED principal at covered price + UNPAID top-up at
 *       (cash - covered) + bidirectional principal↔supplementary linkage + admission PENDING +
 *       bed WAITING. Then pay the top-up → admission IN-PROCESS + bed OCCUPIED.</li>
 *   <li><strong>Scenario B (diff == 0 — no top-up):</strong> INSURANCE admit where covered
 *       price equals cash ward price → COVERED principal only, NO top-up bill, admission
 *       IN-PROCESS + bed OCCUPIED AT ADMIT (activate-at-admit branch).</li>
 *   <li><strong>Scenario C (diff &gt; 0, just the admit):</strong> extended validation of the
 *       created bills — covers the principal/supplementary self-link in both directions.</li>
 * </ul>
 *
 * <p>Seed convention: ward, ward-type, and service_prices (WARD kind) are seeded via the REST
 * API at test scope (no production migration changes — production seed convention preserves
 * existing pattern of no test fixtures in migrations). The test seeds TWO service_prices rows
 * per ward type: a cash row (plan_uid NULL) at the cash price AND a covered insurance row
 * (plan_uid = the test plan) at a LOWER covered price.
 *
 * <p>Option B / CR-07-WARD-INS-PRICE implementation: insurance covered price is keyed on the
 * admitted bed's ward type (PriceLookup.resolve(planUid, WARD, wardTypeUid)). The top-up split
 * is load-bearing when diff &gt; 0. See
 * docs/delivery/increments/07-inpatient-discovery/06-AMBIGUITY-WARD-INSURANCE-PRICE.md.
 *
 * <p>Legacy citations: PatientServiceImpl.java:1795-1965 (INSURANCE doAdmission branch);
 * PatientBillResource.java:352-365 (payment activation); PatientBill.java:65-73
 * (principal/supplementary self-link). CR-07-WARD-INS-PRICE Option B ratified 2026-06-05.
 */
class WardInsuranceBillingIT extends AbstractIntegrationTest {

    // REST paths
    private static final String WARD_CATS   = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES  = "/api/v1/masterdata/ward-types";
    private static final String WARDS       = "/api/v1/masterdata/wards";
    private static final String BEDS        = "/api/v1/masterdata/beds";
    private static final String PRICES      = "/api/v1/masterdata/service-prices";
    private static final String ADMISSIONS  = "/api/v1/inpatient/admissions";
    private static final String PROVIDERS   = "/api/v1/masterdata/insurance-providers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionRepository admissionRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;
    @Autowired ConsultationRepository consultationRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("nurse",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // Scenario A: INSURANCE admit, covered < cash → top-up bill created
    //             admission PENDING + bed WAITING; pay top-up → IN-PROCESS + OCCUPIED
    //
    // NOTE: must NOT be @Transactional — BEFORE_COMMIT listener fires inside the
    // billing payment tx; a wrapping test-tx would prevent it from running.
    // =========================================================================

    @Test
    void insuranceAdmit_coveredLessThanCash_topUpCreated_admissionPending_thenPayTopUp_activates()
            throws Exception {

        String tag = uniq();
        // cash price 1000, covered price 600 → diff = 400
        BigDecimal cashPrice    = new BigDecimal("1000.00");
        BigDecimal coveredPrice = new BigDecimal("600.00");
        BigDecimal expectedDiff = new BigDecimal("400.00");

        // ---- Seed insurance plan ----
        String planUid = seedInsurancePlan(tag);

        // ---- Seed ward + bed + service_prices (cash row + covered insurance row) ----
        String wardBedUid = seedWardWithBedAndInsurancePrice(tag, cashPrice, planUid, coveredPrice);

        // ---- Seed INSURANCE patient ----
        String patientUid = seedInsurancePatient(tag, planUid);

        // ---- POST admission (INSURANCE) ----
        String admBody = admissionJson(patientUid, wardBedUid, "INSURANCE", planUid, "MEM-" + tag);
        MvcResult admRes = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(admBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentType").value("INSURANCE"))
                .andReturn();

        String admUid = objectMapper.readTree(admRes.getResponse().getContentAsString())
                .get("uid").asText();

        // ---- Verify admission is PENDING ----
        var adm = admissionRepository.findByUid(admUid).orElseThrow();
        assertThat(adm.getStatus())
                .as("INSURANCE admission with top-up must stay PENDING until top-up is paid")
                .isEqualTo(AdmissionStatus.PENDING);

        // ---- Verify AdmissionBed is OPENED ----
        var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(beds).hasSize(1);
        String settlementBillUid = beds.get(0).getPatientBillUid();

        // ---- Resolve the top-up bill (AdmissionBed.patientBillUid points to the top-up) ----
        var topUpBill = patientBillRepository.findByUid(settlementBillUid).orElseThrow();

        // Top-up bill assertions
        assertThat(topUpBill.getStatus())
                .as("Top-up bill must be UNPAID")
                .isEqualTo(BillStatus.UNPAID);
        assertThat(topUpBill.getBillItem())
                .as("Top-up billItem must be 'Bed' — verbatim PatientServiceImpl.java:1889")
                .isEqualTo("Bed");
        assertThat(topUpBill.getDescription())
                .as("Top-up description must be 'Ward Bed / Room (Top up)' — verbatim :1890")
                .isEqualTo("Ward Bed / Room (Top up)");
        assertThat(topUpBill.amountValue())
                .as("Top-up amount must equal cash - covered = " + expectedDiff)
                .isEqualByComparingTo(expectedDiff);
        assertThat(topUpBill.getAdmissionUid())
                .as("Top-up bill must be linked to the admission (for discharge gate)")
                .isEqualTo(admUid);

        // ---- Resolve the COVERED principal bill by its uid (stored on the top-up's FK column).
        //      PatientBill self-links are FetchType.LAZY — traverse by reloading via uid rather
        //      than accessing the lazy proxy outside a session.
        // ----
        // The top-up's principalBill FK column (principal_bill_id) is not directly accessible as a
        // uid from outside a session. Use the BillingQueries approach: find the COVERED bill for
        // this admission and patient that is NOT the top-up bill.
        var allAdmissionBills = patientBillRepository.findByPatientUid(patientUid);

        // The principal bill is COVERED and linked to this admission
        var principalBillOpt = allAdmissionBills.stream()
                .filter(b -> b.getStatus() == BillStatus.COVERED
                          && admUid.equals(b.getAdmissionUid()))
                .findFirst();
        assertThat(principalBillOpt)
                .as("COVERED principal ward bill must exist for this admission")
                .isPresent();
        var principalBill = principalBillOpt.get();

        assertThat(principalBill.amountValue())
                .as("Principal covered amount must equal the plan-covered price")
                .isEqualByComparingTo(coveredPrice);
        assertThat(principalBill.getBillItem())
                .as("Principal billItem must be 'Bed'")
                .isEqualTo("Bed");
        assertThat(principalBill.getDescription())
                .as("Principal description must be 'Ward Bed / Room'")
                .isEqualTo("Ward Bed / Room");

        // ---- Verify principal.supplementaryBill → top-up (bidirectional link).
        //      supplementaryBill is also LAZY — reload the principal fresh to get the FK id.
        //      The FK column supplementary_bill_id is set by linkSupplementaryBill; reload to
        //      access the linked proxy uid via a fresh session-bound findByUid call.
        // ----
        // Access supplementaryBill through a query rather than the lazy proxy.
        var supplementaryBills = patientBillRepository.findByPatientUid(patientUid).stream()
                .filter(b -> b.getStatus() == BillStatus.UNPAID
                          && "Ward Bed / Room (Top up)".equals(b.getDescription())
                          && admUid.equals(b.getAdmissionUid()))
                .toList();
        assertThat(supplementaryBills)
                .as("Exactly one UNPAID top-up bill must exist for this admission")
                .hasSize(1);
        assertThat(supplementaryBills.get(0).getUid())
                .as("The top-up bill found via description must be the same as AdmissionBed.patientBillUid")
                .isEqualTo(topUpBill.getUid());

        // ---- Pay the top-up bill → admission must activate IN-PROCESS + bed OCCUPIED ----
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(settlementBillUid, expectedDiff.toPlainString());
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());

        // ---- Verify admission now IN-PROCESS after top-up payment ----
        var admAfterPay = admissionRepository.findByUid(admUid).orElseThrow();
        assertThat(admAfterPay.getStatus())
                .as("Admission must activate IN-PROCESS once the top-up bill is paid")
                .isEqualTo(AdmissionStatus.IN_PROCESS);
    }

    // =========================================================================
    // Scenario B: INSURANCE admit, covered == cash → no top-up, activate at admit
    //             admission must be IN-PROCESS + bed OCCUPIED immediately at doAdmission.
    // =========================================================================

    @Test
    void insuranceAdmit_coveredEqualsCash_noTopUp_activatesAtAdmit() throws Exception {

        String tag = uniq();
        // cash price == covered price → diff = 0
        BigDecimal cashPrice    = new BigDecimal("750.00");
        BigDecimal coveredPrice = new BigDecimal("750.00");

        String planUid    = seedInsurancePlan(tag);
        String wardBedUid = seedWardWithBedAndInsurancePrice(tag, cashPrice, planUid, coveredPrice);
        String patientUid = seedInsurancePatient(tag, planUid);

        // ---- Pre-seed an open PENDING consultation (inc-07 07a-2 sign-out side-effect) ----
        // Legacy PatientServiceImpl.java:1951-1958: PENDING + IN_PROCESS consultations are
        // signed out when the insurance no-top-up admit activates at admit.
        String pendingConsultationUid = seedConsultation(patientUid, ConsultationStatus.PENDING);

        String admBody = admissionJson(patientUid, wardBedUid, "INSURANCE", planUid, "MEM2-" + tag);
        MvcResult admRes = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(admBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentType").value("INSURANCE"))
                .andReturn();

        String admUid = objectMapper.readTree(admRes.getResponse().getContentAsString())
                .get("uid").asText();

        // ---- Admission must be IN-PROCESS at admit (activate-at-admit branch) ----
        var adm = admissionRepository.findByUid(admUid).orElseThrow();
        assertThat(adm.getStatus())
                .as("INSURANCE no-top-up admission must be IN-PROCESS at admit immediately")
                .isEqualTo(AdmissionStatus.IN_PROCESS);

        // ---- AdmissionBed is OPENED ----
        var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(beds).hasSize(1);
        String principalBillUid = beds.get(0).getPatientBillUid();

        // ---- Only the COVERED principal bill exists — no top-up bill ----
        var principalBill = patientBillRepository.findByUid(principalBillUid).orElseThrow();
        assertThat(principalBill.getStatus())
                .as("Principal bill must be COVERED")
                .isEqualTo(BillStatus.COVERED);
        assertThat(principalBill.amountValue())
                .as("Principal covered amount must equal the plan-covered price (== cash)")
                .isEqualByComparingTo(coveredPrice);

        // ---- No supplementary bill on the principal ----
        assertThat(principalBill.getSupplementaryBill())
                .as("No-top-up principal must have supplementaryBill == null")
                .isNull();

        // ---- No UNPAID ward-type bills exist for this admission (no top-up) ----
        var unpaidBills = patientBillRepository
                .findByPatientUidAndStatusIn(patientUid, List.of(BillStatus.UNPAID));
        boolean anyWardTopUp = unpaidBills.stream()
                .anyMatch(b -> "Ward Bed / Room (Top up)".equals(b.getDescription())
                               && admUid.equals(b.getAdmissionUid()));
        assertThat(anyWardTopUp)
                .as("No 'Ward Bed / Room (Top up)' UNPAID bill should exist for the no-top-up case")
                .isFalse();

        // ---- SIGN-OUT side-effect: pre-seeded PENDING consultation must now be SIGNED_OUT ----
        // Reproduces PatientServiceImpl.java:1951-1958 (inc-07 07a-2):
        // consultationSignOut.signOutOpenConsultations is called in AdmissionService before
        // admission.activate() when diff <= 0 (no-top-up insurance activate-at-admit).
        var signedOutConsultation = consultationRepository.findByUid(pendingConsultationUid)
                .orElseThrow();
        assertThat(signedOutConsultation.getStatus())
                .as("Open PENDING consultation must be SIGNED_OUT after insurance no-top-up admit "
                        + "(PatientServiceImpl.java:1951-1958)")
                .isEqualTo(ConsultationStatus.SIGNED_OUT);
    }

    // =========================================================================
    // Scenario D: CASH admit → pay ward bill → IN_PROCESS + IN_PROCESS consultation
    //             signed out via AdmissionSettlementListener (PatientBillResource.java:353-364).
    //             Only IN_PROCESS (not PENDING) consultations are signed out on the cash path.
    //
    // NOTE: must NOT be @Transactional — BEFORE_COMMIT listener fires inside billing tx.
    // =========================================================================

    @Test
    void cashAdmit_payWardBill_signsOutInProcessConsultation_notPending() throws Exception {

        String tag = uniq();
        BigDecimal wardPrice = new BigDecimal("500.00");

        // ---- Seed ward + bed (cash path — no insurance plan needed for the CASH ward price) ----
        String wardBedUid = seedCashWard(tag, wardPrice);

        // ---- Seed CASH patient ----
        String patientUid = seedCashPatient(tag);

        // ---- Pre-seed one IN_PROCESS and one PENDING consultation ----
        // Cash path: PatientBillResource.java:353 uses findAllByPatientAndStatus(patient,"IN-PROCESS")
        // — only IN_PROCESS is signed out; PENDING is left untouched.
        String inProcessUid = seedConsultation(patientUid, ConsultationStatus.IN_PROCESS);
        String pendingUid   = seedConsultation(patientUid, ConsultationStatus.PENDING);

        // ---- POST admission (CASH) ----
        String admBody = admissionJson(patientUid, wardBedUid, "CASH", null, null);
        MvcResult admRes = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(admBody))
                .andExpect(status().isCreated())
                .andReturn();

        String admUid = objectMapper.readTree(admRes.getResponse().getContentAsString())
                .get("uid").asText();

        // Admission is PENDING (cash ward bill unpaid)
        assertThat(admissionRepository.findByUid(admUid).orElseThrow().getStatus())
                .isEqualTo(AdmissionStatus.PENDING);

        // ---- Consultations still open before payment ----
        assertThat(consultationRepository.findByUid(inProcessUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.IN_PROCESS);
        assertThat(consultationRepository.findByUid(pendingUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.PENDING);

        // ---- Pay the CASH ward bill to activate the admission ----
        String wardBillUid = admissionBedRepository
                .findAllByAdmissionUidAndStatus(admUid, "OPENED").get(0).getPatientBillUid();
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(wardBillUid, wardPrice.toPlainString());
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());

        // ---- Admission must now be IN-PROCESS ----
        assertThat(admissionRepository.findByUid(admUid).orElseThrow().getStatus())
                .as("CASH admission must activate IN-PROCESS after ward bill payment")
                .isEqualTo(AdmissionStatus.IN_PROCESS);

        // ---- IN_PROCESS consultation signed out (PatientBillResource.java:353-364) ----
        assertThat(consultationRepository.findByUid(inProcessUid).orElseThrow().getStatus())
                .as("IN_PROCESS consultation must be SIGNED_OUT after cash ward-bill payment "
                        + "(PatientBillResource.java:353-364)")
                .isEqualTo(ConsultationStatus.SIGNED_OUT);

        // ---- PENDING consultation NOT signed out (cash path is IN_PROCESS-only) ----
        assertThat(consultationRepository.findByUid(pendingUid).orElseThrow().getStatus())
                .as("PENDING consultation must remain PENDING on cash path — cash sign-out is "
                        + "IN_PROCESS-only (PatientBillResource.java:353)")
                .isEqualTo(ConsultationStatus.PENDING);
    }

    // =========================================================================
    // Scenario C: extended principal↔supplementary link validation (top-up case)
    // Validates the bidirectional link consistency with different cash/covered values.
    // =========================================================================

    @Test
    void insuranceAdmit_topUpCase_principalSupplementaryLinkIsSymmetric() throws Exception {

        String tag = uniq();
        // cash 2000, covered 1200 → diff 800
        BigDecimal cashPrice    = new BigDecimal("2000.00");
        BigDecimal coveredPrice = new BigDecimal("1200.00");
        BigDecimal expectedDiff = new BigDecimal("800.00");

        String planUid   = seedInsurancePlan(tag);
        String wardBedUid = seedWardWithBedAndInsurancePrice(tag, cashPrice, planUid, coveredPrice);
        String patientUid = seedInsurancePatient(tag, planUid);

        String admBody = admissionJson(patientUid, wardBedUid, "INSURANCE", planUid, "MEM3-" + tag);
        MvcResult admRes = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(admBody))
                .andExpect(status().isCreated())
                .andReturn();

        String admUid = objectMapper.readTree(admRes.getResponse().getContentAsString())
                .get("uid").asText();

        // Resolve bills
        var admBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(admBeds).hasSize(1);
        String topUpUid = admBeds.get(0).getPatientBillUid();

        var topUpBill = patientBillRepository.findByUid(topUpUid).orElseThrow();
        assertThat(topUpBill.amountValue()).isEqualByComparingTo(expectedDiff);

        // Self-links are FetchType.LAZY — avoid traversing the proxy outside a session.
        // Instead resolve the related bills by uid using independent findByUid calls.
        // The COVERED principal bill: find by admission + COVERED status
        var allBills = patientBillRepository.findByPatientUid(patientUid);

        var principalOpt = allBills.stream()
                .filter(b -> b.getStatus() == BillStatus.COVERED
                          && admUid.equals(b.getAdmissionUid()))
                .findFirst();
        assertThat(principalOpt).as("COVERED principal bill must exist").isPresent();
        var principal = principalOpt.get();

        assertThat(principal.amountValue()).isEqualByComparingTo(coveredPrice);
        assertThat(principal.getStatus()).isEqualTo(BillStatus.COVERED);

        // Symmetric link: find the top-up that describes itself as a "Ward Bed / Room (Top up)"
        // bill linked to this admission. Must be the same uid as AdmissionBed.patientBillUid.
        var topUpFromList = allBills.stream()
                .filter(b -> b.getStatus() == BillStatus.UNPAID
                          && "Ward Bed / Room (Top up)".equals(b.getDescription())
                          && admUid.equals(b.getAdmissionUid()))
                .findFirst();
        assertThat(topUpFromList)
                .as("UNPAID top-up bill must exist for this admission")
                .isPresent();
        assertThat(topUpFromList.get().getUid())
                .as("principal.supplementaryBill.uid must == topUp.uid (symmetric link)")
                .isEqualTo(topUpBill.getUid());

        // Round-trip: the top-up found via the list must match the one from AdmissionBed
        assertThat(topUpFromList.get().getUid())
                .as("topUp uid round-trip consistency")
                .isEqualTo(topUpUid);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String uniq() {
        return "I7" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    /**
     * Seed an insurance provider + plan; returns the plan uid.
     * Mirrors the pattern from InsurancePlanIT (inc-09).
     */
    private String seedInsurancePlan(String tag) throws Exception {
        // Provider
        String provBody = """
                {"code":"IPROV-07-%s","name":"Prov 07ins %s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":true}
                """.formatted(tag, tag);
        MvcResult pr = mockMvc.perform(post(PROVIDERS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(provBody))
                .andExpect(status().isCreated()).andReturn();
        String provUid = objectMapper.readTree(pr.getResponse().getContentAsString())
                .get("uid").asText();

        // Plan under provider
        String planBody = """
                {"code":"IPLAN-07-%s","name":"Plan 07ins %s","description":null,
                 "active":true,"insuranceProviderUid":"%s"}
                """.formatted(tag, tag, provUid);
        MvcResult planRes = mockMvc.perform(post(PROVIDERS + "/uid/" + provUid + "/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(planRes.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Seed ward-category + ward-type (cashPrice) + ward + bed + service_prices:
     * a cash row (planUid=null, amount=cashPrice) AND a covered insurance row
     * (planUid=planUid, amount=coveredPrice, covered=true).
     * Returns the ward bed uid.
     */
    private String seedWardWithBedAndInsurancePrice(String tag, BigDecimal cashPrice,
                                                    String planUid, BigDecimal coveredPrice)
            throws Exception {
        // Ward category
        String catBody = """
                {"code":"WCI7-%s","name":"WCat Ins 07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward type (price = cash price)
        String typeBody = """
                {"code":"WTI7-%s","name":"WType Ins 07 %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, cashPrice.toPlainString());
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        // service_prices: CASH row (planUid = null, amount = cashPrice, covered = true)
        // PriceLookup.resolve(null, WARD, wardTypeUid) → cash row for CASH patients
        String cashPriceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, cashPrice.toPlainString());
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(cashPriceBody))
                .andExpect(status().isCreated());

        // service_prices: COVERED insurance row (planUid = planUid, amount = coveredPrice)
        // PriceLookup.resolve(planUid, WARD, wardTypeUid) → covered plan row for INSURANCE patients
        // Option B / CR-07-WARD-INS-PRICE: keyed on wardTypeUid (the admitted bed's ward type)
        String insPriceBody = """
                {"planUid":"%s","kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(planUid, typeUid, coveredPrice.toPlainString());
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(insPriceBody))
                .andExpect(status().isCreated());

        // Ward
        String wardBody = """
                {"code":"WDI7-%s","name":"Ward Ins 07 %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward bed (active, EMPTY)
        String bedBody = """
                {"no":"BDI7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Seed an INSURANCE OUTPATIENT patient directly via the repository.
     * The patient carries the insurancePlanUid and paymentType=INSURANCE.
     */
    private String seedInsurancePatient(String tag, String planUid) {
        String searchKey = "07aInsIT" + tag;
        // Constructor: no, searchKey, firstName, middleName, lastName, dob, gender,
        //              type, paymentType, membershipNo, phoneNo, insurancePlanUid, dayUid
        Patient patient = new Patient(
                null,
                searchKey,
                "Ins07a",
                tag,
                "IT",
                LocalDate.of(1985, 3, 20),
                "M",
                PatientType.OUTPATIENT,
                PaymentType.INSURANCE,
                "MEM-" + tag,   // membershipNo
                null,           // phoneNo
                planUid,        // insurancePlanUid
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    private static String admissionJson(String patientUid, String wardBedUid,
                                        String paymentType,
                                        String insurancePlanUid, String membershipNo) {
        String plan  = insurancePlanUid != null ? "\"" + insurancePlanUid + "\"" : "null";
        String memNo = membershipNo    != null ? "\"" + membershipNo    + "\"" : "null";
        return """
                {"patientUid":"%s","wardBedUid":"%s","paymentType":"%s",
                 "insurancePlanUid":%s,"membershipNo":%s}
                """.formatted(patientUid, wardBedUid, paymentType, plan, memNo);
    }

    /**
     * Seed a CASH OUTPATIENT patient directly via the repository.
     */
    private String seedCashPatient(String tag) {
        Patient patient = new Patient(
                null,
                "07aCashIT" + tag,
                "Cash07a",
                tag,
                "IT",
                LocalDate.of(1990, 6, 15),
                "F",
                PatientType.OUTPATIENT,
                PaymentType.CASH,
                null,   // membershipNo
                null,   // phoneNo
                null,   // insurancePlanUid
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /**
     * Seed a CASH ward: ward-category + ward-type (price) + ward + bed + cash service_price.
     * Returns the ward bed uid.
     *
     * <p>Used by Scenario D (cash-admit path) which does not need an insurance covered price.
     */
    private String seedCashWard(String tag, BigDecimal price) throws Exception {
        // Ward category
        String catBody = """
                {"code":"WCC7-%s","name":"WCat Cash 07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward type (price = cash price)
        String typeBody = """
                {"code":"WTC7-%s","name":"WType Cash 07 %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, price.toPlainString());
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        // service_prices: CASH row (planUid = null)
        String priceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, price.toPlainString());
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isCreated());

        // Ward
        String wardBody = """
                {"code":"WDC7-%s","name":"Ward Cash 07 %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward bed (active, EMPTY)
        String bedBody = """
                {"no":"BDC7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Seed a {@link Consultation} directly via the repository with the given initial status.
     *
     * <p>Used by Scenario B and D to pre-seed open consultations so we can assert the
     * sign-out side-effect on admission activation. Clinic uid, clinician uid, and bill uid
     * are synthetic test values — only the patientUid and status matter for these assertions.
     *
     * @param patientUid the patient whose consultation should be created
     * @param status     the initial status ({@link ConsultationStatus#PENDING} or
     *                   {@link ConsultationStatus#IN_PROCESS})
     * @return the uid of the saved consultation
     */
    private String seedConsultation(String patientUid, ConsultationStatus status) {
        // Use the business constructor; clinic/clinician/bill uids are synthetic for test only.
        Consultation c = new Consultation(
                patientUid,
                null,                           // visitUid — nullable
                "CLINIC-SEED-" + uniq(),        // clinicUid (synthetic)
                "CLINICIAN-SEED-" + uniq(),     // clinicianUserUid (synthetic)
                "BILL-SEED-" + uniq(),          // patientBillUid (synthetic)
                PaymentMode.CASH,               // paymentMode
                false,                          // followUp
                true,                           // settled (avoid payment-gate blocking)
                null,                           // membershipNo
                null,                           // insurancePlanUid
                dayUid);                        // businessDayUid
        // The business constructor always sets status = PENDING.
        // If the test needs IN_PROCESS, advance it via the domain method.
        if (status == ConsultationStatus.IN_PROCESS) {
            c.open();
        }
        return consultationRepository.saveAndFlush(c).getUid();
    }
}
