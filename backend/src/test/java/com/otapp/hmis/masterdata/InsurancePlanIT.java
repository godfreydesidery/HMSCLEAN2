package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLog;
import com.otapp.hmis.shared.audit.AuditLogRepository;
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
 * Integration tests for {@code InsurancePlan} CRUD (build-spec §1.4, §3).
 *
 * <p>Covers: nested creation under provider (POST .../plans → 201+Location),
 * 403 without ADMIN-ACCESS, 401 without token, list-by-provider, flat get/list,
 * update 200, no {@code id} in JSON, audit row on create.
 */
class InsurancePlanIT extends AbstractIntegrationTest {

    private static final String PROVIDERS = "/api/v1/masterdata/insurance-providers";
    private static final String PLANS     = "/api/v1/masterdata/insurance-plans";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(PROVIDERS + "/uid/FAKE-UID/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("IPLAN-AUTH", "No Token Plan", "FAKE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        // Setup (provider) needs ADMIN-ACCESS; only the plan-create call is the unauthorized one.
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        String providerUid = createProvider(adminToken, "IP-PLAN-403-PROV", "Provider For 403 Plan");
        mockMvc.perform(post(PROVIDERS + "/uid/" + providerUid + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("IPLAN-403", "Forbidden Plan", providerUid)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Create nested under provider — 201 + Location + no id + audit row
    // ------------------------------------------------------------------

    @Test
    void createUnderProvider_withAdminAccess_returns201WithLocationAndNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String providerUid = createProvider(token, "IP-PLAN-CREATE-PROV", "Provider For Plan Create");

        MvcResult result = mockMvc.perform(
                        post(PROVIDERS + "/uid/" + providerUid + "/plans")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planJson("IPLAN-CREATE-IT", "IT Create Plan", providerUid)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("IPLAN-CREATE-IT"))
                .andExpect(jsonPath("$.insuranceProviderUid").value(providerUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        List<AuditLog> rows = auditLogRepository
                .findByEntityTypeOrderByOccurredAtAsc("masterdata.InsurancePlan");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // ------------------------------------------------------------------
    // List by provider
    // ------------------------------------------------------------------

    @Test
    void listByProvider_returns200WithPlansForThatProvider() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String providerUid = createProvider(token, "IP-PLAN-LIST-PROV", "Provider For Plan List");

        // Create two plans under this provider
        mockMvc.perform(post(PROVIDERS + "/uid/" + providerUid + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("IPLAN-LIST-A", "Plan List A", providerUid)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(PROVIDERS + "/uid/" + providerUid + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("IPLAN-LIST-B", "Plan List B", providerUid)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PROVIDERS + "/uid/" + providerUid + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    // ------------------------------------------------------------------
    // Get / List (flat)
    // ------------------------------------------------------------------

    @Test
    void getByUid_existingPlan_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String providerUid = createProvider(token, "IP-PLAN-GET-PROV", "Provider For Plan Get");
        MvcResult created = mockMvc.perform(
                        post(PROVIDERS + "/uid/" + providerUid + "/plans")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planJson("IPLAN-GET-IT", "IT Get Plan", providerUid)))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(PLANS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void list_withToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(PLANS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    @Test
    void update_withAdminAccess_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String providerUid = createProvider(token, "IP-PLAN-UPD-PROV", "Provider For Plan Update");
        MvcResult created = mockMvc.perform(
                        post(PROVIDERS + "/uid/" + providerUid + "/plans")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planJson("IPLAN-UPD-BEFORE", "Plan Upd Before", providerUid)))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        String updateBody = """
                {"code":"IPLAN-UPD-BEFORE","name":"Plan Upd After",
                 "description":"updated desc","active":true,
                 "insuranceProviderUid":"%s"}
                """.formatted(providerUid);
        mockMvc.perform(put(PLANS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Plan Upd After"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String createProvider(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(PROVIDERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
    }

    private String planJson(String code, String name, String providerUid) {
        return """
                {"code":"%s","name":"%s","description":null,
                 "active":false,"insuranceProviderUid":"%s"}
                """.formatted(code, name, providerUid);
    }
}
