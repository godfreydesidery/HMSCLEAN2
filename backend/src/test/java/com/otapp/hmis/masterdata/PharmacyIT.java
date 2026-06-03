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
 * Integration tests for {@code /api/v1/masterdata/pharmacies} (build-spec §3).
 */
class PharmacyIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/pharmacies";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pharmacyJson("PH-401", "No Token Pharmacy")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pharmacyJson("PH-403", "Forbidden Pharmacy")))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withAdminAccess_returns201WithLocationAndNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pharmacyJson("PH-CREATE-IT", "IT Create Pharmacy")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("PH-CREATE-IT"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.Pharmacy");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    @Test
    void getByUid_existingPharmacy_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pharmacyJson("PH-GET-IT", "IT Get Pharmacy")))
                .andExpect(status().isCreated()).andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void list_withToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void update_withAdminAccess_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pharmacyJson("PH-UPD-IT", "IT Update Pharmacy Before")))
                .andExpect(status().isCreated()).andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        String updateBody = """
                {"code":"PH-UPD-IT","name":"IT Update Pharmacy After",
                 "description":"updated","location":"Wing B","category":"INPATIENT","active":true}
                """;
        mockMvc.perform(put(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("IT Update Pharmacy After"))
                .andExpect(jsonPath("$.location").value("Wing B"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    private String pharmacyJson(String code, String name) {
        return """
                {"code":"%s","name":"%s","description":"desc",
                 "location":"Main Building","category":"OUTPATIENT","active":false}
                """.formatted(code, name);
    }
}
