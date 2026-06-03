package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import com.otapp.hmis.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CORS allow-list integration tests (build-spec §7, §4 D-1).
 *
 * <p>Verifies the wildcard was replaced with an explicit origin list.
 */
class CorsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void allowedOrigin_echoed_inPreflightResponse() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/auth/token")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        // The allowed origin must be echoed back
        assertThat(allowOrigin).isEqualTo("http://localhost:4200");
    }

    @Test
    void disallowedOrigin_notEchoedInPreflightResponse() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/auth/token")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        // The evil origin must NOT be echoed
        assertThat(allowOrigin).isNotEqualTo("https://evil.example.com");
    }
}
