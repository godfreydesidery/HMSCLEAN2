package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.ProcedureRepository;
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
 * Integration tests for inc-05 C9: Procedure aggregate.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Order on consultation → 201 PENDING + bill created.</li>
 *   <li>Duplicate same type on same consultation → 422 verbatim message.</li>
 *   <li>Accept PENDING → ACCEPTED (stamps accepted_* audit).</li>
 *   <li>add_note on ACCEPTED with settled=true (INSURANCE) → VERIFIED (note saved).</li>
 *   <li>add_note when NOT settled (CASH unsettled) → 422 "Could not add procedure note.
 *       Payment not verified" — THE DISTINCTIVE BILL GATE.</li>
 *   <li>add_note with empty/blank note → 400 validation error (@NotBlank).</li>
 *   <li>add_note on non-ACCEPTED (PENDING) → 422 "Please accept the procedure first".</li>
 *   <li>Update note when ACCEPTED (no status change).</li>
 *   <li>Delete PENDING → 204; delete non-PENDING → 422.</li>
 *   <li>NO approve / reject / hold / collect endpoints → 404.</li>
 *   <li>Worklist: settled=true orders in PENDING/ACCEPTED; CASH unsettled not shown.</li>
 *   <li>By-patient returns consultation procedures (no admission procedures in C9).</li>
 *   <li>OUTSIDER via walk-in non-consultation → 201 PENDING.</li>
 *   <li>INSURANCE → settled=true; CASH → settled=false.</li>
 *   <li>401 without token on all key endpoints.</li>
 *   <li>V35 schema: patient_id dropped, patient_uid added, settled column added.</li>
 * </ol>
 */
class ProcedureIT extends AbstractIntegrationTest {

    private static final String BASE          = "/api/v1/clinical";
    private static final String CONSULT_BASE  = BASE + "/consultations/uid/";
    private static final String NC_BASE       = BASE + "/non-consultations/uid/";
    private static final String PROC_BASE     = BASE + "/procedures";
    private static final String PROVIDERS_URL = "/api/v1/masterdata/insurance-providers";
    private static final String PRICES_URL    = "/api/v1/masterdata/service-prices";
    private static final String PROC_TYPES_URL = "/api/v1/masterdata/procedure-types";

    @Autowired MockMvc                   mockMvc;
    @Autowired ObjectMapper              objectMapper;
    @Autowired TestJwtFactory            jwtFactory;
    @Autowired ConsultationRepository    consultationRepository;
    @Autowired NonConsultationRepository nonConsultationRepository;
    @Autowired ProcedureRepository       procedureRepository;
    @Autowired BusinessDayService        businessDayService;
    @Autowired DataSource                dataSource;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // Order on consultation — INSURANCE → 201 + settled=true
    // =========================================================================

