package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.iam.domain.ClinicianRepository;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for clinic–clinician affiliation (CR-08, build-spec §5.2, AC-4).
 *
 * <p>Seeding mirrors {@code PersonnelExtensionLifecycleIT}: the {@code CLINICIAN} role is seeded
 * directly via {@link RoleRepository} (bypassing the reserved-name API guard) so a CLINICIAN user
 * can be created via the standard user admin API.
 *
 * <p>Covers all AC-4 acceptance criteria:
 * <ul>
 *   <li>Affiliate CLINICIAN user → 201, GET includes them.
 *   <li>Affiliate non-CLINICIAN user → 403 {@code urn:hmis:error:clinician-role-required}.
 *   <li>Affiliate without ADMIN-ACCESS token → 403 (Spring Security @PreAuthorize gate).
 *   <li>Affiliate without token → 401.
 *   <li>DELETE affiliation → 204; GET no longer includes the user.
 *   <li>Idempotent re-DELETE → 204.
 *   <li>GET /masterdata/clinicians/by-role/CLINICIAN → includes the clinician user (admin-gated).
 * </ul>
 */
class ClinicClinicianAffiliationIT extends AbstractIntegrationTest {

    private static final String CLINICS_BASE = "/api/v1/masterdata/clinics";
    private static final String USERS_BASE = "/api/v1/iam/users";
    private static final String CLINICIANS_BY_ROLE = "/api/v1/masterdata/clinicians/by-role/CLINICIAN";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClinicianRepository clinicianRepository;

    @BeforeEach
    void seedReservedRoles() {
        if (roleRepository.findByName("CLINICIAN").isEmpty()) {
            roleRepository.save(new Role("CLINICIAN", "SYSTEM"));
        }
    }

    @Test
    void affiliate_clinicianUser_returns201_andGetIncludesThem() throws Exception {
        String adminToken = adminToken();

        String clinicUid = createClinic(adminToken, "AFF-CLINIC-01", "Affiliation Clinic 01");
        String clinicianUserUid = createClinicianUser(adminToken, "aff_clinician_01", "aff01");

        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUserUid + "\"}"))
                .andExpect(status().isCreated());

        MvcResult listResult = mockMvc.perform(get(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(list.isArray()).isTrue();
        boolean found = false;
        for (JsonNode node : list) {
            if (clinicianUserUid.equals(node.path("uid").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Affiliated clinician user must appear in GET list").isTrue();
    }

    @Test
    void affiliate_nonClinicianUser_returns403_withClinicianRoleRequiredType() throws Exception {
        String adminToken = adminToken();

        String clinicUid = createClinic(adminToken, "AFF-CLINIC-02", "Affiliation Clinic 02");
        String plainUserUid = createPlainUser(adminToken, "aff_plain_02", "plain02");

        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + plainUserUid + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:clinician-role-required"));
    }

    @Test
    void affiliate_withoutAdminAccess_returns403() throws Exception {
        String adminToken = adminToken();
        String clinicUid = createClinic(adminToken, "AFF-CLINIC-03", "Affiliation Clinic 03");
        String clinicianUserUid = createClinicianUser(adminToken, "aff_clinician_03", "aff03");

        String nonAdminToken = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUserUid + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void affiliate_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(CLINICS_BASE + "/uid/SOMECLINICUID0000000000000/clinicians")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"SOMEUSERUID000000000000000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteAffiliation_returns204_andGetNoLongerIncludesUser() throws Exception {
        String adminToken = adminToken();

        String clinicUid = createClinic(adminToken, "AFF-CLINIC-04", "Affiliation Clinic 04");
        String clinicianUserUid = createClinicianUser(adminToken, "aff_clinician_04", "aff04");

        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUserUid + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians/" + clinicianUserUid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        MvcResult listResult = mockMvc.perform(get(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode node : list) {
            if (clinicianUserUid.equals(node.path("uid").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Removed clinician user must NOT appear in GET list").isFalse();
    }

    @Test
    void deleteAffiliation_idempotent_secondDeleteReturns204() throws Exception {
        String adminToken = adminToken();

        String clinicUid = createClinic(adminToken, "AFF-CLINIC-05", "Affiliation Clinic 05");
        String clinicianUserUid = createClinicianUser(adminToken, "aff_clinician_05", "aff05");

        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUserUid + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians/" + clinicianUserUid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians/" + clinicianUserUid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void listAllClinicians_byRole_returnsClinician_adminGated() throws Exception {
        String adminToken = adminToken();

        String clinicianUserUid = createClinicianUser(adminToken, "aff_clinician_06", "aff06");

        MvcResult result = mockMvc.perform(get(CLINICIANS_BY_ROLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode node : list) {
            if (clinicianUserUid.equals(node.path("uid").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("CLINICIAN user must appear in by-role listing").isTrue();
    }

    @Test
    void listAllClinicians_byRole_withoutAdminAccess_returns403() throws Exception {
        String nonAdminToken = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(get(CLINICIANS_BY_ROLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAllClinicians_byRole_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(CLINICIANS_BY_ROLE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void affiliate_idempotent_secondPostReturns201() throws Exception {
        String adminToken = adminToken();

        String clinicUid = createClinic(adminToken, "AFF-CLINIC-07", "Affiliation Clinic 07");
        String clinicianUserUid = createClinicianUser(adminToken, "aff_clinician_07", "aff07");

        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUserUid + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"" + clinicianUserUid + "\"}"))
                .andExpect(status().isCreated());

        MvcResult listResult = mockMvc.perform(get(CLINICS_BASE + "/uid/" + clinicUid + "/clinicians")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        long count = 0;
        for (JsonNode node : list) {
            if (clinicianUserUid.equals(node.path("uid").asText())) {
                count++;
            }
        }
        assertThat(count).as("Idempotent affiliates must result in exactly one entry").isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String adminToken() {
        return jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS", "USER-ALL"));
    }

    /** Creates a clinic via the masterdata API and returns its uid. */
    private String createClinic(String token, String code, String name) throws Exception {
        String body = "{\"code\":\"%s\",\"name\":\"%s\",\"description\":\"test\",\"consultationFee\":1000.00,\"active\":false}"
                .formatted(code, name);
        MvcResult result = mockMvc.perform(post(CLINICS_BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Creates a user with the CLINICIAN role via the IAM admin API and returns their uid.
     * This triggers the Clinician personnel extension lifecycle (UserAdminService).
     */
    private String createClinicianUser(String token, String username, String nickname) throws Exception {
        String body = """
                {"username":"%s","password":"pass1234",
                 "firstName":"Test","lastName":"Clinician","nickname":"%s",
                 "roleNames":["CLINICIAN"]}
                """.formatted(username, nickname);
        MvcResult result = mockMvc.perform(post(USERS_BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Creates a plain user with no roles and returns their uid.
     * Used to verify the CLINICIAN-role business gate rejects non-clinicians.
     */
    private String createPlainUser(String token, String username, String nickname) throws Exception {
        String body = """
                {"username":"%s","password":"pass1234",
                 "firstName":"Plain","lastName":"User","nickname":"%s",
                 "roleNames":[]}
                """.formatted(username, nickname);
        MvcResult result = mockMvc.perform(post(USERS_BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
    }
}
