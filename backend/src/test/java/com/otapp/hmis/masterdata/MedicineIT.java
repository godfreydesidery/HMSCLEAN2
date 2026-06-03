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
 * Integration tests for {@code /api/v1/masterdata/medicines} (build-spec §1.2, §3).
 *
 * <p>Covers: create 201+Location, 403 without ADMIN-ACCESS, 401 without token,
 * get-by-uid 200, list 200, update 200, no {@code id} in JSON, audit log written on create.
 */
class MedicineIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/medicines";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(medicineJson("MED-401", "No Token Medicine")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(medicineJson("MED-403", "Forbidden Medicine")))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withAdminAccess_returns201WithLocationNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(medicineJson("MED-CREATE-IT", "IT Create Medicine")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("MED-CREATE-IT"))
                .andExpect(jsonPath("$.type").value("ORAL"))
                .andExpect(jsonPath("$.category").value("MEDICINE"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.Medicine");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    @Test
    void getByUid_existingMedicine_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(medicineJson("MED-GET-IT", "IT Get Medicine")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.code").value("MED-GET-IT"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getByUid_unknownUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(get(BASE + "/uid/NOTEXIST00000000000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_withToken_returns200Array() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void update_withAdminAccess_returns200WithUpdatedFields() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(medicineJson("MED-UPD-IT", "IT Update Medicine Before")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        String updateBody = """
                {"code":"MED-UPD-IT","name":"IT Update Medicine After","description":"updated",
                 "type":"INJECTION","price":50.00,"uom":"ML","category":"ANTIBIOTIC","active":true}
                """;
        mockMvc.perform(put(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("IT Update Medicine After"))
                .andExpect(jsonPath("$.type").value("INJECTION"))
                .andExpect(jsonPath("$.category").value("ANTIBIOTIC"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    private String medicineJson(String code, String name) {
        return """
                {"code":"%s","name":"%s","description":"desc","type":"ORAL",
                 "price":10.00,"uom":"TABLET","category":"MEDICINE","active":false}
                """.formatted(code, name);
    }
}
