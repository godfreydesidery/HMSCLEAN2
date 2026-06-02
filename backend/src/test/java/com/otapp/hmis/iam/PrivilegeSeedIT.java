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
 * Asserts every privilege code seeded by {@code V2__seed_iam.sql} is present in
 * {@code privileges} after migration (ADR-0006). The expected set is parsed from the migration
 * itself — not a hardcoded test array — so future additions extend coverage automatically.
 */
class PrivilegeSeedIT extends AbstractIntegrationTest {

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

    private Set<String> expectedCodesFromMigration() throws IOException {
        Set<String> codes = new LinkedHashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V2__seed_iam.sql")) {
            assertThat(in).as("V2 migration is on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Scope strictly to the privileges INSERT block so role/user names are not captured.
            int start = sql.indexOf("INSERT INTO privileges");
            assertThat(start).as("privilege INSERT block exists").isGreaterThanOrEqualTo(0);
            int end = sql.indexOf(';', start);
            String block = sql.substring(start, end);
            // Each privilege row: ('<uid>', '<CODE>', now(), 0)
            Pattern pattern = Pattern.compile("'[0-9A-Z]{26}',\\s*'([A-Z0-9_\\-]+)'\\s*,\\s*now\\(\\)");
            Matcher matcher = pattern.matcher(block);
            while (matcher.find()) {
                codes.add(matcher.group(1));
            }
        }
        return codes;
    }
}
