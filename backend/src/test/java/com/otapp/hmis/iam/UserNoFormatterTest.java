package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.otapp.hmis.iam.application.UserNoFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Golden-master verification of {@link UserNoFormatter} (build-spec §7, CR-06).
 *
 * <p>Reproduces the legacy {@code Formater.formatSix} + "USR-" prefix behaviour
 * (legacy: {@code com.orbix.api.accessories.Formater.formatSix} lines 36-52).
 */
class UserNoFormatterTest {

    @ParameterizedTest(name = "format({0}) = {1}")
    @CsvSource({
            "1,      USR-000-001",
            "2,      USR-000-002",
            "1234,   USR-001-234",
            "999999, USR-999-999",
            "100,    USR-000-100",
            "999,    USR-000-999",
            "1000,   USR-001-000",
    })
    void goldenMaster(long input, String expected) {
        assertThat(UserNoFormatter.format(input)).isEqualTo(expected);
    }

    @Test
    void formatOneIsUsrZeroZeroOne() {
        assertThat(UserNoFormatter.format(1)).isEqualTo("USR-000-001");
    }

    @Test
    void formatTwoIsUsrZeroZeroTwo() {
        assertThat(UserNoFormatter.format(2)).isEqualTo("USR-000-002");
    }

    @Test
    void format1234IsUsrZeroOneTwo34() {
        assertThat(UserNoFormatter.format(1234)).isEqualTo("USR-001-234");
    }

    @Test
    void format999999IsUsrNineNineNine() {
        assertThat(UserNoFormatter.format(999_999)).isEqualTo("USR-999-999");
    }

    @Test
    void formatAlwaysHasUsrPrefix() {
        assertThat(UserNoFormatter.format(42)).startsWith("USR-");
    }

    @Test
    void formatAlwaysHasTwoHyphensAfterPrefix() {
        String result = UserNoFormatter.format(42);
        // USR-NNN-NNN: total 11 chars
        assertThat(result).hasSize(11);
        assertThat(result.charAt(7)).isEqualTo('-');
    }

    @Test
    void zeroIsOutOfRange() {
        assertThatThrownBy(() -> UserNoFormatter.format(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneMillion_isOutOfRange() {
        assertThatThrownBy(() -> UserNoFormatter.format(1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
