package com.otapp.hmis.iam.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Asserts the production password encoder is BCrypt strength 12 (ADR-0006, build-spec §7).
 *
 * <p>Pure unit test: it invokes the real {@link SecurityConfig#passwordEncoder()} bean method
 * directly (same package → package-private access) so it neither boots a Spring context nor needs a
 * database. The constructor args are irrelevant to {@code passwordEncoder()} and are passed as null.
 */
class BCryptPasswordEncoderTest {

    private final PasswordEncoder passwordEncoder = new SecurityConfig(null, null).passwordEncoder();

    @Test
    void encoderStrengthIsExactlyTwelve() {
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
        // BCrypt embeds its cost in the hash; strength 12 → "$2a$12$".
        assertThat(passwordEncoder.encode("test"))
                .as("BCrypt hash must use cost 12")
                .contains("$2a$12$");
    }

    @Test
    void encodedPasswordMatchesOriginal() {
        String raw = "my-secure-password";
        assertThat(passwordEncoder.matches(raw, passwordEncoder.encode(raw))).isTrue();
    }

    @Test
    void differentRawPasswordDoesNotMatch() {
        String encoded = passwordEncoder.encode("correct-password");
        assertThat(passwordEncoder.matches("wrong-password", encoded)).isFalse();
    }
}
