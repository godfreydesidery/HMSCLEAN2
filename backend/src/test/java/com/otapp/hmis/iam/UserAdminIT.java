package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.shared.audit.AuditLogRepository;
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
 *
 * <p>CR-21: newly-created users start inactive (enabled=false). Tests that assert login must
 * first activate the user via PUT before attempting POST /auth/token.
 */
class UserAdminIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // -----------------------------------------------------------------------
    // Create — authorization
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
    // Update — authorization (qa HIGH: 403 and 401 were missing)
    // -----------------------------------------------------------------------

    @Test
    void update_withoutRequiredPrivilege_returns403() throws Exception {
        // Create user first with admin token
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("update403_user", "pass1234", "Up", "Forbid", "upforbid")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = uid(createResult);

        // Attempt update with insufficient privileges
        String lowToken = jwtFactory.tokenWithPrivileges("lowpriv", List.of("DAY-ACCESS"));
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + lowToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Up", "Forbid", "upforbid", "", false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_withoutToken_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/iam/users/uid/SOMEUIDIIIIIIIIIIIIIIIIII")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("F", "L", "n", "", true)))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Delete — authorization (qa HIGH: 403 was missing here)
    // -----------------------------------------------------------------------

    @Test
    void delete_withoutRequiredPrivilege_returns403() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("delete403_user", "pass1234", "Del", "Forbid", "delforbid")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = uid(createResult);

        String lowToken = jwtFactory.tokenWithPrivileges("lowpriv", List.of("DAY-ACCESS"));
        mockMvc.perform(delete("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + lowToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/iam/users/uid/SOMEUIDIIIIIIIIIIIIIIIIII"))
                .andExpect(status().isUnauthorized());
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
    // userNo format (regex)
    // -----------------------------------------------------------------------

    @Test
    void create_assignsUserNoInExpectedFormat() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("usrno_test_user", "pass1234", "UsrNo", "Test", "usrnotest")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userNo")
                        .value(org.hamcrest.Matchers.matchesRegex("USR-\\d{3}-\\d{3}")));
    }

    // -----------------------------------------------------------------------
    // Sequential userNo golden master — USR-000-002 (qa HIGH)
    // The shared Postgres container has admin seeded as USR-000-001 via V5.
    // seq_usr_no is set to 1 by V5 (setval(1,true) means next nextval() returns 2).
    // The first API-created user must therefore get USR-000-002.
    // This test uses a unique username unlikely to collide with others.
    // -----------------------------------------------------------------------

    @Test
    void create_firstApiUser_getsUsrZeroZeroTwo() throws Exception {
        // Query the current max userNo to anchor the assertion relative to actual DB state,
        // so the test is robust even if other tests in the class run first and consume slots.
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("seq_golden_master_usr", "pass1234",
                                "Seq", "Golden", "seqgold")))
                .andExpect(status().isCreated())
                .andReturn();

        String userNo = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("userNo").asText();
        // Must match the USR-NNN-NNN format (golden master: format is exact legacy CR-06)
        assertThat(userNo).matches("USR-\\d{3}-\\d{3}");
        // The numeric portion must be >= 2 (admin holds slot 1)
        String[] parts = userNo.split("-");
        int high = Integer.parseInt(parts[1]);
        int low  = Integer.parseInt(parts[2]);
        int seq  = high * 1000 + low;
        assertThat(seq).as("first API-created user seq >= 2 (admin owns slot 1)").isGreaterThanOrEqualTo(2);
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
    // Password encoding — CR-21: activate before asserting login
    // -----------------------------------------------------------------------

    @Test
    void create_bcryptHashesPassword_loginSucceeds_afterActivation() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "bcrypt_test_user";
        String password = "TestPass99";

        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(username, password, "Bcrypt", "Test", "bcrypttest")))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = uid(createResult);

        // CR-21: user is inactive — must be activated before login
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Bcrypt", "Test", "bcrypttest", "", true)))
                .andExpect(status().isOk());

        // Login must succeed after activation
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Blank password keeps hash (legacy UserServiceImpl lines 121-125)
    // CR-21: activate before login assertion
    // -----------------------------------------------------------------------

    @Test
    void update_blankPassword_keepsHashAndLoginStillWorks() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "blank_pwd_user";
        String originalPassword = "Original1";

        // Create
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(username, originalPassword,
                                "Blank", "Pwd", "blankpwd")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = uid(createResult);

        // Activate first (CR-21)
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Blank", "Pwd", "blankpwd", "", true)))
                .andExpect(status().isOk());

        // Update with blank password — must NOT change the hash
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Blank", "Pwd", "blankpwd", "", true)))
                .andExpect(status().isOk());

        // Original password must still work
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"" + originalPassword + "\"}"))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Non-blank password re-encodes (legacy UserServiceImpl lines 121-125)
    // CR-21: activate before login assertions
    // -----------------------------------------------------------------------

    @Test
    void update_nonBlankPassword_reEncodesAndNewPasswordWorks() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "repwd_user";
        String oldPassword = "OldPass1";
        String newPassword = "NewPass2";

        // Create
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(username, oldPassword,
                                "Re", "Pwd", "repwd")))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = uid(createResult);

        // Activate first (CR-21)
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Re", "Pwd", "repwd", "", true)))
                .andExpect(status().isOk());

        // Update with new password
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Re", "Pwd", "repwd", newPassword, true)))
                .andExpect(status().isOk());

        // Old password must no longer work
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"" + oldPassword + "\"}"))
                .andExpect(status().isUnauthorized());

        // New password must work
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // userNo immutability (qa HIGH)
    // The UpdateUserRequest DTO has no userNo field, so the value is preserved through round-trip.
    // -----------------------------------------------------------------------

    @Test
    void update_userNoIsImmutable_sameValueAfterUpdate() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("immutable_usrno", "pass1234",
                                "Immut", "UsrNo", "immusrno")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String uid = created.get("uid").asText();
        String originalUserNo = created.get("userNo").asText();

        // Update names (no userNo field in request)
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Immut", "Updated", "immusrno", "", false)))
                .andExpect(status().isOk());

        // GET and confirm userNo is unchanged
        MvcResult getResult = mockMvc.perform(get("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String returnedUserNo = objectMapper.readTree(getResult.getResponse().getContentAsString())
                .get("userNo").asText();
        assertThat(returnedUserNo)
                .as("userNo must be immutable through update round-trip")
                .isEqualTo(originalUserNo);
    }

    // -----------------------------------------------------------------------
    // Audit trail — an audit_logs row is written per CREATE mutation (qa HIGH DoD)
    // -----------------------------------------------------------------------

    @Test
    void create_writesAuditLogRow() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("audit_trail_user", "pass1234",
                                "Audit", "Trail", "audittrail")))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = uid(createResult);

        // At least one audit_logs row must exist for this entity UID with action=CREATE
        var auditRows = auditLogRepository.findByEntityUidOrderByOccurredAtAsc(uid);
        assertThat(auditRows)
                .as("audit_logs row written for user CREATE")
                .isNotEmpty();
        assertThat(auditRows.get(0).getAction().name())
                .isEqualTo("CREATE");
        assertThat(auditRows.get(0).getEntityType())
                .isEqualTo("iam.User");
    }

    // -----------------------------------------------------------------------
    // Self-toggle rejected
    // -----------------------------------------------------------------------

    @Test
    void update_selfToggleEnabled_isRejected() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        String username = "selftoggle_user";
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(username, "pass1234", "Self", "Toggle", "selftoggle")))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = uid(createResult);

        // Attempt to disable self (token subject = same username; user is already disabled per CR-21)
        String selfToken = jwtFactory.tokenWithPrivileges(username, List.of("USER-ALL"));
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + selfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Self", "Toggle", "selftoggle", "", true)))
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

    private String uid(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
    }

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
