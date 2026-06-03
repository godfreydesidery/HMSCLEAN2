package com.otapp.hmis.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Assign-roles endpoint integration tests (build-spec §7, endpoint #12).
 */
class AssignRolesIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestJwtFactory jwtFactory;

    @Test
    void assignRoles_requiresAnyOfFourCodes_withoutPriv_returns403() throws Exception {
        // Create user first with an ADMIN-ACCESS token
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"assign_roles_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"Assign\",\"lastName\":\"Roles\",\"nickname\":\"assignroles\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Attempt assign with no relevant privilege
        String lowToken = jwtFactory.tokenWithPrivileges("lowuser", List.of("DAY-ACCESS"));
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid + "/roles")
                        .header("Authorization", "Bearer " + lowToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleNames\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignRoles_withUserUpdate_succeeds() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"assign_update_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"Assign\",\"lastName\":\"Update\",\"nickname\":\"assignupdate\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        String userUpdateToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-UPDATE"));
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid + "/roles")
                        .header("Authorization", "Bearer " + userUpdateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleNames\":[\"ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleNames[0]").value("ADMIN"));
    }

    @Test
    void assignRoles_idempotentAdd_doesNotDuplicate() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"idempotent_roles_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"Idempotent\",\"lastName\":\"Roles\",\"nickname\":\"idemroles\"," +
                                "\"roleNames\":[\"ADMIN\"]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Assign the same role again — should not duplicate
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleNames\":[\"ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleNames.length()").value(1));
    }
}
