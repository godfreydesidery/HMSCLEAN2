package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.RadiologyRepository;
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
 * Integration tests for inc-05 C8: Radiology aggregate + attachments.
 *
 * <p>Coverage (radiology-specific deltas from LabTestIT):
 * <ol>
 *   <li>Order on consultation → 201 PENDING + bill created (settled correctly for INSURANCE).</li>
 *   <li>Duplicate same type on same consultation → 422 verbatim message.</li>
 *   <li>Full happy path: PENDING → ACCEPTED → VERIFIED DIRECTLY (no collect step).</li>
 *   <li>NO collect endpoint — a POST to /collect has no route (404).</li>
 *   <li>Verify on non-ACCEPTED (PENDING) → 422 "Please accept the radiology order first".</li>
 *   <li>Reject from PENDING → REJECTED; accept from REJECTED → ACCEPTED — assert rejectComment
 *       is NOT cleared (radiology asymmetry vs LabTest — exact-process mandate).</li>
 *   <li>Hold: ACCEPTED → PENDING (stamps held_* audit fields).</li>
 *   <li>Delete PENDING → 204; delete non-PENDING → 422.</li>
 *   <li>INSURANCE order → settled=true; CASH order → settled=false.</li>
 *   <li>Worklist filters settled=true only (PENDING + ACCEPTED; no COLLECTED).</li>
 *   <li>Attachment: add only when ACCEPTED (before ACCEPTED i.e. PENDING → 422); max 5 (6th → 422).</li>
 *   <li>Delete attachment blocked when VERIFIED.</li>
 *   <li>OUTSIDER (non-consultation) order → 201 PENDING.</li>
 *   <li>401 on all key endpoints without a token.</li>
 *   <li>V34 schema: patient_id dropped, patient_uid added, settled column added.</li>
 *   <li>verify writes inline blob (hasAttachment=true in response).</li>
 * </ol>
 */
class RadiologyIT extends AbstractIntegrationTest {

    private static final String BASE           = "/api/v1/clinical";
    private static final String CONSULT_BASE   = BASE + "/consultations/uid/";
    private static final String NC_BASE        = BASE + "/non-consultations/uid/";
    private static final String RAD_BASE       = BASE + "/radiologies";
    private static final String PROVIDERS_URL  = "/api/v1/masterdata/insurance-providers";
    private static final String PRICES_URL     = "/api/v1/masterdata/service-prices";
    private static final String RAD_TYPES_URL  = "/api/v1/masterdata/radiology-types";

