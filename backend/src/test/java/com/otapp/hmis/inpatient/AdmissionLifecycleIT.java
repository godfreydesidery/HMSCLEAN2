package com.otapp.hmis.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-07 07a — Admission Lifecycle.
 *
 * <p>Drives the real vertical slice against PostgreSQL 16 (Testcontainers via
 * {@link com.otapp.hmis.support.AbstractIntegrationTest}):
 *
 * <ul>
 *   <li>Scenario 1: happy-path doAdmission → PENDING + bed WAITING + UNPAID ward bill +
 *       AdmissionBed OPENED.</li>
 *   <li>Scenario 2: DECEASED patient → 422 PATIENT_DECEASED.</li>
 *   <li>Scenario 3: second doAdmission for same patient → 422 "already admitted".</li>
 *   <li>Scenario 4: bed not EMPTY → claimBed race guard → 409 stale-entity.</li>
 *   <li>Scenario 5: publish BillSettledEvent with the ward bill uid → admission IN-PROCESS
 *       + bed OCCUPIED (settlement listener).</li>
 * </ul>
 *
 * <p>Coverage: PatientServiceImpl.java:1701-2021; PatientBillResource.java:352-365;
 * CR-07-deceased-guard; PatientResource.java:5194-5200.
 */
class AdmissionLifecycleIT extends AbstractIntegrationTest {

    private static final String WARD_CATS  = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES = "/api/v1/masterdata/ward-types";
    private static final String WARDS      = "/api/v1/masterdata/wards";
    private static final String BEDS       = "/api/v1/masterdata/beds";
    private static final String PRICES     = "/api/v1/masterdata/service-prices";
    private static final String ADMISSIONS = "/api/v1/inpatient/admissions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionRepository admissionRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("nurse",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // Scenario 1: happy-path doAdmission
    // =========================================================================

    @Test
    void doAdmission_happyPath_pendingAdmission_waitingBed_unpaidBill_openedBed()
            throws Exception {
        String tag = uniq();
        String patientUid = seedCashPatient(tag);
        String wardBedUid = seedWardWithBed(tag, "500.00");

        String body = admissionJson(patientUid, wardBedUid, "CASH", null, null);
        MvcResult res = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.patientUid").value(patientUid))
                .andExpect(jsonPath("$.wardBedUid").value(wardBedUid))
                .andExpect(jsonPath("$.paymentType").value("CASH"))
                .andReturn();

        String admUid = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();

        // Admission exists and is PENDING
        var adm = admissionRepository.findByUid(admUid).orElseThrow();
        assertThat(adm.getStatus()).isEqualTo(AdmissionStatus.PENDING);

        // AdmissionBed created and OPENED
        var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(beds).hasSize(1);
        String billUid = beds.get(0).getPatientBillUid();

        // Ward-bed bill created and UNPAID
        var bill = patientBillRepository.findByUid(billUid).orElseThrow();
        assertThat(bill.getStatus()).isEqualTo(BillStatus.UNPAID);
        assertThat(bill.getBillItem()).isEqualTo("Bed");
        assertThat(bill.getDescription()).isEqualTo("Ward Bed / Room");
        assertThat(bill.getAdmissionUid()).isEqualTo(admUid);
    }

    // =========================================================================
    // Scenario 2: DECEASED patient → 422 PATIENT_DECEASED
    // =========================================================================

    @Test
    void doAdmission_deceasedPatient_returns422PatientDeceased() throws Exception {
        String tag = uniq();
        String wardBedUid = seedWardWithBed(tag, "300.00");
        String patientUid = seedPatientWithType(tag, PatientType.DECEASED);

        mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid, wardBedUid, "CASH", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:patient-deceased"));
    }

    // =========================================================================
    // Scenario 3: second doAdmission for same patient → 422 "already admitted"
    // =========================================================================

