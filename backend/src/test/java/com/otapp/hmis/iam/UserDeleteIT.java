package com.otapp.hmis.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * User delete endpoint integration tests (build-spec §7, decision #3).
 */
class UserDeleteIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestJwtFactory jwtFactory;

    @Autowired
    UserRepository userRepository;

    @Test
    void delete_reallyDeletesUser() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        // Create user
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"deletable_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"Delete\",\"lastName\":\"Me\",\"nickname\":\"deleteme\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        // Delete
        mockMvc.perform(delete("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Subsequent GET must be 404
        mockMvc.perform(get("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // And repository confirms it's gone
        Assertions.assertThat(userRepository.findByUid(uid)).isEmpty();
    }

    @Test
    void delete_requiresPrivilege_withoutPriv_returns403() throws Exception {
        String adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nodelete_user\",\"password\":\"pass1234\"," +
                                "\"firstName\":\"No\",\"lastName\":\"Delete\",\"nickname\":\"nodelete\"," +
                                "\"roleNames\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("uid").asText();

        String lowToken = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(delete("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + lowToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_unknownUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));
        mockMvc.perform(delete("/api/v1/iam/users/uid/NONEXISTENTUIDIIIIIIIIIIII")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
