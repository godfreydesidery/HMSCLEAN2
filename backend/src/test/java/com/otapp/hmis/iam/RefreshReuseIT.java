package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.AdminCredentialsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Refresh-token reuse detection integration tests (build-spec §7, §4).
 */
class RefreshReuseIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AdminCredentialsFixture adminCredentialsFixture;

    @BeforeEach
    void resetAdminPassword() {
        adminCredentialsFixture.ensureKnownAdminPassword();
    }

    @Test
    void reuseDetected_returns401WithTokenReuseDetectedType() throws Exception {
        // Login to get a refresh token
        JsonNode loginBody = login();
        String firstRefreshToken = loginBody.get("refreshToken").asText();

        // Rotate once (consume the first refresh token)
        JsonNode rotatedBody = rotate(firstRefreshToken);
        assertThat(rotatedBody.has("refreshToken")).isTrue();

        // Present the OLD (now revoked) refresh token again → should get 401 token-reuse-detected
        MvcResult reuseResult = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + firstRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = reuseResult.getResponse().getContentAsString();
        assertThat(body).contains("urn:hmis:error:token-reuse-detected");
        // Must NOT contain any token hash or user uid in the error body
        assertThat(body).doesNotContain("tokenHash");
        assertThat(body).doesNotContain("replacedByUid");
    }

    @Test
    void expiredToken_returns401InvalidToken_notReuseDetected() throws Exception {
        // An unknown/garbage token is treated as invalid-token (not reuse)
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"this-token-never-existed-in-db\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("urn:hmis:error:invalid-token");
        assertThat(body).doesNotContain("urn:hmis:error:token-reuse-detected");
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

    private JsonNode rotate(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
