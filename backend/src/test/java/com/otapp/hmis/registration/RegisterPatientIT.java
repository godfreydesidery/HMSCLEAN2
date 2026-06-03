package com.otapp.hmis.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.RegistrationRepository;
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
 * Integration tests for {@code POST /api/v1/patients} — register patient (CASH + INSURANCE).
 *
 * <p>Build-spec C3 (§8), mirroring the {@code BillingChargeIT} setup pattern:
 * a REGISTRATION cash {@code service_prices} row must exist before the charge call succeeds
 * (build-spec §0 item 2, CR-12). The test seeds prices via the masterdata REST API to ensure
 * full pipeline exercise.
 *
 * <p>Uses the singleton Testcontainer (shared with all ITs); each test uses unique patient
 * data (names include the test-method suffix) to avoid unique-constraint collisions.
 *
 * <p>Assertions per the C3 scope:
 * <ol>
 *   <li>CASH patient: Patient persisted with {@code MRNO/\d{4}/\d+} pattern, searchKey non-blank,
 *       type=OUTPATIENT (default), active=true.</li>
 *   <li>Registration persisted with status=ACTIVE and a non-blank {@code patientBillUid}.</li>
 *   <li>First Visit persisted with sequence=FIRST and status=PENDING.</li>
 *   <li>A {@code patient_bills} row exists for the patient uid (kind=REGISTRATION) via
 *       {@link PatientBillRepository#findByPatientUid}.</li>
 *   <li>An {@code audit_log} CREATE row exists for the patient uid.</li>
 *   <li>REST: 201 + Location header + JSON body without {@code id}; 403 for missing privilege;
 *       401 for no token.</li>
 *   <li>INSURANCE patient: 201 + Registration persisted.</li>
 *   <li>INSURANCE without plan/membership: 422 {@code urn:hmis:error:missing-insurance-information}.</li>
 * </ol>
 */
class RegisterPatientIT extends AbstractIntegrationTest {

    private static final String PATIENTS_URL  = "/api/v1/patients";
    private static final String PRICES_URL    = "/api/v1/masterdata/service-prices";
    private static final String PROVIDERS_URL = "/api/v1/masterdata/insurance-providers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;

    @Autowired PatientRepository patientRepository;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired BusinessDayService businessDayService;

    private String adminToken;
    private String clerkToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "BILL-A", "PATIENT-ALL"));
        clerkToken = jwtFactory.tokenWithPrivileges("clerk",
                List.of("PATIENT-CREATE"));
        ensureDayOpen();
        ensureRegistrationCashPrice();
    }

    // =========================================================================
    // CASH registration — full domain + REST assertions
    // =========================================================================

    @Test
    void registerCashPatient_201_domainObjectsCorrect() throws Exception {
        String body = cashRequest("John", "Doe", "C1-CASH");

        MvcResult result = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.matchesPattern(".*/api/v1/patients/uid/[A-Z0-9]{26}")))
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.no").value(
                        org.hamcrest.Matchers.matchesPattern("MRNO/\\d{4}/\\d+")))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String patientUid = json.get("uid").asText();
        String mrn        = json.get("no").asText();

        // Patient: active, OUTPATIENT default, CASH
        var patient = patientRepository.findByUid(patientUid).orElseThrow();
        assertThat(patient.isActive()).isTrue();
        assertThat(patient.getType().name()).isEqualTo("OUTPATIENT");
        assertThat(patient.getPaymentType().name()).isEqualTo("CASH");
        assertThat(patient.getNo()).matches("MRNO/\\d{4}/\\d+");
        assertThat(patient.getSearchKey()).isNotBlank()
                .contains(mrn);  // searchKey starts with MRN (5-field composition, build-spec §2.2)
        assertThat(patient.getInsurancePlanUid()).isNull(); // CASH → plan forced null

        // Registration: ACTIVE, non-blank bill uid
        var registration = registrationRepository.findByPatient(patient).orElseThrow();
        assertThat(registration.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(registration.getPatientBillUid()).isNotBlank();

        // Visit: FIRST, PENDING
        var visits = visitRepository.findByPatientOrderByCreatedAtDesc(patient);
        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).getSequence().name()).isEqualTo("FIRST");
        assertThat(visits.get(0).getStatus().name()).isEqualTo("PENDING");

        // PatientBill: a bill exists for this patient uid with kind=REGISTRATION
        var bills = patientBillRepository.findByPatientUid(patientUid);
        assertThat(bills).hasSize(1);
        assertThat(bills.get(0).getKind().name()).isEqualTo("REGISTRATION");
        assertThat(bills.get(0).getStatus().name()).isEqualTo("UNPAID"); // cash reg fee → UNPAID
        // The bill uid matches what Registration stores
        assertThat(bills.get(0).getUid()).isEqualTo(registration.getPatientBillUid());

        // Audit log: CREATE rows for Patient + Registration + Visit (ADR-0007 §182)
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(patientUid))
                .anyMatch(a -> "registration.Patient".equals(a.getEntityType())
                        && "CREATE".equals(a.getAction().name()));
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(registration.getUid()))
                .anyMatch(a -> "registration.Registration".equals(a.getEntityType()));
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(visits.get(0).getUid()))
                .anyMatch(a -> "registration.Visit".equals(a.getEntityType()));
    }

    // =========================================================================
    // INSURANCE registration — happy path
    // =========================================================================

    @Test
    void registerInsurancePatient_201_registrationPersisted() throws Exception {
        String planUid = createPlanAndGetUid(
                "REG-IT-INS-PROV-" + System.nanoTime(),
                "REG-IT-INS-PLAN-" + System.nanoTime());
        // Seed a covered REGISTRATION price for this plan
        seedPrice(planUid, "REGISTRATION", null, "300.00", true);

        String membershipNo = "MEM-C3-001";
        String body = insuranceRequest("Jane", "Smith", "C1-INS", planUid, membershipNo);

        MvcResult result = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String patientUid = json.get("uid").asText();

        var patient = patientRepository.findByUid(patientUid).orElseThrow();
        assertThat(patient.getPaymentType().name()).isEqualTo("INSURANCE");
        assertThat(patient.getMembershipNo()).isEqualTo(membershipNo);
        assertThat(patient.getInsurancePlanUid()).isEqualTo(planUid);

        // Registration ACTIVE + bill uid non-blank
        var reg = registrationRepository.findByPatient(patient).orElseThrow();
        assertThat(reg.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(reg.getPatientBillUid()).isNotBlank();

        // First PENDING Visit
        var visits = visitRepository.findByPatientOrderByCreatedAtDesc(patient);
        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).getSequence().name()).isEqualTo("FIRST");

        // Insurance reg fee resolves to the covered plan price → COVERED bill
        var insBills = patientBillRepository.findByPatientUid(patientUid);
        assertThat(insBills).hasSize(1);
        assertThat(insBills.get(0).getStatus().name()).isEqualTo("COVERED");
    }

    // =========================================================================
    // INSURANCE without plan → 422 MISSING_INSURANCE_INFORMATION
    // =========================================================================

    @Test
    void registerInsurancePatient_noPlan_422() throws Exception {
        String body = """
                {
                  "firstName": "Alice",
                  "lastName": "Test422",
                  "dateOfBirth": "1990-01-01",
                  "gender": "FEMALE",
                  "paymentType": "INSURANCE",
                  "membershipNo": "MEM-NOPLAN"
                }
                """;

        mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("urn:hmis:error:missing-insurance-information"));
    }

    @Test
    void registerInsurancePatient_noMembership_422() throws Exception {
        String planUid = createPlanAndGetUid(
                "REG-IT-422-PROV-" + System.nanoTime(),
                "REG-IT-422-PLAN-" + System.nanoTime());

        String body = """
                {
                  "firstName": "Bob",
                  "lastName": "Test422M",
                  "dateOfBirth": "1985-05-20",
                  "gender": "MALE",
                  "paymentType": "INSURANCE",
                  "insurancePlanUid": "%s"
                }
                """.formatted(planUid);

        mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("urn:hmis:error:missing-insurance-information"));
    }

    // =========================================================================
    // RBAC: 403 without PATIENT-CREATE / PATIENT-ALL; 401 without token
    // =========================================================================

    @Test
    void registerPatient_missingPrivilege_403() throws Exception {
        String noPrivToken = jwtFactory.tokenWithPrivileges("viewer", List.of("READ-ONLY"));
        String body = cashRequest("Charlie", "Brown", "C1-403");

        mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + noPrivToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerPatient_noToken_401() throws Exception {
        String body = cashRequest("Dave", "NoToken", "C1-401");

        mockMvc.perform(post(PATIENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // JSON contract: no "id" field in response
    // =========================================================================

    @Test
    void registerCashPatient_responseHasNoIdField() throws Exception {
        String body = cashRequest("Eve", "NoId", "C1-NOID");

        MvcResult result = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.has("id")).isFalse();
        assertThat(json.get("uid").asText()).isNotBlank();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Ensures a business day is open. Robust to the shared singleton Testcontainer:
     * a prior committed test may have left an open day or rolled back.
     */
    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
    }

    /**
     * Seeds a REGISTRATION cash price if one does not yet exist (idempotent guard via
     * duplicate-price 409 suppression). A REGISTRATION cash price is required for every
     * registration call (build-spec §0 item 2, CR-12, BillingChargeIT pattern).
     *
     * <p>The seed is "500.00 TZS, covered=true" — a non-zero cash fee that produces
     * {@code BillStatus.UNPAID}. Tests that need zero or plan prices seed their own rows.
     */
    private void ensureRegistrationCashPrice() throws Exception {
        try {
            seedPrice(null, "REGISTRATION", null, "500.00", true);
        } catch (AssertionError ignored) {
            // 409 DUPLICATE_SERVICE_PRICE means the row is already seeded from a prior test
        }
    }

    private void seedPrice(String planUid, String kind, String serviceUid,
                            String amount, boolean covered) throws Exception {
        String planVal = planUid    != null ? "\"" + planUid    + "\"" : "null";
        String svcVal  = serviceUid != null ? "\"" + serviceUid + "\"" : "null";
        String reqBody = """
                {"planUid":%s,"kind":"%s","serviceUid":%s,"currency":"TZS",
                 "amount":%s,"covered":%b,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(planVal, kind, svcVal, amount, covered);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                // 2xx = created; 409 = already exists (both are acceptable in setup)
                .andExpect(status().is(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is(201),
                                org.hamcrest.Matchers.is(409))));
    }

    private String createPlanAndGetUid(String provCode, String planCode) throws Exception {
        String provBody = """
                {"code":"%s","name":"Provider %s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":false}
                """.formatted(provCode, provCode);
        MvcResult provResult = mockMvc.perform(post(PROVIDERS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provBody))
                .andExpect(status().isCreated())
                .andReturn();
        String provUid = objectMapper
                .readTree(provResult.getResponse().getContentAsString())
                .get("uid").asText();

        String planBody = """
                {"code":"%s","name":"Plan %s","description":null,
                 "active":false,"insuranceProviderUid":"%s"}
                """.formatted(planCode, planCode, provUid);
        MvcResult planResult = mockMvc.perform(
                        post(PROVIDERS_URL + "/uid/" + provUid + "/plans")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planBody))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(planResult.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** Build a minimal valid CASH registration request body. */
    private String cashRequest(String firstName, String lastName, String suffix) {
        return """
                {
                  "firstName": "%s-%s",
                  "lastName": "%s-%s",
                  "dateOfBirth": "1990-06-15",
                  "gender": "MALE",
                  "paymentType": "CASH",
                  "phoneNo": "0712000%s"
                }
                """.formatted(firstName, suffix, lastName, suffix, suffix.replace("-", ""));
    }

    /** Build a minimal valid INSURANCE registration request body. */
    private String insuranceRequest(String firstName, String lastName, String suffix,
                                    String planUid, String membershipNo) {
        return """
                {
                  "firstName": "%s-%s",
                  "lastName": "%s-%s",
                  "dateOfBirth": "1988-03-20",
                  "gender": "FEMALE",
                  "paymentType": "INSURANCE",
                  "insurancePlanUid": "%s",
                  "membershipNo": "%s",
                  "phoneNo": "0713000%s"
                }
                """.formatted(firstName, suffix, lastName, suffix,
                planUid, membershipNo, suffix.replace("-", ""));
    }
}
