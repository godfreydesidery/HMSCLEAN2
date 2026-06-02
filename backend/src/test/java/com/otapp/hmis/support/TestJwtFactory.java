package com.otapp.hmis.support;

import com.otapp.hmis.iam.config.SecurityConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints HS256 access tokens for integration tests using the application's real {@link JwtEncoder},
 * so token validation in the resource server exercises the production signing path.
 */
@Component
public class TestJwtFactory {

    private final JwtEncoder jwtEncoder;

    public TestJwtFactory(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String tokenWithPrivileges(String username, List<String> privileges) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://hmis.otapp.net")
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .claim(SecurityConfig.PRIVILEGES_CLAIM, privileges)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
