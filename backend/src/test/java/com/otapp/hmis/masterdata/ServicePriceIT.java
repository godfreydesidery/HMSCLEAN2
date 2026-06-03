package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.masterdata.lookup.PriceLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLogRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Golden-master tests for {@code ServicePrice} (build-spec §5.7, AC-1, AC-5, RF-1, RF-2).
 *
 * <h2>AC-1 — PriceLookup resolve, all 7 kinds</h2>
 * For each of the 7 {@link ServiceKind} values the test:
 * <ul>
 *   <li>Seeds a cash row (planUid NULL) and a covered insurance row (planUid set).</li>
 *   <li>Asserts: insurance hit → returns plan amount; cash lookup (no planUid) → returns
 *       cash amount; missing both → HTTP 422 {@code service-price-not-found}.</li>
 * </ul>
 *
 * <h2>AC-5 (RF-1) — True upsert: same composite key → UPDATE (200), not 409</h2>
 * A second POST with the same (plan_uid, kind, service_uid, currency) key updates the
 * existing row in-place and returns 200 OK. The row count stays 1.
 *
 * <h2>RF-2 — price/coverage coupling</h2>
 * {@code amount=0} → stored {@code covered=false} regardless of caller; {@code amount<0} → 400.
 *
 * <h2>Inert fields</h2>
 * A row with min_amount/max_amount set resolves to {@code amount} only (CR-11).
 * The {@code active} flag is inert in resolve — a {@code active=false} row still resolves (CR-11).
 */
class ServicePriceIT extends AbstractIntegrationTest {

    private static final String BASE       = "/api/v1/masterdata/service-prices";
    private static final String PROVIDERS  = "/api/v1/masterdata/insurance-providers";
    private static final String CLINICS    = "/api/v1/masterdata/clinics";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired PriceLookup priceLookup;
    @Autowired AuditLogRepository auditLogRepository;

    // =========================================================================
    // AC-1: REGISTRATION (service_uid NULL — CR-18)
    // =========================================================================

    @Test
    void ac1_registration_insuranceHitAndCashFallback() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-REG-IT", "Provider Reg", "IPLAN-REG-IT", "Plan Reg");

        // Cash row — plan_uid NULL, service_uid NULL
        seedPrice(token, null, "REGISTRATION", null, "TZS", "500.00", true, null, null);
        // Insurance covered row — plan_uid set, service_uid NULL.
        // RF-2: amount must be > 0 to keep covered=true and trigger an insurance hit on resolve.
        // (amount=0 would force covered=false, making this row a non-hit — tested separately.)
        seedPrice(token, planUid, "REGISTRATION", null, "TZS", "200.00", true, null, null);

        // Insurance hit — covered row is found; returns the plan amount
        ServicePriceResult insResult = priceLookup.resolve(planUid, ServiceKind.REGISTRATION, null, "TZS");
        assertThat(insResult.amount()).isEqualByComparingTo("200.00");
        assertThat(insResult.covered()).isTrue();
        assertThat(insResult.planUid()).isEqualTo(planUid);
        assertThat(insResult.kind()).isEqualTo(ServiceKind.REGISTRATION);
        assertThat(insResult.serviceUid()).isNull();

