package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.iam.domain.Privilege;
import com.otapp.hmis.iam.domain.PrivilegeRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asserts the privilege seed is complete and correctly categorised (build-spec §7).
 *
 * <p>Increment-01 seed: 35 codes (V2). Increment-07a-3 delta: +3 APPROVE codes (V47).
 * Increment-07d delta: +1 MEDICATION-ADMINISTER code (V52, CR-07-MAR) → 39 total.
 * <ul>
 *   <li>Asserts exactly 39 codes match the golden-master fixture {@code expected-privilege-codes.txt}.
 *   <li>Asserts the 9 dead codes have {@code category='DEAD'} and the 30 live codes are {@code 'ACTIVE'}.
 * </ul>
 */
class PrivilegeSeedIT extends AbstractIntegrationTest {

    static final Set<String> DEAD_CODES = Set.of(
            "BILL-A", "GOO-ALL", "PATIENT-A", "PATIENT-C", "PATIENT-U",
            "PROCUREMENT-ACCESS", "PRODUCT-CREATE", "ROLE-CREATE", "ROLE-U");

    @Autowired
    PrivilegeRepository privilegeRepository;

    @Test
    void allSeededPrivilegeCodesArePresent() throws IOException {
        Set<String> expected = expectedCodesFromMigration();
        assertThat(expected)
                .as("the migration parser found the seeded codes")
                .isNotEmpty();

        List<String> actual = privilegeRepository.findAllByOrderByCodeAsc().stream()
                .map(Privilege::getCode)
                .toList();

        assertThat(actual)
                .as("every seeded privilege code is persisted")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void exactlyThirtyFiveCodesMatchFixture() throws IOException {
        List<String> fixture = fixtureLines();
        assertThat(fixture).as("fixture has exactly 39 codes (35 V2 + 3 V47 + 1 V52)").hasSize(39);

        List<String> actual = privilegeRepository.findAllByOrderByCodeAsc().stream()
                .map(Privilege::getCode)
                .toList();

        assertThat(actual)
                .as("DB codes match golden-master fixture exactly")
                .containsExactlyInAnyOrderElementsOf(fixture);
    }

    @Test
    void nineDeadCodesHaveCategoryDead() {
        List<Privilege> deadPrivileges = privilegeRepository.findByCategory("DEAD");
        assertThat(deadPrivileges).as("exactly 9 DEAD privileges").hasSize(9);

        Set<String> deadCodes = new java.util.HashSet<>();
        deadPrivileges.forEach(p -> deadCodes.add(p.getCode()));
        assertThat(deadCodes).as("dead codes match spec §1").isEqualTo(DEAD_CODES);
    }

    @Test
    void twentySixActiveCodesHaveCategoryActive() {
        List<Privilege> activePrivileges = privilegeRepository.findByCategory("ACTIVE");
        // 26 original active codes (V2) + 3 disposition APPROVE codes (V47) + 1 MAR code (V52) = 30 ACTIVE
        assertThat(activePrivileges).as("exactly 30 ACTIVE privileges (26 original + 3 V47 + 1 V52)").hasSize(30);

        // None of the active codes should be in the dead set
        activePrivileges.forEach(p ->
                assertThat(DEAD_CODES).as("active code not in dead set: " + p.getCode())
                        .doesNotContain(p.getCode()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Set<String> expectedCodesFromMigration() throws IOException {
        Set<String> codes = new LinkedHashSet<>();
        // V2: original 35 privilege codes
        parsePrivilegesFromMigration("/db/migration/V2__seed_iam.sql", codes);
        // V47: 3 disposition APPROVE codes added in inc-07 07a-3 (CR-07-SoD)
        parsePrivilegesFromMigration("/db/migration/V47__iam_disposition_approve_privileges.sql", codes);
        // V52: MEDICATION-ADMINISTER added in inc-07 07d (CR-07-MAR)
        parsePrivilegesFromMigration("/db/migration/V52__iam_medication_administer_privilege.sql", codes);
        return codes;
    }

    private void parsePrivilegesFromMigration(String classpathPath, Set<String> codes) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(classpathPath)) {
            if (in == null) {
                return; // migration not present — skip gracefully
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("INSERT INTO privileges");
            if (start < 0) {
                return; // no privilege INSERT in this file
            }
            int end = sql.indexOf(';', start);
            String block = sql.substring(start, end);
            Pattern pattern = Pattern.compile("'[0-9A-Z]{26}',\\s*'([A-Z0-9_\\-]+)'\\s*,\\s*now\\(\\)");
            Matcher matcher = pattern.matcher(block);
            while (matcher.find()) {
                codes.add(matcher.group(1));
            }
        }
    }

    private List<String> fixtureLines() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/expected-privilege-codes.txt")) {
            assertThat(in).as("expected-privilege-codes.txt fixture exists on classpath").isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return content.lines()
                    .map(String::strip)
                    .filter(l -> !l.isBlank())
                    .toList();
        }
    }
}
