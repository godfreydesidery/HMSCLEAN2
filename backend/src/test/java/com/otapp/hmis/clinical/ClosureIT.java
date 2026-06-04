package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.clinical.domain.DeceasedNoteRepository;
import com.otapp.hmis.clinical.domain.DeceasedNoteStatus;
import com.otapp.hmis.clinical.domain.LabTest;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.clinical.domain.ReferralPlanRepository;
import com.otapp.hmis.clinical.domain.ReferralPlanStatus;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.time.Instant;
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
 * Integration tests for inc-05 C12: OPD Closure — DeceasedNote + ReferralPlan.
 *
 * <p>Coverage:
 * <ol>
 *   <li>save_deceased_note: 201 + consultation→HELD + note PENDING.</li>
 *   <li>save_deceased_note: missing summary/cause → 422 verbatim message.</li>
 *   <li>save_deceased_note: reuse-if-exists (second call updates note, still PENDING).</li>
 *   <li>approve_deceased: when HELD + no unsettled orders → SIGNED_OUT + APPROVED + Patient.type=DECEASED (KEY end-to-end event test).</li>
 *   <li>approve_deceased: when consultation not HELD → silent no-op (status unchanged).</li>
 *   <li>approve_deceased: when unsettled lab order exists → 422 "uncleared bills".</li>
 *   <li>list_deceased: hides ARCHIVED (by status-filter contract); shows PENDING and APPROVED.</li>
 *   <li>save_referral_plan: 201 + consultation→SIGNED_OUT + plan PENDING + Patient.paymentType=CASH (insurance-cleared event).</li>
 *   <li>save_referral_plan: existing PENDING plan → 422.</li>
 *   <li>save_referral_plan: unsettled clinical order → 422 (referral gate UNPAID-only).</li>
 *   <li>approve_referral: plan→APPROVED + consultation re-SIGNED_OUT.</li>
 *   <li>list_referrals: returns PENDING|APPROVED.</li>
 *   <li>401 on all key endpoints without a token.</li>
 *   <li>V37 schema: patient_id dropped, patient_uid added on both tables.</li>
 * </ol>
 *
 * <p><strong>KEY test — deceased event end-to-end (test 4):</strong>
 * Seeds a real {@link Patient} in the DB, seeds a {@link Consultation} with that patient's uid,
 * calls save_deceased_note (→ HELD + PENDING note), then calls approve (→ SIGNED_OUT + APPROVED).
 * After approval, loads the Patient via PatientRepository and asserts {@code type == DECEASED}.
 * This proves the cross-module event seam (PatientDeceasedEvent → PatientClosureListener →
 * Patient.changeType(DECEASED)) works end-to-end within the same transaction.
 *
 * <p><strong>UNPAID-vs-UNPAID|VERIFIED asymmetry (CR-INC05-09):</strong>
 * Both the deceased gate and the referral gate are currently approximated via {@code settled=false}.
 * The asymmetry is preserved structurally (two separate gate methods in ClosureService) even though
 * they currently compute the same result. Test 10 (referral gate) verifies the referral gate blocks
 * on an unsettled order; the same assertion would apply to the deceased gate (test 6). A comment in
 * the test marks this structural note.
 */
class ClosureIT extends AbstractIntegrationTest {

    private static final String BASE          = "/api/v1/clinical";
    private static final String CONSULT_BASE  = BASE + "/consultations/uid/";
    private static final String DECEASED_BASE = BASE + "/deceased-notes";
    private static final String REFERRAL_BASE = BASE + "/referrals";
    private static final String PATIENTS_URL  = "/api/v1/patients";
    private static final String PRICES_URL    = "/api/v1/masterdata/service-prices";