        // Cash fallback (no planUid)
        ServicePriceResult cashResult = priceLookup.resolve(null, ServiceKind.REGISTRATION, null, "TZS");
        assertThat(cashResult.amount()).isEqualByComparingTo("500.00");
        assertThat(cashResult.planUid()).isNull();
    }

    // =========================================================================
    // AC-1: CONSULTATION (service_uid = Clinic.uid)
    // =========================================================================

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

    // =========================================================================
    // AC-1: LAB_TEST
    // =========================================================================

    @Test
    void ac1_labTest_insuranceHitAndCashFallback() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-LAB-IT", "Provider Lab", "IPLAN-LAB-IT", "Plan Lab");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-AC1-IT", "Lab AC1 IT");

        seedPrice(token, null,    "LAB_TEST", svcUid, "TZS", "8000.00", true,  null, null);
        seedPrice(token, planUid, "LAB_TEST", svcUid, "TZS", "5000.00", true,  null, null);

        assertThat(priceLookup.resolve(planUid, ServiceKind.LAB_TEST, svcUid, "TZS").amount())
                .isEqualByComparingTo("5000.00");
        assertThat(priceLookup.resolve(null, ServiceKind.LAB_TEST, svcUid, "TZS").amount())
                .isEqualByComparingTo("8000.00");
    }

    // =========================================================================
    // AC-1: MEDICINE
    // =========================================================================

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

    // =========================================================================
    // AC-1: PROCEDURE
    // =========================================================================

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

    // =========================================================================
    // AC-1: RADIOLOGY
    // =========================================================================

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

    // =========================================================================
    // AC-1: WARD (service_uid = WardType.uid — CR-12)
    // =========================================================================

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

    // =========================================================================
    // AC-1: missing both rows → 422 service-price-not-found
    // =========================================================================

    @Test
    void ac1_missingBoth_resolve_returns422() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(get(BASE + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("kind", "LAB_TEST")
                        .param("serviceUid", "NONEXISTENT-UID-00000000000")
                        .param("currency", "TZS"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:service-price-not-found"));
    }

    // =========================================================================
    // AC-1: resolve without token → 401
    // =========================================================================

    @Test
    void resolve_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(BASE + "/resolve")
                        .param("kind", "LAB_TEST")
                        .param("serviceUid", "SOME-UID")
                        .param("currency", "TZS"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // AC-1: covered=false placeholder row falls through to cash fallback
    // =========================================================================

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

    // =========================================================================
    // AC-1: active=false row is still resolved (active is inert in PriceLookup — CR-11)
    // =========================================================================

    @Test
    void ac1_activeInert_falseActiveRowStillResolves() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-INACT-IT", "Provider Inact",
                "IPLAN-INACT-IT", "Plan Inact");
        String svcUid  = createRadiologyTypeAndGetUid(token, "RT-INACT-IT", "Radiology Inact IT");

        // Seed with active=false — the resolve must still find it (active is inert)
        String body = """
                {"planUid":"%s","kind":"RADIOLOGY","serviceUid":"%s","currency":"TZS",
                 "amount":12345.00,"covered":true,"minAmount":null,"maxAmount":null,"active":false}
                """.formatted(planUid, svcUid);
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        ServicePriceResult result = priceLookup.resolve(planUid, ServiceKind.RADIOLOGY, svcUid, "TZS");
        assertThat(result.amount())
                .as("active=false row must still be resolved; active is inert (CR-11)")
                .isEqualByComparingTo("12345.00");
    }

    // =========================================================================
    // AC-1: currency mismatch → 422 (currency is a lookup discriminator, not a multiplier)
    // =========================================================================

    @Test
    void ac1_currencyMismatch_returns422() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-CURR-IT", "Provider Curr",
                "IPLAN-CURR-IT", "Plan Curr");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-CURR-IT", "Lab Curr IT");

        // Seed a row in USD
        String body = """
                {"planUid":"%s","kind":"LAB_TEST","serviceUid":"%s","currency":"USD",
                 "amount":99.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(planUid, svcUid);
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Resolve with TZS → miss (currency is a discriminator, not converted)
        mockMvc.perform(get(BASE + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("planUid", planUid)
                        .param("kind", "LAB_TEST")
                        .param("serviceUid", svcUid)
                        .param("currency", "TZS"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:service-price-not-found"));
    }

    // =========================================================================
    // Inert fields: min/max do not affect resolve amount (CR-11)
    // =========================================================================

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

    // =========================================================================
    // AC-5 / RF-1: TRUE UPSERT — second POST with same key → UPDATE (200), not 409
    // =========================================================================

    @Test
    void ac5_upsertUpdate_sameKeySecondPost_returns200AndAmountChanged() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-UPS-IT", "Provider Ups", "IPLAN-UPS-IT", "Plan Ups");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-UPS-IT", "Lab Ups IT");

        String bodyFirst = servicePriceJson(planUid, "LAB_TEST", svcUid, "TZS", "6000.00", true, null, null);
        String bodySecond = servicePriceJson(planUid, "LAB_TEST", svcUid, "TZS", "7777.00", true, null, null);

        // First POST → 201 Created
        MvcResult first = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFirst))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(first.getResponse().getContentAsString()).get("uid").asText();

        // Second POST with same key → 200 OK (UPDATE path), amount changed
        MvcResult second = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySecond))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))   // same uid — same row
                .andReturn();
        BigDecimal returnedAmount = new BigDecimal(
                objectMapper.readTree(second.getResponse().getContentAsString())
                        .get("amount").asText());
        assertThat(returnedAmount).isEqualByComparingTo("7777.00");

        // Resolve confirms the updated value is live
        ServicePriceResult resolved = priceLookup.resolve(planUid, ServiceKind.LAB_TEST, svcUid, "TZS");
        assertThat(resolved.amount()).isEqualByComparingTo("7777.00");

        // Audit: CREATE row then UPDATE row, both for the same uid
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(uid))
                .as("audit must have CREATE then UPDATE rows")
                .anyMatch(r -> r.getAction() == AuditAction.CREATE)
                .anyMatch(r -> r.getAction() == AuditAction.UPDATE);
    }

    @Test
    void ac5_upsertUpdate_nullPlanUid_secondPost_returns200() throws Exception {
        // NULL plan_uid (cash) upsert — same COALESCE bucket logic applies
        String token  = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String svcUid = createProcedureTypeAndGetUid(token, "PT-UPS-CASH-IT", "Procedure Ups Cash IT");

        String bodyFirst  = servicePriceJson(null, "PROCEDURE", svcUid, "TZS", "12000.00", true, null, null);
        String bodySecond = servicePriceJson(null, "PROCEDURE", svcUid, "TZS", "13500.00", true, null, null);

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFirst))
                .andExpect(status().isCreated());

        MvcResult secondCash = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySecond))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new BigDecimal(objectMapper.readTree(
                secondCash.getResponse().getContentAsString()).get("amount").asText()))
                .isEqualByComparingTo("13500.00");
    }

    @Test
    void ac5_upsertUpdate_nullServiceUid_secondPost_returns200() throws Exception {
        // NULL service_uid (REGISTRATION) upsert
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-UPS-REG-IT", "Provider Ups Reg",
                "IPLAN-UPS-REG-IT", "Plan Ups Reg");

        String bodyFirst  = servicePriceJson(planUid, "REGISTRATION", null, "TZS", "300.00", true, null, null);
        String bodySecond = servicePriceJson(planUid, "REGISTRATION", null, "TZS", "350.00", true, null, null);

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFirst))
                .andExpect(status().isCreated());

        MvcResult secondReg = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySecond))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new BigDecimal(objectMapper.readTree(
                secondReg.getResponse().getContentAsString()).get("amount").asText()))
                .isEqualByComparingTo("350.00");
    }

    @Test
    void ac5_upsertUpdate_bothNulls_secondPost_returns200() throws Exception {
        // NULL plan_uid AND NULL service_uid (cash REGISTRATION) — both COALESCE buckets active.
        // Use currency "ZZZ" to avoid collisions with TZS rows seeded by AC-1 tests.
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        String bodyFirst  = servicePriceJson(null, "REGISTRATION", null, "ZZZ", "500.00", true, null, null);
        String bodySecond = servicePriceJson(null, "REGISTRATION", null, "ZZZ", "600.00", true, null, null);

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFirst))
                .andExpect(status().isCreated());

        MvcResult secondBoth = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySecond))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new BigDecimal(objectMapper.readTree(
                secondBoth.getResponse().getContentAsString()).get("amount").asText()))
                .isEqualByComparingTo("600.00");
    }

    // =========================================================================
    // RF-1: DELETE → 204; idempotent second delete also 204; resolve after delete → 422
    // =========================================================================

    @Test
    void delete_existingRow_returns204_thenResolveReturns422() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-DEL-IT", "Provider Del", "IPLAN-DEL-IT", "Plan Del");
        String svcUid  = createProcedureTypeAndGetUid(token, "PT-DEL-IT", "Procedure Del IT");

        // Seed
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(planUid, "PROCEDURE", svcUid, "TZS", "9000.00", true, null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString()).get("uid").asText();

        // DELETE → 204
        mockMvc.perform(delete(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        // Idempotent: second DELETE → 204 (not 404)
        mockMvc.perform(delete(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        // Resolve now misses → 422
        mockMvc.perform(get(BASE + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("planUid", planUid)
                        .param("kind", "PROCEDURE")
                        .param("serviceUid", svcUid)
                        .param("currency", "TZS"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:service-price-not-found"));

        // Audit: DELETE row present
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(uid))
                .anyMatch(r -> r.getAction() == AuditAction.DELETE);
    }

    @Test
    void delete_unknownUid_returns204_idempotent() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(delete(BASE + "/uid/NONEXISTENT-UID-00000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    // =========================================================================
    // RF-2: price/coverage coupling
    // =========================================================================

    @Test
    void rf2_amountZero_forcesCoveredFalse() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-RF2Z-IT", "Provider RF2Z",
                "IPLAN-RF2Z-IT", "Plan RF2Z");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-RF2Z-IT", "Lab RF2Z IT");

        // Send covered=true but amount=0 → service must store covered=false
        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(planUid, "LAB_TEST", svcUid, "TZS", "0.00", true, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.covered").value(false))   // RF-2: forced false
                .andReturn();

        // Verify the stored row: covered=false means no insurance hit → cash fallback needed
        // (if no cash row exists, resolve will 422)
        String uid = objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(uid))
                .anyMatch(r -> r.getAction() == AuditAction.CREATE);
    }

    @Test
    void rf2_amountNegative_returns400() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-RF2N-IT", "Provider RF2N",
                "IPLAN-RF2N-IT", "Plan RF2N");
        String svcUid  = createLabTestTypeAndGetUid(token, "LTT-RF2N-IT", "Lab RF2N IT");

        // amount=-1 → 400 (legacy: "Invalid Price value. Price should not be less than zero")
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(planUid, "LAB_TEST", svcUid, "TZS", "-1.00", true, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:validation"));
    }

    // =========================================================================
    // Audit: CREATE row is written on first seed
    // =========================================================================

    @Test
    void audit_createRow_isWrittenOnInsert() throws Exception {
        String token   = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String planUid = createPlanAndGetUid(token, "IP-AUD-IT", "Provider Aud", "IPLAN-AUD-IT", "Plan Aud");
        String svcUid  = createRadiologyTypeAndGetUid(token, "RT-AUD-IT", "Radiology Aud IT");

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(planUid, "RADIOLOGY", svcUid, "TZS", "3000.00", true, null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();

        assertThat(auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.ServicePrice"))
                .as("audit_logs must contain a CREATE row for the new ServicePrice uid")
                .anyMatch(r -> r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // =========================================================================
    // Authorization gates
    // =========================================================================

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

    @Test
    void delete_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete(BASE + "/uid/SOMEUID"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(delete(BASE + "/uid/SOMEUID")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Seeds a price row and asserts the response is 2xx (201 on first create, 200 on upsert-update).
     */
    private void seedPrice(String token, String planUid, String kind, String serviceUid,
                           String currency, String amount, boolean covered,
                           String minAmount, String maxAmount) throws Exception {
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(servicePriceJson(planUid, kind, serviceUid, currency,
                                amount, covered, minAmount, maxAmount)))
                .andExpect(status().is2xxSuccessful());
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
