package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.dto.TokenResponse;
import com.otapp.hmis.iam.config.JwtProperties;
import com.otapp.hmis.iam.config.SecurityConfig;
import com.otapp.hmis.iam.domain.RefreshToken;
import com.otapp.hmis.iam.domain.RefreshTokenRepository;
import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.InvalidTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login + refresh-token rotation (ADR-0006).
 *
 * <p>Both {@link #login} and {@link #refresh} mint an access token carrying the {@code privileges}
 * claim — the legacy {@code "roles"}-claim refresh defect is corrected here from day one.
 * Constructor injection is Lombok-generated (DIRECTIVE 1); the {@code clock} defaults to UTC.
 */
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final AuditRecorder auditRecorder;
    private Clock clock = Clock.systemUTC();

    @Transactional
    public TokenResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        if (!user.isEnabled() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        // Audit the authenticated user lookup (ADR-0007). action = CREATE on login.
        auditRecorder.record("iam.User", user.getUid(), AuditAction.CREATE, user.getUsername());
        return issue(user);
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognised"));
        Instant now = Instant.now(clock);
        if (!stored.isUsable(now)) {
            // Reuse / expired-after-revoke detection: revoke all of the user's live tokens.
            refreshTokenRepository.findByUserUidAndRevokedFalse(stored.getUserUid())
                    .forEach(RefreshToken::revoke);
            throw new InvalidTokenException("Refresh token is expired or already used");
        }
        stored.revoke();
        User user = userRepository.findByUid(stored.getUserUid())
                .orElseThrow(() -> new InvalidTokenException("User no longer exists"));
        return issue(user);
    }

    private TokenResponse issue(User user) {
        List<String> privileges = List.copyOf(user.privilegeCodes());
        Instant now = Instant.now(clock);
        Instant accessExpiry = now.plus(jwtProperties.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(accessExpiry)
                .claim("uid", user.getUid())
                // Canonical claim name — ALWAYS "privileges", never "roles" (ADR-0006).
                .claim(SecurityConfig.PRIVILEGES_CLAIM, privileges)
                .build();
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken =
                jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();

        String rawRefresh = randomToken();
        Instant refreshExpiry = now.plus(jwtProperties.refreshTokenTtlHours(), ChronoUnit.HOURS);
        refreshTokenRepository.save(new RefreshToken(user.getUid(), sha256(rawRefresh), refreshExpiry));

        long expiresInSeconds = jwtProperties.accessTokenTtlMinutes() * 60;
        return new TokenResponse(accessToken, rawRefresh, "Bearer", expiresInSeconds, privileges);
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
