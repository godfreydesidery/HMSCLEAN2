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
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientCreditNoteRepository;
import com.otapp.hmis.billing.domain.PatientPaymentDetailRepository;
import com.otapp.hmis.billing.domain.PaymentDetailStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
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
 * Integration tests for inc-05 C7: LabTest aggregate + attachments.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Order on consultation → 201 PENDING + bill created (settled correctly for INSURANCE).</li>
 *   <li>Duplicate same type on same consultation → 422 verbatim message.</li>
 *   <li>Full happy path: PENDING → ACCEPTED → COLLECTED → VERIFIED (verify writes result fields).</li>
 *   <li>Reject from PENDING → REJECTED (clears accept fields); accept from REJECTED → ACCEPTED
 *       (clears reject fields).</li>
 *   <li>Hold: ACCEPTED → PENDING (stamps held_* audit fields).</li>
 *   <li>Collect on non-ACCEPTED → 422 "Please accept the lab test first".</li>
 *   <li>Verify on non-COLLECTED → 422 "Please collect the lab test first".</li>
 *   <li>Delete PENDING → 204 ok; delete non-PENDING → 422.</li>
 *   <li>INSURANCE order → settled=true; CASH order → settled=false.</li>
 *   <li>Worklist filters settled=true only.</li>
 *   <li>Attachment: add only when COLLECTED (before COLLECTED → 422); max 5 (6th → 422).</li>
 *   <li>Delete attachment blocked when VERIFIED.</li>
 *   <li>OUTSIDER (non-consultation) order.</li>
 *   <li>401 on all key endpoints without a token.</li>
 *   <li>V33 schema: patient_id dropped, patient_uid added, settled column added.</li>
 * </ol>
 */
class LabTestIT extends AbstractIntegrationTest {

    private static final String BASE           = "/api/v1/clinical";
    private static final String CONSULT_BASE   = BASE + "/consultations/uid/";
    private static final String NC_BASE        = BASE + "/non-consultations/uid/";
    private static final String LAB_BASE       = BASE + "/lab-tests";
    private static final String PROVIDERS_URL  = "/api/v1/masterdata/insurance-providers";
    private static final String PRICES_URL     = "/api/v1/masterdata/service-prices";
    private static final String LAB_TYPES_URL  = "/api/v1/masterdata/lab-test-types";