    @Autowired MockMvc                  mockMvc;
    @Autowired ObjectMapper             objectMapper;
    @Autowired TestJwtFactory           jwtFactory;
    @Autowired ConsultationRepository   consultationRepository;
    @Autowired DeceasedNoteRepository   deceasedNoteRepository;
    @Autowired ReferralPlanRepository   referralPlanRepository;
    @Autowired LabTestRepository        labTestRepository;
    @Autowired PatientRepository        patientRepository;
    @Autowired BusinessDayService       businessDayService;
    @Autowired DataSource               dataSource;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
        // seedRealPatient registers via POST /patients, which charges a REGISTRATION fee through
        // billing — that requires a REGISTRATION cash price to exist (else the charge 422s).
        ensureRegistrationCashPrice();
    }

    private void ensureRegistrationCashPrice() throws Exception {
        String body = """
                {"planUid":null,"kind":"REGISTRATION","serviceUid":null,"currency":"TZS",
                 "amount":500.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """;
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }

    // =========================================================================
    // 1. save_deceased_note → 201 + HELD + PENDING
    // =========================================================================

    @Test
    void saveDeceasedNote_201_heldAndPending() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationInProcess(tag, patUid);

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/deceased-note")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deceasedNoteBody("Patient summary", "Heart failure",
                                        null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.patientSummary").value("Patient summary"))
                .andExpect(jsonPath("$.causeOfDeath").value("Heart failure"))
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String noteUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(noteUid).isNotBlank();

        // Consultation must be HELD
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(c.getStatus()).isEqualTo(ConsultationStatus.HELD);

        // Note must be PENDING
        assertThat(deceasedNoteRepository.findByUid(noteUid).orElseThrow().getStatus())
                .isEqualTo(DeceasedNoteStatus.PENDING);
    }

    // =========================================================================
    // 2. save_deceased_note: missing summary/cause → 422 verbatim
    // =========================================================================

    @Test
    void saveDeceasedNote_missingSummary_422_verbatim() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultationInProcess(tag, fakeUid("PAT", tag));

        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/deceased-note")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deceasedNoteBody("", "Heart failure", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Summary and cause of death are missing"));
    }

    @Test
    void saveDeceasedNote_missingCauseOfDeath_422_verbatim() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultationInProcess(tag, fakeUid("PAT", tag));

        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/deceased-note")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deceasedNoteBody("Some summary", null, null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Summary and cause of death are missing"));
    }

    // =========================================================================
    // 3. save_deceased_note: reuse-if-exists (idempotent save)
    // =========================================================================

    @Test
    void saveDeceasedNote_reuseIfExists_updatesInPlace() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationInProcess(tag, patUid);

        // First save
        String firstNoteUid = postDeceasedNote(consultUid, "First summary", "Cause A");

        // Second save — same consultation, different narrative
        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/deceased-note")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deceasedNoteBody("Updated summary", "Cause B", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").value(firstNoteUid))  // same uid — reuse
                .andExpect(jsonPath("$.patientSummary").value("Updated summary"))
                .andExpect(jsonPath("$.causeOfDeath").value("Cause B"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Only one note should exist for this consultation
        assertThat(deceasedNoteRepository.findAll()
                .stream()
                .filter(n -> firstNoteUid.equals(n.getUid()))
                .count()).isEqualTo(1);
    }

    // =========================================================================
    // 4. approve_deceased: HELD + no unsettled orders → SIGNED_OUT + APPROVED
    //    + Patient.type = DECEASED  ← KEY end-to-end event seam test
    // =========================================================================

    @Test
    void approveDeceased_held_noUnsettledOrders_signedOut_approved_patientDeceased()
            throws Exception {
        String tag = uniq();

        // Seed a REAL patient in the DB (needed for the event listener to find + mutate it)
        Patient patient = seedRealPatient(tag);
        String patUid = patient.getUid();

        // Seed consultation bound to that real patient (IN_PROCESS so it can be held)
        String consultUid = seedConsultationInProcess(tag, patUid);

        // Save deceased note (moves consultation → HELD)
        String noteUid = postDeceasedNote(consultUid, "Full summary", "Cardiac arrest");

        // Verify HELD before approve
        assertThat(consultationRepository.findByUid(consultUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.HELD);

        // Approve (no unsettled orders — no lab/radiology/procedure/prescription seeded)
        mockMvc.perform(
                        post(DECEASED_BASE + "/uid/" + noteUid + "/approve")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByUserUid").value("admin"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());

        // Consultation must be SIGNED_OUT
        assertThat(consultationRepository.findByUid(consultUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.SIGNED_OUT);

        // Note must be APPROVED
        assertThat(deceasedNoteRepository.findByUid(noteUid).orElseThrow().getStatus())
                .isEqualTo(DeceasedNoteStatus.APPROVED);

        // KEY assertion: Patient.type must be DECEASED (cross-module event seam worked)
        Patient updated = patientRepository.findByUid(patUid).orElseThrow();
        assertThat(updated.getType())
                .as("Patient.type must be DECEASED after deceased note approval "
                        + "(PatientDeceasedEvent → PatientClosureListener)")
                .isEqualTo(PatientType.DECEASED);
    }

    // =========================================================================
    // 5. approve_deceased: consultation not HELD → silent no-op
    // =========================================================================

    @Test
    void approveDeceased_notHeld_silentNoOp() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);

        // Seed consultation in IN_PROCESS (not HELD)
        String consultUid = seedConsultationInProcess(tag, patUid);

        // Manually create a PENDING deceased note for this consultation (bypass service to control state)
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        com.otapp.hmis.clinical.domain.DeceasedNote note =
                new com.otapp.hmis.clinical.domain.DeceasedNote(
                        c, patUid, "Summary", "Cause", null, null, dayUid);
        deceasedNoteRepository.saveAndFlush(note);
        String noteUid = note.getUid();

        // Approve — consultation is IN_PROCESS (not HELD) → silent no-op
        mockMvc.perform(
                        post(DECEASED_BASE + "/uid/" + noteUid + "/approve")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));  // unchanged

        // Consultation remains IN_PROCESS
        assertThat(consultationRepository.findByUid(consultUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.IN_PROCESS);
    }

    // =========================================================================
    // 6. approve_deceased: unsettled lab order → 422 "uncleared bills"
    //    (UNPAID|VERIFIED gate — currently approximated via settled=false)
    // =========================================================================

    @Test
    void approveDeceased_unsettledLabOrder_422_unclearedBills() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationInProcess(tag, patUid);

        // Save deceased note → HELD
        String noteUid = postDeceasedNote(consultUid, "Summary", "Cause");

        // Seed an unsettled lab test for this consultation (settled=false)
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        seedUnsettledLabTest(c, tag);

        // Approve → 422 (bill gate)
        mockMvc.perform(
                        post(DECEASED_BASE + "/uid/" + noteUid + "/approve")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not get deceased summary. Patient have uncleared bills."));
    }

    // =========================================================================
    // 7. list_deceased: hides ARCHIVED; shows PENDING and APPROVED
    // =========================================================================

    @Test
    void listDeceased_hideArchived_showsPendingAndApproved() throws Exception {
        String tag = uniq();
        Patient patient = seedRealPatient(tag + "L");
        String patUid = patient.getUid();
        String consultUid = seedConsultationInProcess(tag + "L", patUid);

        // Create a PENDING note via the endpoint
        String pendingNoteUid = postDeceasedNote(consultUid, "List test summary", "List cause");

        // The list must contain this note
        MvcResult listResult = mockMvc.perform(
                        get(DECEASED_BASE)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean found = false;
        for (JsonNode node : array) {
            if (pendingNoteUid.equals(node.get("uid").asText())) {
                found = true;
                assertThat(node.get("status").asText()).isIn("PENDING", "APPROVED");
                // ARCHIVED must not appear
                assertThat(node.get("status").asText()).isNotEqualTo("ARCHIVED");
            }
            // Double-check: no ARCHIVED note in the list (by contract)
            assertThat(node.get("status").asText()).isNotEqualTo("ARCHIVED");
        }
        assertThat(found).as("The seeded PENDING note must appear in the list").isTrue();
    }

    // =========================================================================
    // 8. save_referral_plan: 201 + SIGNED_OUT + PENDING + Patient→CASH
    //    (PatientInsuranceClearedEvent end-to-end)
    // =========================================================================

    @Test
    void saveReferralPlan_201_signedOut_pending_patientCashInsuranceCleared() throws Exception {
        String tag = uniq();

        // Seed a real INSURANCE patient so we can verify the insurance gets cleared
        Patient patient = seedRealInsurancePatient(tag);
        String patUid = patient.getUid();
        assertThat(patient.getPaymentType()).isEqualTo(PaymentType.INSURANCE);

        String consultUid = seedConsultationInProcess(tag, patUid);
        String extProviderUid = fakeUid("EXT", tag);

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/referral")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(referralBody(extProviderUid, "Diabetes", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.externalMedicalProviderUid").value(extProviderUid))
                .andExpect(jsonPath("$.referringDiagnosis").value("Diabetes"))
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String planUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(planUid).isNotBlank();

        // Consultation must be SIGNED_OUT
        assertThat(consultationRepository.findByUid(consultUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.SIGNED_OUT);

        // Plan must be PENDING
        assertThat(referralPlanRepository.findByUid(planUid).orElseThrow().getStatus())
                .isEqualTo(ReferralPlanStatus.PENDING);

        // KEY assertion: Patient.paymentType must be CASH (PatientInsuranceClearedEvent)
        Patient updated = patientRepository.findByUid(patUid).orElseThrow();
        assertThat(updated.getPaymentType())
                .as("Patient.paymentType must be CASH after referral save "
                        + "(PatientInsuranceClearedEvent → PatientClosureListener)")
                .isEqualTo(PaymentType.CASH);
        assertThat(updated.getInsurancePlanUid())
                .as("Patient.insurancePlanUid must be null after insurance cleared")
                .isNull();
    }

    // =========================================================================
    // 9. save_referral_plan: existing PENDING plan → 422
    // =========================================================================

    @Test
    void saveReferralPlan_existingPending_422() throws Exception {
        String tag = uniq();
        Patient patient = seedRealPatient(tag + "R");
        String consultUid = seedConsultationInProcess(tag + "R", patient.getUid());
        String extProviderUid = fakeUid("EXT", tag);

        // First referral
        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/referral")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(referralBody(extProviderUid, "Diagnosis A", null)))
                .andExpect(status().isCreated());

        // Second referral on same consultation → 422
        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/referral")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(referralBody(extProviderUid, "Diagnosis B", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("A pending referral plan already exists for this consultation"));
    }

    // =========================================================================
    // 10. save_referral_plan: unsettled clinical order → 422 (UNPAID-only gate)
    //     CR-INC05-09 asymmetry: referral gate is UNPAID-only (vs UNPAID|VERIFIED for deceased).
    //     Currently both map to settled=false; structural separation is preserved in ClosureService.
    // =========================================================================

    @Test
    void saveReferralPlan_unsettledOrder_422_referralGateUnpaidOnly() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String consultUid = seedConsultationInProcess(tag, patUid);

        // Seed unsettled lab test (settled=false — represents UNPAID)
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        seedUnsettledLabTest(c, tag);

        String extProviderUid = fakeUid("EXT", tag);

        // Referral gate (UNPAID-only, approximated via settled=false) → 422
        mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/referral")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(referralBody(extProviderUid, "Diagnosis", null)))
                .andExpect(status().isUnprocessableEntity())
                // The unsettled order in this fixture is a LAB TEST → the first per-type gate fires
                // with the verbatim legacy message (PatientResource.java:5469).
                .andExpect(jsonPath("$.detail")
                        .value("Could not save. Patient have uncleared lab test bill(s)"));
        // NOTE (CR-INC05-09 asymmetry): The deceased gate uses "UNPAID|VERIFIED" semantics and
        // the referral gate uses "UNPAID-only" semantics in the legacy system. Both are currently
        // approximated as settled=false because the local flag does not distinguish these billing
        // states. The structural separation exists in ClosureService.hasUnsettledOrdersForDeceasedGate
        // vs hasUnsettledOrdersForReferralGate so future billing-integration can differentiate.
    }

    // =========================================================================
    // 11. approve_referral: plan→APPROVED + consultation re-SIGNED_OUT
    // =========================================================================

    @Test
    void approveReferral_approved_consultationSignedOut() throws Exception {
        String tag = uniq();
        Patient patient = seedRealPatient(tag + "A");
        String consultUid = seedConsultationInProcess(tag + "A", patient.getUid());
        String extProviderUid = fakeUid("EXT", tag);

        // Save referral (→ SIGNED_OUT + PENDING)
        String planUid = postReferralPlan(consultUid, extProviderUid, "Hypertension");

        // Approve referral
        mockMvc.perform(
                        post(REFERRAL_BASE + "/uid/" + planUid + "/approve")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByUserUid").value("admin"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());

        // Plan must be APPROVED
        assertThat(referralPlanRepository.findByUid(planUid).orElseThrow().getStatus())
                .isEqualTo(ReferralPlanStatus.APPROVED);

        // Consultation must be SIGNED_OUT (unconditional re-confirm)
        assertThat(consultationRepository.findByUid(consultUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.SIGNED_OUT);
    }

    // =========================================================================
    // 12. list_referrals: returns PENDING|APPROVED
    // =========================================================================

    @Test
    void listReferrals_returnsPendingAndApproved() throws Exception {
        String tag = uniq();
        Patient patient = seedRealPatient(tag + "LR");
        String consultUid = seedConsultationInProcess(tag + "LR", patient.getUid());
        String extProviderUid = fakeUid("EXT", tag);

        String planUid = postReferralPlan(consultUid, extProviderUid, "Listing test");

        MvcResult listResult = mockMvc.perform(
                        get(REFERRAL_BASE)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();

        boolean found = false;
        for (JsonNode node : array) {
            if (planUid.equals(node.get("uid").asText())) {
                found = true;
                assertThat(node.get("status").asText()).isIn("PENDING", "APPROVED");
            }
            assertThat(node.get("status").asText()).isNotEqualTo("ARCHIVED");
        }
        assertThat(found).as("Seeded PENDING referral plan must appear in list").isTrue();
    }

    // =========================================================================
    // 13. 401 without token
    // =========================================================================

    @Test
    void allEndpoints_401_noToken() throws Exception {
        String cUid   = "NOCONSULT000000000000000001";
        String noteUid = "NONOTE00000000000000000001";
        String refUid  = "NOREF000000000000000000001";

        mockMvc.perform(post(CONSULT_BASE + cUid + "/deceased-note")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(DECEASED_BASE + "/uid/" + noteUid + "/approve"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(DECEASED_BASE))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(CONSULT_BASE + cUid + "/referral")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(REFERRAL_BASE + "/uid/" + refUid + "/approve"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(REFERRAL_BASE))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 14. V37 schema assertions
    // =========================================================================

    @Test
    void v37_deceasedNotes_patientIdDropped_patientUidAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE from deceased_notes
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'deceased_notes' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("deceased_notes.patient_id must be dropped by V37").isZero();

        // patient_uid must exist on deceased_notes
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'deceased_notes' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("deceased_notes.patient_uid must exist after V37").isEqualTo(1);

        // fk_deceased_notes_patient must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'deceased_notes' "
                        + "AND constraint_name = 'fk_deceased_notes_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_deceased_notes_patient must be dropped by V37").isZero();

        // idx_deceased_notes_patient_uid must exist
        Integer idxUid = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'deceased_notes' "
                        + "AND indexname = 'idx_deceased_notes_patient_uid'",
                Integer.class);
        assertThat(idxUid)
                .as("idx_deceased_notes_patient_uid must exist after V37").isEqualTo(1);
    }

    @Test
    void v37_referralPlans_patientIdDropped_patientUidAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE from referral_plans
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'referral_plans' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("referral_plans.patient_id must be dropped by V37").isZero();

        // patient_uid must exist on referral_plans
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'referral_plans' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("referral_plans.patient_uid must exist after V37").isEqualTo(1);

        // fk_referral_plans_patient must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'referral_plans' "
                        + "AND constraint_name = 'fk_referral_plans_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_referral_plans_patient must be dropped by V37").isZero();

        // idx_referral_plans_patient_uid must exist
        Integer idxUid = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'referral_plans' "
                        + "AND indexname = 'idx_referral_plans_patient_uid'",
                Integer.class);
        assertThat(idxUid)
                .as("idx_referral_plans_patient_uid must exist after V37").isEqualTo(1);
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "C12" + Long.toHexString(System.nanoTime()).substring(0, 8);
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

    /**
     * Seed a Consultation in IN_PROCESS status for the given patientUid.
     * IN_PROCESS is needed so the consultation can be moved to HELD (save_deceased_note)
     * or SIGNED_OUT (save_referral_plan).
     */
    private String seedConsultationInProcess(String tag, String patientUid) {
        Consultation c = new Consultation(
                patientUid,
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                fakeUid("BIL", tag),
                PaymentMode.CASH,
                false,
                true,   // settled=true so no payment gate issues
                "",
                null,
                dayUid);
        c.open();  // PENDING → IN_PROCESS
        return consultationRepository.saveAndFlush(c).getUid();
    }

    /**
     * Seed a real Patient (CASH, OUTPATIENT) with a real DB row — required for the
     * PatientDeceasedEvent listener to find the patient by uid and mutate its type.
     *
     * <p>We use the registration REST endpoint to seed so that the full registration
     * process (MRN assignment, searchKey, business day association) runs correctly.
     */
    private Patient seedRealPatient(String tag) throws Exception {
        String body = """
                {"firstName":"Test%s","middleName":null,"lastName":"Patient%s",
                 "dateOfBirth":"1990-01-01","gender":"MALE","paymentType":"CASH",
                 "membershipNo":null,"insurancePlanUid":null,"phoneNo":"0700000000",
                 "address":null,"email":null,"nationality":null,"nationalId":null,
                 "passportNo":null,"kinFullName":null,"kinRelationship":null,"kinPhoneNo":null}
                """.formatted(tag, tag);

        MvcResult r = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String patUid = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("uid").asText();
        return patientRepository.findByUid(patUid).orElseThrow();
    }

    /**
     * Seed a real INSURANCE Patient — used for the insurance-cleared event test.
     * Requires a real insurance plan uid; since the plan lookup isn't enforced on Patient
     * create (it's a loose ref), we use a fake plan uid.
     */
    private Patient seedRealInsurancePatient(String tag) throws Exception {
        // Create an insurance provider + plan to get a real plan uid
        String planUid = createInsurancePlan(tag);

        String body = """
                {"firstName":"Ins%s","middleName":null,"lastName":"Patient%s",
                 "dateOfBirth":"1985-06-15","gender":"FEMALE","paymentType":"INSURANCE",
                 "membershipNo":"MEMB-%s","insurancePlanUid":"%s","phoneNo":"0711111111",
                 "address":null,"email":null,"nationality":null,"nationalId":null,
                 "passportNo":null,"kinFullName":null,"kinRelationship":null,"kinPhoneNo":null}
                """.formatted(tag, tag, tag, planUid);

        MvcResult r = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String patUid = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("uid").asText();
        return patientRepository.findByUid(patUid).orElseThrow();
    }

    private String createInsurancePlan(String tag) throws Exception {
        String provBody = """
                {"code":"PROV-%s","name":"Provider %s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":false}
                """.formatted(tag, tag);
        MvcResult provR = mockMvc.perform(post("/api/v1/masterdata/insurance-providers")
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
                        post("/api/v1/masterdata/insurance-providers/uid/" + provUid + "/plans")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planBody))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(planR.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Seed an unsettled LabTest for the given consultation (settled=false).
     * Uses the domain constructor directly (bypasses billing — avoids needing lab type + price).
     */
    private void seedUnsettledLabTest(Consultation consultation, String tag) {
        LabTest lt = LabTest.forConsultation(
                consultation,
                fakeUid("LTT", tag),   // labTestTypeUid — loose, no existence check needed in seed
                fakeUid("BILL", tag),  // patientBillUid
                false,                 // settled=false → unsettled
                "CASH",
                "",
                null,
                null,
                fakeUid("DOC", tag),
                "admin",
                dayUid,
                Instant.now());
        labTestRepository.saveAndFlush(lt);
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private String postDeceasedNote(String consultUid, String summary, String cause)
            throws Exception {
        MvcResult r = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/deceased-note")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deceasedNoteBody(summary, cause, null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String postReferralPlan(String consultUid, String extProviderUid, String diagnosis)
            throws Exception {
        MvcResult r = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/referral")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(referralBody(extProviderUid, diagnosis, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    private static String deceasedNoteBody(String summary, String cause,
                                            String date, String time) {
        String sumVal  = summary != null ? "\"" + summary + "\"" : "null";
        String cauVal  = cause   != null ? "\"" + cause   + "\"" : "null";
        String dateVal = date    != null ? "\"" + date    + "\"" : "null";
        String timeVal = time    != null ? "\"" + time    + "\"" : "null";
        return """
                {"patientSummary":%s,"causeOfDeath":%s,"deathDate":%s,"deathTime":%s}
                """.formatted(sumVal, cauVal, dateVal, timeVal);
    }

    private static String referralBody(String extProviderUid, String referringDiagnosis,
                                       String history) {
        String diagVal = referringDiagnosis != null
                ? "\"" + referringDiagnosis + "\"" : "null";
        String histVal = history != null ? "\"" + history + "\"" : "null";
        return """
                {"externalMedicalProviderUid":"%s","referringDiagnosis":%s,
                 "history":%s,"investigation":null,"management":null,
                 "operationNote":null,"icuAdmissionNote":null,"generalRecommendation":null}
                """.formatted(extProviderUid, diagVal, histVal);
    }
}
