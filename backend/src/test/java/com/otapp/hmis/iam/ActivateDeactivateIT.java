package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Activate/deactivate user tests (build-spec §7, decision #3).
 * Verifies the real toggle behaviour — no legacy no-op stub.
 */
class ActivateDeactivateIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestJwtFactory jwtFactory;

    @Test
    void deactivateUser_setsEnabledFalse() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        // Create user
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"toggle_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"Toggle\",\"lastName\":\"User\",\"nickname\":\"toggleuser\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String uid = created.get("uid").asText();
        assertThat(created.get("enabled").asBoolean()).isTrue();

        // Deactivate
        MvcResult deactivateResult = mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Toggle\",\"lastName\":\"User\",\"nickname\":\"toggleuser\"," +
                                "\"password\":\"\",\"enabled\":false,\"roleNames\":[]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode deactivated = objectMapper.readTree(deactivateResult.getResponse().getContentAsString());
        assertThat(deactivated.get("enabled").asBoolean()).isFalse();
    }

    @Test
    void reactivateUser_setsEnabledTrue() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        // Create and then deactivate
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reactivate_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"React\",\"lastName\":\"User\",\"nickname\":\"reactuser\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"React\",\"lastName\":\"User\",\"nickname\":\"reactuser\"," +
                                "\"password\":\"\",\"enabled\":false,\"roleNames\":[]}"))
                .andExpect(status().isOk());

        // Re-activate
        MvcResult activateResult = mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"React\",\"lastName\":\"User\",\"nickname\":\"reactuser\"," +
                                "\"password\":\"\",\"enabled\":true,\"roleNames\":[]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode reactivated = objectMapper.readTree(activateResult.getResponse().getContentAsString());
        assertThat(reactivated.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void deactivatedUser_cannotLogin() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "locked_user";
        String password = "pass1234";

        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"," +
                                "\"firstName\":\"Locked\",\"lastName\":\"User\",\"nickname\":\"lockeduser\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Verify login works
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        // Deactivate
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Locked\",\"lastName\":\"User\",\"nickname\":\"lockeduser\"," +
                                "\"password\":\"\",\"enabled\":false,\"roleNames\":[]}"))
                .andExpect(status().isOk());

        // Login must now fail
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