    @Autowired MockMvc                  mockMvc;
    @Autowired ObjectMapper             objectMapper;
    @Autowired TestJwtFactory           jwtFactory;
    @Autowired ConsultationRepository   consultationRepository;
    @Autowired NonConsultationRepository nonConsultationRepository;
    @Autowired LabTestRepository        labTestRepository;
    @Autowired PatientBillRepository    billRepository;
    @Autowired PatientPaymentDetailRepository paymentDetailRepository;
    @Autowired PatientCreditNoteRepository    creditNoteRepository;
    @Autowired BusinessDayService       businessDayService;
    @Autowired DataSource               dataSource;

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
        String labTypeUid = createLabTestType(tag);
        seedPrice(null,    "LAB_TEST", labTypeUid, "5000.00", true);
        String planUid    = createPlan(tag);
        seedPrice(planUid, "LAB_TEST", labTypeUid, "3500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.INSURANCE, planUid, true);

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/lab-tests")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(labTypeUid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.labTestTypeUid").value(labTypeUid))
                .andExpect(jsonPath("$.patientBillUid").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String labUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(labUid).isNotBlank();
    }

    // =========================================================================
    // Order on consultation — CASH → settled=false
    // =========================================================================

    @Test
    void order_cashConsultation_settled_false() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "4000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/lab-tests")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(labTypeUid)))
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
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "3000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // First order — succeeds
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/lab-tests")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(labTypeUid)))
                .andExpect(status().isCreated());

        // Second order — same type, same consultation → 422
        mockMvc.perform(post(CONSULT_BASE + consultUid + "/lab-tests")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(labTypeUid)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate lab test type is not allowed for this encounter"));
    }

    // =========================================================================
    // Unknown lab test type → 404
    // =========================================================================

    @Test
    void order_unknownLabTestType_404() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        mockMvc.perform(post(CONSULT_BASE + consultUid + "/lab-tests")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("BADLTTUID0000000000000000001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Lab test type not found"));
    }

    // =========================================================================
    // Full happy path: PENDING → ACCEPTED → COLLECTED → VERIFIED
    // =========================================================================

    @Test
    void fullHappyPath_pendingToVerified() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "6000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // Order → PENDING
        String labUid = orderLabTest(consultUid, labTypeUid);
        assertLabStatus(labUid, "PENDING");

        // Accept → ACCEPTED
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.acceptedAt").isNotEmpty());

        // Collect → COLLECTED
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTED"))
                .andExpect(jsonPath("$.collectedByUserUid").isNotEmpty());

        // Verify → VERIFIED (with result fields)
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("12.5", "NORMAL", "10-15", "g/dL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.result").value("12.5"))
                .andExpect(jsonPath("$.level").value("NORMAL"))
                .andExpect(jsonPath("$.testRange").value("10-15"))
                .andExpect(jsonPath("$.unit").value("g/dL"))
                .andExpect(jsonPath("$.verifiedByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
    }

    // =========================================================================
    // Reject from PENDING → clears accept fields; accept from REJECTED → clears reject fields
    // =========================================================================

    @Test
    void reject_fromPending_setsRejectComment_acceptFromRejected_clearsReject() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "5500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Reject from PENDING
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Insufficient sample\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectComment").value("Insufficient sample"))
                .andExpect(jsonPath("$.rejectedByUserUid").isNotEmpty());

        // Accept from REJECTED → ACCEPTED, reject fields cleared
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.rejectComment").isEmpty())
                .andExpect(jsonPath("$.acceptedByUserUid").isNotEmpty());
    }

    // =========================================================================
    // C3 (ITEM3): save_reason_for_rejection — re-callable rejectComment edit on a
    // REJECTED order; edit on a non-REJECTED order → 422 verbatim.
    // =========================================================================

    @Test
    void saveRejectComment_onRejected_editsComment_reCallable() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "5500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Reject first.
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Initial reason\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Edit the reject comment (status stays REJECTED).
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/reject-comment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Corrected reason\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectComment").value("Corrected reason"));

        // Re-callable: edit again.
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/reject-comment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Second correction\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectComment").value("Second correction"));
    }

    @Test
    void saveRejectComment_onNonRejected_422_verbatim() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "5500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);  // PENDING (not REJECTED)

        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/reject-comment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectComment\":\"Should fail\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not save. Only allowed for rejected tests"));
    }

    // =========================================================================
    // Hold: ACCEPTED → PENDING
    // =========================================================================

    @Test
    void hold_fromAccepted_revertsToP_stampsHeldAudit() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "4500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Accept first
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // Hold → PENDING
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/hold")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.heldByUserUid").isNotEmpty())
                .andExpect(jsonPath("$.heldAt").isNotEmpty());
    }

    // =========================================================================
    // Collect on non-ACCEPTED → 422
    // =========================================================================

    @Test
    void collect_onNonAccepted_422() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "3500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Lab is PENDING — collect without accepting
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Please accept the lab test first"));
    }

    // =========================================================================
    // Verify on non-COLLECTED → 422
    // =========================================================================

    @Test
    void verify_onNonCollected_422() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "3500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Accept (ACCEPTED) but don't collect
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify on ACCEPTED (not COLLECTED) → 422
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("12.5", null, null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Please collect the lab test first"));
    }

    // =========================================================================
    // Delete PENDING → 204; delete non-PENDING → 422
    // =========================================================================

    @Test
    void delete_pending_204_deleteNonPending_422() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "2500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);
        assertLabStatus(labUid, "PENDING");

        // Delete PENDING → 204
        mockMvc.perform(delete(LAB_BASE + "/uid/" + labUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Confirm deleted
        assertThat(labTestRepository.findByUid(labUid)).isEmpty();

        // Delete again (404 since it's gone)
        mockMvc.perform(delete(LAB_BASE + "/uid/" + labUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_nonPending_422() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "2500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Accept → ACCEPTED
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Delete ACCEPTED → 422
        mockMvc.perform(delete(LAB_BASE + "/uid/" + labUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not delete, only a PENDING lab test can be deleted"));
    }

    // =========================================================================
    // C1 (ITEM1): deleting a PENDING lab test whose bill was already PAID raises the
    // credit-note reversal via billing.api.cancelCharge — bill→CANCELED, payment→REFUNDED,
    // a PENDING PatientCreditNote (ref "Canceled lab test") for the full amount.
    // (Legacy PatientResource.java:2922-2961; soft-flag + CR-10 fix per the ratified standard.)
    // =========================================================================

    @Test
    void delete_paidPendingLabTest_reversesBill_refunds_raisesCreditNote() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        // CASH order so a real billable PatientBill exists and can be paid at the cashier.
        seedPrice(null, "LAB_TEST", labTypeUid, "2500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // Order → PENDING; capture the order's real bill uid from the DTO.
        MvcResult orderResult = mockMvc.perform(post(CONSULT_BASE + consultUid + "/lab-tests")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labTestTypeUid\":\"" + labTypeUid + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode orderNode = objectMapper.readTree(orderResult.getResponse().getContentAsString());
        String labUid  = orderNode.get("uid").asText();
        String billUid = orderNode.get("patientBillUid").asText();
        assertThat(billUid).isNotBlank();

        // Pay the bill at the cashier → PatientPaymentDetail RECEIVED, bill PAID.
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billUids\":[\"" + billUid + "\"],"
                                + "\"tenderedTotal\":{\"amount\":2500.00,\"currency\":\"TZS\"},"
                                + "\"paymentMode\":\"CASH\"}"))
                .andExpect(status().isCreated());
        assertThat(paymentDetailRepository.findByBillUid(billUid).orElseThrow().getStatus())
                .isEqualTo(PaymentDetailStatus.RECEIVED);

        // Delete the still-PENDING lab test → seam fires.
        mockMvc.perform(delete(LAB_BASE + "/uid/" + labUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Order row gone.
        assertThat(labTestRepository.findByUid(labUid)).isEmpty();

        // Bill soft-canceled (NOT hard-deleted — ratified deviation).
        assertThat(billRepository.findByUid(billUid).orElseThrow().getStatus())
                .as("deleted lab test's bill must be CANCELED").isEqualTo(BillStatus.CANCELED);

        // Payment refunded (soft, kept).
        assertThat(paymentDetailRepository.findByBillUid(billUid).orElseThrow().getStatus())
                .as("RECEIVED payment must flip to REFUNDED").isEqualTo(PaymentDetailStatus.REFUNDED);

        // A PENDING credit-note for this bill, full amount, legacy reference label.
        boolean creditNoteRaised = creditNoteRepository.findAll().stream()
                .anyMatch(cn -> billUid.equals(cn.getPatientBillUid())
                        && "Canceled lab test".equals(cn.getReference()));
        assertThat(creditNoteRaised)
                .as("a PENDING credit-note (ref 'Canceled lab test') must be raised for the paid bill")
                .isTrue();
    }

    // =========================================================================
    // Worklist filters settled=true
    // =========================================================================

    @Test
    void worklist_onlyShowsSettledOrders() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        String planUid    = createPlan(tag + "WL");
        seedPrice(null,    "LAB_TEST", labTypeUid, "5000.00", true);
        seedPrice(planUid, "LAB_TEST", labTypeUid, "3000.00", true);

        // INSURANCE consultation → settled=true
        String consultIns  = seedConsultation(tag + "WLI", PaymentMode.INSURANCE, planUid, true);
        String labInsUid   = orderLabTest(consultIns, labTypeUid);

        // Second lab type for CASH
        String labTypeUid2 = createLabTestType(tag + "B");
        seedPrice(null, "LAB_TEST", labTypeUid2, "4000.00", true);
        String consultCash = seedConsultation(tag + "WLC", PaymentMode.CASH, null, false);
        orderLabTest(consultCash, labTypeUid2);

        // Worklist should contain the INSURANCE order (settled=true) but NOT the CASH (settled=false)
        MvcResult wl = mockMvc.perform(get(LAB_BASE + "/worklist")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(wl.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean foundIns  = false;
        boolean foundCash = false;
        for (JsonNode node : array) {
            if (labInsUid.equals(node.get("uid").asText())) {
                foundIns = true;
                assertThat(node.get("settled").asBoolean()).isTrue();
            }
            // Cash lab should not appear in worklist
            if (labTypeUid2.equals(node.get("labTestTypeUid").asText())
                    && !node.get("settled").asBoolean()) {
                foundCash = true;
            }
        }
        assertThat(foundIns).as("INSURANCE settled order must be in worklist").isTrue();
        assertThat(foundCash).as("CASH unsettled order must NOT be in worklist").isFalse();
    }

    // =========================================================================
    // QA-06: worklist excludes VERIFIED; a settled COLLECTED order is present.
    // The lab worklist query is settled=true AND status IN {PENDING,ACCEPTED,COLLECTED}
    // (LabTestRepository.findBySettledAndStatusInOrderByCreatedAtAsc) — VERIFIED is
    // terminal and must drop off the queue.
    // =========================================================================

    @Test
    void worklist_excludesVerified_includesCollected() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        String planUid    = createPlan(tag + "WV");
        seedPrice(null,    "LAB_TEST", labTypeUid, "5000.00", true);
        seedPrice(planUid, "LAB_TEST", labTypeUid, "3000.00", true);

        // Order A (INSURANCE → settled): drive to COLLECTED → must be ON the worklist.
        String consultCollected = seedConsultation(tag + "WVC", PaymentMode.INSURANCE, planUid, true);
        String collectedUid     = orderLabTest(consultCollected, labTypeUid);
        mockMvc.perform(post(LAB_BASE + "/uid/" + collectedUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + collectedUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTED"));

        // Order B (INSURANCE → settled): drive to VERIFIED → must be ABSENT from the worklist.
        String consultVerified = seedConsultation(tag + "WVV", PaymentMode.INSURANCE, planUid, true);
        String verifiedUid     = orderLabTest(consultVerified, labTypeUid);
        mockMvc.perform(post(LAB_BASE + "/uid/" + verifiedUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + verifiedUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + verifiedUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("13.0", "NORMAL", "10-15", "g/dL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        MvcResult wl = mockMvc.perform(get(LAB_BASE + "/worklist")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(wl.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean foundCollected = false;
        boolean foundVerified  = false;
        for (JsonNode node : array) {
            if (collectedUid.equals(node.get("uid").asText())) {
                foundCollected = true;
                assertThat(node.get("status").asText()).isEqualTo("COLLECTED");
            }
            if (verifiedUid.equals(node.get("uid").asText())) {
                foundVerified = true;
            }
        }
        assertThat(foundCollected)
                .as("settled COLLECTED order must be in the worklist").isTrue();
        assertThat(foundVerified)
                .as("VERIFIED order must NOT be in the worklist").isFalse();
    }

    // =========================================================================
    // Attachments: add only when COLLECTED; max 5; delete blocked when VERIFIED
    // =========================================================================

    @Test
    void attachment_addBeforeCollected_422() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "7000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);
        // Lab is PENDING — try to attach before COLLECTED
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("Report A", "file-" + tag + "-a.pdf")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Can only attach for collected tests"));
    }

    @Test
    void attachment_max5_6thRejected() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "7000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Accept → Collect
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Add 5 attachments — all succeed
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/attachments")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(attachmentBody("Report " + i,
                                    "file-" + tag + "-" + i + ".pdf")))
                    .andExpect(status().isCreated());
        }

        // 6th attachment → 422 max exceeded
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("Report 6",
                                "file-" + tag + "-6.pdf")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Can not add more than 5 attachments"));
    }

    @Test
    void attachment_deleteBlockedWhenVerified() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "8000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Full lifecycle to COLLECTED
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Add one attachment
        MvcResult addResult = mockMvc.perform(
                        post(LAB_BASE + "/uid/" + labUid + "/attachments")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(attachmentBody("CBC", "file-" + tag + "-del.pdf")))
                .andExpect(status().isCreated())
                .andReturn();
        String attUid = objectMapper.readTree(addResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Verify the order → VERIFIED
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("14.0", "NORMAL", "12-16", "g/dL")))
                .andExpect(status().isOk());

        // Try to delete attachment when VERIFIED → 422
        mockMvc.perform(delete(LAB_BASE + "/attachments/uid/" + attUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Cannot delete attachment from a verified lab test"));
    }

    // =========================================================================
    // List attachments
    // =========================================================================

    @Test
    void listAttachments_returnsAllForLabTest() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "6500.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        // Accept → Collect
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Add 2 attachments
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("CBC", "file-" + tag + "-1.pdf")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/attachments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attachmentBody("LFT", "file-" + tag + "-2.pdf")))
                .andExpect(status().isCreated());

        // List
        MvcResult listResult = mockMvc.perform(
                        get(LAB_BASE + "/uid/" + labUid + "/attachments")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        array.forEach(n -> assertThat(n.has("id")).isFalse());
    }

    // =========================================================================
    // OUTSIDER (non-consultation) order
    // =========================================================================

    @Test
    void order_onNonConsultation_201_pending() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "5000.00", true);
        String ncUid = seedNonConsultation(tag);

        MvcResult result = mockMvc.perform(
                        post(NC_BASE + ncUid + "/lab-tests")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBodyForNonConsult(labTypeUid,
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
    // saveResult and addReport (COLLECTED, no status change)
    // =========================================================================

    @Test
    void saveResult_andAddReport_billGated() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "4200.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        // Order via raw endpoint to capture the bill uid (CASH → bill UNPAID).
        MvcResult orderResult = mockMvc.perform(post(CONSULT_BASE + consultUid + "/lab-tests")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labTestTypeUid\":\"" + labTypeUid + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode orderNode = objectMapper.readTree(orderResult.getResponse().getContentAsString());
        String labUid  = orderNode.get("uid").asText();
        String billUid = orderNode.get("patientBillUid").asText();

        // Accept → Collect
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // saveResult (COLLECTED gate, unchanged)
        mockMvc.perform(put(LAB_BASE + "/uid/" + labUid + "/result")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"Positive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("Positive"))
                .andExpect(jsonPath("$.status").value("COLLECTED"));

        // addReport BEFORE payment → 422 bill-gate (legacy parity, C5).
        mockMvc.perform(put(LAB_BASE + "/uid/" + labUid + "/report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Should be blocked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not add report. Payment not verified"));

        // Pay the bill at the cashier → bill PAID.
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billUids\":[\"" + billUid + "\"],"
                                + "\"tenderedTotal\":{\"amount\":4200.00,\"currency\":\"TZS\"},"
                                + "\"paymentMode\":\"CASH\"}"))
                .andExpect(status().isCreated());

        // addReport AFTER payment → 200 (bill PAID; order still COLLECTED, not VERIFIED).
        mockMvc.perform(put(LAB_BASE + "/uid/" + labUid + "/report")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Detailed report text here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").value("Detailed report text here"))
                .andExpect(jsonPath("$.status").value("COLLECTED"));
    }

    // =========================================================================
    // Get by uid
    // =========================================================================

    @Test
    void getByUid_200_noIdLeak() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "3800.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);
        String labUid = orderLabTest(consultUid, labTypeUid);

        mockMvc.perform(get(LAB_BASE + "/uid/" + labUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(labUid))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_404_unknown() throws Exception {
        mockMvc.perform(get(LAB_BASE + "/uid/NONEXISTENTLABTEST000000001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // List by consultation
    // =========================================================================

    @Test
    void listForConsultation_returnsOrders() throws Exception {
        String tag = uniq();
        String ltt1 = createLabTestType(tag + "A");
        String ltt2 = createLabTestType(tag + "B");
        seedPrice(null, "LAB_TEST", ltt1, "2000.00", true);
        seedPrice(null, "LAB_TEST", ltt2, "3000.00", true);
        String consultUid = seedConsultation(tag, PaymentMode.CASH, null, false);

        orderLabTest(consultUid, ltt1);
        orderLabTest(consultUid, ltt2);

        MvcResult result = mockMvc.perform(
                        get(CONSULT_BASE + consultUid + "/lab-tests")
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
    void byPatient_returnsLabTestsForPatient() throws Exception {
        String tag = uniq();
        String labTypeUid = createLabTestType(tag);
        seedPrice(null, "LAB_TEST", labTypeUid, "4400.00", true);
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationWithPatient(tag, PaymentMode.CASH, null, false, patUid);

        orderLabTest(consultUid, labTypeUid);

        MvcResult result = mockMvc.perform(
                        get(LAB_BASE + "?patientUid=" + patUid)
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
        String uid = "NOLABTEST000000000000000001";
        String cUid = "NOCONSULT000000000000000001";

        mockMvc.perform(post(CONSULT_BASE + cUid + "/lab-tests")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody("X")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LAB_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LAB_BASE + "/uid/" + uid + "/accept"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LAB_BASE + "/uid/" + uid + "/reject"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LAB_BASE + "/uid/" + uid + "/collect"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LAB_BASE + "/uid/" + uid + "/verify"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LAB_BASE + "/uid/" + uid + "/hold"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(LAB_BASE + "/uid/" + uid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LAB_BASE + "/worklist"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LAB_BASE + "?patientUid=SOMEPATIENT0000000000000001"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LAB_BASE + "/uid/" + uid + "/attachments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LAB_BASE + "/uid/" + uid + "/attachments")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(LAB_BASE + "/attachments/uid/ATTUID0000000000000000001"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // V33 schema assertions
    // =========================================================================

    @Test
    void v33_labTests_patientIdDropped_patientUidAdded_settledAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'lab_tests' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("lab_tests.patient_id must be dropped by V33").isZero();

        // patient_uid must exist
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'lab_tests' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("lab_tests.patient_uid must exist after V33").isEqualTo(1);

        // fk_lab_tests_patient constraint must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'lab_tests' "
                        + "AND constraint_name = 'fk_lab_tests_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_lab_tests_patient must be dropped by V33").isZero();

        // patient_uid index must exist
        Integer idxPatientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'lab_tests' "
                        + "AND indexname = 'idx_lab_tests_patient_uid'",
                Integer.class);
        assertThat(idxPatientUidCount)
                .as("idx_lab_tests_patient_uid must exist after V33").isEqualTo(1);

        // settled column must exist
        Integer settledCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'lab_tests' AND column_name = 'settled'",
                Integer.class);
        assertThat(settledCount)
                .as("lab_tests.settled must exist after V33").isEqualTo(1);

        // settled index must exist
        Integer idxSettledCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'lab_tests' "
                        + "AND indexname = 'idx_lab_tests_settled'",
                Integer.class);
        assertThat(idxSettledCount)
                .as("idx_lab_tests_settled must exist after V33").isEqualTo(1);
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "C7" + Long.toHexString(System.nanoTime()).substring(0, 10);
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

    /** Create a LabTestType via the masterdata REST API. Returns its uid. */
    private String createLabTestType(String tag) throws Exception {
        String body = """
                {"code":"LTT-%s","name":"LabType %s","description":null,"price":5000.00,"active":true}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(LAB_TYPES_URL)
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

    /**
     * Seed a consultation directly via repository (PENDING, settled per parameter).
     * Uses a synthetic patientUid derived from the tag.
     */
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
        // Force to IN_PROCESS so lab orders can be placed (legacy: lab ordered when consultation open)
        c.open();
        return consultationRepository.saveAndFlush(c).getUid();
    }

    /**
     * Seed an IN_PROCESS NonConsultation directly via repository.
     */
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

    /** Order a lab test via HTTP POST and return its uid. */
    private String orderLabTest(String consultUid, String labTypeUid) throws Exception {
        MvcResult r = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/lab-tests")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderBody(labTypeUid)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    /** Assert the current status of a lab test via GET. */
    private void assertLabStatus(String labUid, String expectedStatus) throws Exception {
        mockMvc.perform(get(LAB_BASE + "/uid/" + labUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    private static String orderBody(String labTestTypeUid) {
        return """
                {"labTestTypeUid":"%s"}
                """.formatted(labTestTypeUid);
    }

    private static String orderBodyForNonConsult(String labTestTypeUid, String patientUid,
                                                  String paymentType) {
        return """
                {"labTestTypeUid":"%s","patientUid":"%s","paymentType":"%s"}
                """.formatted(labTestTypeUid, patientUid, paymentType);
    }

    private static String verifyBody(String result, String level, String testRange, String unit) {
        return """
                {"result":%s,"level":%s,"testRange":%s,"unit":%s}
                """.formatted(js(result), js(level), js(testRange), js(unit));
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
