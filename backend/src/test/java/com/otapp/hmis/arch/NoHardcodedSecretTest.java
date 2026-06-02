package com.otapp.hmis.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural mirror of the CI hardcoded-secret release gate (ADR-0013 §3): zero legacy auth0-style
 * HMAC signing literals may survive under {@code src/}. The forbidden token is assembled by
 * concatenation below so the scan stays clean against this file itself.
 */
class NoHardcodedSecretTest {

    private static final String FORBIDDEN = "HMAC" + "256";

    @Test
    void noHmac256LiteralInSources() throws IOException {
        Path src = Path.of("src");
        List<String> offenders = new ArrayList<>();
        if (Files.exists(src)) {
            try (Stream<Path> paths = Files.walk(src)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".java") || name.endsWith(".yml")
                                    || name.endsWith(".yaml") || name.endsWith(".properties");
                        })
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p, StandardCharsets.UTF_8);
                                if (content.contains(FORBIDDEN)) {
                                    offenders.add(p.toString());
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException("Unable to read " + p, e);
                            }
                        });
            }
        }
        assertThat(offenders)
                .as("ADR-0013 §3: no hardcoded JWT signing literal may survive under src/")
                .isEmpty();
    }
}
