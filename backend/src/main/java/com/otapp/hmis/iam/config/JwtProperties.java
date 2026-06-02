package com.otapp.hmis.iam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing configuration (ADR-0006, ADR-0013). The secret is bound from {@code ${JWT_SECRET}}
 * in {@code application.yml} — never a literal in source (the hardcoded-secret grep gate enforces this).
 *
 * @param secret               base64-or-raw HS256 signing key (&gt;= 256 bit)
 * @param issuer               token issuer
 * @param accessTokenTtlMinutes access-token lifetime in minutes (ADR-0006: 15)
 * @param refreshTokenTtlHours  refresh-token lifetime in hours (ADR-0006: 8)
 */
@ConfigurationProperties(prefix = "hmis.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        long accessTokenTtlMinutes,
        long refreshTokenTtlHours) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "https://hmis.otapp.net";
        }
        if (accessTokenTtlMinutes <= 0) {
            accessTokenTtlMinutes = 15;
        }
        if (refreshTokenTtlHours <= 0) {
            refreshTokenTtlHours = 8;
        }
    }
}
