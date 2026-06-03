package com.otapp.hmis.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Integration tests for {@code /api/v1/masterdata/supplier-item-prices} (build-spec §1.2, §3).
 *
 * <p>Gate for mutations is {@code SUPPLIER_PRICE_LIST-ALL} (exact legacy code — build-spec §3).
 * Covers: create 201+Location with correct gate, 403 with wrong gate, 401 without token,
 * get list, delete 204.
 */
class SupplierItemPriceIT extends AbstractIntegrationTest {

    private static final String ITEMS     = "/api/v1/masterdata/items";
    private static final String SUPPLIERS = "/api/v1/masterdata/suppliers";
    private static final String BASE      = "/api/v1/masterdata/supplier-item-prices";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;

    // ------------------------------------------------------------------
    // Authorization: SUPPLIER_PRICE_LIST-ALL gate
    // ------------------------------------------------------------------

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":10.00,\"terms\":null,\"active\":false,"
                                + "\"supplierUid\":\"FAKE\",\"itemUid\":\"FAKE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withAdminAccessOnly_returns403() throws Exception {
        // ADMIN-ACCESS is NOT sufficient — gate requires SUPPLIER_PRICE_LIST-ALL
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":10.00,\"terms\":null,\"active\":false,"
                                + "\"supplierUid\":\"FAKE\",\"itemUid\":\"FAKE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withSupplierPriceListAll_returns201() throws Exception {
        String priceToken = jwtFactory.tokenWithPrivileges("pricemgr", List.of("SUPPLIER_PRICE_LIST-ALL"));
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        String supplierUid = createSupplier(adminToken, "SIP-SUP-IT", "SIP Supplier IT");
        String itemUid     = createItem(adminToken, "SIP-ITEM-IT", "SIP Item IT");

        String body = """
                {"price":99.50,"terms":"Net 30","active":true,
                 "supplierUid":"%s","itemUid":"%s"}
                """.formatted(supplierUid, itemUid);

        MvcResult result = mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + priceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uid/")))
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.supplierUid").value(supplierUid))
                .andExpect(jsonPath("$.itemUid").value(itemUid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();

        // Delete with correct gate → 204
        mockMvc.perform(delete(BASE + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + priceToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_withAdminAccessOnly_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(delete(BASE + "/uid/FAKEPRICE000000000000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
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
    // Helpers
    // ------------------------------------------------------------------

    private String createSupplier(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","contactName":"Test Contact",
                 "active":true,"tin":null,"vrn":null,"termsOfContract":null,
                 "physicalAddress":null,"postCode":null,"postAddress":null,
                 "telephone":null,"mobile":null,"email":null,"fax":null,
                 "bankAccountName":null,"bankPhysicalAddress":null,"bankPostCode":null,
                 "bankPostAddress":null,"bankName":null,"bankAccountNo":null}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(SUPPLIERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

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
}
