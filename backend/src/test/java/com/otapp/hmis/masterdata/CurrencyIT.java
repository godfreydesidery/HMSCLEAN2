package com.otapp.hmis.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@code /api/v1/masterdata/currencies} (build-spec §1.5, CurrencyIT).
 *
 * <h2>Seeded row assertion</h2>
 * V14 seeds one TZS default row. GET must return it with {@code defaultCurrency=true}.
 *
 * <h2>Create new currency</h2>
 * POST a non-default USD row with ADMIN-ACCESS → 201.
 *
 * <h2>Gate coverage</h2>
 * POST without ADMIN-ACCESS → 403; without token → 401. GET is role-ungated.
 */
class CurrencyIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/currencies";

    @Autowired MockMvc mockMvc;
    @Autowired TestJwtFactory jwtFactory;

    // ------------------------------------------------------------------
    // GET — seeded TZS default row
    // ------------------------------------------------------------------

    @Test
    void list_returnsSeedeTzsDefaultRow() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));

        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.code=='TZS')].defaultCurrency").value(true))
                .andExpect(jsonPath("$[?(@.code=='TZS')].name")
                        .value("Tanzanian Shilling"));
    }

    @Test
    void list_noIdInResponse() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // POST — create new currency
    // ------------------------------------------------------------------

    @Test
    void post_withAdminAccess_createsNewCurrencyAndReturns201() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"USD","name":"US Dollar","defaultCurrency":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.code").value("USD"))
                .andExpect(jsonPath("$.defaultCurrency").value(false))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void post_duplicateCode_returns409() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        // TZS already seeded — posting again with same code must conflict
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"TZS","name":"Duplicate Shilling","defaultCurrency":false}
                                """))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // Gate coverage
    // ------------------------------------------------------------------

    @Test
    void post_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"GBP","name":"British Pound","defaultCurrency":false}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"EUR","name":"Euro","defaultCurrency":false}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }
}
