package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.shared.audit.AuditAction;
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
 * Negative-auth and smoke tests for {@code /api/v1/masterdata/suppliers}
 * (build-spec §3 AC-8, qa-review HIGH).
 *
 * <p>Covers: 401 without token, 403 without ADMIN-ACCESS, 201+Location with ADMIN-ACCESS,
 * no {@code id} in response, audit CREATE row.
 */
class SupplierIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/suppliers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson("SUP-401", "No Token Supplier")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson("SUP-403", "Forbidden Supplier")))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withAdminAccess_returns201WithLocationAndAuditRow() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson("SUP-CREATE-IT", "IT Create Supplier")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("SUP-CREATE-IT"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        assertThat(auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.Supplier"))
                .anyMatch(r -> r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    private String supplierJson(String code, String name) {
        return """
                {"code":"%s","name":"%s","contactName":"Test Contact","active":true,
                 "tin":null,"vrn":null,"termsOfContract":null,"physicalAddress":null,
                 "postCode":null,"postAddress":null,"telephone":null,"mobile":null,
                 "email":null,"fax":null,"bankAccountName":null,"bankPhysicalAddress":null,
                 "bankPostCode":null,"bankPostAddress":null,"bankName":null,"bankAccountNo":null}
                """.formatted(code, name);
    }
}
