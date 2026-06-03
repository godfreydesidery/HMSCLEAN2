package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.masterdata.lookup.PriceLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Golden-master tests for {@code ServicePrice} (build-spec §5.7, AC-1, AC-5).
 *
 * <h2>AC-1 — PriceLookup resolve, all 7 kinds</h2>
 * For each of the 7 {@link ServiceKind} values the test:
 * <ul>
 *   <li>Seeds a cash row (planUid NULL) and a covered insurance row (planUid set).</li>
 *   <li>Asserts: insurance hit → returns plan amount; cash lookup (no planUid) → returns
 *       cash amount; missing both → HTTP 422 {@code service-price-not-found}.</li>
 * </ul>
 *
 * <h2>AC-5 — Uniqueness 409 with NULL plan_uid and NULL service_uid</h2>
 * Duplicate POST with the same composite key → 409 (not 500, not silent), including
 * the edge cases where plan_uid IS NULL (cash) and service_uid IS NULL (REGISTRATION).
 *
 * <h2>Inert fields</h2>
 * A row with min_amount/max_amount set resolves to {@code amount} only (CR-11).
 */
class ServicePriceIT extends AbstractIntegrationTest {

    private static final String BASE       = "/api/v1/masterdata/service-prices";
    private static final String PROVIDERS  = "/api/v1/masterdata/insurance-providers";
    private static final String CLINICS    = "/api/v1/masterdata/clinics";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired PriceLookup priceLookup;

    // AC-1: REGISTRATION (service_uid NULL — CR-18)

    @Test
    void ac1_registration_insuranceHitAndCashFallback() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-REG-IT", "Provider Reg", "IPLAN-REG-IT", "Plan Reg");

        // Cash row — plan_uid NULL, service_uid NULL
        seedPrice(token, null, "REGISTRATION", null, "TZS", "500.00", true, null, null);
        // Insurance covered row — plan_uid set, service_uid NULL
        seedPrice(token, planUid, "REGISTRATION", null, "TZS", "0.00", true, null, null);

        // Insurance hit
        ServicePriceResult insResult = priceLookup.resolve(planUid, ServiceKind.REGISTRATION, null, "TZS");
        assertThat(insResult.amount()).isEqualByComparingTo("0.00");
        assertThat(insResult.covered()).isTrue();
        assertThat(insResult.planUid()).isEqualTo(planUid);
        assertThat(insResult.kind()).isEqualTo(ServiceKind.REGISTRATION);
        assertThat(insResult.serviceUid()).isNull();

