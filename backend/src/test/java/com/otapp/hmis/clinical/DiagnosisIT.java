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
import com.otapp.hmis.clinical.domain.FinalDiagnosisRepository;
import com.otapp.hmis.clinical.domain.WorkingDiagnosisRepository;
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
 * Integration tests for inc-05 C6: WorkingDiagnosis and FinalDiagnosis.
 *
 * <p>Covers:
 * <ol>
 *   <li>Add working dx to a consultation → 201, uid in response, no id leak.</li>
 *   <li>Add a SECOND working dx of a DIFFERENT type → 201, both rows exist.</li>
 *   <li>Add a DUPLICATE type for working dx → 422 "Duplicate Diagnosis Types is not allowed".</li>
 *   <li>The SAME diagnosisTypeUid in working AND final for the same consultation → BOTH succeed
 *       (separate tables, no cross-table constraint).</li>
 *   <li>List working diagnoses by consultation → contains both entries, none deleted.</li>
 *   <li>Delete one working dx → 204; re-delete → 404 (clean-404 behaviour).</li>
 *   <li>Final diagnosis mirrors working (201, dup-guard, list, delete).</li>
 *   <li>Unknown consultation uid → 404.</li>
 *   <li>Unknown diagnosisTypeUid → 404 "Diagnosis type not found".</li>
 *   <li>401 without token on all 6 endpoints.</li>
 *   <li>V32 schema assertion: patient_id dropped + patient_uid added on BOTH tables,
 *       FK constraints gone, patient_uid indexes present.</li>
 * </ol>
 *
 * <p>DiagnosisType uids are seeded directly via JDBC (masterdata.diagnosis_types table)
 * to avoid HTTP booking dependencies and keep the test self-contained. Consultations are
 * seeded via the ConsultationRepository (same pattern as ClinicalDocumentationIT).
 */
class DiagnosisIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/clinical";

    @Autowired MockMvc                    mockMvc;
    @Autowired ObjectMapper               objectMapper;
    @Autowired TestJwtFactory             jwtFactory;
    @Autowired ConsultationRepository     consultationRepository;
    @Autowired WorkingDiagnosisRepository workingDiagnosisRepository;
    @Autowired FinalDiagnosisRepository   finalDiagnosisRepository;
    @Autowired BusinessDayService         businessDayService;
    @Autowired DataSource                 dataSource;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL"));
        ensureDayOpen();
    }

    // =========================================================================
    // WorkingDiagnosis — add (consultation path)
    // =========================================================================

    @Test
    void addWorkingDiagnosis_firstType_returns201() throws Exception {
        String tag = uniq();
        String consultUid   = seedConsultation(tag);
        String dtUid        = seedDiagnosisType(tag + "A");

        MvcResult result = mockMvc.perform(
                        post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(diagBody(dtUid, "Suspected malaria")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.diagnosisTypeUid").value(dtUid))
                .andExpect(jsonPath("$.description").value("Suspected malaria"))
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("uid").asText()).isNotBlank();
        assertThat(node.has("id")).isFalse();
    }

    @Test
    void addWorkingDiagnosis_secondDifferentType_201_bothExist() throws Exception {
        String tag = uniq();
        String consultUid   = seedConsultation(tag);
        String dtUid1       = seedDiagnosisType(tag + "A");
        String dtUid2       = seedDiagnosisType(tag + "B");

        // Add first type
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid1, "First dx")))
                .andExpect(status().isCreated());

        // Add second (different) type — must also succeed
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid2, "Second dx")))
                .andExpect(status().isCreated());

        // Confirm both rows in repository
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(workingDiagnosisRepository.findByConsultationOrderByCreatedAtAsc(c))
                .as("both distinct working diagnoses must exist").hasSize(2);
    }

    @Test
    void addWorkingDiagnosis_duplicateType_422WithVerbatimMessage() throws Exception {
        String tag = uniq();
        String consultUid   = seedConsultation(tag);
        String dtUid        = seedDiagnosisType(tag + "A");

        // First add — succeeds
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "First")))
                .andExpect(status().isCreated());

        // Second add — SAME type — must fail with verbatim message
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "Duplicate")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate Diagnosis Types is not allowed"));
    }

    @Test
    void addWorkingDiagnosis_unknownConsultation_404() throws Exception {
        String tag  = uniq();
        String dtUid = seedDiagnosisType(tag + "A");

        mockMvc.perform(post(BASE + "/consultations/uid/BADCONSULT00000000000000001/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "desc")))
                .andExpect(status().isNotFound());
    }

    @Test
    void addWorkingDiagnosis_unknownDiagnosisType_404WithMessage() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);

        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody("BADDTUID0000000000000000001", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Diagnosis type not found"));
    }

    // =========================================================================
    // WorkingDiagnosis — same type allowed in working AND final independently
    // =========================================================================

    @Test
    void sameTypeAllowedInWorkingAndFinalForSameConsultation() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid      = seedDiagnosisType(tag + "A");

        // Add as working — succeeds
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "provisional")))
                .andExpect(status().isCreated());

        // Add SAME type as final — must also succeed (separate tables, no cross-table dup guard)
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "confirmed")))
                .andExpect(status().isCreated());

        // Confirm one row in each table
        Consultation c = consultationRepository.findByUid(consultUid).orElseThrow();
        assertThat(workingDiagnosisRepository.findByConsultationOrderByCreatedAtAsc(c)).hasSize(1);
        assertThat(finalDiagnosisRepository.findByConsultationOrderByCreatedAtAsc(c)).hasSize(1);
    }

    // =========================================================================
    // WorkingDiagnosis — list
    // =========================================================================

    @Test
    void listWorkingDiagnoses_returnsAllForConsultation() throws Exception {
        String tag = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid1     = seedDiagnosisType(tag + "A");
        String dtUid2     = seedDiagnosisType(tag + "B");

        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid1, "dx1")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid2, "dx2")))
                .andExpect(status().isCreated());

        MvcResult listResult = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        // No id leak in any element
        array.forEach(node -> assertThat(node.has("id")).isFalse());
    }

    @Test
    void listWorkingDiagnoses_emptyConsultation_returnsEmptyArray() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);

        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =========================================================================
    // WorkingDiagnosis — delete (clean-404 path)
    // =========================================================================

    @Test
    void deleteWorkingDiagnosis_204OnSuccess_404OnRedeletion() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid      = seedDiagnosisType(tag + "A");

        // Create
        MvcResult create = mockMvc.perform(
                        post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(diagBody(dtUid, "to be deleted")))
                .andExpect(status().isCreated())
                .andReturn();
        String dxUid = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("uid").asText();

        // First delete → 204
        mockMvc.perform(delete(BASE + "/working-diagnoses/uid/" + dxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Verify gone from repository
        assertThat(workingDiagnosisRepository.findByUid(dxUid)).isEmpty();

        // Re-delete → 404 (clean-404 path; documented defensive improvement over legacy raw deleteById)
        mockMvc.perform(delete(BASE + "/working-diagnoses/uid/" + dxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // FinalDiagnosis — mirrors working (all same guards)
    // =========================================================================

    @Test
    void addFinalDiagnosis_firstType_returns201() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid      = seedDiagnosisType(tag + "A");

        MvcResult result = mockMvc.perform(
                        post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(diagBody(dtUid, "Confirmed malaria")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.diagnosisTypeUid").value(dtUid))
                .andExpect(jsonPath("$.description").value("Confirmed malaria"))
                .andExpect(jsonPath("$.consultationUid").value(consultUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText()).isNotBlank();
    }

    @Test
    void addFinalDiagnosis_duplicateType_422WithVerbatimMessage() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid      = seedDiagnosisType(tag + "A");

        // First add
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "first")))
                .andExpect(status().isCreated());

        // Duplicate — same type, same consultation
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid, "dup")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Duplicate Diagnosis Types is not allowed"));
    }

    @Test
    void listFinalDiagnoses_returnsAllForConsultation() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid1     = seedDiagnosisType(tag + "A");
        String dtUid2     = seedDiagnosisType(tag + "B");

        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid1, "final1")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody(dtUid2, "final2")))
                .andExpect(status().isCreated());

        MvcResult listResult = mockMvc.perform(
                        get(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        array.forEach(node -> assertThat(node.has("id")).isFalse());
    }

    @Test
    void deleteFinalDiagnosis_204OnSuccess_404OnRedeletion() throws Exception {
        String tag        = uniq();
        String consultUid = seedConsultation(tag);
        String dtUid      = seedDiagnosisType(tag + "A");

        // Create
        MvcResult create = mockMvc.perform(
                        post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(diagBody(dtUid, "to delete")))
                .andExpect(status().isCreated())
                .andReturn();
        String dxUid = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("uid").asText();

        // First delete → 204
        mockMvc.perform(delete(BASE + "/final-diagnoses/uid/" + dxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(finalDiagnosisRepository.findByUid(dxUid)).isEmpty();

        // Re-delete → 404
        mockMvc.perform(delete(BASE + "/final-diagnoses/uid/" + dxUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 401 without token on all 6 endpoints
    // =========================================================================

    @Test
    void allEndpoints_401_noToken() throws Exception {
        String consultUid = "NOCONSULT000000000000000001";
        String dxUid      = "NODXUID000000000000000000001";

        // Working
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody("DTUID0000000000000000000001", null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/working-diagnoses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/working-diagnoses/uid/" + dxUid))
                .andExpect(status().isUnauthorized());

        // Final
        mockMvc.perform(post(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody("DTUID0000000000000000000001", null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/consultations/uid/" + consultUid + "/final-diagnoses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/final-diagnoses/uid/" + dxUid))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // V32 schema assertions (patient_id dropped, patient_uid added, FK gone)
    // =========================================================================

    @Test
    void v32_workingDiagnoses_patientIdDropped_patientUidAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'working_diagnoses' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("working_diagnoses.patient_id must be dropped by V32").isZero();

        // patient_uid must exist
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'working_diagnoses' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("working_diagnoses.patient_uid must exist after V32").isEqualTo(1);

        // fk_working_diagnoses_patient constraint must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'working_diagnoses' "
                        + "AND constraint_name = 'fk_working_diagnoses_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_working_diagnoses_patient must be dropped by V32").isZero();

        // patient_uid index must exist
        Integer idxCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'working_diagnoses' "
                        + "AND indexname = 'idx_working_diagnoses_patient_uid'",
                Integer.class);
        assertThat(idxCount)
                .as("idx_working_diagnoses_patient_uid must exist after V32").isEqualTo(1);
    }

    @Test
    void v32_finalDiagnoses_patientIdDropped_patientUidAdded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // patient_id must be GONE
        Integer patientIdCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'final_diagnoses' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(patientIdCount)
                .as("final_diagnoses.patient_id must be dropped by V32").isZero();

        // patient_uid must exist
        Integer patientUidCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'final_diagnoses' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(patientUidCount)
                .as("final_diagnoses.patient_uid must exist after V32").isEqualTo(1);

        // fk_final_diagnoses_patient constraint must be GONE
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'final_diagnoses' "
                        + "AND constraint_name = 'fk_final_diagnoses_patient'",
                Integer.class);
        assertThat(fkCount)
                .as("fk_final_diagnoses_patient must be dropped by V32").isZero();

        // patient_uid index must exist
        Integer idxCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'final_diagnoses' "
                        + "AND indexname = 'idx_final_diagnoses_patient_uid'",
                Integer.class);
        assertThat(idxCount)
                .as("idx_final_diagnoses_patient_uid must exist after V32").isEqualTo(1);
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "D6" + Long.toHexString(System.nanoTime());
    }

    /**
     * Build a deterministic 26-char synthetic uid from prefix + tag.
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

    /**
     * Seed a DiagnosisType row directly via JDBC into masterdata.diagnosis_types.
     *
     * <p>We need a real uid that the masterdata DiagnosisTypeLookup will resolve.
     * Using JDBC rather than the masterdata API controller to avoid HTTP boundary
     * and keep this test focused on the clinical C6 behaviour.
     *
     * <p>Returns the inserted uid so the test can reference it in diagnosis requests.
     */
    private String seedDiagnosisType(String tag) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String uid = fakeUid("DT", tag);
        // Check if already exists (idempotent seed — tests share the Spring context)
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM diagnosis_types WHERE uid = ?",
                Integer.class, uid);
        if (exists == null || exists == 0) {
            jdbc.update(
                    "INSERT INTO diagnosis_types "
                            + "(uid, code, name, description, active, "
                            + " created_at, updated_at, created_by, updated_by, version) "
                            + "VALUES (?, ?, ?, ?, true, now(), null, 'test', null, 0)",
                    uid,
                    "CODE-" + tag,
                    "DiagType-" + tag,
                    "Test diagnosis type for C6 IT");
        }
        return uid;
    }

    // =========================================================================
    // Helpers — request body builders
    // =========================================================================

    private String diagBody(String diagnosisTypeUid, String description) {
        return """
                {
                  "diagnosisTypeUid": %s,
                  "description":      %s
                }
                """.formatted(js(diagnosisTypeUid), js(description));
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
