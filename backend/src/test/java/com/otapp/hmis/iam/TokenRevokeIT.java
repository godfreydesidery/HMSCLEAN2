package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.AdminCredentialsFixture;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Token revoke endpoint integration tests (build-spec §7, CR-10, endpoint #15).
 */
class TokenRevokeIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AdminCredentialsFixture adminCredentialsFixture;

    @Autowired
    TestJwtFactory jwtFactory;

    @BeforeEach
    void resetAdminPassword() {
        adminCredentialsFixture.ensureKnownAdminPassword();
    }

    @Test
    void revoke_returns204AndRevokesToken() throws Exception {
        // Login to get a refresh token
        JsonNode loginBody = login();
        String refreshToken = loginBody.get("refreshToken").asText();
        String accessToken = loginBody.get("accessToken").asText();

        // Revoke
        mockMvc.perform(post("/api/v1/auth/token/revoke")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        // Subsequent refresh of the revoked token should return 401
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Should be reuse-detected (token was revoked, presenting it triggers reuse branch)
        String body = refreshResult.getResponse().getContentAsString();
        assertThat(body).contains("urn:hmis:error:");
    }

    @Test
    void revoke_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"some-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revoke_unknownToken_returns204NonEnumerating() throws Exception {
        // Revoke endpoint must not leak whether a token exists — always 204
        String accessToken = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post("/api/v1/auth/token/revoke")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"this-token-never-existed-anywhere\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void revoke_alreadyRevoked_returns204Idempotent() throws Exception {
        JsonNode loginBody = login();
        String refreshToken = loginBody.get("refreshToken").asText();
        String accessToken = loginBody.get("accessToken").asText();

        // First revoke
        mockMvc.perform(post("/api/v1/auth/token/revoke")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        // Second revoke (idempotent — same access token still valid since it's a JWT)
        mockMvc.perform(post("/api/v1/auth/token/revoke")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JsonNode login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
