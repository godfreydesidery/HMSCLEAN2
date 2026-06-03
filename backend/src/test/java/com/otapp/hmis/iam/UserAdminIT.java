package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * User administration endpoint integration tests (build-spec §7).
 */
class UserAdminIT extends AbstractIntegrationTest {

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
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/iam/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("noauth_user", "pass1234", "No", "Auth", "noauth")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutRequiredPrivilege_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("lowpriv", List.of("DAY-ACCESS"));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("lowpriv_user", "pass1234", "Low", "Priv", "lowpriv")))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withUserAll_returns201WithLocation() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("newuser_loc", "pass1234", "New", "Location", "newloc")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).contains("/api/v1/iam/users/uid/");
    }

    @Test
    void create_withAdminAccess_returns201() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("admin_access_user", "pass1234", "Admin", "Access", "aauser")))
                .andExpect(status().isCreated());
    }

    // -----------------------------------------------------------------------
    // Root username rejected
    // -----------------------------------------------------------------------

    @Test
    void create_rejectsRootUsername() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("root", "pass1234", "Root", "User", "rootnick")))
                .andExpect(status().is4xxClientError());
    }

    // -----------------------------------------------------------------------
    // userNo format
    // -----------------------------------------------------------------------

    @Test
    void create_assignsUserNoInExpectedFormat() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("usrno_test_user", "pass1234", "UsrNo", "Test", "usrnotest")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userNo").value(org.hamcrest.Matchers.matchesRegex("USR-\\d{3}-\\d{3}")));
    }

    // -----------------------------------------------------------------------
    // No numeric id in response
    // -----------------------------------------------------------------------

    @Test
    void response_hasNoNumericIdField() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("noid_test_user", "pass1234", "NoId", "Test", "noidtest")))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"id\":");
    }

    // -----------------------------------------------------------------------
    // Password encoding
    // -----------------------------------------------------------------------

    @Test
    void create_bcryptHashesPassword_loginSucceeds() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "bcrypt_test_user";
        String password = "TestPass99";

        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(username, password, "Bcrypt", "Test", "bcrypttest")))
                .andExpect(status().isCreated());

        // Login should succeed with that password
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Self-toggle rejected
    // -----------------------------------------------------------------------

    @Test
    void update_selfToggleEnabled_isRejected() throws Exception {
        // First create a user
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "selftoggle_user";
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(username, "pass1234", "Self", "Toggle", "selftoggle")))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String uid = objectMapper.readTree(responseBody).get("uid").asText();

        // Attempt to disable self (token subject = same username)
        String selfToken = jwtFactory.tokenWithPrivileges(username, List.of("USER-ALL"));
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + selfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Self", "Toggle", "selftoggle", "", false)))
                .andExpect(status().is4xxClientError());
    }

    // -----------------------------------------------------------------------
    // List (ungated — only authentication required)
    // -----------------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/iam/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_withAnyToken_returns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("DAY-ACCESS"));
        mockMvc.perform(get("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String createUserJson(String username, String password,
                                         String firstName, String lastName, String nickname) {
        return String.format(
                "{\"username\":\"%s\",\"password\":\"%s\",\"firstName\":\"%s\"," +
                "\"lastName\":\"%s\",\"nickname\":\"%s\",\"roleNames\":[]}",
                username, password, firstName, lastName, nickname);
    }

    private static String updateUserJson(String firstName, String lastName,
                                         String nickname, String password, boolean enabled) {
        return String.format(
                "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"nickname\":\"%s\"," +
                "\"password\":\"%s\",\"enabled\":%s,\"roleNames\":[]}",
                firstName, lastName, nickname, password, enabled);
    }
}
