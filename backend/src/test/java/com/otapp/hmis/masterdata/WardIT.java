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
 * Integration tests for {@code /api/v1/masterdata/wards} (build-spec §3).
 *
 * <p>Specifically verifies FK-by-uid resolution (wardType + wardCategory), 404 when FK uid
 * unknown, 401/403 auth gates, 201+Location on create, list, update, and no {@code id} in JSON.
 * Also asserts an {@code audit_logs} row is written on create.
 */
class WardIT extends AbstractIntegrationTest {

    private static final String WARDS      = "/api/v1/masterdata/wards";
    private static final String WARD_TYPES = "/api/v1/masterdata/ward-types";
    private static final String WARD_CATS  = "/api/v1/masterdata/ward-categories";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // ------------------------------------------------------------------
    // Helper: create WardType + WardCategory, return their uids
    // ------------------------------------------------------------------

    private String createWardType(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"price":500.00,"active":true}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(WARD_TYPES)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createWardCategory(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"active":true}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(WARD_CATS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(WARDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("W-401", "Auth Ward", "FAKE_CAT", "FAKE_TYPE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("W-403", "Forbidden Ward", "FAKE_CAT", "FAKE_TYPE")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // FK-uid resolution: 404 when FK uid unknown
    // ------------------------------------------------------------------

    @Test
    void create_unknownWardCategoryUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        // valid type, invalid category
        String typeUid = createWardType(token, "WT-FK-CAT-404", "WardType FK Cat 404");
        mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("W-FK-CAT-404", "Ward FK Cat 404",
                                "NONEXISTENT00000000000000", typeUid)))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_unknownWardTypeUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        // valid category, invalid type
        String catUid = createWardCategory(token, "WC-FK-TYPE-404", "WardCat FK Type 404");
        mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("W-FK-TYPE-404", "Ward FK Type 404",
                                catUid, "NONEXISTENT00000000000000")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Create 201 + Location + FK resolution + no id + audit
    // ------------------------------------------------------------------

    @Test
    void create_withValidFkUids_returns201WithResolvedUidsAndNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String typeUid = createWardType(token, "WT-WARD-CREATE", "WardType For Ward Create");
        String catUid  = createWardCategory(token, "WC-WARD-CREATE", "WardCat For Ward Create");

        MvcResult result = mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("WARD-CREATE-IT", "IT Create Ward", catUid, typeUid)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.wardCategoryUid").value(catUid))
                .andExpect(jsonPath("$.wardTypeUid").value(typeUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();

        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.Ward");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // ------------------------------------------------------------------
    // Get by uid
    // ------------------------------------------------------------------

    @Test
    void getByUid_existingWard_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String typeUid = createWardType(token, "WT-WARD-GET", "WardType For Ward Get");
        String catUid  = createWardCategory(token, "WC-WARD-GET", "WardCat For Ward Get");

        MvcResult created = mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("WARD-GET-IT", "IT Get Ward", catUid, typeUid)))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(WARDS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @Test
    void list_withToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    @Test
    void update_withAdminAccess_returns200WithUpdatedFields() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String typeUid = createWardType(token, "WT-WARD-UPD", "WardType For Ward Upd");
        String catUid  = createWardCategory(token, "WC-WARD-UPD", "WardCat For Ward Upd");

        MvcResult created = mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wardJson("WARD-UPD-IT", "IT Update Ward Before", catUid, typeUid)))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        String updateBody = """
                {"code":"WARD-UPD-IT","name":"IT Update Ward After",
                 "noOfBeds":20,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(catUid, typeUid);
        mockMvc.perform(put(WARDS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("IT Update Ward After"))
                .andExpect(jsonPath("$.noOfBeds").value(20))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private String wardJson(String code, String name, String catUid, String typeUid) {
        return """
                {"code":"%s","name":"%s","noOfBeds":5,"active":false,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(code, name, catUid, typeUid);
    }
}
