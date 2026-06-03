package com.otapp.hmis.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@code /api/v1/shared/business-days} admin endpoints
 * (build-spec §5, P5 BusinessDay).
 *
 * <h2>Ordered lifecycle</h2>
 * Tests are ordered to exercise the full open → current → second-open-409 →
 * close → current-422 lifecycle in a single shared-container pass.
 *
 * <h2>Gate coverage</h2>
 * open/close require DAY-ACCESS; without the privilege → 403; without token → 401.
 * current() requires a valid token (authenticated, no role gate).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BusinessDayIT extends AbstractIntegrationTest {

    private static final String OPEN    = "/api/v1/shared/business-days/open";
    private static final String CLOSE   = "/api/v1/shared/business-days/close";
    private static final String CURRENT = "/api/v1/shared/business-days/current";

    @Autowired MockMvc mockMvc;
    @Autowired TestJwtFactory jwtFactory;

    // ------------------------------------------------------------------
    // Authorization gates (before any day is opened — current returns 422)
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void open_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(OPEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void open_withoutDayAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post(OPEN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void current_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(CURRENT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void current_whenNoDayOpen_returns422NoDayOpen() throws Exception {
        // Pre-condition: no day has ever been opened in this test run yet (order=4)
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(CURRENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:no-day-open"))
                .andExpect(jsonPath("$.code").value("NO_DAY_OPEN")); // distinct assertion
    }

    // ------------------------------------------------------------------
    // Open a day
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    void open_withDayAccess_returns200AndOpenDay() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("operator", List.of("DAY-ACCESS"));
        mockMvc.perform(post(OPEN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").isString())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.businessDate").isString())
                .andExpect(jsonPath("$.openedAt").isString())
                .andExpect(jsonPath("$.closedAt").isEmpty())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Current — returns the open day
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void current_afterOpen_returnsOpenDay() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(CURRENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.uid").isString());
    }

    // ------------------------------------------------------------------
    // Second open → 409
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void open_whenAlreadyOpen_returns409Conflict() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("operator", List.of("DAY-ACCESS"));
        mockMvc.perform(post(OPEN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:conflict"));
    }

    // ------------------------------------------------------------------
    // Close the day
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    void close_withDayAccess_returnsClosedDay() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("operator", List.of("DAY-ACCESS"));
        mockMvc.perform(post(CLOSE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isString());
    }

    // ------------------------------------------------------------------
    // Current after close → 422
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    void current_afterClose_returns422NoDayOpen() throws Exception {
        // Pre-condition: a day was opened (order=5) and then closed (order=8)
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(CURRENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:no-day-open"))
                .andExpect(jsonPath("$.title").value("No business day is open")); // distinct assertion
    }

    // ------------------------------------------------------------------
    // Close without DAY-ACCESS / without token
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    void close_withoutDayAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post(CLOSE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    void close_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(CLOSE))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Sanity: re-open after close succeeds (day lifecycle repeatable)
    // ------------------------------------------------------------------

    @Test
    @Order(12)
    void canReopenAfterClose() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("operator", List.of("DAY-ACCESS"));
        mockMvc.perform(post(OPEN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Cleanup: close again so subsequent test runs in the same container start clean
        mockMvc.perform(post(CLOSE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}
