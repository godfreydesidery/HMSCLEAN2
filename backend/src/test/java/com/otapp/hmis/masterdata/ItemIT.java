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
 * Integration tests for {@code /api/v1/masterdata/items} (build-spec §1.2, §3).
 *
 * <p>Gate is {@code ADMIN-ACCESS} (CR-15 DEVIATION-1). Covers: create 201+Location, 403, 401,
 * get-by-uid, list, update, no {@code id} in JSON, audit log on create.
 */
class ItemIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/items";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("ITEM-401", "No Token Item")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("ITEM-403", "Forbidden Item")))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withAdminAccess_returns201WithLocationNoId() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("ITEM-CREATE-IT", "IT Create Item")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("ITEM-CREATE-IT"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.Item");
        assertThat(rows).anyMatch(r ->
                r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    @Test
    void getByUid_existingItem_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("ITEM-GET-IT", "IT Get Item")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(get(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.code").value("ITEM-GET-IT"))
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

    @Test
    void update_withAdminAccess_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        MvcResult created = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("ITEM-UPD-IT", "IT Update Item Before")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        String updateBody = """
                {"code":"ITEM-UPD-IT","name":"IT Update Item After","barcode":"BAR123",
                 "shortName":null,"commonName":null,"vat":5.00,"uom":"KG","packSize":2.000000,
                 "category":"SUPPLIES","costPriceVatIncl":100.00,"sellingPriceVatIncl":120.00,
                 "active":false,"ingredients":"none"}
                """;
        mockMvc.perform(put(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("IT Update Item After"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    private String itemJson(String code, String name) {
        return """
                {"code":"%s","name":"%s","barcode":null,"shortName":null,"commonName":null,
                 "vat":0.00,"uom":null,"packSize":1.000000,"category":null,
                 "costPriceVatIncl":0.00,"sellingPriceVatIncl":0.00,
                 "active":true,"ingredients":""}
                """.formatted(code, name);
    }
}
