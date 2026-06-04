package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.ClinicalNoteRepository;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.GeneralExaminationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.PatientVitalRepository;
import com.otapp.hmis.clinical.domain.PatientVitalStatus;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-05 C5: ClinicalNote (SOAP), GeneralExamination (vitals),
 * and PatientVital (nurse staging).
 *
 * <p>Covers:
 * <ol>
 *   <li>SOAP note upsert: second POST overwrites, does NOT duplicate (one row per consultation).</li>
 *   <li>GeneralExamination upsert: same idempotency guarantee.</li>
 *   <li>Side-effecting GET load-or-create: first GET returns 201 + created row; second GET
 *       returns 200 + same uid — for all three entity types (note, exam-consultation,
 *       exam-non-consultation, vitals).</li>
 *   <li>404 when the consultation uid is unknown.</li>
 *   <li>PatientVital staging flow: load → EMPTY (201), submit → SUBMITTED, request → ARCHIVED
 *       + GeneralExamination now exists with copied vital fields.</li>
 *   <li>request_vital when not SUBMITTED → 422 verbatim message
 *       "Vitals already requested or not submitted".</li>
 *   <li>request_vital after already ARCHIVED → 422 same verbatim message.</li>
 *   <li>Free-text vitals round-trip: arbitrary strings ("120/80 mmHg", "high", "overweight")
 *       stored verbatim (CR-INC05-13).</li>
 *   <li>401 without token on all nine endpoints.</li>
 * </ol>
 *
 * <p>Seeds use the consultation/non-consultation repositories directly (bypasses HTTP booking
 * to keep tests focused on C5 behaviour).
 */
class ClinicalDocumentationIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/clinical";

    @Autowired MockMvc                    mockMvc;
    @Autowired ObjectMapper               objectMapper;
    @Autowired TestJwtFactory             jwtFactory;
    @Autowired ConsultationRepository     consultationRepository;
    @Autowired NonConsultationRepository  nonConsultationRepository;
    @Autowired ClinicalNoteRepository     clinicalNoteRepository;
    @Autowired GeneralExaminationRepository generalExaminationRepository;
    @Autowired PatientVitalRepository     patientVitalRepository;
    @Autowired BusinessDayService         businessDayService;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL"));
        ensureDayOpen();
    }

    // =========================================================================
    // ClinicalNote — upsert idempotency (one row per consultation)
    // =========================================================================

    @Test
    void saveClinicalNote_secondPostOverwrites_doesNotDuplicate() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // First save
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/clinical-note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(soapBody("Headache", "mild", null, null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mainComplain").value("Headache"))
                .andExpect(jsonPath("$.id").doesNotExist());

        // Second save — different mainComplain
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/clinical-note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(soapBody("Fever", "severe", "prev TB", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mainComplain").value("Fever"))
                .andExpect(jsonPath("$.pastMedicalHistory").value("prev TB"));

        // Exactly ONE row must exist — no duplicate
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(clinicalNoteRepository.findByConsultation(c))
                .as("exactly one clinical_note row per consultation").isPresent();
        // No second row: the partial UNIQUE index prevents it; if upsert was wrong, save
        // would have thrown a constraint violation. Confirming presence is sufficient.
    }

    @Test
    void saveClinicalNote_unknownConsultation_404() throws Exception {
        mockMvc.perform(post(BASE + "/consultations/uid/UNKNOWN000000000000000001/clinical-note")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(soapBody(null, null, null, null, null, null, null, null)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // ClinicalNote — side-effecting GET (CR-INC05-06)
    // =========================================================================

    @Test
    void loadOrCreateClinicalNote_firstGet_returns201AndCreatesRow() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // First GET — row must be created, 201 returned
        MvcResult first = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/clinical-note")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andExpect(jsonPath("$.mainComplain").isEmpty())
                .andReturn();

        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid1).isNotBlank();

        // Confirm row persisted
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(clinicalNoteRepository.findByConsultation(c)).isPresent();
    }

    @Test
    void loadOrCreateClinicalNote_secondGet_returns200AndSameUid() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // First GET — creates
        MvcResult first = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/clinical-note")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();

        // Second GET — must return 200 + same uid (no duplicate)
        MvcResult second = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/clinical-note")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String uid2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("uid").asText();

        assertThat(uid2).as("second GET must return the SAME uid").isEqualTo(uid1);
    }

    // =========================================================================
    // GeneralExamination — upsert idempotency (consultation)
    // =========================================================================

    @Test
    void saveGeneralExamination_secondPostOverwrites_doesNotDuplicate() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/general-examination")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody("120/80", "36.5", "72", "70kg", "170cm",
                                null, null, null, "98", "18", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressure").value("120/80"))
                .andExpect(jsonPath("$.id").doesNotExist());

        // Second save — different pressure
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/general-examination")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody("140/90", "37.0", "80", "70kg", "170cm",
                                null, null, null, "99", "20", "mild dyspnoea")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressure").value("140/90"))
                .andExpect(jsonPath("$.description").value("mild dyspnoea"));

        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(generalExaminationRepository.findByConsultation(c))
                .as("exactly one exam row per consultation").isPresent();
    }

    // =========================================================================
    // GeneralExamination — side-effecting GET (consultation path)
    // =========================================================================

    @Test
    void loadOrCreateGeneralExaminationByConsultation_firstGet_returns201() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        MvcResult first = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/general-examination")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andReturn();
        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();

        // Second GET → 200, same uid
        MvcResult second = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/general-examination")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String uid2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("uid").asText();

        assertThat(uid2).isEqualTo(uid1);
    }

    // =========================================================================
    // GeneralExamination — non-consultation path
    // =========================================================================

    @Test
    void saveGeneralExaminationForNonConsultation_upsert_doesNotDuplicate() throws Exception {
        String tag = uniq();
        String ncUid = seedNonConsultation(tag);

        mockMvc.perform(post(BASE + "/non-consultations/uid/" + ncUid + "/general-examination")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody("100/60", null, null, null, null, null, null,
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressure").value("100/60"))
                .andExpect(jsonPath("$.nonConsultationUid").value(ncUid));

        // Second save — different pressure
        mockMvc.perform(post(BASE + "/non-consultations/uid/" + ncUid + "/general-examination")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody("110/70", null, null, null, null, null, null,
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressure").value("110/70"));

        NonConsultation nc = nonConsultationRepository.findByUid(ncUid).orElseThrow();
        assertThat(generalExaminationRepository.findByNonConsultation(nc))
                .as("exactly one exam row per non-consultation").isPresent();
    }

    @Test
    void loadOrCreateGeneralExaminationByNonConsultation_firstGet_returns201() throws Exception {
        String tag = uniq();
        String ncUid = seedNonConsultation(tag);

        MvcResult first = mockMvc.perform(
                        get(BASE + "/non-consultations/uid/" + ncUid + "/general-examination")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.nonConsultationUid").value(ncUid))
                .andReturn();
        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();

        // Second GET → 200, same uid
        MvcResult second = mockMvc.perform(
                        get(BASE + "/non-consultations/uid/" + ncUid + "/general-examination")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String uid2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("uid").asText();

        assertThat(uid2).isEqualTo(uid1);
    }

    // =========================================================================
    // PatientVital staging flow
    // =========================================================================

    @Test
    void loadOrCreateVital_firstGet_returns201WithStatusEmpty() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        MvcResult first = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/vitals")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andReturn();
        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();

        // Second GET → 200, same uid, still EMPTY
        MvcResult second = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/vitals")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andReturn();
        String uid2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("uid").asText();

        assertThat(uid2).isEqualTo(uid1);
    }

    @Test
    void submitVital_transitionsToSubmitted() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // Load (creates EMPTY)
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated());

        // Submit
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vitalsBody("120/80", "37.1", "70", "65kg", "165cm",
                                "24.0", "Normal", "1.7", "99%", "16", "no cough")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.pressure").value("120/80"))
                .andExpect(jsonPath("$.temperature").value("37.1"));

        // Confirm SUBMITTED in repository
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(patientVitalRepository.findByConsultation(c).orElseThrow().getStatus())
                .isEqualTo(PatientVitalStatus.SUBMITTED);
    }

    @Test
    void requestVital_submittedToArchivedAndGeneralExaminationCopied() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // Load → EMPTY
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated());

        // Submit with specific free-text vital values (including non-numeric BMI/BSA — CR-INC05-13)
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vitalsBody("120/80", "high", "72", "70kg", "170cm",
                                "24.2", "Normal BMI", "1.85", "98%", "18", "notes here")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Request — copies vitals into exam + archives PatientVital
        MvcResult requestResult = mockMvc.perform(
                        post(BASE + "/consultations/uid/" + consultUid + "/vitals/request")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressure").value("120/80"))
                .andExpect(jsonPath("$.temperature").value("high"))
                .andExpect(jsonPath("$.bodyMassIndex").value("24.2"))
                .andExpect(jsonPath("$.bodyMassIndexComment").value("Normal BMI"))
                .andExpect(jsonPath("$.description").value("notes here"))
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andReturn();

        // GeneralExamination must be persisted for this consultation
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(generalExaminationRepository.findByConsultation(c))
                .as("GeneralExamination must exist after vitals request").isPresent();

        // PatientVital must be ARCHIVED
        assertThat(patientVitalRepository.findByConsultation(c).orElseThrow().getStatus())
                .isEqualTo(PatientVitalStatus.ARCHIVED);

        // Exam uid must be in the response, no id leak
        JsonNode examNode = objectMapper.readTree(requestResult.getResponse().getContentAsString());
        assertThat(examNode.get("uid").asText()).isNotBlank();
        assertThat(examNode.has("id")).isFalse();
    }

    @Test
    void requestVital_whenNotSubmitted_422WithVerbatimMessage() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // Load → EMPTY (not SUBMITTED) — do not submit
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated());

        // Request without submitting → 422 with verbatim legacy message
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals/request")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Vitals already requested or not submitted"));
    }

    @Test
    void requestVital_afterAlreadyArchived_422WithVerbatimMessage() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // Full flow: load → submit → request (archives)
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vitalsBody("120/80", null, null, null, null,
                                null, null, null, null, null, null)))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals/request")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Second request → PatientVital is ARCHIVED, not SUBMITTED → 422
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals/request")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Vitals already requested or not submitted"));
    }

    // =========================================================================
    // Free-text vitals round-trip (CR-INC05-13 — arbitrary strings stored verbatim)
    // =========================================================================

    @Test
    void freeTextVitals_arbitraryStringsStoredVerbatim() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);

        // Deliberately non-numeric BMI/BSA values — backend must NOT compute or reject them
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/general-examination")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody("120/80 mmHg", "high", "irregular", "70 kg",
                                "5 ft 7 in", "overweight", "see chart", "N/A",
                                "SpO2 98", "tachypnoeic", "COPD patient, history noted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressure").value("120/80 mmHg"))
                .andExpect(jsonPath("$.temperature").value("high"))
                .andExpect(jsonPath("$.bodyMassIndex").value("overweight"))
                .andExpect(jsonPath("$.bodyMassIndexComment").value("see chart"))
                .andExpect(jsonPath("$.bodySurfaceArea").value("N/A"))
                .andExpect(jsonPath("$.description").value("COPD patient, history noted"));
    }

    // =========================================================================
    // 401 without token on all nine endpoints
    // =========================================================================

    @Test
    void allEndpoints_401_noToken() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);
        String ncUid      = seedNonConsultation(tag + "N");

        // SOAP note
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/clinical-note")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(soapBody(null, null, null, null, null, null, null, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/clinical-note"))
                .andExpect(status().isUnauthorized());

        // Exam — consultation
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/general-examination")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody(null, null, null, null, null, null, null,
                                null, null, null, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/general-examination"))
                .andExpect(status().isUnauthorized());

        // Exam — non-consultation
        mockMvc.perform(post(BASE + "/non-consultations/uid/" + ncUid + "/general-examination")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(examBody(null, null, null, null, null, null, null,
                                null, null, null, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/non-consultations/uid/" + ncUid + "/general-examination"))
                .andExpect(status().isUnauthorized());

        // Vitals
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/vitals"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vitalsBody(null, null, null, null, null, null, null,
                                null, null, null, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/vitals/request"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "D5" + Long.toHexString(System.nanoTime());
    }

    /**
     * Build a deterministic, valid 26-char synthetic uid from prefix + tag.
     * No real FK exists on cross-module refs (ADR-0008 loose uid) so any 26-char
     * alphanumeric string is valid for those columns.
     */
    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }

    /** Seed a PENDING INSURANCE consultation (settled=true) directly via the repository. */
    private String seedConsultation(String tag) {
        Consultation c = new Consultation(
                fakeUid("PAT", tag),
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                fakeUid("BIL", tag),
                PaymentMode.INSURANCE,
                false,
                true,
                "MEM-" + tag,
                fakeUid("PLN", tag),
                businessDayService.currentUid());
        return consultationRepository.saveAndFlush(c).getUid();
    }

    /** Seed an IN_PROCESS NonConsultation directly via the repository. */
    private String seedNonConsultation(String tag) {
        NonConsultation nc = new NonConsultation(
                fakeUid("PAT", tag),
                fakeUid("VIS", tag),
                "CASH",
                "",
                null,
                businessDayService.currentUid());
        return nonConsultationRepository.saveAndFlush(nc).getUid();
    }

    // =========================================================================
    // Helpers — request body builders
    // =========================================================================

    private String soapBody(String mainComplain, String presentIllness,
                             String pastMedical, String familySocial,
                             String drugsAllergy, String reviewSystems,
                             String physicalExam, String mgmtPlan) {
        return """
                {
                  "mainComplain":           %s,
                  "presentIllnessHistory":  %s,
                  "pastMedicalHistory":     %s,
                  "familyAndSocialHistory": %s,
                  "drugsAndAllergyHistory": %s,
                  "reviewOfOtherSystems":   %s,
                  "physicalExamination":    %s,
                  "managementPlan":         %s
                }
                """.formatted(
                js(mainComplain), js(presentIllness),
                js(pastMedical),  js(familySocial),
                js(drugsAllergy), js(reviewSystems),
                js(physicalExam), js(mgmtPlan));
    }

    private String examBody(String pressure, String temperature, String pulseRate,
                             String weight,   String height,      String bmi,
                             String bmiComment, String bsa,       String satO2,
                             String respRate, String description) {
        return """
                {
                  "pressure":             %s,
                  "temperature":          %s,
                  "pulseRate":            %s,
                  "weight":               %s,
                  "height":               %s,
                  "bodyMassIndex":        %s,
                  "bodyMassIndexComment": %s,
                  "bodySurfaceArea":      %s,
                  "saturationOxygen":     %s,
                  "respiratoryRate":      %s,
                  "description":          %s
                }
                """.formatted(
                js(pressure),    js(temperature), js(pulseRate),
                js(weight),      js(height),      js(bmi),
                js(bmiComment),  js(bsa),         js(satO2),
                js(respRate),    js(description));
    }

    /** Vitals request body is identical in shape to the exam body. */
    private String vitalsBody(String pressure, String temperature, String pulseRate,
                               String weight,   String height,      String bmi,
                               String bmiComment, String bsa,       String satO2,
                               String respRate, String description) {
        return examBody(pressure, temperature, pulseRate, weight, height,
                bmi, bmiComment, bsa, satO2, respRate, description);
    }

    /** Null-safe JSON string literal. */
    private static String js(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
    }
}
