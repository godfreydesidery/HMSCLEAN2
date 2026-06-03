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
 * Integration tests for {@code /api/v1/masterdata/beds} (build-spec §3).
 *
 * <p>Verifies ward FK resolution (404 when ward uid unknown), 401/403 gates, 201+Location,
 * get-by-uid, list, update (ward is NOT changed on update — updatable=false), no {@code id}
 * in JSON, and audit row on create.
 */
class WardBedIT extends AbstractIntegrationTest {

    private static final String BEDS       = "/api/v1/masterdata/beds";
    private static final String WARDS      = "/api/v1/masterdata/wards";
    private static final String WARD_TYPES = "/api/v1/masterdata/ward-types";
    private static final String WARD_CATS  = "/api/v1/masterdata/ward-categories";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // ------------------------------------------------------------------
    // Setup helpers
    // ------------------------------------------------------------------

    private String createWard(String token, String code, String name) throws Exception {
        String typeBody = """
                {"code":"%s","name":"%s","description":null,"price":300.00,"active":true}
                """.formatted("WBT-" + code, "WBT " + name);
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString()).get("uid").asText();

        String catBody = """
                {"code":"%s","name":"%s","description":null,"active":true}
                """.formatted("WBC-" + code, "WBC " + name);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString()).get("uid").asText();

        String wardBody = """
                {"code":"%s","name":"%s","noOfBeds":10,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(code, name, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(wr.getResponse().getContentAsString()).get("uid").asText();
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BEDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bedJson("B-401", "FAKE_WARD")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BEDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bedJson("B-403", "FAKE_WARD")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // FK resolution: 404 for unknown ward uid
    // ------------------------------------------------------------------

    @Test
    void create_unknownWardUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post(BEDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bedJson("B-404", "NONEXISTENT00000000000000")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Create 201 + audit row
    // ------------------------------------------------------------------

    @Test
    void create_withValidWardUid_returns201AndWritesAuditRow() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String wardUid = createWard(token, "WARD-BED-CREATE", "Ward For Bed Create");

        MvcResult result = mockMvc.perform(post(BEDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bedJson("BED-01", wardUid)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.wardUid").value(wardUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.WardBed");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // ------------------------------------------------------------------
    // Get by uid + list
    // ------------------------------------------------------------------

    @Test
    void getByUid_existingBed_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String wardUid = createWard(token, "WARD-BED-GET", "Ward For Bed Get");

        MvcResult created = mockMvc.perform(post(BEDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bedJson("BED-GET-01", wardUid)))
                .andExpect(status().isCreated()).andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(BEDS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void list_withToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BEDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // Update — ward FK must NOT change (updatable=false)
    // ------------------------------------------------------------------

    @Test
    void update_wardUidIgnored_wardRemainsOriginal() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String wardUid     = createWard(token, "WARD-BED-UPD", "Ward For Bed Upd");
        String otherWardUid = createWard(token, "WARD-BED-OTHER", "Ward For Bed Other");

        MvcResult created = mockMvc.perform(post(BEDS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bedJson("BED-UPD-01", wardUid)))
                .andExpect(status().isCreated()).andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        // Update with a different wardUid — it must be silently ignored
        String updateBody = """
                {"no":"BED-UPD-02","status":"OCCUPIED","active":true,"wardUid":"%s"}
                """.formatted(otherWardUid);
        mockMvc.perform(put(BEDS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.no").value("BED-UPD-02"))
                .andExpect(jsonPath("$.status").value("OCCUPIED"))
                // wardUid must still be the original
                .andExpect(jsonPath("$.wardUid").value(wardUid))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private String bedJson(String no, String wardUid) {
        return """
                {"no":"%s","status":"AVAILABLE","active":false,"wardUid":"%s"}
                """.formatted(no, wardUid);
    }
}
