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
import com.otapp.hmis.clinical.domain.ConsultationTransfer;
import com.otapp.hmis.clinical.domain.ConsultationTransferRepository;
import com.otapp.hmis.clinical.domain.ConsultationTransferStatus;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
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
 * Integration tests for the {@link ConsultationTransfer} two-phase clinic-to-clinic hand-off
 * (inc-05 C3, ADR-0022 D4).
 *
 * <p>Exercises the raise / cancel / pending-queue endpoints via the full HTTP stack against
 * repository-seeded consultations. The exact legacy guards + verbatim messages are asserted:
 * <ul>
 *   <li>RAISE: source must be IN_PROCESS (else 422 "Can not transfer. Not an active
 *       consultation"); destination clinic must differ (else 422); one PENDING transfer per
 *       patient (the partial-unique index + guard).</li>
 *   <li>CANCEL: TRANSFERED → IN_PROCESS + transfer CANCELED; non-TRANSFERED → silent no-op.</li>
 *   <li>QUEUE: GET /transfers?status=PENDING returns ALL pending transfers system-wide.</li>
 * </ul>
 *
 * <p>The no-PENDING-child-orders guard at raise is DEFERRED to C7-C10 (those order entities do
 * not exist yet) and is therefore not asserted here.
 */
class ConsultationTransferIT extends AbstractIntegrationTest {

    private static final String CLINICAL_URL = "/api/v1/clinical/consultations";

    private static final String PATIENTS_URL = "/api/v1/patients";
    private static final String PRICES_URL   = "/api/v1/masterdata/service-prices";
    private static final String CLINICS_URL  = "/api/v1/masterdata/clinics";
    private static final String USERS_URL    = "/api/v1/iam/users";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired ConsultationTransferRepository transferRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired BusinessDayService businessDayService;

    private String token;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        token = jwtFactory.tokenWithPrivileges("doc1", List.of("PATIENT-ALL"));
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        if (roleRepository.findByName("CLINICIAN").isEmpty()) {
            roleRepository.save(new Role("CLINICIAN", "SYSTEM"));
        }
        ensureDayOpen();
        ensureRegistrationCashPrice();
    }

    // =========================================================================
    // RAISE
    // =========================================================================

    @Test
    void raise_fromInProcess_transitionsSourceToTransferedAndCreatesPendingTransfer() throws Exception {
        String tag = uniq();
        String consultationUid = seedInProcess(tag, fakeUid("SRC", tag));
        String destClinic = fakeUid("DST", tag);

        MvcResult r = mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + destClinic + "\",\"reason\":\"need specialist\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.consultationUid").value(consultationUid))
                .andExpect(jsonPath("$.destinationClinicUid").value(destClinic))
                .andExpect(jsonPath("$.reason").value("need specialist"))
                .andReturn();

        // Source consultation must now be TRANSFERED
        Consultation source = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(ConsultationStatus.TRANSFERED);

        // The transfer row is PENDING
        String transferUid = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("uid").asText();
        ConsultationTransfer t = transferRepository.findByUid(transferUid).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(ConsultationTransferStatus.PENDING);
    }

    @Test
    void raise_fromPending_returns422NotActiveConsultation() throws Exception {
        String tag = uniq();
        String consultationUid = seedWithStatus(tag, fakeUid("SRC", tag), ConsultationStatus.PENDING);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + fakeUid("DST", tag) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Can not transfer. Not an active consultation"));
    }

    @Test
    void raise_toSameClinic_returns422() throws Exception {
        String tag = uniq();
        String sourceClinic = fakeUid("SRC", tag);
        String consultationUid = seedInProcess(tag, sourceClinic);

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + sourceClinic + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                // Verbatim legacy message (PatientServiceImpl.java:2805 — "Can not", not "Cannot")
                .andExpect(jsonPath("$.detail").value("Can not transfer to the same clinic"));
    }

    @Test
    void raise_secondPendingForSamePatient_isRejected() throws Exception {
        String tag = uniq();
        String patientUid = fakeUid("PAT", tag);

        // First IN_PROCESS consultation for the patient → raise OK
        String c1 = seedInProcessForPatient(tag + "a", patientUid, fakeUid("CLA", tag));
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + c1 + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + fakeUid("DST", tag) + "\"}"))
                .andExpect(status().isCreated());

        // Second IN_PROCESS consultation for the SAME patient → raise must be rejected
        // (one PENDING transfer per patient; the partial-unique index + guard).
        String c2 = seedInProcessForPatient(tag + "b", patientUid, fakeUid("CLB", tag));
        // QA-04: must be exactly 422 with verbatim legacy message (PatientServiceImpl.java:2766)
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + c2 + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + fakeUid("DS2", tag) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Can not transfer, the patient already have a pending transfer"));
    }

    // =========================================================================
    // CANCEL
    // =========================================================================

    @Test
    void cancel_transfered_revertsSourceToInProcessAndTransferToCanceled() throws Exception {
        String tag = uniq();
        String consultationUid = seedInProcess(tag, fakeUid("SRC", tag));
        // Raise first
        MvcResult raised = mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + fakeUid("DST", tag) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String transferUid = objectMapper.readTree(raised.getResponse().getContentAsString())
                .get("uid").asText();

        // Cancel
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/cancel-transfer")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Source reverts to IN_PROCESS (NOT PENDING)
        Consultation source = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(ConsultationStatus.IN_PROCESS);
        // Transfer is CANCELED (row kept, not deleted)
        ConsultationTransfer t = transferRepository.findByUid(transferUid).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(ConsultationTransferStatus.CANCELED);
    }

    @Test
    void cancel_whenNotTransfered_isSilentNoOp() throws Exception {
        String tag = uniq();
        // IN_PROCESS consultation, no transfer raised → cancel-transfer is a silent no-op
        String consultationUid = seedInProcess(tag, fakeUid("SRC", tag));

        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/cancel-transfer")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Status unchanged (still IN_PROCESS)
        Consultation source = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(ConsultationStatus.IN_PROCESS);
    }

    // =========================================================================
    // PENDING QUEUE
    // =========================================================================

    @Test
    void pendingQueue_returnsRaisedTransferSystemWide() throws Exception {
        String tag = uniq();
        String consultationUid = seedInProcess(tag, fakeUid("SRC", tag));
        MvcResult raised = mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationClinicUid\":\"" + fakeUid("DST", tag) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String transferUid = objectMapper.readTree(raised.getResponse().getContentAsString())
                .get("uid").asText();

        // The queue must contain our PENDING transfer (system-wide, unscoped)
        MvcResult q = mockMvc.perform(get(CLINICAL_URL + "/transfers?status=PENDING")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(q.getResponse().getContentAsString()).contains(transferUid);
    }

    @Test
    void lifecycle_401_withoutToken() throws Exception {
        mockMvc.perform(get(CLINICAL_URL + "/transfers?status=PENDING"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Seed helpers
    // =========================================================================

    /** Build a deterministic, valid 26-char synthetic uid (VARCHAR(26); loose, no FK). */
    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "000000000000000000000000000000").substring(0, 26);
    }

    private static String uniq() {
        // Deterministic-but-unique per call via a counter; Math.random/new Date are unavailable.
        return "T" + Long.toString(System.nanoTime(), 36);
    }

    /** Seed an IN_PROCESS consultation with the given source clinic uid. */
    private String seedInProcess(String tag, String clinicUid) {
        return seedInProcessForPatient(tag, fakeUid("PAT", tag), clinicUid);
    }

    private String seedInProcessForPatient(String tag, String patientUid, String clinicUid) {
        Consultation c = buildConsultation(tag, patientUid, clinicUid);
        c.open(); // PENDING (settled=true) → IN_PROCESS
        return consultationRepository.saveAndFlush(c).getUid();
    }

    private String seedWithStatus(String tag, String clinicUid, ConsultationStatus status) {
        Consultation c = buildConsultation(tag, fakeUid("PAT", tag), clinicUid);
        if (status == ConsultationStatus.IN_PROCESS) {
            c.open();
        }
        // PENDING: leave as constructed.
        return consultationRepository.saveAndFlush(c).getUid();
    }

    /** A CASH consultation pre-settled (so open() passes the settlement gate in seeding). */
    private Consultation buildConsultation(String tag, String patientUid, String clinicUid) {
        return new Consultation(
                patientUid,
                null,                       // visitUid
                clinicUid,
                fakeUid("DOC", tag),        // clinicianUserUid
                fakeUid("BIL", tag),        // patientBillUid
                PaymentMode.CASH,
                false,                      // followUp
                true,                       // settled=true so open() passes the gate
                "",                         // membershipNo
                null,                       // insurancePlanUid
                businessDayUid());
    }

    private String businessDayUid() {
        // Reuse the same fake day uid shape; transfers don't validate the day exists (loose uid).
        return fakeUid("DAY", "FIXED");
    }

    // =========================================================================
    // QA-03 — transfer completion seam: rebook to correct / wrong clinic
    // =========================================================================

    /**
     * QA-03a: seed a TRANSFERED consultation + PENDING transfer, then send-to-doctor to the
     * matching destination clinic → transfer must become COMPLETED.
     */
    @Test
    void rebook_toCorrectDestination_completesTransfer() throws Exception {
        String tag = uniq();

        // Bootstrap real clinic + clinician + patient via HTTP (needed by send-to-doctor)
        String destClinicUid = createClinic("DEST-" + tag, "Dest Clinic " + tag);
        String srcClinicUid  = createClinic("SRC-" + tag, "Src Clinic " + tag);
        String clinicianUid  = createAffiliatedClinician(destClinicUid, "doc_qa03a_" + tag,
                "Doc QA03A " + tag);
        seedConsultationPrice(destClinicUid);
        seedConsultationPrice(srcClinicUid);
        String patientUid = registerCash("QA03A-" + tag);

        // Seed the source consultation, raise the transfer (TRANSFERED), then FREE it (SIGNED_OUT).
        // Legacy precondition (discovery state machine): the source must be freed before the
        // destination rebook, else the open-work guard {PENDING,TRANSFERED,IN-PROCESS} blocks it.
        // The transfer itself stays PENDING until the rebook completes it.
        Consultation src = new Consultation(
                patientUid, null, srcClinicUid, fakeUid("DOC", tag),
                fakeUid("BIL", tag), PaymentMode.CASH, false, true, "", null,
                businessDayUid());
        src.open();
        src.markTransferred();
        src.signOut();              // free_consultation on a TRANSFERED consultation → SIGNED_OUT
        consultationRepository.saveAndFlush(src);

        // Seed the PENDING transfer with destClinicUid as destination
        ConsultationTransfer transfer = new ConsultationTransfer(
                src, patientUid, destClinicUid, "test transfer", businessDayUid());
        transferRepository.saveAndFlush(transfer);
        String transferUid = transfer.getUid();

        // send-to-doctor to the CORRECT destination clinic → completePendingTransferOnRebook fires
        String sendBody = """
                {"clinicUid":"%s","clinicianUserUid":"%s","followUp":false}
                """.formatted(destClinicUid, clinicianUid);
        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody))
                .andExpect(status().isCreated());

        // Transfer must now be COMPLETED
        ConsultationTransfer completed = transferRepository.findByUid(transferUid).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ConsultationTransferStatus.COMPLETED);
    }

    /**
     * QA-03b: seed a TRANSFERED consultation + PENDING transfer, then send-to-doctor to a
     * WRONG clinic → 422 with verbatim F9 legacy message containing the required clinic name.
     */
    @Test
    void rebook_toWrongClinic_422VerbatimMessage() throws Exception {
        String tag = uniq();

        // Bootstrap clinics
        String destClinicUid  = createClinic("DEST2-" + tag, "Right Clinic " + tag);
        String wrongClinicUid = createClinic("WRONG-" + tag, "Wrong Clinic " + tag);
        String clinicianUid   = createAffiliatedClinician(wrongClinicUid, "doc_qa03b_" + tag,
                "Doc QA03B " + tag);
        seedConsultationPrice(destClinicUid);
        seedConsultationPrice(wrongClinicUid);
        String patientUid = registerCash("QA03B-" + tag);

        // Seed source, raise transfer (TRANSFERED toward destClinic), then FREE it (SIGNED_OUT)
        // so the open-work guard passes and the rebook reaches the transfer-clinic-match check.
        Consultation src = new Consultation(
                patientUid, null, wrongClinicUid, fakeUid("DOC", tag),
                fakeUid("BI2", tag), PaymentMode.CASH, false, true, "", null,
                businessDayUid());
        src.open();
        src.markTransferred();
        src.signOut();
        consultationRepository.saveAndFlush(src);

        ConsultationTransfer transfer = new ConsultationTransfer(
                src, patientUid, destClinicUid, "test transfer", businessDayUid());
        transferRepository.saveAndFlush(transfer);

        // send-to-doctor to the WRONG clinic → 422 with verbatim F9 message
        String sendBody = """
                {"clinicUid":"%s","clinicianUserUid":"%s","followUp":false}
                """.formatted(wrongClinicUid, clinicianUid);
        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(
                                "Can not send to the specified clinic. Patient has been transfered to")));
    }

    // =========================================================================
    // QA-03 helpers (booking infrastructure)
    // =========================================================================

    private String createClinic(String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":"test","consultationFee":1000.00,"active":false}
                """.formatted(code, name);
        MvcResult r = mockMvc.perform(post(CLINICS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createAffiliatedClinician(String clinicUid, String username,
                                              String nickname) throws Exception {
        String body = """
                {"username":"%s","password":"pass1234","firstName":"Test","lastName":"Clinician",
                 "nickname":"%s","roleNames":["CLINICIAN"]}
                """.formatted(username, nickname);
        MvcResult r = mockMvc.perform(post(USERS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String clinicianUid = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("uid").asText();
        mockMvc.perform(post(CLINICS_URL + "/uid/" + clinicUid + "/clinicians")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUid + "\"}"))
                .andExpect(status().isCreated());
        return clinicianUid;
    }

    private void seedConsultationPrice(String clinicUid) throws Exception {
        String body = """
                {"planUid":null,"kind":"CONSULTATION","serviceUid":"%s","currency":"TZS",
                 "amount":1000.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(clinicUid);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }

    private String registerCash(String last) throws Exception {
        String body = """
                {"firstName":"TR","lastName":"%s","dateOfBirth":"1990-01-01","gender":"MALE",
                 "paymentType":"CASH"}
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
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }
}
