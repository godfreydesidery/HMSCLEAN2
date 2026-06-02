package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.iam.config.SecurityConfig;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.AdminCredentialsFixture;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Asserts BOTH {@code POST /api/v1/auth/token} and {@code POST /api/v1/auth/token/refresh} mint an
 * access token whose {@code privileges} claim is a non-empty string array — and never a
 * {@code roles} claim (ADR-0006 defect fix).
 */
class TokenEndpointsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AdminCredentialsFixture adminCredentialsFixture;

    @BeforeEach
    void resetAdminPassword() {
        adminCredentialsFixture.ensureKnownAdminPassword();
    }

    @Test
    void loginIssuesAccessTokenWithPrivilegesClaim() throws Exception {
        JsonNode body = login();
        String accessToken = body.get("accessToken").asText();
        assertPrivilegesClaim(accessToken);
        // The DTO mirror is also present and non-empty.
        assertThat(body.get("privileges").isArray()).isTrue();
        assertThat(body.get("privileges")).isNotEmpty();
    }

    @Test
    void refreshIssuesAccessTokenWithPrivilegesClaimNotRoles() throws Exception {
        JsonNode loginBody = login();
        String refreshToken = loginBody.get("refreshToken").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();

        Jwt jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getClaimAsStringList(SecurityConfig.PRIVILEGES_CLAIM))
                .as("refresh path must emit privileges, not roles")
                .isNotEmpty();
        assertThat(jwt.getClaims()).doesNotContainKey("roles");
    }

    private JsonNode login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertPrivilegesClaim(String accessToken) {
        Jwt jwt = jwtDecoder.decode(accessToken);
        List<String> privileges = jwt.getClaimAsStringList(SecurityConfig.PRIVILEGES_CLAIM);
        assertThat(privileges).contains("ADMIN-ACCESS");
        assertThat(jwt.getClaims()).doesNotContainKey("roles");
    }
}