    @Autowired MockMvc                   mockMvc;
    @Autowired ObjectMapper              objectMapper;
    @Autowired TestJwtFactory            jwtFactory;
    @Autowired ConsultationRepository    consultationRepository;
    @Autowired NonConsultationRepository nonConsultationRepository;
    @Autowired RadiologyRepository       radiologyRepository;
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
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/radiologies")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(radTypeUid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.radiologyTypeUid").value(radTypeUid))
                .andExpect(jsonPath("$.patientBillUid").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String radUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(radUid).isNotBlank();
    }

    // =========================================================================
    // Order on consultation — CASH → settled=false
    // =========================================================================

    @Test
    void order_cashConsultation_settled_false() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "7000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/radiologies")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(radTypeUid)))
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
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "5000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // First order — succeeds
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/radiologies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(radTypeUid)))
                .andExpect(status().isCreated());

        // Second order — same type, same consultation → 422
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/radiologies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(radTypeUid)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate radiology type is not allowed for this encounter"));
    }

    // =========================================================================
    // Unknown radiology type → 404
    // =========================================================================

    @Test
    void order_unknownRadiologyType_404() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(post(CONSULT_BASE + consultUid + "/radiologies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("BADRADTYPEUID000000000000001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Radiology type not found"));
    }

    // =========================================================================
    // Full happy path: PENDING → ACCEPTED → VERIFIED (NO collect step)
    // =========================================================================

    @Test
    void fullHappyPath_pendingToVerified_noCollectStep() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "9000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // Order → PENDING
        String radUid = orderRadiology(consultUid, radTypeUid);
        assertRadiologyStatus(radUid, "PENDING");

        // Accept → ACCEPTED
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.acceptedAt").isNotEmpty());

        // Verify → VERIFIED DIRECTLY from ACCEPTED (no collect step)
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("Normal chest", "Full report here", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.result").value("Normal chest"))
                .andExpect(jsonPath("$.report").value("Full report here"))
                .andExpect(jsonPath("$.verifiedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
    }

    // =========================================================================
    // verify writes inline blob — hasAttachment=true
    // =========================================================================

    @Test
    void verify_withInlineBlob_hasAttachmentTrue() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "9500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify with a non-null attachment blob (base64-encoded in JSON as byte[])
        // Spring will deserialise a JSON byte array (array of ints) into byte[]
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"Opacity found\",\"report\":\"Detailed\","
                                + "\"attachment\":[72,101,108,108,111]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.hasAttachment").value(true));
    }

    // =========================================================================
    // NO collect endpoint — POST /collect has no route
    // =========================================================================

    @Test
    void noCollectEndpoint_returnsNotFound() throws Exception {
        // There is NO /radiologies/uid/{uid}/collect endpoint (CR-INC05-14).
        // A POST to this path should return 404 (no route registered).
        mockMvc.perform(post(RAD_BASE + "/uid/SOMERADUID0000000000000001/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Verify on non-ACCEPTED (PENDING) → 422 "Please accept the radiology order first"
    // =========================================================================

    @Test
    void verify_onPending_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Radiology is PENDING — try to verify without accepting first
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("result", "report", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Please accept the radiology order first"));
    }

    // =========================================================================
    // Reject from PENDING → REJECTED; accept from REJECTED → ACCEPTED.
    // ASSERT: rejectComment is NOT cleared on accept (radiology asymmetry vs LabTest).
    // =========================================================================

    @Test
    void reject_fromPending_setsRejectComment_acceptFromRejected_doesNOTClearComment() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "7500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Reject from PENDING
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Equipment unavailable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectComment").value("Equipment unavailable"))
                .andExpect(jsonPath("$.rejectedByUserUid").isNotEmpty());

        // Accept from REJECTED → ACCEPTED.
        // CRITICAL ASSERTION: rejectComment must NOT be cleared — radiology asymmetry.
        // (LabTest.accept() clears it; Radiology.accept() does NOT.)
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedByUserUid").isNotEmpty())
                // THE KEY ASSERTION — comment persists after accept (exact-process asymmetry)
                .andExpect(jsonPath("$.rejectComment").value("Equipment unavailable"));
    }

    // =========================================================================
    // C3 (ITEM3): save_reason_for_rejection — re-callable rejectComment edit on a
    // REJECTED order; edit on a non-REJECTED order → 422 verbatim.
    // =========================================================================

    @Test
    void saveRejectComment_onRejected_editsComment_reCallable() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "7500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Initial reason\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/reject-comment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Corrected reason\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectComment").value("Corrected reason"));

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/reject-comment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Second correction\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectComment").value("Second correction"));
    }

    @Test
    void saveRejectComment_onNonRejected_422_verbatim() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "7500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);  // PENDING (not REJECTED)

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/reject-comment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Should fail\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not save. Only allowed for rejected tests"));
    }

    // =========================================================================
    // C5 (ITEM2): stand-alone bill-gated add_report. INSURANCE order (bill COVERED) →
    // add_report writes the report at any order status; CASH order (bill UNPAID) → 422.
    // =========================================================================

    @Test
    void addReport_insuranceCovered_writesReport_anyOrderStatus() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);
        // INSURANCE → bill COVERED → bill-gate passes without a cashier payment.
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String radUid = orderRadiology(consultUid, radTypeUid);  // PENDING

        // add_report on a PENDING order (no order-status guard — legacy parity).
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/add-report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Chest X-ray: no acute findings.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").value("Chest X-ray: no acute findings."))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void addReport_cashUnpaid_422_paymentNotVerified() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "8000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);  // bill UNPAID

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/add-report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Should be blocked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not add report. Payment not verified"));
    }

    // =========================================================================
    // C6 (ITEM4): post-VERIFIED report is immutable via add-report (-> 422) and amendable only via
    // the audited amend-report path, which retains the prior narrative + stamps the amend triplet.
    // =========================================================================

    @Test
    void verifiedReport_blockedViaAddReport_amendableViaAmendPath_retainsPrior() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Drive to VERIFIED (radiology: ACCEPTED → VERIFIED direct), writing the original report.
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("Normal study", "Original verified narrative", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.report").value("Original verified narrative"));

        // add-report on a VERIFIED order must be BLOCKED (routes to amend).
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/add-report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Sneaky overwrite\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not add report. A verified report can only be amended via the amend path"));

        // Amend → new report, PRIOR narrative retained, amend triplet stamped.
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/amend-report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Corrected narrative\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").value("Corrected narrative"))
                .andExpect(jsonPath("$.priorReport").value("Original verified narrative"))
                .andExpect(jsonPath("$.reportAmendedByUserUid").value("admin"))
                .andExpect(jsonPath("$.reportAmendedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    void amendReport_onNonVerified_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String radUid = orderRadiology(consultUid, radTypeUid);  // PENDING, bill COVERED

        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/amend-report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Too early\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not amend report. Radiology is not verified"));
    }

    // =========================================================================
    // Hold: ACCEPTED → PENDING
    // =========================================================================

    @Test
    void hold_fromAccepted_revertsToP_stampsHeldAudit() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "8500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept first
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // Hold → PENDING
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/hold")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.heldByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.heldAt").isNotEmpty());
    }

    // =========================================================================
    // Delete PENDING → 204; delete non-PENDING → 422
    // =========================================================================

    @Test
    void delete_pending_204_deleteNonPending_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "5000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);
        assertRadiologyStatus(radUid, "PENDING");

        // Delete PENDING → 204
        mockMvc.perform(delete(RAD_BASE + "/uid/" + radUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(radiologyRepository.findByUid(radUid)).isEmpty();

        // Delete again (404 since it's gone)
        mockMvc.perform(delete(RAD_BASE + "/uid/" + radUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_nonPending_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "4000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept → ACCEPTED
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Delete ACCEPTED → 422
        mockMvc.perform(delete(RAD_BASE + "/uid/" + radUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not delete, only a PENDING radiology can be deleted"));
    }

    // =========================================================================
    // Worklist filters settled=true only
    // =========================================================================

    @Test
    void worklist_onlyShowsSettledOrders() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        String planUid    = createPlan(tag + "WL");
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);

        // INSURANCE consultation → settled=true
        String consultIns  = seedConsultation(tag + "WLI", PaymentMode.INSURANCE, planUid, true);
        String radInsUid   = orderRadiology(consultIns, radTypeUid);

        // Second radiology type for CASH
        String radTypeUid2 = createRadiologyType(tag + "B");
        seedPrice(null, "RADIOLOGY", radTypeUid2, "7000.00", true);
        String consultCash = seedConsultation(tag + "WLC", PaymentMode.CASH, null, false);
        orderRadiology(consultCash, radTypeUid2);

        // Worklist should contain INSURANCE order (settled=true) but NOT the CASH (settled=false)
        MvcResult wl = mockMvc.perform(get(RAD_BASE + "/worklist")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(wl.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean foundIns  = false;
        boolean foundCash = false;
        for (JsonNode node : array) {
            if (radInsUid.equals(node.get("uid").asText())) {
                foundIns = true;
                assertThat(node.get("settled").asBoolean()).isTrue();
            }
            if (radTypeUid2.equals(node.get("radiologyTypeUid").asText())
                    && !node.get("settled").asBoolean()) {
                foundCash = true;
            }
        }
        assertThat(foundIns).as("INSURANCE settled order must be in worklist").isTrue();
        assertThat(foundCash).as("CASH unsettled order must NOT be in worklist").isFalse();
    }

    // =========================================================================
    // QA-06: worklist excludes VERIFIED; a settled ACCEPTED order is present.
    // The radiology worklist query is settled=true AND status IN {PENDING,ACCEPTED}
    // (RadiologyRepository.findBySettledAndStatusInOrderByCreatedAtAsc) — radiology has
    // no live COLLECTED state (ACCEPTED → VERIFIED direct), and VERIFIED is terminal.
    // =========================================================================

    @Test
    void worklist_excludesVerified_includesAccepted() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        String planUid    = createPlan(tag + "WV");
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);

        // Order A (INSURANCE → settled): drive to ACCEPTED → must be ON the worklist.
        String consultAccepted = seedConsultation(tag + "WVA", PaymentMode.INSURANCE, planUid, true);
        String acceptedUid     = orderRadiology(consultAccepted, radTypeUid);
        mockMvc.perform(post(RAD_BASE + "/uid/" + acceptedUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // Order B (INSURANCE → settled): drive to VERIFIED → must be ABSENT from the worklist.
        String consultVerified = seedConsultation(tag + "WVV", PaymentMode.INSURANCE, planUid, true);
        String verifiedUid     = orderRadiology(consultVerified, radTypeUid);
        mockMvc.perform(post(RAD_BASE + "/uid/" + verifiedUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(RAD_BASE + "/uid/" + verifiedUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("Normal chest", "Full report here", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        MvcResult wl = mockMvc.perform(get(RAD_BASE + "/worklist")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(wl.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean foundAccepted = false;
        boolean foundVerified = false;
        for (JsonNode node : array) {
            if (acceptedUid.equals(node.get("uid").asText())) {
                foundAccepted = true;
                assertThat(node.get("status").asText()).isEqualTo("ACCEPTED");
            }
            if (verifiedUid.equals(node.get("uid").asText())) {
                foundVerified = true;
            }
        }
        assertThat(foundAccepted)
                .as("settled ACCEPTED order must be in the worklist").isTrue();
        assertThat(foundVerified)
                .as("VERIFIED order must NOT be in the worklist").isFalse();
    }

    // =========================================================================
    // Attachments: add only when ACCEPTED (PENDING → 422); max 5; delete blocked when VERIFIED
    // =========================================================================

    @Test
    void attachment_addBeforeAccepted_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "10000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Radiology is PENDING — try to attach before ACCEPTED
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("Report A", "rad-" + tag + "-a.pdf")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Can only attach for accepted tests"));
    }

    @Test
    void attachment_max5_6thRejected() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "10000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept → ACCEPTED (attachment gate is ACCEPTED, not COLLECTED)
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Add 5 attachments — all succeed
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/attachments")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(attachmentBody("Scan " + i,
                                    "rad-" + tag + "-" + i + ".dcm")))
                    .andExpect(status().isCreated());
        }

        // 6th attachment → 422 max exceeded
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("Scan 6", "rad-" + tag + "-6.dcm")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Can not add more than 5 attachments"));
    }

    @Test
    void attachment_deleteBlockedWhenVerified() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "11000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept → ACCEPTED
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Add one attachment while ACCEPTED
        MvcResult addResult = mockMvc.perform(
                        post(RAD_BASE + "/uid/" + radUid + "/attachments")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(attachmentBody("Chest CT", "rad-" + tag + "-del.dcm")))
                .andExpect(status().isCreated())
                .andReturn();
        String attUid = objectMapper.readTree(addResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Verify → VERIFIED (ACCEPTED → VERIFIED directly)
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("Normal", "No abnormality", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        // Try to delete attachment when VERIFIED → 422 (corrected verbatim legacy message, C7).
        mockMvc.perform(delete(RAD_BASE + "/attachments/uid/" + attUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not delete. Radiology already verified"));
    }

    // =========================================================================
    // C7 (ITEM5): multipart upload + inline download (local-disk storage).
    // Upload (ACCEPTED, settled) → 201; download before VERIFIED → 422; download after
    // VERIFIED → 200 inline bytes.
    // =========================================================================

    @Test
    void uploadAttachment_thenDownload_afterVerified() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null,    "RADIOLOGY", radTypeUid, "8000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "RADIOLOGY", radTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept (radiology attach-gate is ACCEPTED).
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

        // Upload a small file (multipart) → 201 with a fileName.
        byte[] content = ("IMG-BYTES-" + tag).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "xray.png", "image/png", content);
        MvcResult upload = mockMvc.perform(multipart(RAD_BASE + "/uid/" + radUid + "/attachments/upload")
                        .file(file)
                        .param("name", "X-ray")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.fileName").isNotEmpty())
                .andReturn();
        String attUid = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("uid").asText();

        // Download BEFORE VERIFIED → 422 (download gate).
        mockMvc.perform(get(RAD_BASE + "/attachments/uid/" + attUid + "/download")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not download. Radiology is not verified"));

        // Verify (radiology ACCEPTED → VERIFIED direct).
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("Normal study", "No abnormality", null)))
                .andExpect(status().isOk());

        // Download AFTER VERIFIED → 200 inline bytes.
        mockMvc.perform(get(RAD_BASE + "/attachments/uid/" + attUid + "/download")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("inline")))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content));
    }

    // =========================================================================
    // List attachments
    // =========================================================================

    @Test
    void listAttachments_returnsAllForRadiology() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "9000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept first (ACCEPTED gate for attachments)
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Add 2 attachments
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("AP View", "rad-" + tag + "-1.dcm")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("Lateral View", "rad-" + tag + "-2.dcm")))
                .andExpect(status().isCreated());

        // List
        MvcResult listResult = mockMvc.perform(
                        get(RAD_BASE + "/uid/" + radUid + "/attachments")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        array.forEach(n -> assertThat(n.has("id")).isFalse());
    }

    // =========================================================================
    // saveResult — allowed when ACCEPTED (not COLLECTED like lab)
    // =========================================================================

    @Test
    void saveResult_whenAccepted_succeeds() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "8200.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // Accept → ACCEPTED
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // saveResult when ACCEPTED
        mockMvc.perform(put(RAD_BASE + "/uid/" + radUid + "/result")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"Mass detected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("Mass detected"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void saveResult_whenPending_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "7800.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        // saveResult when PENDING → 422 (ACCEPTED gate)
        mockMvc.perform(put(RAD_BASE + "/uid/" + radUid + "/result")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"Too early\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Please accept the radiology order first"));
    }

    // =========================================================================
    // OUTSIDER (non-consultation) order
    // =========================================================================

    @Test
    void order_onNonConsultation_201_pending() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "8000.00", true);
        String ncUid = seedNonConsultation(tag);

        MvcResult result = mockMvc.perform(
                        post(NC_BASE + ncUid + "/radiologies")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBodyForNonConsult(radTypeUid,
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
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "6500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String radUid = orderRadiology(consultUid, radTypeUid);

        mockMvc.perform(get(RAD_BASE + "/uid/" + radUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(radUid))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_404_unknown() throws Exception {
        mockMvc.perform(get(RAD_BASE + "/uid/NONEXISTENTRAD0000000000001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // List by consultation
    // =========================================================================

    @Test
    void listForConsultation_returnsOrders() throws Exception {
        String tag = uniq();
        String rtt1 = createRadiologyType(tag + "A");
        String rtt2 = createRadiologyType(tag + "B");
        seedPrice(null, "RADIOLOGY", rtt1, "3000.00", true);
        seedPrice(null, "RADIOLOGY", rtt2, "4000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        orderRadiology(consultUid, rtt1);
        orderRadiology(consultUid, rtt2);

        MvcResult result = mockMvc.perform(
                        get(CONSULT_BASE + consultUid + "/radiologies")
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
    void byPatient_returnsRadiologiesForPatient() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(null, "RADIOLOGY", radTypeUid, "7200.00", true);
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationWithPatient(tag, PaymentMode.CASH, null, false, patUid);

        orderRadiology(consultUid, radTypeUid);

        MvcResult result = mockMvc.perform(
                        get(RAD_BASE + "?patientUid=" + patUid)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isGreaterThanOrEqualTo(1);
        assertThat(array.get(0).get("patientUid").asText()).isEqualTo(patUid);
    }

    // =========================================================================
    // 401 without token
    // =========================================================================

    @Test
    void allEndpoints_401_noToken() throws Exception {
        String uid  = "NORADIOLOGY00000000000000001";
        String cUid = "NOCONSULT000000000000000001";

        mockMvc.perform(post(CONSULT_BASE + cUid + "/radiologies")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody("X")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RAD_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RAD_BASE + "/uid/" + uid + "/accept"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RAD_BASE + "/uid/" + uid + "/reject"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RAD_BASE + "/uid/" + uid + "/verify"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RAD_BASE + "/uid/" + uid + "/hold"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(RAD_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RAD_BASE + "/worklist"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RAD_BASE + "?patientUid=SOMEPATIENT0000000000000001"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(RAD_BASE + "/uid/" + uid + "/attachments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RAD_BASE + "/uid/" + uid + "/attachments")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(RAD_BASE + "/attachments/uid/ATTUID0000000000000000001"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // V34 schema assertions
    // =========================================================================

    @Test
    void v34_radiologies_patientIdDropped_patientUidAdded_settledAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'radiologies' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("radiologies.patient_id must be dropped by V34").isZero();

        // patient_uid must exist
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'radiologies' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("radiologies.patient_uid must exist after V34").isEqualTo(1);

        // fk_radiologies_patient constraint must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'radiologies' "
                        + "AND constraint_name = 'fk_radiologies_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_radiologies_patient must be dropped by V34").isZero();

        // patient_uid index must exist
        Integer idxPatientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'radiologies' "
                        + "AND indexname = 'idx_radiologies_patient_uid'",
                Integer.class);
        assertThat(idxPatientUidCount)
                .as("idx_radiologies_patient_uid must exist after V34").isEqualTo(1);

        // settled column must exist
        Integer settledCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'radiologies' AND column_name = 'settled'",
                Integer.class);
        assertThat(settledCount)
                .as("radiologies.settled must exist after V34").isEqualTo(1);

        // settled index must exist
        Integer idxSettledCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'radiologies' "
                        + "AND indexname = 'idx_radiologies_settled'",
                Integer.class);
        assertThat(idxSettledCount)
                .as("idx_radiologies_settled must exist after V34").isEqualTo(1);
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "C8" + Long.toHexString(System.nanoTime()).substring(0, 10);
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

    /** Create a RadiologyType via the masterdata REST API. Returns its uid. */
    private String createRadiologyType(String tag) throws Exception {
        String body = """
                {"code":"RT-%s","name":"RadType %s","description":null,"price":8000.00,"active":true}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(RAD_TYPES_URL)
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

    /** Order a radiology exam via HTTP POST and return its uid. */
    private String orderRadiology(String consultUid, String radTypeUid) throws Exception {
        MvcResult r = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/radiologies")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(radTypeUid)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private void assertRadiologyStatus(String radUid, String expectedStatus) throws Exception {
        mockMvc.perform(get(RAD_BASE + "/uid/" + radUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    private static String orderBody(String radiologyTypeUid) {
        return """
                {"radiologyTypeUid":"%s"}
                """.formatted(radiologyTypeUid);
    }

    private static String orderBodyForNonConsult(String radiologyTypeUid, String patientUid,
                                                  String paymentType) {
        return """
                {"radiologyTypeUid":"%s","patientUid":"%s","paymentType":"%s"}
                """.formatted(radiologyTypeUid, patientUid, paymentType);
    }

    private static String verifyBody(String result, String report, byte[] attachment) {
        String attVal = attachment == null ? "null" : "[]";
        return """
                {"result":%s,"report":%s,"attachment":%s}
                """.formatted(js(result), js(report), attVal);
    }

    private static String attachmentBody(String name, String fileName) {
        return """
                {"name":"%s","fileName":"%s"}
                """.formatted(name, fileName);
    }

    private static String js(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }
}
