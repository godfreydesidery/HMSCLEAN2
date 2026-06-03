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
 * <p>Increment-01 additions:
 * <ul>
 *   <li>Asserts exactly 35 codes match the golden-master fixture {@code expected-privilege-codes.txt}.
 *   <li>Asserts the 9 dead codes have {@code category='DEAD'} and the 26 live codes are {@code 'ACTIVE'}.
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
        assertThat(fixture).as("fixture has exactly 35 codes").hasSize(35);

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
        assertThat(activePrivileges).as("exactly 26 ACTIVE privileges").hasSize(26);

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
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V2__seed_iam.sql")) {
            assertThat(in).as("V2 migration is on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("INSERT INTO privileges");
            assertThat(start).as("privilege INSERT block exists").isGreaterThanOrEqualTo(0);
            int end = sql.indexOf(';', start);
            String block = sql.substring(start, end);
            Pattern pattern = Pattern.compile("'[0-9A-Z]{26}',\\s*'([A-Z0-9_\\-]+)'\\s*,\\s*now\\(\\)");
            Matcher matcher = pattern.matcher(block);
            while (matcher.find()) {
                codes.add(matcher.group(1));
            }
        }
        return codes;
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