    @Test
    void order_insuranceConsultation_201_pendingAndSettledTrue() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null,    "PROCEDURE", procTypeUid, "15000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "PROCEDURE", procTypeUid, "10000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/procedures")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(procTypeUid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.procedureTypeUid").value(procTypeUid))
                .andExpect(jsonPath("$.patientBillUid").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String procUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(procUid).isNotBlank();
    }

    // =========================================================================
    // Order on consultation — CASH → settled=false
    // =========================================================================

    @Test
    void order_cashConsultation_settled_false() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "12000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/procedures")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(procTypeUid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.settled").value(false));
    }

    // =========================================================================
    // Duplicate same type on same consultation → 422 verbatim
    // =========================================================================

    @Test
    void order_duplicateTypeOnConsultation_422() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "9000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // First order — succeeds
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/procedures")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(procTypeUid)))
                .andExpect(status().isCreated());

        // Second order — same type, same consultation → 422
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/procedures")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(procTypeUid)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate procedure type is not allowed for this encounter"));
    }

    // =========================================================================
    // Unknown procedure type → 404
    // =========================================================================

    @Test
    void order_unknownProcedureType_404() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(post(CONSULT_BASE + consultUid + "/procedures")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("BADPROCTYPEUID00000000000001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Procedure type not found"));
    }

    // =========================================================================
    // Accept PENDING → ACCEPTED
    // =========================================================================

    @Test
    void accept_pending_becomesAccepted_stampsAudit() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "11000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.acceptedAt").isNotEmpty());
    }

    // =========================================================================
    // add_note on ACCEPTED + settled=true → VERIFIED (INSURANCE path)
    // THE HAPPY PATH OF THE DISTINCTIVE BILL GATE
    // =========================================================================

    @Test
    void addNote_acceptedAndSettled_becomesVerified() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null,    "PROCEDURE", procTypeUid, "20000.00", true);
        String planUid    = createPlan(tag + "AN");
        seedPrice(planUid, "PROCEDURE", procTypeUid, "15000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String procUid = orderProcedure(consultUid, procTypeUid);

        // Accept → ACCEPTED
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // add_note on ACCEPTED + settled=true → VERIFIED
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Procedure completed successfully. No complications.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.note").value("Procedure completed successfully. No complications."))
                .andExpect(jsonPath("$.verifiedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
    }

    // =========================================================================
    // add_note when NOT settled (CASH unsettled) → 422 VERBATIM legacy message
    // THIS IS THE DISTINCTIVE BILL GATE — the ONE in-method settlement check
    // =========================================================================

    @Test
    void addNote_notSettled_422_verbatimLegacyMessage() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "10000.00", true);
        // CASH consultation → settled=false
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        // Accept → ACCEPTED (accept does NOT re-check settlement)
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.settled").value(false));

        // add_note on ACCEPTED but settled=false → 422 with VERBATIM legacy message
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Procedure done.\"}"))
                .andExpect(status().isUnprocessableEntity())
                // VERBATIM legacy message (PatientResource.java:3410-3412)
                .andExpect(jsonPath("$.detail")
                        .value("Could not add procedure note. Payment not verified"));
    }

    // =========================================================================
    // add_note with blank note → 400 validation error (@NotBlank)
    // =========================================================================

    @Test
    void addNote_blankNote_400_validationError() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null,    "PROCEDURE", procTypeUid, "8000.00", true);
        String planUid    = createPlan(tag + "BN");
        seedPrice(planUid, "PROCEDURE", procTypeUid, "5000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String procUid = orderProcedure(consultUid, procTypeUid);

        // Accept
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // add_note with blank note → 400 (@NotBlank on request)
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // add_note on non-ACCEPTED (PENDING) → 422 "Please accept the procedure first"
    // =========================================================================

    @Test
    void addNote_onPending_422_notAccepted() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null,    "PROCEDURE", procTypeUid, "7000.00", true);
        String planUid    = createPlan(tag + "PA");
        seedPrice(planUid, "PROCEDURE", procTypeUid, "5500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String procUid = orderProcedure(consultUid, procTypeUid);
        // Procedure is PENDING — do NOT accept

        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Some note.\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Please accept the procedure first"));
    }

    // =========================================================================
    // Update note when ACCEPTED — no status change
    // =========================================================================

    @Test
    void update_whenAccepted_noStatusChange() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "9500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        // Accept
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Update note — no status change
        mockMvc.perform(put(PROC_BASE + "/uid/" + procUid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Pre-operative note updated.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Pre-operative note updated."))
                // Status must still be ACCEPTED — update does NOT change status
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    // =========================================================================
    // Update when not ACCEPTED (PENDING) → 422
    // =========================================================================

    @Test
    void update_whenPending_422() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        mockMvc.perform(put(PROC_BASE + "/uid/" + procUid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Too early.\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Procedure order must be accepted to update"));
    }

    // =========================================================================
    // Delete PENDING → 204; delete non-PENDING → 422
    // =========================================================================

    @Test
    void delete_pending_204() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "5000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        mockMvc.perform(delete(PROC_BASE + "/uid/" + procUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(procedureRepository.findByUid(procUid)).isEmpty();
    }

    @Test
    void delete_nonPending_422() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "5500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        // Accept → ACCEPTED
        mockMvc.perform(post(PROC_BASE + "/uid/" + procUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Delete ACCEPTED → 422
        mockMvc.perform(delete(PROC_BASE + "/uid/" + procUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Only a pending procedure order can be deleted"));
    }

    // =========================================================================
    // NO approve / reject / hold / collect endpoints → 404
    // =========================================================================

    @Test
    void noApproveEndpoint_404() throws Exception {
        mockMvc.perform(post(PROC_BASE + "/uid/SOMEPROCUID0000000000000001/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void noRejectEndpoint_404() throws Exception {
        mockMvc.perform(post(PROC_BASE + "/uid/SOMEPROCUID0000000000000001/reject")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void noHoldEndpoint_404() throws Exception {
        mockMvc.perform(post(PROC_BASE + "/uid/SOMEPROCUID0000000000000001/hold")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void noCollectEndpoint_404() throws Exception {
        mockMvc.perform(post(PROC_BASE + "/uid/SOMEPROCUID0000000000000001/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Worklist: settled=true orders shown; CASH (settled=false) NOT shown
    // =========================================================================

    @Test
    void worklist_settledFilterShowsInsurance_hidesCash() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        String planUid     = createPlan(tag + "WL");
        seedPrice(null,    "PROCEDURE", procTypeUid, "18000.00", true);
        seedPrice(planUid, "PROCEDURE", procTypeUid, "13000.00", true);

        // INSURANCE consultation → settled=true
        String consultIns  = seedConsultation(tag + "WLI", PaymentMode.INSURANCE, planUid, true);
        String procInsUid  = orderProcedure(consultIns, procTypeUid);

        // CASH needs a different procedure type to avoid duplicate guard
        String procTypeUid2 = createProcedureType(tag + "B");
        seedPrice(null, "PROCEDURE", procTypeUid2, "16000.00", true);
        String consultCash = seedConsultation(tag + "WLC", PaymentMode.CASH, null, false);
        orderProcedure(consultCash, procTypeUid2);

        MvcResult wl = mockMvc.perform(get(PROC_BASE + "/worklist")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(wl.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean foundIns  = false;
        boolean foundCash = false;
        for (JsonNode node : array) {
            if (procInsUid.equals(node.get("uid").asText())) {
                foundIns = true;
                assertThat(node.get("settled").asBoolean()).isTrue();
            }
            if (procTypeUid2.equals(node.get("procedureTypeUid").asText())
                    && !node.get("settled").asBoolean()) {
                foundCash = true;
            }
        }
        assertThat(foundIns).as("INSURANCE settled order must be in worklist").isTrue();
        assertThat(foundCash).as("CASH unsettled order must NOT be in worklist").isFalse();
    }

    // =========================================================================
    // By-patient returns consultation procedures
    // =========================================================================

    @Test
    void byPatient_returnsConsultationProcedures() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "14000.00", true);
        String patUid     = fakeUid("PAT", tag);
        String consultUid = seedConsultationWithPatient(tag, PaymentMode.CASH, null, false, patUid);

        orderProcedure(consultUid, procTypeUid);

        MvcResult result = mockMvc.perform(
                        get(PROC_BASE + "?patientUid=" + patUid)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isGreaterThanOrEqualTo(1);
        assertThat(array.get(0).get("patientUid").asText()).isEqualTo(patUid);
    }

    // =========================================================================
    // OUTSIDER via walk-in non-consultation → 201 PENDING
    // =========================================================================

    @Test
    void order_onNonConsultation_201_pending() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "12500.00", true);
        String ncUid = seedNonConsultation(tag);

        MvcResult result = mockMvc.perform(
                        post(NC_BASE + ncUid + "/procedures")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBodyForNonConsult(procTypeUid,
                                        fakeUid("PAT", tag), "CASH")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.nonConsultationUid").value(ncUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText()).isNotBlank();
    }

    // =========================================================================
    // Get by uid
    // =========================================================================

    @Test
    void getByUid_200_noIdLeak() throws Exception {
        String tag = uniq();
        String procTypeUid = createProcedureType(tag);
        seedPrice(null, "PROCEDURE", procTypeUid, "8500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String procUid = orderProcedure(consultUid, procTypeUid);

        mockMvc.perform(get(PROC_BASE + "/uid/" + procUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(procUid))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_404_unknown() throws Exception {
        mockMvc.perform(get(PROC_BASE + "/uid/NONEXISTENTPROC000000000001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // List by consultation
    // =========================================================================

    @Test
    void listByConsultation_returnsOrders() throws Exception {
        String tag = uniq();
        String pt1 = createProcedureType(tag + "A");
        String pt2 = createProcedureType(tag + "B");
        seedPrice(null, "PROCEDURE", pt1, "5000.00", true);
        seedPrice(null, "PROCEDURE", pt2, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        orderProcedure(consultUid, pt1);
        orderProcedure(consultUid, pt2);

        MvcResult result = mockMvc.perform(
                        get(CONSULT_BASE + consultUid + "/procedures")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isGreaterThanOrEqualTo(2);
        array.forEach(n -> assertThat(n.has("id")).isFalse());
    }

    // =========================================================================
    // 401 without token
    // =========================================================================

    @Test
    void allEndpoints_401_noToken() throws Exception {
        String uid  = "NOPROCEDURE0000000000000001";
        String cUid = "NOCONSULT000000000000000001";

        mockMvc.perform(post(CONSULT_BASE + cUid + "/procedures")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody("X")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROC_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PROC_BASE + "/uid/" + uid + "/accept"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PROC_BASE + "/uid/" + uid + "/note")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(PROC_BASE + "/uid/" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PROC_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROC_BASE + "/worklist"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROC_BASE + "?patientUid=SOMEPATIENT0000000000000001"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // V35 schema assertions
    // =========================================================================

    @Test
    void v35_procedures_patientIdDropped_patientUidAdded_settledAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'procedures' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("procedures.patient_id must be dropped by V35").isZero();

        // patient_uid must exist
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'procedures' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("procedures.patient_uid must exist after V35").isEqualTo(1);

        // fk_procedures_patient constraint must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'procedures' "
                        + "AND constraint_name = 'fk_procedures_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_procedures_patient must be dropped by V35").isZero();

        // patient_uid index must exist
        Integer idxPatientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'procedures' "
                        + "AND indexname = 'idx_procedures_patient_uid'",
                Integer.class);
        assertThat(idxPatientUidCount)
                .as("idx_procedures_patient_uid must exist after V35").isEqualTo(1);

        // settled column must exist
        Integer settledCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'procedures' AND column_name = 'settled'",
                Integer.class);
        assertThat(settledCount)
                .as("procedures.settled must exist after V35").isEqualTo(1);

        // settled index must exist
        Integer idxSettledCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'procedures' "
                        + "AND indexname = 'idx_procedures_settled'",
                Integer.class);
        assertThat(idxSettledCount)
                .as("idx_procedures_settled must exist after V35").isEqualTo(1);
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "C9" + Long.toHexString(System.nanoTime()).substring(0, 10);
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

    /** Create a ProcedureType via the masterdata REST API. Returns its uid. */
    private String createProcedureType(String tag) throws Exception {
        String body = """
                {"code":"PT-%s","name":"ProcType %s","description":null,"price":10000.00,"active":true}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(PROC_TYPES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    /** Create an insurance provider+plan and return the plan uid. */
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

    /**
     * Seed a service price via the masterdata REST API.
     *
     * <p>QA-07: each per-test price uses a unique, tag'd serviceUid so it is always a fresh
     * CREATE (201). Asserting {@code isCreated()} instead of {@code is2xxSuccessful()} prevents
     * a silent 409 (duplicate key conflict) from being swallowed and masking a broken price seed.
     */
    private void seedPrice(String planUid, String kind, String serviceUid,
                           String amount, boolean covered) throws Exception {
        String planVal = planUid    != null ? "\"" + planUid + "\""    : "null";
        String svcVal  = serviceUid != null ? "\"" + serviceUid + "\"" : "null";
        String body = """
                {"planUid":%s,"kind":"%s","serviceUid":%s,"currency":"TZS",
                 "amount":%s,"covered":%b,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(planVal, kind, svcVal, amount, covered);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
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

    /** Order a procedure via HTTP POST and return its uid. */
    private String orderProcedure(String consultUid, String procTypeUid) throws Exception {
        MvcResult r = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/procedures")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(procTypeUid)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    private static String orderBody(String procedureTypeUid) {
        return """
                {"procedureTypeUid":"%s"}
                """.formatted(procedureTypeUid);
    }

    private static String orderBodyForNonConsult(String procedureTypeUid, String patientUid,
                                                  String paymentType) {
        return """
                {"procedureTypeUid":"%s","patientUid":"%s","paymentType":"%s"}
                """.formatted(procedureTypeUid, patientUid, paymentType);
    }
}
