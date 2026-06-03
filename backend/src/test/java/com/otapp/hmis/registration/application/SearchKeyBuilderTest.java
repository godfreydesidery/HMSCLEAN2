package com.otapp.hmis.registration.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Byte-verbatim golden-master for {@link SearchKeyBuilder} (legacy createSearchKey +
 * Sanitizer.sanitizeString — build-spec §2.2, CR-09).
 */
class SearchKeyBuilderTest {

    @Test
    void composesFiveFields_casePreserved() {
        // case is PRESERVED (NOT lowercased — the planning-doc lowercase is DRIFT)
        assertThat(SearchKeyBuilder.build("MRNO/2026/7", "John", "M", "Doe", "0712345678"))
                .isEqualTo("MRNO/2026/7 John M Doe 0712345678");
        assertThat(SearchKeyBuilder.build("MRNO/2026/7", "JOHN", "", "DOE", ""))
                .isEqualTo("MRNO/2026/7 JOHN DOE");
    }

    @Test
    void collapsesWhitespace_whenOptionalFieldsBlank() {
        assertThat(SearchKeyBuilder.build("X", "John", "", "Doe", "072"))
                .isEqualTo("X John Doe 072");
    }

    @Test
    void nullField_concatenatedAsLiteralNull_perLegacy() {
        // legacy plain '+' concat turns null into the literal "null" — reproduced verbatim
        assertThat(SearchKeyBuilder.build("X", "John", null, "Doe", "072"))
                .isEqualTo("X John null Doe 072");
    }

    @Test
    void hashDollarPercentAmp_NOT_stripped_dollarIsRegexAnchor() {
        // LATENT BUG (reproduced verbatim): the legacy regex "[+^]*#$%&" intends to strip "#$%&"
        // but the '$' is a regex END-OF-LINE ANCHOR, so the pattern can never match mid-string —
        // it strips NOTHING. Faithful reproduction therefore PRESERVES "#$%&" in the key.
        assertThat(SearchKeyBuilder.build("X", "Jo#$%&hn", "", "Doe", ""))
                .isEqualTo("X Jo#$%&hn Doe");
    }

    @Test
    void plusSign_replacedWithSpace_bySanitizer() {
        assertThat(SearchKeyBuilder.build("X", "Jo+hn", "", "Doe", ""))
                .isEqualTo("X Jo hn Doe");
    }
}
