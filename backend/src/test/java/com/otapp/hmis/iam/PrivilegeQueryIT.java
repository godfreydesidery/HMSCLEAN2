package com.otapp.hmis.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Privilege catalogue endpoint integration tests (build-spec §7, endpoint #13).
 */
class PrivilegeQueryIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestJwtFactory jwtFactory;

    @Test
    void listPrivileges_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/iam/privileges"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listPrivileges_withAnyToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/iam/privileges")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void listPrivileges_returns35Codes() throws Exception {
        // 35 (V2) + 3 (V47 disposition APPROVE — inc-07 07a-3 CR-07-SoD) + 1 (V52 MEDICATION-ADMINISTER — 07d CR-07-MAR) = 39
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(get("/api/v1/iam/privileges")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(39));
    }

    @Test
    void listPrivileges_filteredByAdminRole_returnsNonEmpty() throws Exception {
        // ADMIN has all 39 privileges (V2 + V47 + V52)
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(get("/api/v1/iam/privileges?roleName=ADMIN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(39));
    }
}
