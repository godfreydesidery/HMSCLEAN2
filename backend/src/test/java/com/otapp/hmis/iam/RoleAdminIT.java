package com.otapp.hmis.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Role administration endpoint integration tests (build-spec §7).
 */
class RoleAdminIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestJwtFactory jwtFactory;

    // -----------------------------------------------------------------------
    // Authorization
    // -----------------------------------------------------------------------

    @Test
    void create_requiresRoleAllOrAdminAccess_withoutPriv_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"MY-ROLE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withRoleAll_returns201WithLocation() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ROLE-ALL"));
        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ORG-ROLE-A\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    void create_withAdminAccess_returns201() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ORG-ROLE-B\"}"))
                .andExpect(status().isCreated());
    }

    // -----------------------------------------------------------------------
    // Reserved names (15-list verbatim)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "create rejects reserved name: {0}")
    @ValueSource(strings = {
            "ROOT", "ADMIN", "RECEPTION", "CASHIER", "HUMAN-RESOURCE",
            "PROCUREMENT", "MANAGER", "ACCOUNTANT", "STORE-PERSON",
            "CLINICIAN", "NURSE", "PHARMACIST", "LABORATORIST",
            "RADIOGRAPHER", "RADIOLOGIST"
    })
    void create_rejectsReservedName(String reservedName) throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ROLE-ALL"));
        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + reservedName + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    // -----------------------------------------------------------------------
    // owner=ORGANIZATION on create
    // -----------------------------------------------------------------------

    @Test
    void create_setsOwnerOrganization() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ROLE-ALL"));
        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ORG-OWNER-CHECK\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value("ORGANIZATION"));
    }

    // -----------------------------------------------------------------------
    // Replace privileges — gate enforced (CR-15)
    // -----------------------------------------------------------------------

    @Test
    void replacePrivileges_requiresRoleAllOrAdminAccess_withoutPriv_returns403() throws Exception {
        // First create a role
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("ROLE-ALL"));
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PRIV-REPLACE-ROLE\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Attempt replace with insufficient privileges
        String lowToken = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(put("/api/v1/iam/roles/uid/" + uid + "/privileges")
                        .header("Authorization", "Bearer " + lowToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privilegeCodes\":[\"USER-ALL\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void replacePrivileges_fullReplaceSemantics() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ROLE-ALL"));

        // Create role
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"REPLACE-SEMANTICS-ROLE\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Set initial privileges
        mockMvc.perform(put("/api/v1/iam/roles/uid/" + uid + "/privileges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privilegeCodes\":[\"USER-ALL\",\"ROLE-ALL\"]}"))
                .andExpect(status().isOk());

        // Replace with a different single privilege — old ones must be gone
        mockMvc.perform(put("/api/v1/iam/roles/uid/" + uid + "/privileges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privilegeCodes\":[\"DAY-ACCESS\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privilegeCodes").isArray())
                .andExpect(jsonPath("$.privilegeCodes[0]").value("DAY-ACCESS"))
                .andExpect(jsonPath("$.privilegeCodes.length()").value(1));
    }

    // -----------------------------------------------------------------------
    // List (ungated)
    // -----------------------------------------------------------------------

    @Test
    void list_withAnyToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/iam/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