    @Test
    void doAdmission_patientAlreadyAdmitted_returns422AlreadyAdmitted() throws Exception {
        String tag = uniq();
        String patientUid = seedCashPatient(tag);
        String bedUid1 = seedWardWithBed(tag + "A", "400.00");
        String bedUid2 = seedWardWithBed(tag + "B", "400.00");

        // First admission succeeds
        mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid, bedUid1, "CASH", null, null)))
                .andExpect(status().isCreated());

        // Second admission for the same patient → 422 with the verbatim legacy message
        mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid, bedUid2, "CASH", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        "Could not process admission. The patient is already admitted"));
    }

    // =========================================================================
    // Scenario 4: bed not EMPTY → claimBed rejects with 409 stale-entity
    // =========================================================================

    @Test
    void doAdmission_bedNotEmpty_returns409StaleEntity() throws Exception {
        String tag = uniq();
        String patientUid1 = seedCashPatient(tag + "P1");
        String patientUid2 = seedCashPatient(tag + "P2");
        String wardBedUid = seedWardWithBed(tag, "350.00");

        // First admission claims the bed → WAITING
        mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid1, wardBedUid, "CASH", null, null)))
                .andExpect(status().isCreated());

        // Second attempt on the same (now WAITING) bed → 409
        mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid2, wardBedUid, "CASH", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:stale-entity"));
    }

    // =========================================================================
    // Scenario 5: pay the ward-bed bill via the cashier endpoint → admission
    //             IN-PROCESS + bed OCCUPIED (settlement listener BEFORE_COMMIT seam)
    //
    // NOTE: this test must NOT be @Transactional. The AdmissionSettlementListener uses
    // @TransactionalEventListener(phase = BEFORE_COMMIT) which fires as part of the
    // BILLING payment transaction's commit sequence. If this test were @Transactional,
    // Spring would wrap everything in one rolled-back tx and the listener would never fire.
    // The pattern mirrors SettlementSeamIT (same rationale — see SettlementSeamIT.java:53-68).
    // =========================================================================

    @Test
    void doAdmission_thenPayBill_activatesAdmission_occupiesBed() throws Exception {
        String tag = uniq();
        String patientUid = seedCashPatient(tag);
        String wardBedUid = seedWardWithBed(tag, "600.00");

        // Step 1: Admit — doAdmission commits in its own @Transactional; result is PENDING.
        MvcResult res = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid, wardBedUid, "CASH", null, null)))
                .andExpect(status().isCreated()).andReturn();
        String admUid = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();

        // Step 2: Resolve the ward-bed bill uid from the committed AdmissionBed row.
        var admBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(admBeds).hasSize(1);
        String billUid = admBeds.get(0).getPatientBillUid();

        // Step 3: Look up the bill amount so we can tender the exact amount.
        var bill = patientBillRepository.findByUid(billUid).orElseThrow();
        String amount = bill.amountValue().toPlainString();

        // Step 4: Pay the ward-bed bill via the cashier endpoint.
        // PaymentService commits its own @Transactional tx; inside that tx BEFORE_COMMIT
        // fires: BillSettledEvent → AdmissionSettlementListener activates the admission and
        // occupies the bed atomically with the PAID transition.
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(billUid, amount);
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isCreated());

        // Step 5: Reload and assert admission is now IN-PROCESS.
        var adm = admissionRepository.findByUid(admUid).orElseThrow();
        assertThat(adm.getStatus())
                .as("Admission must activate IN-PROCESS once the ward-bed bill is paid")
                .isEqualTo(AdmissionStatus.IN_PROCESS);
    }

    // =========================================================================
    // Auth guard
    // =========================================================================

    @Test
    void doAdmission_noToken_returns401() throws Exception {
        mockMvc.perform(post(ADMISSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson("X", "Y", "CASH", null, null)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Read surface (inc-07): GET /admissions list + GET /admissions/uid/{uid}
    // =========================================================================

    @Test
    void getAdmissionByUid_returnsTheAdmission() throws Exception {
        String tag = uniq();
        String patientUid = seedCashPatient(tag);
        String wardBedUid = seedWardWithBed(tag, "500.00");
        String admUid = createAdmission(patientUid, wardBedUid);

        mockMvc.perform(get(ADMISSIONS + "/uid/" + admUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(admUid))
                .andExpect(jsonPath("$.patientUid").value(patientUid))
                .andExpect(jsonPath("$.wardBedUid").value(wardBedUid))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getAdmissionByUid_unknown_returns404() throws Exception {
        mockMvc.perform(get(ADMISSIONS + "/uid/NOSUCHADMISSION0000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAdmissions_returnsCreatedAdmission_andStatusFilterWorks() throws Exception {
        String tag = uniq();
        String patientUid = seedCashPatient(tag);
        String wardBedUid = seedWardWithBed(tag, "500.00");
        String admUid = createAdmission(patientUid, wardBedUid);

        // Unfiltered list contains the new admission
        mockMvc.perform(get(ADMISSIONS).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.uid == '" + admUid + "')]").exists());

        // PENDING filter contains it; SIGNED-OUT filter does not
        mockMvc.perform(get(ADMISSIONS).param("status", "PENDING")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.uid == '" + admUid + "')]").exists());

        mockMvc.perform(get(ADMISSIONS).param("status", "SIGNED-OUT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.uid == '" + admUid + "')]").doesNotExist());
    }

    @Test
    void listAdmissions_unknownStatus_returns404() throws Exception {
        mockMvc.perform(get(ADMISSIONS).param("status", "BOGUS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** Create a PENDING admission and return its uid (reuses the happy-path POST). */
    private String createAdmission(String patientUid, String wardBedUid) throws Exception {
        MvcResult res = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(admissionJson(patientUid, wardBedUid, "CASH", null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private static String uniq() {
        return "A7" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
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
     * Seed a CASH OUTPATIENT patient directly via the repository (no REST round-trip needed
     * for the admission test — we only care the patient uid exists in the DB).
     */
    private String seedCashPatient(String tag) {
        String searchKey = "07aIT" + tag;
        Patient patient = new Patient(
                null,           // no (MRN) — assigned later; not required for admission test
                searchKey,
                "Test07a",
                tag,
                "IT",
                LocalDate.of(1990, 1, 1),
                "M",
                PatientType.OUTPATIENT,
                PaymentType.CASH,
                "",
                null,
                null,
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /** Seed a patient with an explicit type (used for the DECEASED guard scenario). */
    private String seedPatientWithType(String tag, PatientType type) {
        String searchKey = "07aIT" + tag + "D";
        Patient patient = new Patient(
                null,
                searchKey,
                "Dead07a",
                tag,
                "IT",
                LocalDate.of(1980, 6, 15),
                "F",
                type,
                PaymentType.CASH,
                "",
                null,
                null,
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /**
     * Seed a ward category + ward type (with price) + ward + ward bed via the REST API.
     * Returns the ward bed uid.
     */
    private String seedWardWithBed(String tag, String price) throws Exception {
        // Ward category
        String catBody = """
                {"code":"WC07-%s","name":"Ward Cat 07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward type with the given price
        String typeBody = """
                {"code":"WT07-%s","name":"Ward Type 07 %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, price);
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        // Seed the service-price for WARD kind so BillingChargeService can resolve the price
        String priceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, price);
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isCreated());

        // Ward
        String wardBody = """
                {"code":"WD07-%s","name":"Ward 07 %s","noOfBeds":5,"active":true,
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
                {"no":"B07-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    private static String admissionJson(String patientUid, String wardBedUid,
                                        String paymentType,
                                        String insurancePlanUid, String membershipNo) {
        String plan = insurancePlanUid != null ? "\"" + insurancePlanUid + "\"" : "null";
        String memNo = membershipNo != null ? "\"" + membershipNo + "\"" : "null";
        return """
                {"patientUid":"%s","wardBedUid":"%s","paymentType":"%s",
                 "insurancePlanUid":%s,"membershipNo":%s}
                """.formatted(patientUid, wardBedUid, paymentType, plan, memNo);
    }
}
