package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.iam.application.AuthenticationService;
import com.otapp.hmis.iam.domain.RefreshTokenRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.AdminCredentialsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Refresh-token reuse detection integration tests (build-spec §7, §4).
 */
class RefreshReuseIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminCredentialsFixture adminCredentialsFixture;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAdminPassword() {
        adminCredentialsFixture.ensureKnownAdminPassword();
    }

    // -----------------------------------------------------------------------
    // BLOCKER fix: reuse detection revokes ALL live tokens for that user
    // -----------------------------------------------------------------------

    @Test
    void reuseDetected_returns401WithTokenReuseDetectedType_andRevokesAllLiveTokens()
            throws Exception {
        // First login — produces refresh token A
        JsonNode firstLogin = login();
        String tokenA = firstLogin.get("refreshToken").asText();

        // Second login — produces refresh token B (independent session)
        JsonNode secondLogin = login();
        String tokenB = secondLogin.get("refreshToken").asText();

        // Rotate token A once → it is now consumed/revoked; token A' is issued
        JsonNode rotated = rotate(tokenA);
        assertThat(rotated.has("refreshToken")).isTrue();

        // Present the OLD token A again → reuse detected
        MvcResult reuseResult = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokenA + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = reuseResult.getResponse().getContentAsString();
        assertThat(body).contains("urn:hmis:error:token-reuse-detected");
        // Must NOT leak any token hash or uid in error body
        assertThat(body).doesNotContain("tokenHash");
        assertThat(body).doesNotContain("replacedByUid");

        // Security-critical: ALL live tokens for that user must now be revoked.
        // Token B was never used but must have been swept by the reuse handler.
        MvcResult tokenBResult = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokenB + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Token B should be revoked — presenting it hits the reuse branch
        String tokenBBody = tokenBResult.getResponse().getContentAsString();
        assertThat(tokenBBody).contains("urn:hmis:error:");

        // Also confirm at the repository level: no live (revoked=false) tokens remain for admin
        String adminUid = jdbcTemplate.queryForObject(
                "SELECT uid FROM users WHERE username = 'admin'", String.class);
        long liveCount = refreshTokenRepository.findByUserUidAndRevokedFalse(adminUid).size();
        assertThat(liveCount).as("all live tokens revoked after reuse detection").isZero();
    }

    // -----------------------------------------------------------------------
    // Unknown token → invalid-token (Branch 1)
    // -----------------------------------------------------------------------

    @Test
    void unknownToken_returns401InvalidToken_notReuseDetected() throws Exception {
        // An unknown/never-persisted token is treated as invalid-token (Branch 1)
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
    // Genuinely expired token → invalid-token (Branch 3, distinct from Branch 1)
    // -----------------------------------------------------------------------

    @Test
    void expiredToken_returns401InvalidToken_notReuseDetected() throws Exception {
        // Login to get a real, persisted refresh token
        JsonNode loginBody = login();
        String rawRefreshToken = loginBody.get("refreshToken").asText();

        // Back-date the token's expiry to one hour ago so it is genuinely expired (Branch 3)
        String tokenHash = AuthenticationService.sha256(rawRefreshToken);
        jdbcTemplate.update(
                "UPDATE refresh_tokens SET expires_at = now() - INTERVAL '1 hour' " +
                "WHERE token_hash = ? AND revoked = FALSE",
                tokenHash);

        // Presenting the now-expired token must return invalid-token, NOT reuse-detected
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefreshToken + "\"}"))
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
