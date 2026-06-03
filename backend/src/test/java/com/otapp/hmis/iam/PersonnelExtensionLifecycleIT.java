package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.iam.domain.CashierRepository;
import com.otapp.hmis.iam.domain.ClinicianRepository;
import com.otapp.hmis.iam.domain.ManagementRepository;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
/**
 * Personnel extension lifecycle integration tests (build-spec §7, 07-DECISIONS-RATIFIED §C).
 *
 * <p>The 15 reserved role names cannot be created via {@code POST /iam/roles} (the reserved-name
 * guard blocks them). This test seeds CLINICIAN, CASHIER, NURSE, and MANAGER directly via
 * {@link RoleRepository}, bypassing the API guard, then asserts the full positive-path lifecycle:
 * create with roles → extensions active; remove role → extension deactivated (AMB-5 symmetric);
 * MANAGER triggers Management (AMB-2); one-per-type enforced.
 *
 * <p>CR-21: newly-created users are inactive. Extensions are still created on the inactive user;
 * no activation is needed to verify the extension state at the repository level.
 */
class PersonnelExtensionLifecycleIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ClinicianRepository clinicianRepository;
    @Autowired CashierRepository cashierRepository;
    @Autowired ManagementRepository managementRepository;

    /**
     * Seed reserved role rows directly via the repository before each test that needs them.
     * Uses saveIfAbsent to be safe across the shared Testcontainers instance.
     */
    @BeforeEach
    void seedReservedRoles() {
        seedRoleIfAbsent("CLINICIAN");
        seedRoleIfAbsent("CASHIER");
        seedRoleIfAbsent("NURSE");
        seedRoleIfAbsent("MANAGER");
    }

    private void seedRoleIfAbsent(String name) {
        if (roleRepository.findByName(name).isEmpty()) {
            // owner=SYSTEM marks seeded/reserved roles; bypasses the API guard entirely.
            roleRepository.save(new Role(name, "SYSTEM"));
        }
    }

    // -----------------------------------------------------------------------
    // Positive path: CLINICIAN + CASHIER both created active
    // -----------------------------------------------------------------------

    @Test
    void createWithClinicianAndCashier_bothExtensionsActive() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ext_clin_cash","password":"pass1234",
                                 "firstName":"Ext","lastName":"ClinicianCashier","nickname":"excc",
                                 "roleNames":["CLINICIAN","CASHIER"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = bodyUid(result);
        User user = userRepository.findByUid(uid).orElseThrow();

        // Both extensions must exist and be active (CR-21: user is inactive; extensions still created)
        assertThat(clinicianRepository.findByUser(user))
                .as("clinician extension created")
                .isPresent()
                .get()
                .extracting(com.otapp.hmis.iam.domain.Clinician::isActive)
                .isEqualTo(true);

        assertThat(cashierRepository.findByUser(user))
                .as("cashier extension created")
                .isPresent()
                .get()
                .extracting(com.otapp.hmis.iam.domain.Cashier::isActive)
                .isEqualTo(true);
    }

    // -----------------------------------------------------------------------
    // AMB-5: removing CASHIER role deactivates only cashier; clinician stays active
    // -----------------------------------------------------------------------

    @Test
    void removingCashierRole_deactivatesCashierOnly_clinicianRemainsActive() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        // Create with both roles
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ext_deactivate_cash","password":"pass1234",
                                 "firstName":"Ext","lastName":"DeactivateCash","nickname":"exdc",
                                 "roleNames":["CLINICIAN","CASHIER"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = bodyUid(createResult);

        // Update: remove CASHIER, keep CLINICIAN
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ext","lastName":"DeactivateCash","nickname":"exdc",
                                 "password":"","enabled":false,"roleNames":["CLINICIAN"]}
                                """))
                .andExpect(status().isOk());

        User user = userRepository.findByUid(uid).orElseThrow();

        assertThat(cashierRepository.findByUser(user))
                .as("cashier extension must be deactivated after role removal (AMB-5)")
                .isPresent()
                .get()
                .extracting(com.otapp.hmis.iam.domain.Cashier::isActive)
                .isEqualTo(false);

        assertThat(clinicianRepository.findByUser(user))
                .as("clinician extension must remain active")
                .isPresent()
                .get()
                .extracting(com.otapp.hmis.iam.domain.Clinician::isActive)
                .isEqualTo(true);
    }

    // -----------------------------------------------------------------------
    // AMB-2: MANAGER triggers Management extension (not MANAGEMENT)
    // -----------------------------------------------------------------------

    @Test
    void createWithManagerRole_managementExtensionActive() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ext_manager_user","password":"pass1234",
                                 "firstName":"Ext","lastName":"Manager","nickname":"exmgr",
                                 "roleNames":["MANAGER"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = bodyUid(result);
        User user = userRepository.findByUid(uid).orElseThrow();

        assertThat(managementRepository.findByUser(user))
                .as("Management extension triggered by MANAGER role (AMB-2)")
                .isPresent()
                .get()
                .extracting(com.otapp.hmis.iam.domain.Management::isActive)
                .isEqualTo(true);
    }

    // -----------------------------------------------------------------------
    // One-per-type: re-assigning same role does not create a duplicate row
    // -----------------------------------------------------------------------

    @Test
    void reassigningSameRole_doesNotCreateDuplicateExtension() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        // Create with CLINICIAN
        MvcResult createResult = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ext_one_per_type","password":"pass1234",
                                 "firstName":"Ext","lastName":"OnePerType","nickname":"exopt",
                                 "roleNames":["CLINICIAN"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = bodyUid(createResult);

        // Update with CLINICIAN again (no-op role change)
        mockMvc.perform(put("/api/v1/iam/users/uid/" + uid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ext","lastName":"OnePerType","nickname":"exopt",
                                 "password":"","enabled":false,"roleNames":["CLINICIAN"]}
                                """))
                .andExpect(status().isOk());

        User user = userRepository.findByUid(uid).orElseThrow();

        // Exactly one clinician row per user: findByUser returns Optional, so Spring Data throws
        // IncorrectResultSizeDataAccessException if there were >1, and the DB UNIQUE(user_id) enforces
        // it at the schema level (AMB-1). isPresent() therefore proves exactly one. (Matched via the FK
        // query, not User.equals / a lazy c.getUser() walk, which would need an open session.)
        assertThat(clinicianRepository.findByUser(user))
                .as("one-per-type: exactly one clinician row per user")
                .isPresent();
    }

    // -----------------------------------------------------------------------
    // Negative path: ADMIN role does not trigger any extension
    // -----------------------------------------------------------------------

    @Test
    void createWithAdminRole_noExtensionCreated() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ext_admin_user","password":"pass1234",
                                 "firstName":"Ext","lastName":"Admin","nickname":"extadmin",
                                 "roleNames":["ADMIN"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = bodyUid(result);
        User user = userRepository.findByUid(uid).orElseThrow();

        assertThat(clinicianRepository.findByUser(user)).isEmpty();
        assertThat(cashierRepository.findByUser(user)).isEmpty();
        assertThat(managementRepository.findByUser(user)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Negative path: no roles → no extensions
    // -----------------------------------------------------------------------

    @Test
    void createWithNoRoles_noExtensionsCreated() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("USER-ALL"));

        MvcResult result = mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ext_noroles_user","password":"pass1234",
                                 "firstName":"Ext","lastName":"Noroles","nickname":"extnoroles",
                                 "roleNames":[]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = bodyUid(result);
        User user = userRepository.findByUid(uid).orElseThrow();

        assertThat(clinicianRepository.findByUser(user)).isEmpty();
        assertThat(cashierRepository.findByUser(user)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private String bodyUid(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("uid").asText();
    }
}
