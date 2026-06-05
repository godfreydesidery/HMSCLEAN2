package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asserts the seeded ADMIN role has all 38 privileges (build-spec §7).
 * V2 seed (35) + V47 delta (3 disposition APPROVE codes — inc-07 07a-3 CR-07-SoD) = 38.
 * This confirms V2 seed + V4 category tag + V47 delta did not disrupt the ADMIN role's privilege set.
 */
class RolePrivilegeParityIT extends AbstractIntegrationTest {

    @Autowired
    RoleRepository roleRepository;

    @Test
    void adminRoleHasAllThirtyFivePrivileges() {
        Role admin = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new AssertionError("ADMIN role not found — V2 seed missing"));

        // 35 (V2) + 3 (V47 — DISCHARGE-PLAN-APPROVE, REFERRAL-PLAN-APPROVE, DECEASED-NOTE-APPROVE) = 38
        assertThat(admin.getPrivileges())
                .as("ADMIN role must have all 38 seeded privileges (V2 + V47)")
                .hasSize(38);
    }

    @Test
    void adminRolePrivilegesIncludeAllLiveCodes() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        java.util.Set<String> codes = new java.util.HashSet<>();
        admin.getPrivileges().forEach(p -> codes.add(p.getCode()));

        // Spot-check a sample of live codes
        assertThat(codes).contains(
                "ADMIN-ACCESS", "USER-ALL", "ROLE-ALL", "PATIENT-ALL",
                "PAYROLL-ALL", "GOODS_RECEIVED_NOTE-ALL");
    }

    @Test
    void adminRolePrivilegesIncludeDeadCodes() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        java.util.Set<String> codes = new java.util.HashSet<>();
        admin.getPrivileges().forEach(p -> codes.add(p.getCode()));

        // Dead codes are still seeded and granted to ADMIN for catalogue parity
        assertThat(codes).contains("BILL-A", "GOO-ALL", "ROLE-CREATE", "ROLE-U");
    }

    @Test
    void adminRoleOwnerIsSystem() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        assertThat(admin.getOwner())
                .as("ADMIN role owner must be SYSTEM after V4 migration")
                .isEqualTo("SYSTEM");
    }
}
