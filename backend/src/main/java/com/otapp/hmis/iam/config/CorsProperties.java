package com.otapp.hmis.iam.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS origin allow-list (build-spec §4 D-1, ADR-0013).
 *
 * <p>Bound from {@code hmis.cors.allowed-origins} (env var {@code CORS_ALLOWED_ORIGINS});
 * defaults to {@code http://localhost:4200} for local development. Replaces the legacy
 * wildcard {@code addAllowedOriginPattern("*")} in {@link SecurityConfig}.
 *
 * <p>Note: {@code allowCredentials} is deliberately NOT enabled — bearer tokens are sent in
 * the {@code Authorization} header, not as cookies, so credentials mode is unnecessary.
 */
@ConfigurationProperties(prefix = "hmis.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:4200");
        }
    }
}
