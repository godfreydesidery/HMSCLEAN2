package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.registration.domain.PatientRepository;
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
 * Integration tests for the consultation lifecycle state machine (inc-05 C2, ADR-0022 D4).
 *
 * <p>Tests all five transitions (open, openFollowUp, cancel, free, switchToNormal) against
 * the ratified guards and verbatim error messages from 11-DECISIONS-RATIFIED.md.
 *
 * <p>Settlement gate tests (CR-INC05-01):
 * <ul>
 *   <li>INSURANCE/COVERED (settled=true at booking) → open passes.</li>
 *   <li>CASH (settled=false at booking) → open throws 422 PAY_BEFORE_SERVICE.</li>
 *   <li>Follow-up NONE (settled=true at booking pre-pass) → openFollowUp passes.</li>
 * </ul>
 *
 * <p>Uses the full HTTP stack (MockMvc) via the clinical controller endpoints:
 * POST /api/v1/clinical/consultations/uid/{uid}/open etc.
 */
class ConsultationLifecycleIT extends AbstractIntegrationTest {

    private static final String PATIENTS_URL    = "/api/v1/patients";
    private static final String PRICES_URL      = "/api/v1/masterdata/service-prices";
    private static final String CLINICS_URL     = "/api/v1/masterdata/clinics";
    private static final String USERS_URL       = "/api/v1/iam/users";
    private static final String CLINICAL_URL    = "/api/v1/clinical/consultations";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired RoleRepository roleRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired BusinessDayService businessDayService;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        if (roleRepository.findByName("CLINICIAN").isEmpty()) {
            roleRepository.save(new Role("CLINICIAN", "SYSTEM"));
        }
        ensureDayOpen();
        ensureRegistrationCashPrice();
    }

    // =========================================================================
    // GET /uid/{uid}
    // =========================================================================

    @Test
    void getByUid_200_returnsConsultation() throws Exception {
        String u = uniq();
        String[] refs = createConsultationViaBooking(u, false);
        String consultationUid = refs[0];

        mockMvc.perform(get(CLINICAL_URL + "/uid/" + consultationUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(consultationUid))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_404_unknownUid() throws Exception {
        mockMvc.perform(get(CLINICAL_URL + "/uid/NONEXISTENT000000000000001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // open — INSURANCE (settled=true) → IN_PROCESS
    // =========================================================================

    @Test
    void open_insuranceSettled_transitionsToInProcess() throws Exception {
        String u = uniq();
        // Seed an INSURANCE consultation directly with settled=true
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, false, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"))
                .andExpect(jsonPath("$.uid").value(consultationUid));

        Consultation loaded = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ConsultationStatus.IN_PROCESS);
    }

    // =========================================================================
    // open — CASH unsettled (settled=false) → 422 PAY_BEFORE_SERVICE
    // =========================================================================

    @Test
    void open_cashUnsettled_422PayBeforeService() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultation(u, PaymentMode.CASH, false, false);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:pay-before-service"));

        // Status must remain PENDING
        assertThat(consultationRepository.findByUid(consultationUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.PENDING);
    }

    // =========================================================================
    // open — non-PENDING → 422 "Not a pending consultation"
    // =========================================================================

    @Test
    void open_nonPending_422NotPendingMessage() throws Exception {
        String u = uniq();
        // Seed settled INSURANCE so we can open it, then try to open again
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, false, true);
        // First open — succeeds
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        // Second open — fails with verbatim message
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Not a pending consultation"));
    }

    // =========================================================================
    // openFollowUp — followUp=true, settled=true (NONE bill pre-pass) → IN_PROCESS
    // =========================================================================

    @Test
    void openFollowUp_followUpSettled_transitionsToInProcess() throws Exception {
        String u = uniq();
        // Follow-up consultation: settled=true at booking (NONE bill auto-pass)
        String consultationUid = seedConsultation(u, PaymentMode.CASH, true, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open-follow-up")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"));

        assertThat(consultationRepository.findByUid(consultationUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.IN_PROCESS);
    }

    // =========================================================================
    // openFollowUp — followUp=false → 422 "This is not a follow up consultation"
    // =========================================================================

    @Test
    void openFollowUp_notFollowUp_422VerbatimMessage() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, false, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open-follow-up")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("This is not a follow up consultation"));
    }

    // =========================================================================
    // openFollowUp — non-PENDING status → silent no-op (legacy parity)
    // =========================================================================

    @Test
    void openFollowUp_nonPendingStatus_silentNoOp() throws Exception {
        String u = uniq();
        // Seed a follow-up that is already IN_PROCESS (simulate prior open)
        String consultationUid = seedConsultationWithStatus(u, PaymentMode.CASH, true, true,
                ConsultationStatus.IN_PROCESS);

        // Must return 200 with unchanged IN_PROCESS status (no error, no state change)
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open-follow-up")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"));
    }

    // =========================================================================
    // cancel — PENDING → CANCELED
    // =========================================================================

    @Test
    void cancel_pending_transitionsToCanceled() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, false, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        assertThat(consultationRepository.findByUid(consultationUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.CANCELED);
    }

    // =========================================================================
    // cancel — non-PENDING → 422 verbatim message
    // =========================================================================

    @Test
    void cancel_nonPending_422VerbatimMessage() throws Exception {
        String u = uniq();
        // IN_PROCESS consultation
        String consultationUid = seedConsultationWithStatus(u, PaymentMode.INSURANCE, false, true,
                ConsultationStatus.IN_PROCESS);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not cancel, only a PENDING consultation can be canceled"));
    }

    // =========================================================================
    // free — IN_PROCESS → SIGNED_OUT
    // =========================================================================

    @Test
    void free_inProcess_transitionsToSignedOut() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultationWithStatus(u, PaymentMode.INSURANCE, false, true,
                ConsultationStatus.IN_PROCESS);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/free")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED-OUT"));

        assertThat(consultationRepository.findByUid(consultationUid).orElseThrow().getStatus())
                .isEqualTo(ConsultationStatus.SIGNED_OUT);
    }

    // =========================================================================
    // free — TRANSFERED → SIGNED_OUT
    // =========================================================================

    @Test
    void free_transfered_transitionsToSignedOut() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultationWithStatus(u, PaymentMode.INSURANCE, false, true,
                ConsultationStatus.TRANSFERED);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/free")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED-OUT"));
    }

    // =========================================================================
    // free — wrong status → 422 verbatim message
    // =========================================================================

    @Test
    void free_wrongStatus_422VerbatimMessage() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, false, true);
        // PENDING — not freeable

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/free")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Could not free, only a TRANSFERED or IN-PROCESS consultation can be freed"));
    }

    // =========================================================================
    // switchToNormal — CASH follow-up: resets settled=false
    // =========================================================================

    @Test
    void switchToNormal_cashFollowUp_resetsSettledFlag() throws Exception {
        String u = uniq();
        // CASH follow-up, settled=true (pre-pass for NONE bill)
        String consultationUid = seedConsultation(u, PaymentMode.CASH, true, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/switch-to-normal")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUp").value(false));

        Consultation loaded = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(loaded.isFollowUp()).isFalse();
        // CASH: settled must be reset to false so the payment gate fires on next open
        assertThat(loaded.isSettled()).isFalse();
    }

    // =========================================================================
    // switchToNormal — INSURANCE follow-up: settled stays true
    // =========================================================================

    @Test
    void switchToNormal_insuranceFollowUp_settledRemainsTrue() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, true, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/switch-to-normal")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUp").value(false));

        Consultation loaded = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(loaded.isFollowUp()).isFalse();
        // INSURANCE: covered patients do not need to re-pay; settled stays true
        assertThat(loaded.isSettled()).isTrue();
    }

    // =========================================================================
    // reception-queue
    // =========================================================================

    @Test
    void receptionQueue_returnsOnlySettledPendingNonFollowUp() throws Exception {
        String u = uniq();
        String clinicianUid = createClinicianUserRaw("doc_rq_" + u, "Doc RQ " + u);

        // Settled PENDING non-follow-up → should appear
        String c1 = seedConsultationForClinician(u + "A", clinicianUid, false, true);
        // Unsettled PENDING non-follow-up → must NOT appear
        String c2 = seedConsultationForClinician(u + "B", clinicianUid, false, false);
        // Settled PENDING follow-up → must NOT appear (followUp=true excluded)
        String c3 = seedConsultationForClinician(u + "C", clinicianUid, true, true);

        MvcResult r = mockMvc.perform(get(CLINICAL_URL + "/reception-queue")
                        .param("clinicianUserUid", clinicianUid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode arr =
                objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        // c1 must be in the queue
        boolean hasC1 = false;
        for (com.fasterxml.jackson.databind.JsonNode node : arr) {
            if (c1.equals(node.get("uid").asText())) hasC1 = true;
            // c2 and c3 must NOT be in the queue
            assertThat(node.get("uid").asText()).isNotIn(c2, c3);
        }
        assertThat(hasC1).as("settled PENDING non-follow-up must appear in reception queue").isTrue();
    }

    // =========================================================================
    // RBAC: 401 without token, 200 with token (authenticated-only per CR-INC05-02)
    // =========================================================================

    @Test
    void lifecycle_401_noToken() throws Exception {
        String u = uniq();
        String consultationUid = seedConsultation(u, PaymentMode.INSURANCE, false, true);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String uniq() {
        return "CL" + Long.toHexString(System.nanoTime());
    }

    /**
     * Seed a PENDING consultation directly via the repository (bypassing the HTTP booking
     * endpoint) to test lifecycle transitions in isolation.
     */
    private String seedConsultation(String tag, PaymentMode mode, boolean followUp, boolean settled) {
        return seedConsultationWithStatus(tag, mode, followUp, settled, ConsultationStatus.PENDING);
    }

    /**
     * Build a deterministic, valid 26-char synthetic ULID-shaped uid from a prefix + tag.
     * Cross-module uid columns are {@code VARCHAR(26)}; method-name-derived tags can be long,
     * so we hash+pad to a guaranteed 26-char value (no real FK exists — ADR-0008 loose uid).
     */
    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        String padded = (base + "000000000000000000000000000000");
        return padded.substring(0, 26);
    }

    private String seedConsultationWithStatus(String tag, PaymentMode mode, boolean followUp,
                                               boolean settled, ConsultationStatus status) {
        Consultation c = new Consultation(
                fakeUid("PAT", tag),
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                fakeUid("BIL", tag),
                mode,
                followUp,
                settled,
                mode == PaymentMode.INSURANCE ? "MEM-" + tag : "",
                mode == PaymentMode.INSURANCE ? fakeUid("PLN", tag) : null,
                businessDayService.currentUid());
        // Force the status after construction for non-PENDING seeds
        if (status != ConsultationStatus.PENDING) {
            // Use domain method to set correct status
            switch (status) {
                case IN_PROCESS -> c.open();
                case CANCELED   -> c.cancel();
                case SIGNED_OUT -> { c.open(); c.free(); }
                case TRANSFERED -> {
                    /* No domain transition to TRANSFERED yet (ConsultationTransfer arrives in C3+).
                       Seed as IN_PROCESS instead — both IN_PROCESS and TRANSFERED free to SIGNED_OUT,
                       so the free-transition test is valid either way. */
                    c.open();
                }
                default -> { /* PENDING, HELD: leave as-is */ }
            }
        }
        return consultationRepository.save(c).getUid();
    }

    private String seedConsultationForClinician(String tag, String clinicianUid,
                                                 boolean followUp, boolean settled) {
        Consultation c = new Consultation(
                fakeUid("PAT", tag),
                null,
                fakeUid("CLN", tag),
                clinicianUid,
                fakeUid("BIL", tag),
                PaymentMode.CASH,
                followUp,
                settled,
                "",
                null,
                businessDayService.currentUid());
        return consultationRepository.save(c).getUid();
    }

    /** Create a consultation via the real send-to-doctor booking endpoint. */
    private String[] createConsultationViaBooking(String u, boolean followUp) throws Exception {
        String clinicUid = createClinic("CLN-LC-" + u, "Clinic LC " + u);
        String clinicianUid = createAffiliatedClinician(clinicUid, "doc_lc_" + u, "Doc LC " + u);
        seedConsultationPrice(clinicUid, "1500.00");
        String patientUid = registerCash("PatLC-" + u);

        String sendBody = "{\"clinicUid\":\"%s\",\"clinicianUserUid\":\"%s\",\"followUp\":%b}"
                .formatted(clinicUid, clinicianUid, followUp);
        MvcResult r = mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody))
                .andExpect(status().isCreated()).andReturn();
        String consultationUid = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("uid").asText();
        return new String[]{consultationUid, patientUid, clinicianUid};
    }

    private String createClinic(String code, String name) throws Exception {
        String body = "{\"code\":\"%s\",\"name\":\"%s\",\"description\":\"t\",\"consultationFee\":1000.00,\"active\":false}"
                .formatted(code, name);
        MvcResult r = mockMvc.perform(post(CLINICS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createClinicianUserRaw(String username, String nickname) throws Exception {
        String body = """
                {"username":"%s","password":"pass1234","firstName":"Test","lastName":"Clinician",
                 "nickname":"%s","roleNames":["CLINICIAN"]}
                """.formatted(username, nickname);
        MvcResult r = mockMvc.perform(post(USERS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createAffiliatedClinician(String clinicUid, String username,
                                              String nickname) throws Exception {
        String clinicianUid = createClinicianUserRaw(username, nickname);
        mockMvc.perform(post(CLINICS_URL + "/uid/" + clinicUid + "/clinicians")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUid + "\"}"))
                .andExpect(status().isCreated());
        return clinicianUid;
    }

    private void seedConsultationPrice(String clinicUid, String amount) throws Exception {
        String body = """
                {"planUid":null,"kind":"CONSULTATION","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(clinicUid, amount);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }

    private String registerCash(String last) throws Exception {
        String body = """
                {"firstName":"LC","lastName":"%s","dateOfBirth":"1990-06-15","gender":"MALE","paymentType":"CASH"}
                """.formatted(last);
        MvcResult r = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
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
}