        // Cash fallback (no planUid)
        ServicePriceResult cashResult = priceLookup.resolve(null, ServiceKind.REGISTRATION, null, "TZS");
        assertThat(cashResult.amount()).isEqualByComparingTo("500.00");
        assertThat(cashResult.planUid()).isNull();
    }

    // AC-1: CONSULTATION (service_uid = Clinic.uid)

    @Test
    void ac1_consultation_insuranceHitAndCashFallback() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid    = createPlanAndGetUid(token, "IP-CON-IT", "Provider Con", "IPLAN-CON-IT", "Plan Con");
        String clinicUid  = createClinicAndGetUid(token, "CLINIC-CON-IT", "Clinic Con IT");

        seedPrice(token, null,    "CONSULTATION", clinicUid, "TZS", "3000.00", true,  null, null);
        seedPrice(token, planUid, "CONSULTATION", clinicUid, "TZS", "1500.00", true,  null, null);

        ServicePriceResult insResult = priceLookup.resolve(planUid, ServiceKind.CONSULTATION, clinicUid, "TZS");
        assertThat(insResult.amount()).isEqualByComparingTo("1500.00");
        assertThat(insResult.planUid()).isEqualTo(planUid);
        assertThat(insResult.serviceUid()).isEqualTo(clinicUid);

        ServicePriceResult cashResult = priceLookup.resolve(null, ServiceKind.CONSULTATION, clinicUid, "TZS");
        assertThat(cashResult.amount()).isEqualByComparingTo("3000.00");
        assertThat(cashResult.planUid()).isNull();
    }

    // AC-1: LAB_TEST

    @Test
    void ac1_labTest_insuranceHitAndCashFallback() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-LAB-IT", "Provider Lab", "IPLAN-LAB-IT", "Plan Lab");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-AC1-IT", "Lab AC1 IT");

        seedPrice(token, null,    "LAB_TEST", svcUid, "TZS", "8000.00", true,  null, null);
        seedPrice(token, planUid, "LAB_TEST", svcUid, "TZS", "5000.00", true,  null, null);

        ServicePriceResult insResult = priceLookup.resolve(planUid, ServiceKind.LAB_TEST, svcUid, "TZS");
        assertThat(insResult.amount()).isEqualByComparingTo("5000.00");

        ServicePriceResult cashResult = priceLookup.resolve(null, ServiceKind.LAB_TEST, svcUid, "TZS");
        assertThat(cashResult.amount()).isEqualByComparingTo("8000.00");
    }

    // AC-1: MEDICINE

    @Test
    void ac1_medicine_insuranceHitAndCashFallback() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-MED-IT", "Provider Med", "IPLAN-MED-IT", "Plan Med");
        String svcUid  = createMedicineAndGetUid(token, "MED-AC1-IT", "Medicine AC1 IT");

        seedPrice(token, null,    "MEDICINE", svcUid, "TZS", "200.00", true,  null, null);
        seedPrice(token, planUid, "MEDICINE", svcUid, "TZS", "100.00", true,  null, null);

        assertThat(priceLookup.resolve(planUid, ServiceKind.MEDICINE, svcUid, "TZS").amount())
                .isEqualByComparingTo("100.00");
        assertThat(priceLookup.resolve(null, ServiceKind.MEDICINE, svcUid, "TZS").amount())
                .isEqualByComparingTo("200.00");
    }

    // AC-1: PROCEDURE

    @Test
    void ac1_procedure_insuranceHitAndCashFallback() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-PROC-IT", "Provider Proc", "IPLAN-PROC-IT", "Plan Proc");
        String svcUid  = createProcedureTypeAndGetUid(token, "PT-AC1-IT", "Procedure AC1 IT");

        seedPrice(token, null,    "PROCEDURE", svcUid, "TZS", "15000.00", true, null, null);
        seedPrice(token, planUid, "PROCEDURE", svcUid, "TZS", "10000.00", true, null, null);

        assertThat(priceLookup.resolve(planUid, ServiceKind.PROCEDURE, svcUid, "TZS").amount())
                .isEqualByComparingTo("10000.00");
        assertThat(priceLookup.resolve(null, ServiceKind.PROCEDURE, svcUid, "TZS").amount())
                .isEqualByComparingTo("15000.00");
    }

    // AC-1: RADIOLOGY

    @Test
    void ac1_radiology_insuranceHitAndCashFallback() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-RAD-IT", "Provider Rad", "IPLAN-RAD-IT", "Plan Rad");
        String svcUid  = createRadiologyTypeAndGetUid(token, "RT-AC1-IT", "Radiology AC1 IT");

        seedPrice(token, null,    "RADIOLOGY", svcUid, "TZS", "25000.00", true, null, null);
        seedPrice(token, planUid, "RADIOLOGY", svcUid, "TZS", "18000.00", true, null, null);

        assertThat(priceLookup.resolve(planUid, ServiceKind.RADIOLOGY, svcUid, "TZS").amount())
                .isEqualByComparingTo("18000.00");
        assertThat(priceLookup.resolve(null, ServiceKind.RADIOLOGY, svcUid, "TZS").amount())
                .isEqualByComparingTo("25000.00");
    }

    // AC-1: WARD (service_uid = WardType.uid — CR-12)

    @Test
    void ac1_ward_insuranceHitAndCashFallback() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-WARD-IT", "Provider Ward", "IPLAN-WARD-IT", "Plan Ward");
        String svcUid  = createWardTypeAndGetUid(token, "WT-AC1-IT", "WardType AC1 IT");

        seedPrice(token, null,    "WARD", svcUid, "TZS", "50000.00", true, null, null);
        seedPrice(token, planUid, "WARD", svcUid, "TZS", "40000.00", true, null, null);

        assertThat(priceLookup.resolve(planUid, ServiceKind.WARD, svcUid, "TZS").amount())
                .isEqualByComparingTo("40000.00");
        assertThat(priceLookup.resolve(null, ServiceKind.WARD, svcUid, "TZS").amount())
                .isEqualByComparingTo("50000.00");
    }

    // AC-1: missing both rows → 422 service-price-not-found

    @Test
    void ac1_missingBoth_resolve_returns422() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("kind", "LAB_TEST")
                        .param("serviceUid", "NONEXISTENT-UID-00000000000")
                        .param("currency", "TZS"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:service-price-not-found"));
    }

    // AC-1: covered=false placeholder row falls through to cash fallback

    @Test
    void ac1_coveredFalseRow_doesNotTriggerInsuranceHit_fallsThroughToCash() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-COVF-IT", "Provider CovF", "IPLAN-COVF-IT", "Plan CovF");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-COVF-IT", "Lab CovF IT");

        // covered=FALSE insurance placeholder — must NOT be returned as an insurance hit
        seedPrice(token, planUid, "LAB_TEST", svcUid, "TZS", "9999.00", false, null, null);
        // Cash row
        seedPrice(token, null, "LAB_TEST", svcUid, "TZS", "4000.00", true, null, null);

        // Even though planUid is supplied, covered=false means no insurance hit → cash fallback
        ServicePriceResult result = priceLookup.resolve(planUid, ServiceKind.LAB_TEST, svcUid, "TZS");
        assertThat(result.amount()).isEqualByComparingTo("4000.00");
        assertThat(result.planUid()).isNull();  // came from cash row
    }

    // Inert fields: min/max do not affect resolve amount (CR-11)

    @Test
    void inertFields_minMaxDoNotAffectResolveAmount() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-INERT-IT", "Provider Inert", "IPLAN-INERT-IT", "Plan Inert");
        String svcUid  = createRadiologyTypeAndGetUid(token, "RT-INERT-IT", "Radiology Inert IT");

        // Row has min_amount and max_amount set — they must not affect the resolved amount
        seedPrice(token, planUid, "RADIOLOGY", svcUid, "TZS", "7500.00", true, "1000.00", "20000.00");

        ServicePriceResult result = priceLookup.resolve(planUid, ServiceKind.RADIOLOGY, svcUid, "TZS");
        assertThat(result.amount())
                .as("amount must equal the stored 7500.00, not min or max (CR-11 inert)")
                .isEqualByComparingTo("7500.00");
        // min/max are passed through for the consumer's information only
        assertThat(result.minAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.maxAmount()).isEqualByComparingTo("20000.00");
    }

    // AC-5: uniqueness 409 — duplicate composite key

    @Test
    void ac5_duplicatePost_sameKey_returns409() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-DUP-IT", "Provider Dup", "IPLAN-DUP-IT", "Plan Dup");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-DUP-IT", "Lab Dup IT");

        String body = servicePriceJson(planUid, "LAB_TEST", svcUid, "TZS", "6000.00", true, null, null);

        // First create succeeds
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Second create with same composite key → 409
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:duplicate-service-price"));
    }

    @Test
    void ac5_duplicateCashRow_nullPlanUid_returns409() throws Exception {
        // NULL plan_uid (cash) must also be caught by the COALESCE pre-check
        String token  = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String svcUid = createProcedureTypeAndGetUid(token, "PT-DUP-CASH-IT", "Procedure Dup Cash IT");

        String body = servicePriceJson(null, "PROCEDURE", svcUid, "TZS", "12000.00", true, null, null);

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:duplicate-service-price"));
    }

    @Test
    void ac5_duplicateRegistrationRow_nullServiceUid_returns409() throws Exception {
        // NULL service_uid (REGISTRATION) must also be caught — CR-18
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-DUP-REG-IT", "Provider Dup Reg",
                "IPLAN-DUP-REG-IT", "Plan Dup Reg");

        String body = servicePriceJson(planUid, "REGISTRATION", null, "TZS", "0.00", true, null, null);

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:duplicate-service-price"));
    }

    @Test
    void ac5_duplicateCashRegistrationRow_bothNulls_returns409() throws Exception {
        // NULL plan_uid AND NULL service_uid (cash REGISTRATION) — both COALESCE buckets active.
        // Use a test-only currency ("ZZZ") so this both-NULL row does not collide with the TZS
        // REGISTRATION cash row created by the AC-1 tests in the shared singleton-container DB.
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String body  = servicePriceJson(null, "REGISTRATION", null, "ZZZ", "500.00", true, null, null);

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:duplicate-service-price"));
    }

    // Authorization gates

    @Test
    void post_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(null, "REGISTRATION", null, "TZS", "0.00", true, null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(null, "REGISTRATION", null, "TZS", "0.00", true, null, null)))
                .andExpect(status().isForbidden());
    }

    // Helpers

    private void seedPrice(String token, String planUid, String kind, String serviceUid,
                           String currency, String amount, boolean covered,
                           String minAmount, String maxAmount) throws Exception {
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(planUid, kind, serviceUid, currency,
                                amount, covered, minAmount, maxAmount)))
                .andExpect(status().isCreated());
    }

    private String servicePriceJson(String planUid, String kind, String serviceUid,
                                    String currency, String amount, boolean covered,
                                    String minAmount, String maxAmount) {
        String planVal     = planUid    != null ? "\"" + planUid    + "\"" : "null";
        String svcVal      = serviceUid != null ? "\"" + serviceUid + "\"" : "null";
        String minVal      = minAmount  != null ? minAmount  : "null";
        String maxVal      = maxAmount  != null ? maxAmount  : "null";
        return """
                {"planUid":%s,"kind":"%s","serviceUid":%s,"currency":"%s",
                 "amount":%s,"covered":%b,"minAmount":%s,"maxAmount":%s,"active":true}
                """.formatted(planVal, kind, svcVal, currency, amount, covered, minVal, maxVal);
    }

    // Catalog entity creators (return uid from 201 response)

    private String createPlanAndGetUid(String token,
                                        String provCode, String provName,
                                        String planCode, String planName) throws Exception {
        String provBody = """
                {"code":"%s","name":"%s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":false}
                """.formatted(provCode, provName);
        MvcResult provResult = mockMvc.perform(post(PROVIDERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provBody))
                .andExpect(status().isCreated())
                .andReturn();
        String providerUid = objectMapper.readTree(provResult.getResponse().getContentAsString())
                .get("uid").asText();

        String planBody = """
                {"code":"%s","name":"%s","description":null,
                 "active":false,"insuranceProviderUid":"%s"}
                """.formatted(planCode, planName, providerUid);
        MvcResult planResult = mockMvc.perform(
                        post(PROVIDERS + "/uid/" + providerUid + "/plans")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planBody))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(planResult.getResponse().getContentAsString())
                .get("uid").asText();
    }

    private String createClinicAndGetUid(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"consultationFee":3000.00,"active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(CLINICS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createLabTestTypeAndGetUid(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"price":8000.00,"uom":null,"active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/masterdata/lab-test-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createMedicineAndGetUid(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"type":"ORAL",
                 "price":200.00,"uom":"TABLET","category":"MEDICINE","active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/masterdata/medicines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createProcedureTypeAndGetUid(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"price":15000.00,"uom":null,"active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/masterdata/procedure-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createRadiologyTypeAndGetUid(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"price":25000.00,"uom":null,"active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/masterdata/radiology-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createWardTypeAndGetUid(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"price":50000.00,"active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/masterdata/ward-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }
}
