package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultationStatus;
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
 * Integration tests for the walk-in (NonConsultation) track — inc-05 C4.
 *
 * <p>Covers:
 * <ol>
 *   <li>get-or-create idempotency: a second POST for the same patient returns the SAME
 *       IN_PROCESS row (no duplicate).</li>
 *   <li>sign-out: POST /uid/{uid}/sign-out → SIGNED_OUT; DB confirms the status converter
 *       round-trips the exact "SIGNED-OUT" string.</li>
 *   <li>After sign-out: a new get-or-create call creates a NEW IN_PROCESS row (different uid).</li>
 *   <li>Status converter round-trip: reload from repo → status == IN_PROCESS / SIGNED_OUT;
 *       raw JDBC confirms the DB column holds the exact hyphenated strings.</li>
 *   <li>401 without a token on every endpoint.</li>
 *   <li>V31 schema assertion: patient_uid/visit_uid added; patient_id/visit_id + their FKs dropped
 *       from non_consultations.</li>
 * </ol>
 *
 * <p>Uses the full HTTP stack (MockMvc) via the NonConsultationController endpoints.
 * Seeds are injected directly via the repository (bypassing the HTTP layer) where the test
 * only needs a pre-existing row.
 */
class NonConsultationIT extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/v1/clinical/non-consultations";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired NonConsultationRepository nonConsultationRepository;
    @Autowired BusinessDayService businessDayService;
    @Autowired DataSource dataSource;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL"));
        ensureDayOpen();
    }

    // =========================================================================
    // GET /uid/{uid} — read one
    // =========================================================================

    @Test
    void getByUid_200_returnsNonConsultation() throws Exception {
        String tag = uniq();
        String uid = seedInProcess(tag);

        mockMvc.perform(get(BASE_URL + "/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.status").value("IN-PROCESS"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_404_unknownUid() throws Exception {
        mockMvc.perform(get(BASE_URL + "/uid/NONEXISTENT000000000000001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // POST / — get-or-create idempotency (core C4 requirement)
    // =========================================================================

    /**
     * Core test: a second POST for the same patient returns the SAME IN_PROCESS row.
     * No duplicate non-consultation is created (legacy get-or-create parity:
     * PatientServiceImpl.java:790-806).
     */
    @Test
    void openOrGet_secondCallForSamePatient_returnsExistingRow() throws Exception {
        String tag = uniq();
        String patientUid = fakeUid("PAT", tag);
        String visitUid   = fakeUid("VIS", tag);

        String body = buildOpenRequest(patientUid, visitUid, "CASH", "", null);

        // First call — creates a new IN_PROCESS row
        MvcResult first = mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"))
                .andExpect(jsonPath("$.patientUid").value(patientUid))
                .andReturn();

        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();

        // Second call — must return the SAME uid (reuse, not duplicate)
        MvcResult second = mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"))
                .andReturn();

        String uid2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("uid").asText();

        assertThat(uid2).as("second get-or-create must return the SAME uid — no duplicate").isEqualTo(uid1);

        // Confirm only one row exists for this patient
        long count = nonConsultationRepository.findByPatientUidOrderByCreatedAtDesc(patientUid).size();
        assertThat(count).as("only ONE non-consultation row must exist for this patient").isEqualTo(1);
    }

    // =========================================================================
    // POST /uid/{uid}/sign-out — IN_PROCESS → SIGNED_OUT
    // =========================================================================

    @Test
    void signOut_inProcess_transitionsToSignedOut() throws Exception {
        String tag = uniq();
        String uid = seedInProcess(tag);

        mockMvc.perform(post(BASE_URL + "/uid/" + uid + "/sign-out")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED-OUT"))
                .andExpect(jsonPath("$.uid").value(uid));

        // Repository reload confirms status
        NonConsultation loaded = nonConsultationRepository.findByUid(uid).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(NonConsultationStatus.SIGNED_OUT);
    }

    /**
     * Sign-out guard: trying to sign out an already-SIGNED-OUT encounter must fail with 422.
     */
    @Test
    void signOut_alreadySignedOut_422() throws Exception {
        String tag = uniq();
        String uid = seedSignedOut(tag);

        mockMvc.perform(post(BASE_URL + "/uid/" + uid + "/sign-out")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Walk-in encounter is not open"));
    }

    @Test
    void signOut_notFound_404() throws Exception {
        mockMvc.perform(post(BASE_URL + "/uid/DOESNOTEXIST00000000000001/sign-out")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // After sign-out: new get-or-create creates a NEW IN_PROCESS row
    // =========================================================================

    /**
     * After the existing IN_PROCESS encounter is signed out, a new get-or-create must produce
     * a NEW (different uid) IN_PROCESS row (legacy: the signed-out encounter cannot be reused).
     */
    @Test
    void openOrGet_afterSignOut_createsNewInProcessRow() throws Exception {
        String tag = uniq();
        String patientUid = fakeUid("PAT", tag);
        String visitUid   = fakeUid("VIS", tag);

        String body = buildOpenRequest(patientUid, visitUid, "CASH", "", null);

        // First: create IN_PROCESS
        MvcResult first = mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String uid1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("uid").asText();

        // Sign out
        mockMvc.perform(post(BASE_URL + "/uid/" + uid1 + "/sign-out")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED-OUT"));

        // New get-or-create must produce a DIFFERENT uid
        MvcResult second = mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"))
                .andReturn();
        String uid2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("uid").asText();

        assertThat(uid2).as("after sign-out, a new get-or-create must produce a NEW uid").isNotEqualTo(uid1);

        // Two rows now: one SIGNED_OUT, one IN_PROCESS
        List<NonConsultation> rows = nonConsultationRepository.findByPatientUidOrderByCreatedAtDesc(patientUid);
        assertThat(rows).hasSize(2);
        long inProcessCount = rows.stream()
                .filter(r -> r.getStatus() == NonConsultationStatus.IN_PROCESS)
                .count();
        assertThat(inProcessCount).isEqualTo(1);
    }

    // =========================================================================
    // Status converter round-trip (IN-PROCESS / SIGNED-OUT exact DB strings)
    // =========================================================================

    /**
     * Asserts that the status converter writes the exact hyphenated legacy strings to the DB
     * and reads them back correctly via the JPA entity.
     */
    @Test
    void statusConverter_roundTrips_exactDbStrings() {
        String tag = uniq();
        String uid = seedInProcess(tag);

        // JPA reload → IN_PROCESS
        NonConsultation loaded = nonConsultationRepository.findByUid(uid).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(NonConsultationStatus.IN_PROCESS);

        // Raw JDBC → exact "IN-PROCESS" string in the column
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String rawStatus = jdbc.queryForObject(
                "SELECT status FROM non_consultations WHERE uid = ?", String.class, uid);
        assertThat(rawStatus).as("DB must store the exact hyphenated string 'IN-PROCESS'")
                .isEqualTo("IN-PROCESS");

        // Sign out via entity domain method + save
        loaded.signOut();
        nonConsultationRepository.saveAndFlush(loaded);

        // JPA reload → SIGNED_OUT
        NonConsultation reloaded = nonConsultationRepository.findByUid(uid).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NonConsultationStatus.SIGNED_OUT);

        // Raw JDBC → exact "SIGNED-OUT" string
        String rawSignedOut = jdbc.queryForObject(
                "SELECT status FROM non_consultations WHERE uid = ?", String.class, uid);
        assertThat(rawSignedOut).as("DB must store the exact hyphenated string 'SIGNED-OUT'")
                .isEqualTo("SIGNED-OUT");
    }

    // =========================================================================
    // 401 without token
    // =========================================================================

    @Test
    void endpoints_401_noToken() throws Exception {
        // POST /
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildOpenRequest(fakeUid("PAT", "auth"), fakeUid("VIS", "auth"), "CASH", "", null)))
                .andExpect(status().isUnauthorized());

        // GET /uid/{uid}
        mockMvc.perform(get(BASE_URL + "/uid/ANYUID0000000000000000001"))
                .andExpect(status().isUnauthorized());

        // POST /uid/{uid}/sign-out
        mockMvc.perform(post(BASE_URL + "/uid/ANYUID0000000000000000001/sign-out"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // V31 schema assertion — patient_uid/visit_uid added; patient_id/visit_id + FKs dropped
    // =========================================================================

    @Test
    void v31_addsPatientUidColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'non_consultations' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(count).as("non_consultations.patient_uid must exist (V31)").isEqualTo(1);
    }

    @Test
    void v31_addsVisitUidColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'non_consultations' AND column_name = 'visit_uid'",
                Integer.class);
        assertThat(count).as("non_consultations.visit_uid must exist (V31)").isEqualTo(1);
    }

    @Test
    void v31_dropsPatientIdColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'non_consultations' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(count).as("non_consultations.patient_id must be DROPPED (ADR-0022 D2 Correction)")
                .isZero();
    }

    @Test
    void v31_dropsVisitIdColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'non_consultations' AND column_name = 'visit_id'",
                Integer.class);
        assertThat(count).as("non_consultations.visit_id must be DROPPED (ADR-0022 D2 Correction)")
                .isZero();
    }

    @Test
    void v31_dropsPatientAndVisitForeignKeys() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conname IN ('fk_non_consultations_patient', 'fk_non_consultations_visit')",
                Integer.class);
        assertThat(count)
                .as("the legacy patient/visit FKs must be dropped from non_consultations (ADR-0022 D2 Correction)")
                .isZero();
    }

    @Test
    void v31_patientUidIndexExists() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'non_consultations' "
                        + "AND indexname = 'idx_non_consultations_patient_uid'",
                Integer.class);
        assertThat(count).as("idx_non_consultations_patient_uid must exist (V31)").isEqualTo(1);
    }

    @Test
    void v31_visitUidIndexExists() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'non_consultations' "
                        + "AND indexname = 'idx_non_consultations_visit_uid'",
                Integer.class);
        assertThat(count).as("idx_non_consultations_visit_uid must exist (V31)").isEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String uniq() {
        return "NC" + Long.toHexString(System.nanoTime());
    }

    /**
     * Build a deterministic, valid 26-char synthetic uid from prefix + tag.
     * Cross-module uid columns are VARCHAR(26). No real FK exists — ADR-0008 loose uid.
     * The tag may be up to ~20 chars; prefixed + padded to exactly 26 chars.
     */
    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        String padded = base + "000000000000000000000000000000";
        return padded.substring(0, 26);
    }

    /** Seed a new IN_PROCESS NonConsultation directly via the repository. */
    private String seedInProcess(String tag) {
        NonConsultation nc = new NonConsultation(
                fakeUid("PAT", tag),
                fakeUid("VIS", tag),
                "CASH",
                "",
                null,
                businessDayService.currentUid());
        return nonConsultationRepository.saveAndFlush(nc).getUid();
    }

    /** Seed a new SIGNED_OUT NonConsultation directly via the repository. */
    private String seedSignedOut(String tag) {
        NonConsultation nc = new NonConsultation(
                fakeUid("PAT", tag),
                fakeUid("VIS", tag),
                "CASH",
                "",
                null,
                businessDayService.currentUid());
        nc.signOut();
        return nonConsultationRepository.saveAndFlush(nc).getUid();
    }

    private String buildOpenRequest(String patientUid, String visitUid,
                                    String paymentType, String membershipNo,
                                    String insurancePlanUid) {
        String plan = insurancePlanUid == null ? "null" : "\"" + insurancePlanUid + "\"";
        return """
                {"patientUid":"%s","visitUid":"%s","paymentType":"%s","membershipNo":"%s","insurancePlanUid":%s}
                """.formatted(patientUid, visitUid, paymentType, membershipNo, plan).trim();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
    }
}
