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
 * Integration tests for {@code /api/v1/masterdata/clinics} (build-spec §3, §5.5).
 *
 * <p>Covers: create 201 + Location, 403 without ADMIN-ACCESS, 401 without token,
 * get-by-uid 200, list 200, update 200, no {@code id} in response, audit log written on create.
 */
class ClinicIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/clinics";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("AUTH-401", "No Token Clinic")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("AUTH-403", "Forbidden Clinic")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Create 201 + Location + no id + audit row
    // ------------------------------------------------------------------

    @Test
    void create_withAdminAccess_returns201WithLocationAndNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String body = clinicJson("CLINIC-CREATE-IT", "IT Create Clinic");

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("CLINIC-CREATE-IT"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        // Audit log must have a CREATE row for this uid
        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.Clinic");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // ------------------------------------------------------------------
    // Get by uid
    // ------------------------------------------------------------------

    @Test
    void getByUid_existingClinic_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        // Create first
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("CLINIC-GET-IT", "IT Get Clinic")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        // Read it back
        mockMvc.perform(get(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.code").value("CLINIC-GET-IT"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_unknownUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(get(BASE + "/uid/NOTEXIST00000000000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @Test
    void list_withToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
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
        // Create
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("CLINIC-UPD-IT", "IT Update Clinic Before")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        // Update
        String updateBody = """
                {"code":"CLINIC-UPD-IT","name":"IT Update Clinic After",
                 "description":"updated","consultationFee":5000.00,"active":true}
                """;
        mockMvc.perform(put(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("IT Update Clinic After"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private String clinicJson(String code, String name) {
        return """
                {"code":"%s","name":"%s","description":"desc","consultationFee":1000.00,"active":false}
                """.formatted(code, name);
    }
}
