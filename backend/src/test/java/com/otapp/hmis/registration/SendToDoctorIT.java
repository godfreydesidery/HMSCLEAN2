package com.otapp.hmis.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.VisitRepository;
import com.otapp.hmis.shared.audit.AuditLogRepository;
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
 * C6 integration tests — send-to-doctor (build-spec §3, ≡ legacy do_consultation). Sets up a clinic
 * + an affiliated CLINICIAN user (iam) + a CONSULTATION price, then exercises the atomic
 * consultation + fee-bill + SUBSEQUENT-visit flow and its guards.
 */
class SendToDoctorIT extends AbstractIntegrationTest {

    private static final String PATIENTS_URL = "/api/v1/patients";
    private static final String PRICES_URL   = "/api/v1/masterdata/service-prices";
    private static final String CLINICS_URL  = "/api/v1/masterdata/clinics";
    private static final String USERS_URL    = "/api/v1/iam/users";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired RoleRepository roleRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AuditLogRepository auditLogRepository;
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
    // Success (CASH) — atomic consultation + fee bill + SUBSEQUENT visit
    // =========================================================================

    @Test
    void sendToDoctor_cash_201_createsConsultationFeeBillAndVisit() throws Exception {
        String u = uniq();
        String clinicUid = createClinic("CLN-OK-" + u, "Clinic OK " + u);
        String clinicianUid = createAffiliatedClinician(clinicUid, "doc_ok_" + u, "Doc OK " + u);
        seedConsultationPrice(clinicUid, "2000.00");
        String patientUid = registerCash("Pat-" + u);

        MvcResult r = mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.clinicUid").value(clinicUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();
        String consultationUid = objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();

        // guard via patientUid (ADR-0022 D6 — re-keyed from entity to uid)
        assertThat(consultationRepository.existsByPatientUidAndStatus(patientUid, ConsultationStatus.PENDING)).isTrue();

        // A CONSULTATION fee bill exists for the patient (in addition to the REGISTRATION bill)
        List<PatientBill> bills = patientBillRepository.findByPatientUid(patientUid);
        assertThat(bills).anyMatch(b -> b.getKind().name().equals("CONSULTATION"));

        // A SUBSEQUENT visit was added (FIRST from registration + SUBSEQUENT from send-to-doctor)
        // VisitRepository still takes a Patient entity (registration-owned); load here.
        var patient = patientRepository.findByUid(patientUid).orElseThrow();
        var visits = visitRepository.findByPatientOrderByCreatedAtDesc(patient);
        assertThat(visits).hasSize(2);
        assertThat(visits.stream().anyMatch(v -> v.getSequence().name().equals("SUBSEQUENT"))).isTrue();
        assertThat(consultationUid).isNotBlank();

        // Audit: a Consultation CREATE row exists (ADR-0007).
        // Entity type is "clinical.Consultation" after ADR-0022 ownership transfer (C2).
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(consultationUid))
                .anyMatch(a -> "clinical.Consultation".equals(a.getEntityType())
                        && "CREATE".equals(a.getAction().name()));
    }

    // =========================================================================
    // Follow-up → NONE consultation bill (no charge, no CONSULTATION price needed)
    // =========================================================================

    @Test
    void sendToDoctor_followUp_createsNoneBill() throws Exception {
        String u = uniq();
        String clinicUid = createClinic("CLN-FU-" + u, "Clinic FU " + u);
        String clinicianUid = createAffiliatedClinician(clinicUid, "doc_fu_" + u, "Doc FU " + u);
        String patientUid = registerCash("PatFU-" + u);   // no CONSULTATION price seeded

        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, true)))
                .andExpect(status().isCreated());

        List<PatientBill> bills = patientBillRepository.findByPatientUid(patientUid);
        assertThat(bills)
                .filteredOn(b -> b.getKind().name().equals("CONSULTATION"))
                .isNotEmpty()
                .allMatch(b -> b.getStatus() == BillStatus.NONE)
                .allMatch(b -> b.amountValue().compareTo(java.math.BigDecimal.ZERO) == 0);
    }

    // =========================================================================
    // Guards
    // =========================================================================

    @Test
    void sendToDoctor_notOutpatient_422() throws Exception {
        String u = uniq();
        String clinicUid = createClinic("CLN-NO-" + u, "Clinic NO " + u);
        String clinicianUid = createAffiliatedClinician(clinicUid, "doc_no_" + u, "Doc NO " + u);
        String patientUid = registerCash("PatNO-" + u);
        // flip to OUTSIDER
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + patientUid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"OUTSIDER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, false)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sendToDoctor_clinicianNotAffiliated_422() throws Exception {
        String u = uniq();
        String clinicUid = createClinic("CLN-AFF-" + u, "Clinic AFF " + u);
        // create a clinician but do NOT affiliate them to the clinic
        String clinicianUid = createClinicianUser("doc_naf_" + u, "Doc NAF " + u);
        seedConsultationPrice(clinicUid, "2000.00");
        String patientUid = registerCash("PatNAF-" + u);

        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, false)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sendToDoctor_existingPendingConsultation_422() throws Exception {
        String u = uniq();
        String clinicUid = createClinic("CLN-DUP-" + u, "Clinic DUP " + u);
        String clinicianUid = createAffiliatedClinician(clinicUid, "doc_dup_" + u, "Doc DUP " + u);
        seedConsultationPrice(clinicUid, "2000.00");
        String patientUid = registerCash("PatDUP-" + u);

        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, false)))
                .andExpect(status().isCreated());
        // second send → blocked
        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, false)))
                .andExpect(status().isUnprocessableEntity());
    }

    // =========================================================================
    // Atomicity — charge failure (no CONSULTATION price) rolls back the whole unit
    // =========================================================================

    @Test
    void sendToDoctor_chargeFails_rollsBackEntirely() throws Exception {
        String u = uniq();
        String clinicUid = createClinic("CLN-ATOM-" + u, "Clinic ATOM " + u);
        String clinicianUid = createAffiliatedClinician(clinicUid, "doc_atom_" + u, "Doc ATOM " + u);
        // NO CONSULTATION price seeded → recordClinicalCharge throws SERVICE_PRICE_NOT_FOUND (422)
        String patientUid = registerCash("PatATOM-" + u);

        mockMvc.perform(post(PATIENTS_URL + "/uid/" + patientUid + "/send-to-doctor")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(clinicUid, clinicianUid, false)))
                .andExpect(status().isUnprocessableEntity());

        // Whole unit rolled back: no PENDING consultation, no SUBSEQUENT visit (only FIRST),
        // no CONSULTATION bill — only the registration bill remains.
        Patient patient = patientRepository.findByUid(patientUid).orElseThrow();
        // guard via patientUid (ADR-0022 D6 — re-keyed from entity to uid)
        assertThat(consultationRepository.existsByPatientUidAndStatus(patientUid, ConsultationStatus.PENDING)).isFalse();
        assertThat(visitRepository.findByPatientOrderByCreatedAtDesc(patient)).hasSize(1);
        assertThat(patientBillRepository.findByPatientUid(patientUid))
                .noneMatch(b -> b.getKind().name().equals("CONSULTATION"));
    }

    // =========================================================================
    // RBAC
    // =========================================================================

    @Test
    void sendToDoctor_403_withoutPatientPrivilege() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("viewer", List.of("DAY-ACCESS"));
        mockMvc.perform(post(PATIENTS_URL + "/uid/SOMEUID/send-to-doctor")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("C", "D", false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendToDoctor_401_noToken() throws Exception {
        mockMvc.perform(post(PATIENTS_URL + "/uid/SOMEUID/send-to-doctor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("C", "D", false)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String uniq() {
        return "S" + Long.toHexString(System.nanoTime());
    }

    private static String sendBody(String clinicUid, String clinicianUid, boolean followUp) {
        return "{\"clinicUid\":\"%s\",\"clinicianUserUid\":\"%s\",\"followUp\":%b}"
                .formatted(clinicUid, clinicianUid, followUp);
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

    private String createClinicianUser(String username, String nickname) throws Exception {
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

    private String createAffiliatedClinician(String clinicUid, String username, String nickname) throws Exception {
        String clinicianUid = createClinicianUser(username, nickname);
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
                {"firstName":"Send","lastName":"%s","dateOfBirth":"1990-06-15","gender":"MALE","paymentType":"CASH"}
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
