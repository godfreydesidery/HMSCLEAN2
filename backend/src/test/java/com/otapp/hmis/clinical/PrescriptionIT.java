package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-05 C10: Prescription aggregate + dispense + batches.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Prescribe on consultation → 201 NOT-GIVEN + bill created + alerts=[] + balance=qty.</li>
 *   <li>Duplicate medicine same consultation → 422 verbatim message.</li>
 *   <li>Duplicate medicine on non-consultation (OUTSIDER) → 422 (CR-INC05-05 corrected check).</li>
 *   <li>Issue NOT-GIVEN → GIVEN with issued==qty (balance→0, status GIVEN, approved_* set).</li>
 *   <li>Issue with issued != qty → 422 "You can only issue the prescribed qty".</li>
 *   <li>Issue with issued > qty → 422 "Invalid issue value".</li>
 *   <li>Issue on already-GIVEN → 422 "not a pending prescription".</li>
 *   <li>Delete NOT-GIVEN → 204 ok.</li>
 *   <li>Delete GIVEN → 422 "Only a pending prescription can be deleted".</li>
 *   <li>Status converter: NOT_GIVEN serialised as "NOT-GIVEN", GIVEN as "GIVEN".</li>
 *   <li>Pharmacy worklist returns NOT-GIVEN prescriptions.</li>
 *   <li>settled=true for INSURANCE; settled=false for CASH.</li>
 *   <li>OUTSIDER via walk-in (non-consultation) → 201 NOT-GIVEN.</li>
 *   <li>Add batch → 201; list batches → 200.</li>
 *   <li>401 on all key endpoints without a token.</li>
 *   <li>V36 schema: patient_id dropped, patient_uid added, settled added on prescriptions.</li>
 *   <li>Module boundaries respected (Spring Modulith rules enforced by the build).</li>
 * </ol>
 */
class PrescriptionIT extends AbstractIntegrationTest {

    private static final String BASE         = "/api/v1/clinical";
    private static final String CONSULT_BASE = BASE + "/consultations/uid/";
    private static final String NC_BASE      = BASE + "/non-consultations/uid/";
    private static final String RX_BASE      = BASE + "/prescriptions";
    private static final String MEDICINES_URL = "/api/v1/masterdata/medicines";
    private static final String PROVIDERS_URL = "/api/v1/masterdata/insurance-providers";
    private static final String PRICES_URL    = "/api/v1/masterdata/service-prices";

    @Autowired MockMvc                   mockMvc;
    @Autowired ObjectMapper              objectMapper;
    @Autowired TestJwtFactory            jwtFactory;
    @Autowired ConsultationRepository    consultationRepository;
    @Autowired NonConsultationRepository nonConsultationRepository;
    @Autowired PrescriptionRepository    prescriptionRepository;
    @Autowired BusinessDayService        businessDayService;
    @Autowired DataSource                dataSource;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // Prescribe on consultation — CASH → 201 NOT-GIVEN + balance=qty + alerts=[]
    // =========================================================================

