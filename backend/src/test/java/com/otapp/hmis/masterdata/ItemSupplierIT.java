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
 * Negative-auth and smoke tests for {@code /api/v1/masterdata/item-suppliers}
 * (build-spec §3 AC-8, qa-review HIGH).
 *
 * <p>Covers: 401 without token, 403 without ADMIN-ACCESS, 201+Location with ADMIN-ACCESS,
 * no {@code id} in response, audit CREATE row.
 */
class ItemSupplierIT extends AbstractIntegrationTest {

    private static final String ITEMS     = "/api/v1/masterdata/items";
    private static final String SUPPLIERS = "/api/v1/masterdata/suppliers";
    private static final String BASE      = "/api/v1/masterdata/item-suppliers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"X\",\"supplierUid\":\"Y\","
                                + "\"costPriceVatIncl\":10.00,\"costPriceVatExcl\":8.70}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"X\",\"supplierUid\":\"Y\","
                                + "\"costPriceVatIncl\":10.00,\"costPriceVatExcl\":8.70}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withAdminAccess_returns201WithLocationAndAuditRow() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        String itemUid     = createItem(token, "IS-ITEM-CREATE-IT", "IS Item Create IT");
        String supplierUid = createSupplier(token, "IS-SUP-CREATE-IT", "IS Supplier Create IT");

        String body = """
                {"itemUid":"%s","supplierUid":"%s",
                 "costPriceVatIncl":100.00,"costPriceVatExcl":86.96,"active":true}
                """.formatted(itemUid, supplierUid);

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(uid).isNotBlank();

        assertThat(auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.ItemSupplier"))
                .anyMatch(r -> r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createItem(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","barcode":null,"shortName":null,"commonName":null,
                 "vat":0.00,"uom":null,"packSize":1.000000,"category":null,
                 "costPriceVatIncl":0.00,"sellingPriceVatIncl":0.00,
                 "active":true,"ingredients":""}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(ITEMS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createSupplier(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","contactName":"Contact","active":true,
                 "tin":null,"vrn":null,"termsOfContract":null,"physicalAddress":null,
                 "postCode":null,"postAddress":null,"telephone":null,"mobile":null,
                 "email":null,"fax":null,"bankAccountName":null,"bankPhysicalAddress":null,
                 "bankPostCode":null,"bankPostAddress":null,"bankName":null,"bankAccountNo":null}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(SUPPLIERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }
}
