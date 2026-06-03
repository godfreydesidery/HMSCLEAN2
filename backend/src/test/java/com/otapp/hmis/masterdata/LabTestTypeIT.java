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
 * Integration tests for {@code /api/v1/masterdata/lab-test-types} (build-spec §1.3, §3).
 *
 * <p>Covers: create 201+Location, 403, 401, get-by-uid, list, update, no {@code id} in JSON,
 * audit log on create; range create/list under parent; code-immutable-on-update (AC-9.4).
 */
class LabTestTypeIT extends AbstractIntegrationTest {

    private static final String BASE   = "/api/v1/masterdata/lab-test-types";

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
                        .content(lttJson("LTT-401", "No Token LTT")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lttJson("LTT-403", "Forbidden LTT")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Create 201 + Location + no id + audit
    // ------------------------------------------------------------------

    @Test
    void create_withAdminAccess_returns201WithLocationNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lttJson("LTT-CREATE-IT", "IT Create LabTestType")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("LTT-CREATE-IT"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.LabTestType");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // ------------------------------------------------------------------
    // Get / List
    // ------------------------------------------------------------------

    @Test
    void getByUid_existingLtt_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lttJson("LTT-GET-IT", "IT Get LabTestType")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.code").value("LTT-GET-IT"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void list_withToken_returns200Array() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // Update + AC-9.4: code is IMMUTABLE on update
    // ------------------------------------------------------------------

    @Test
    void update_codeIsImmutable_onlyOtherFieldsChange() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        // Create with code "LTT-IMMUT-ORIG"
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lttJson("LTT-IMMUT-ORIG", "IT LTT Code Immut Before")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        // Attempt to update code to "LTT-IMMUT-NEW" (must be silently ignored per AC-9.4)
        String updateBody = """
                {"code":"LTT-IMMUT-NEW","name":"IT LTT Code Immut After",
                 "description":"updated","price":50.00,"uom":"ML","active":true}
                """;
        MvcResult updated = mockMvc.perform(put(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("IT LTT Code Immut After"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        // AC-9.4: code must still be the original value — NOT "LTT-IMMUT-NEW"
        String returnedCode = objectMapper.readTree(updated.getResponse().getContentAsString())
                .get("code").asText();
        assertThat(returnedCode)
                .as("code must be immutable on update (AC-9.4 / LabTestTypeServiceImpl.java:47-48)")
                .isEqualTo("LTT-IMMUT-ORIG");
    }

    // ------------------------------------------------------------------
    // Range: create under parent + list
    // ------------------------------------------------------------------

    @Test
    void createRange_andListRanges_underParentLabTestType() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        // Create parent
        MvcResult parentResult = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lttJson("LTT-RANGE-IT", "IT LTT With Ranges")))
                .andExpect(status().isCreated())
                .andReturn();
        String parentUid = objectMapper.readTree(parentResult.getResponse().getContentAsString())
                .get("uid").asText();

        String rangesUrl = BASE + "/uid/" + parentUid + "/ranges";

        // Create range
        MvcResult rangeResult = mockMvc.perform(post(rangesUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Normal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.name").value("Normal"))
                .andExpect(jsonPath("$.labTestTypeUid").value(parentUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String rangeUid = objectMapper.readTree(rangeResult.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(rangeUid).isNotBlank();

        // Audit log for range
        List<AuditLog> rangeRows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.LabTestTypeRange");
        assertThat(rangeRows).anyMatch(r ->
                r.getEntityUid().equals(rangeUid) && r.getAction() == AuditAction.CREATE);

        // List ranges under parent
        mockMvc.perform(get(rangesUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("Normal"))
                .andExpect(jsonPath("$[0].labTestTypeUid").value(parentUid));
    }

    @Test
    void createRange_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE + "/uid/FAKEPARENT0000000000000000/ranges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Low\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRange_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE + "/uid/FAKEPARENT0000000000000000/ranges")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Low\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private String lttJson(String code, String name) {
        return """
                {"code":"%s","name":"%s","description":"desc","price":100.00,"uom":"mg","active":false}
                """.formatted(code, name);
    }
}