    @Test
    void prescribe_cashConsultation_201_notGiven_balanceEqQty_alertsEmpty() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "2000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "2.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"))
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.medicineUid").value(medUid))
                .andExpect(jsonPath("$.patientBillUid").isNotEmpty())
                .andExpect(jsonPath("$.qty").value(2.0))
                .andExpect(jsonPath("$.issued").value(0.0))
                .andExpect(jsonPath("$.balance").value(2.0))
                .andExpect(jsonPath("$.alerts").isArray())
                .andExpect(jsonPath("$.alerts.length()").value(0))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String rxUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(rxUid).isNotBlank();
    }

    // =========================================================================
    // Prescribe INSURANCE → settled=true
    // =========================================================================

    @Test
    void prescribe_insuranceConsultation_settledTrue() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        String planUid = createPlan(tag);
        seedPrice(null,    "MEDICINE", medUid, "2000.00", true);
        seedPrice(planUid, "MEDICINE", medUid, "1500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);

        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "1.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"))
                .andExpect(jsonPath("$.settled").value(true));
    }

    // =========================================================================
    // Duplicate medicine same consultation → 422
    // =========================================================================

    @Test
    void prescribe_duplicateMedicineOnConsultation_422() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // First prescription succeeds
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/prescriptions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescribeBody(medUid, "1.0")))
                .andExpect(status().isCreated());

        // Second prescription — same medicine, same consultation → 422
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/prescriptions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescribeBody(medUid, "1.0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate medicine is not allowed for this encounter"));
    }

    // =========================================================================
    // Duplicate medicine on non-consultation (OUTSIDER) → 422 — CR-INC05-05 corrected check
    // =========================================================================

    @Test
    void prescribe_duplicateMedicineOnNonConsultation_422_cr_inc05_05() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1200.00", true);
        String ncUid = seedNonConsultation(tag);

        // First prescription on non-consultation succeeds
        mockMvc.perform(post(NC_BASE + ncUid + "/prescriptions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescribeBody(medUid, "3.0")))
                .andExpect(status().isCreated());

        // Second prescription — same medicine, same non-consultation → 422 (CR-INC05-05)
        // This is the CORRECTED check. The legacy code would have NPE'd here.
        mockMvc.perform(post(NC_BASE + ncUid + "/prescriptions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescribeBody(medUid, "3.0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate medicine is not allowed for this encounter"));
    }

    // =========================================================================
    // Unknown medicine → 404
    // =========================================================================

    @Test
    void prescribe_unknownMedicine_404() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(post(CONSULT_BASE + consultUid + "/prescriptions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescribeBody("UNKNOWNMED00000000000000001", "1.0")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Medicine not found"));
    }

    // =========================================================================
    // Issue NOT-GIVEN → GIVEN with issued==qty
    // =========================================================================

    @Test
    void issue_notGivenToGiven_issuedEqQty_approved_audit() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "3000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "5.0");

        mockMvc.perform(
                        post(RX_BASE + "/uid/" + rxUid + "/issue")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(issueBody("5.0", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GIVEN"))
                .andExpect(jsonPath("$.issued").value(5.0))
                .andExpect(jsonPath("$.balance").value(0.0))
                .andExpect(jsonPath("$.approvedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.approvedOnDayUid").isNotEmpty())
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
    }

    // =========================================================================
    // Issue with issued != qty → 422 "You can only issue the prescribed qty"
    // =========================================================================

    @Test
    void issue_partialQty_422_allOrNothing() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "2500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "10.0");

        mockMvc.perform(
                        post(RX_BASE + "/uid/" + rxUid + "/issue")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(issueBody("7.0", null)))  // 7 != 10
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("You can only issue the prescribed qty"));
    }

    // =========================================================================
    // Issue with issued > balance → 422 "Invalid issue value"
    // =========================================================================

    @Test
    void issue_issuedExceedsBalance_422() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "2000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "4.0");

        // 99 > balance=4 → "Invalid issue value"
        mockMvc.perform(
                        post(RX_BASE + "/uid/" + rxUid + "/issue")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(issueBody("99.0", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Invalid issue value"));
    }

    // =========================================================================
    // Issue on already-GIVEN → 422 "not a pending prescription"
    // =========================================================================

    @Test
    void issue_alreadyGiven_422() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1800.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "2.0");

        // First issue succeeds
        mockMvc.perform(post(RX_BASE + "/uid/" + rxUid + "/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("2.0", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GIVEN"));

        // Second issue on already-GIVEN → 422
        mockMvc.perform(post(RX_BASE + "/uid/" + rxUid + "/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("2.0", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("not a pending prescription"));
    }

    // =========================================================================
    // Delete NOT-GIVEN → 204
    // =========================================================================

    @Test
    void delete_notGiven_204() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "1.0");

        mockMvc.perform(delete(RX_BASE + "/uid/" + rxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(prescriptionRepository.findByUid(rxUid)).isEmpty();
    }

    // =========================================================================
    // Delete GIVEN → 422
    // =========================================================================

    @Test
    void delete_given_422() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "3.0");

        // Issue it first
        mockMvc.perform(post(RX_BASE + "/uid/" + rxUid + "/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("3.0", null)))
                .andExpect(status().isOk());

        // Now try to delete GIVEN → 422
        mockMvc.perform(delete(RX_BASE + "/uid/" + rxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Only a pending prescription can be deleted"));
    }

    // =========================================================================
    // Status converter: "NOT-GIVEN" / "GIVEN" on wire (not "NOT_GIVEN")
    // =========================================================================

    @Test
    void statusConverter_notGivenOnWire() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "1.0");

        mockMvc.perform(get(RX_BASE + "/uid/" + rxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"));
    }

    @Test
    void statusConverter_givenOnWire() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "2.0");

        mockMvc.perform(post(RX_BASE + "/uid/" + rxUid + "/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("2.0", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GIVEN"));  // hyphenated form
    }

    // =========================================================================
    // Pharmacy worklist returns NOT-GIVEN prescriptions
    // =========================================================================

    @Test
    void pharmacyWorklist_returnsNotGiven() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1200.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "6.0");

        MvcResult wl = mockMvc.perform(get(RX_BASE + "/worklist")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(wl.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean found = false;
        for (JsonNode node : array) {
            if (rxUid.equals(node.get("uid").asText())) {
                found = true;
                assertThat(node.get("status").asText()).isEqualTo("NOT-GIVEN");
            }
        }
        assertThat(found).as("NOT-GIVEN prescription must be in pharmacy worklist").isTrue();
    }

    // =========================================================================
    // OUTSIDER (non-consultation) prescribe → 201 NOT-GIVEN
    // =========================================================================

    @Test
    void prescribe_onNonConsultation_201_notGiven() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "900.00", true);
        String ncUid = seedNonConsultation(tag);

        MvcResult result = mockMvc.perform(
                        post(NC_BASE + ncUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "4.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"))
                .andExpect(jsonPath("$.nonConsultationUid").value(ncUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText()).isNotBlank();
    }

    // =========================================================================
    // Add batch → 201; list batches → 200
    // =========================================================================

    @Test
    void addBatch_andListBatches() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "800.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String rxUid = prescribe(consultUid, medUid, "5.0");

        // Add batch
        mockMvc.perform(
                        post(RX_BASE + "/uid/" + rxUid + "/batches")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(batchBody("BATCH-" + tag, "5.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.prescriptionUid").value(rxUid))
                .andExpect(jsonPath("$.no").value("BATCH-" + tag))
                .andExpect(jsonPath("$.id").doesNotExist());

        // List batches
        MvcResult listResult = mockMvc.perform(
                        get(RX_BASE + "/uid/" + rxUid + "/batches")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(1);
        assertThat(array.get(0).get("no").asText()).isEqualTo("BATCH-" + tag);
    }

    // =========================================================================
    // List by consultation
    // =========================================================================

    @Test
    void listByConsultation_returnsAllPrescriptions() throws Exception {
        String tag = uniq();
        String med1 = createMedicine(tag + "A");
        String med2 = createMedicine(tag + "B");
        seedPrice(null, "MEDICINE", med1, "1000.00", true);
        seedPrice(null, "MEDICINE", med2, "2000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        prescribe(consultUid, med1, "1.0");
        prescribe(consultUid, med2, "2.0");

        MvcResult result = mockMvc.perform(
                        get(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isGreaterThanOrEqualTo(2);
        array.forEach(n -> assertThat(n.has("id")).isFalse());
    }

    // =========================================================================
    // By-patient query
    // =========================================================================

    @Test
    void byPatient_returnsPrescriptionsForPatient() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(null, "MEDICINE", medUid, "1100.00", true);
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationWithPatient(tag, PaymentMode.CASH, null, false,
                patUid);
        prescribe(consultUid, medUid, "2.0");

        MvcResult result = mockMvc.perform(
                        get(RX_BASE + "?patientUid=" + patUid)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isGreaterThanOrEqualTo(1);
        assertThat(array.get(0).get("patientUid").asText()).isEqualTo(patUid);
    }

    // =========================================================================
    // V36 schema assertions
    // =========================================================================

    @Test
    void v36_prescriptions_patientIdDropped_patientUidAdded_settledAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'prescriptions' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("prescriptions.patient_id must be dropped by V36").isZero();

        // patient_uid must exist
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'prescriptions' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("prescriptions.patient_uid must exist after V36").isEqualTo(1);

        // fk_prescriptions_patient must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'prescriptions' "
                        + "AND constraint_name = 'fk_prescriptions_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_prescriptions_patient must be dropped by V36").isZero();

        // settled column must exist
        Integer settledCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'prescriptions' AND column_name = 'settled'",
                Integer.class);
        assertThat(settledCount)
                .as("prescriptions.settled must exist after V36").isEqualTo(1);

        // idx_prescriptions_patient_uid must exist
        Integer idxPatientUid = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'prescriptions' "
                        + "AND indexname = 'idx_prescriptions_patient_uid'",
                Integer.class);
        assertThat(idxPatientUid)
                .as("idx_prescriptions_patient_uid must exist after V36").isEqualTo(1);

        // idx_prescriptions_settled must exist
        Integer idxSettled = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'prescriptions' "
                        + "AND indexname = 'idx_prescriptions_settled'",
                Integer.class);
        assertThat(idxSettled)
                .as("idx_prescriptions_settled must exist after V36").isEqualTo(1);
    }

    // =========================================================================
    // 401 without token
    // =========================================================================

    @Test
    void allEndpoints_401_noToken() throws Exception {
        String uid  = "NORXID000000000000000000001";
        String cUid = "NOCONSULT000000000000000001";

        mockMvc.perform(post(CONSULT_BASE + cUid + "/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(CONSULT_BASE + cUid + "/prescriptions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(NC_BASE + cUid + "/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RX_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RX_BASE + "/uid/" + uid + "/issue")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(RX_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RX_BASE + "/worklist"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RX_BASE + "?patientUid=SOMEPAT0000000000000000001"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RX_BASE + "/uid/" + uid + "/batches")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RX_BASE + "/uid/" + uid + "/batches"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "C10" + Long.toHexString(System.nanoTime()).substring(0, 8);
    }

    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    /** Create a Medicine via the masterdata REST API. Returns its uid. */
    private String createMedicine(String tag) throws Exception {
        String body = """
                {"code":"MED-%s","name":"Medicine %s","description":null,
                 "type":"ORAL","price":500.00,"uom":"TAB","category":"MEDICINE","active":true}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(MEDICINES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    /** Create an insurance provider + plan and return the plan uid. */
    private String createPlan(String tag) throws Exception {
        String provBody = """
                {"code":"PROV-%s","name":"Provider %s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":false}
                """.formatted(tag, tag);
        MvcResult provR = mockMvc.perform(post(PROVIDERS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provBody))
                .andExpect(status().isCreated())
                .andReturn();
        String provUid = objectMapper.readTree(provR.getResponse().getContentAsString())
                .get("uid").asText();

        String planBody = """
                {"code":"PLAN-%s","name":"Plan %s","description":null,
                 "active":false,"insuranceProviderUid":"%s"}
                """.formatted(tag, tag, provUid);
        MvcResult planR = mockMvc.perform(
                        post(PROVIDERS_URL + "/uid/" + provUid + "/plans")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planBody))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(planR.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** Seed a service price via the masterdata REST API. */
    private void seedPrice(String planUid, String kind, String serviceUid,
                           String amount, boolean covered) throws Exception {
        String planVal = planUid    != null ? "\"" + planUid + "\"" : "null";
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

    private String seedConsultation(String tag, PaymentMode mode, String planUid,
                                    boolean settled) {
        return seedConsultationWithPatient(tag, mode, planUid, settled, fakeUid("PAT", tag));
    }

    private String seedConsultationWithPatient(String tag, PaymentMode mode, String planUid,
                                               boolean settled, String patientUid) {
        Consultation c = new Consultation(
                patientUid,
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                fakeUid("BIL", tag),
                mode,
                false,
                settled,
                mode == PaymentMode.INSURANCE ? "MEM-" + tag : "",
                planUid,
                dayUid);
        c.open();
        return consultationRepository.saveAndFlush(c).getUid();
    }

    private String seedNonConsultation(String tag) {
        NonConsultation nc = new NonConsultation(
                fakeUid("PAT", tag),
                fakeUid("VST", tag),
                "CASH",
                "",
                null,
                dayUid);
        return nonConsultationRepository.saveAndFlush(nc).getUid();
    }

    /** Prescribe via HTTP POST and return the prescription uid. */
    private String prescribe(String consultUid, String medUid, String qty) throws Exception {
        MvcResult r = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, qty)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    private static String prescribeBody(String medicineUid, String qty) {
        return """
                {"medicineUid":"%s","qty":%s,"dosage":"1 tablet","frequency":"OD",
                 "route":"ORAL","days":"7"}
                """.formatted(medicineUid, qty);
    }

    private static String issueBody(String issued, String pharmacyUid) {
        String pharm = pharmacyUid != null ? "\"" + pharmacyUid + "\"" : "null";
        return """
                {"issued":%s,"issuePharmacyUid":%s}
                """.formatted(issued, pharm);
    }

    private static String batchBody(String no, String qty) {
        return """
                {"no":"%s","qty":%s,"manufacturedDate":null,"expiryDate":null}
                """.formatted(no, qty);
    }
}
