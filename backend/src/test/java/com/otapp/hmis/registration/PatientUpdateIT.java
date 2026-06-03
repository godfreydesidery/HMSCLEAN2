package com.otapp.hmis.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.registration.domain.Consultation;
import com.otapp.hmis.registration.domain.ConsultationRepository;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
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
 * Integration tests for C4 — update demographics + patient-type/payment-type flips
 * (build-spec §8 C4, §2.3; legacy change_type :398-506, change_payment_type :359-373).
 */
class PatientUpdateIT extends AbstractIntegrationTest {

    private static final String PATIENTS_URL = "/api/v1/patients";
    private static final String PRICES_URL   = "/api/v1/masterdata/service-prices";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired PatientRepository patientRepository;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired BusinessDayService businessDayService;

    private String adminToken;   // PATIENT-ALL — register + update
    private String viewerToken;  // no patient privilege — 403

    @BeforeEach
    void setUp() throws Exception {
        adminToken  = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS", "BILL-A", "PATIENT-ALL"));
        viewerToken = jwtFactory.tokenWithPrivileges("viewer", List.of("DAY-ACCESS"));
        ensureDayOpen();
        ensureRegistrationCashPrice();
    }

    // ---- update demographics ----

    @Test
    void updateDemographics_200_fieldsChanged() throws Exception {
        String uid = registerCash("UPD");

        String body = """
                {"firstName":"Johnny","lastName":"Updated","dateOfBirth":"1990-06-15",
                 "gender":"MALE","phoneNo":"0799000111","address":"New Street 5",
                 "email":"j@x.io","kinFullName":"Mary Kin","kinRelationship":"Spouse","kinPhoneNo":"0788"}
                """;
        mockMvc.perform(put(PATIENTS_URL + "/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Johnny"))
                .andExpect(jsonPath("$.address").value("New Street 5"))
                .andExpect(jsonPath("$.id").doesNotExist());

        Patient p = patientRepository.findByUid(uid).orElseThrow();
        assertThat(p.getLastName()).isEqualTo("Updated");
        assertThat(p.getKinFullName()).isEqualTo("Mary Kin");
        // searchKey is recomputed from the new name (PatientServiceImpl.java:706-707)
        assertThat(p.getSearchKey()).contains("Updated").contains("Johnny");
    }

    // ---- patient-type flips ----

    @Test
    void changePatientType_outpatientToOutsider_200() throws Exception {
        String uid = registerCash("FLIP-OK");
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"OUTSIDER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OUTSIDER"));
    }

    @Test
    void changePatientType_blockedByActiveConsultation_422() throws Exception {
        String uid = registerCash("FLIP-BLK");
        Patient p = patientRepository.findByUid(uid).orElseThrow();
        // Seed a PENDING consultation directly (saveAndFlush so it's visible to the endpoint's tx)
        seedPendingConsultation(p);

        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"OUTSIDER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:business-rule"));
    }

    @Test
    void changePatientType_outsiderToOutpatient_200() throws Exception {
        String uid = registerCash("FLIP-BACK");
        // OUTPATIENT -> OUTSIDER then OUTSIDER -> OUTPATIENT (the reverse flip; REG-3 order check deferred)
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"OUTSIDER\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"OUTPATIENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OUTPATIENT"));
    }

    @Test
    void changePatientType_deceasedTarget_422() throws Exception {
        String uid = registerCash("FLIP-DEC");
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"DECEASED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changePatientType_inpatient_422() throws Exception {
        String uid = registerCash("FLIP-INP");
        Patient p = patientRepository.findByUid(uid).orElseThrow();
        p.changeType(PatientType.INPATIENT);
        patientRepository.save(p);

        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/patient-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"OUTSIDER\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- payment-type flips ----

    @Test
    void changePaymentType_cashToInsurance_requiresPlanAndMembership() throws Exception {
        String uid = registerCash("PAY-INS");
        // Missing plan+membership → 422
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/payment-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"INSURANCE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:missing-insurance-information"));

        // With plan + membership → 200
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/payment-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"INSURANCE\",\"insurancePlanUid\":\"PLAN00000000000000000001\",\"membershipNo\":\"MEM-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentType").value("INSURANCE"))
                .andExpect(jsonPath("$.membershipNo").value("MEM-1"));
    }

    @Test
    void changePaymentType_insuranceToCash_collapses() throws Exception {
        String uid = registerCash("PAY-CASH");
        // Make it INSURANCE first
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/payment-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"INSURANCE\",\"insurancePlanUid\":\"PLAN00000000000000000002\",\"membershipNo\":\"MEM-2\"}"))
                .andExpect(status().isOk());
        // Flip back to CASH → plan nulled
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/payment-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"CASH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentType").value("CASH"));

        // plan nulled on collapse (assert on the persisted entity — robust to Jackson null handling)
        assertThat(patientRepository.findByUid(uid).orElseThrow().getInsurancePlanUid()).isNull();
    }

    @Test
    void changePaymentType_blockedByActiveConsultation_422() throws Exception {
        // M1: the payment-type flip is blocked when the patient has a PENDING consultation
        String uid = registerCash("PAY-BLK");
        seedPendingConsultation(patientRepository.findByUid(uid).orElseThrow());

        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/payment-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"INSURANCE\",\"insurancePlanUid\":\"PLAN00000000000000000003\",\"membershipNo\":\"MEM-3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:business-rule"));
    }

    @Test
    void changePaymentType_returns403_withoutPatientUpdate() throws Exception {
        // CR-03: the payment-type flip is now gated (legacy was ungated)
        mockMvc.perform(patch(PATIENTS_URL + "/uid/SOMEUID/payment-type")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"CASH\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- RBAC / 404 ----

    @Test
    void update_returns403_withoutPatientUpdate() throws Exception {
        mockMvc.perform(put(PATIENTS_URL + "/uid/SOMEUID")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"dateOfBirth\":\"1990-01-01\",\"gender\":\"MALE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changePatientType_returns401_whenNoToken() throws Exception {
        mockMvc.perform(patch(PATIENTS_URL + "/uid/SOMEUID/patient-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"OUTSIDER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_unknownUid_404() throws Exception {
        mockMvc.perform(put(PATIENTS_URL + "/uid/NONEXISTENTUID000000000001")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"dateOfBirth\":\"1990-01-01\",\"gender\":\"MALE\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private String registerCash(String suffix) throws Exception {
        String body = """
                {"firstName":"Pat-%s","lastName":"Last-%s","dateOfBirth":"1990-06-15",
                 "gender":"MALE","paymentType":"CASH","phoneNo":"071%s"}
                """.formatted(suffix, suffix, suffix.replaceAll("[^0-9]", "") + "1");
        MvcResult r = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    /** Seed a PENDING consultation for the patient (saveAndFlush so the endpoint tx sees it). */
    private void seedPendingConsultation(Patient p) {
        consultationRepository.saveAndFlush(new Consultation(
                p, null, "CLINIC0000000000000000001", "CLINICIAN00000000000001",
                "BILL000000000000000000001", PaymentType.CASH, false,
                businessDayService.currentUid()));
    }

    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
    }

    private void ensureRegistrationCashPrice() throws Exception {
        String reqBody = """
                {"planUid":null,"kind":"REGISTRATION","serviceUid":null,"currency":"TZS",
                 "amount":500.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """;
        // service-prices is an UPSERT: 201 (first create), 200 (update existing — common on the
        // shared singleton container where a prior test already seeded the row), or 409.
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(reqBody))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }
}
