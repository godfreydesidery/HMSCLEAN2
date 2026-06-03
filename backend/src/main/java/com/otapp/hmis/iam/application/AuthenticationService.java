package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.IamEvents.RefreshTokenReuseDetectedEvent;
import com.otapp.hmis.iam.application.IamEvents.RefreshTokenRevokedEvent;
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
import com.otapp.hmis.shared.error.TokenReuseException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
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
 * <p>Increment-01 enhancements (build-spec §4):
 * <ul>
 *   <li>Three-way refresh branch: unknown → invalid-token; reused (revoked) → revoke-all +
 *       {@link TokenReuseException}; expired → invalid-token; usable → rotate.
 *   <li>Normal rotation records {@code replacedByUid} on the old token and {@code revokedAt}.
 *   <li>New {@link #revoke(String, String)} method for the explicit revoke endpoint (CR-10).
 * </ul>
 *
 * <p>Both {@link #login} and {@link #refresh} mint an access token carrying the {@code privileges}
 * claim — the legacy {@code "roles"}-claim refresh defect is corrected here from day one.
 * Constructor injection is Lombok-generated (DIRECTIVE 1).
 */
@Slf4j
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
    private final ApplicationEventPublisher eventPublisher;
    private Clock clock = Clock.systemUTC();

    /** Visible for testing — allows injection of a fixed {@link Clock}. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional
    public TokenResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        if (!user.isEnabled() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        auditRecorder.record("iam.User", user.getUid(), AuditAction.CREATE, user.getUsername());
        return issue(user);
    }

    /**
     * Three-way refresh branch (build-spec §4):
     * <ol>
     *   <li>Unknown hash → {@link InvalidTokenException} (401 invalid-token).
     *   <li>Found + revoked==true → reuse detected: revoke all live tokens, audit, publish event,
     *       throw {@link TokenReuseException} (401 token-reuse-detected). Body contains NO hashes.
     *   <li>Found + not revoked + expired → {@link InvalidTokenException}.
     *   <li>Found + usable → rotate: old.replacedByUid=new.uid, old.revokedAt=now, issue new pair.
     * </ol>
     */
    // noRollbackFor: the reuse branch revokes ALL the user's live tokens and THEN throws. Without this,
    // the thrown RuntimeException would roll back the security-critical revoke-all, leaving sibling tokens
    // live (caught by RefreshReuseIT). The exception still propagates to the handler → 401.
    @Transactional(noRollbackFor = TokenReuseException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognised"));

        Instant now = Instant.now(clock);

        // Branch 2: reuse detected (token was already revoked/rotated)
        if (stored.isRevoked()) {
            revokeAllLiveTokens(stored.getUserUid());
            auditRecorder.record("iam.RefreshToken", stored.getUserUid(), AuditAction.DELETE);
            // Publish event — uid only, no hash
            eventPublisher.publishEvent(new RefreshTokenReuseDetectedEvent(stored.getUserUid(), "anonymous"));
            throw new TokenReuseException();
        }

        // Branch 3: expired but not yet revoked
        if (stored.isExpired(now)) {
            stored.revoke();
            throw new InvalidTokenException("Refresh token is expired");
        }

        // Branch 4: usable — rotate
        User user = userRepository.findByUid(stored.getUserUid())
                .orElseThrow(() -> new InvalidTokenException("User no longer exists"));

        // Issue new token pair first so we have the successor UID
        String rawNewRefresh = randomToken();
        Instant refreshExpiry = now.plus(jwtProperties.refreshTokenTtlHours(), ChronoUnit.HOURS);
        RefreshToken newToken = refreshTokenRepository.save(
                new RefreshToken(user.getUid(), sha256(rawNewRefresh), refreshExpiry));

        // Record rotation linkage on the old token, then revoke it
        stored.recordRotation(newToken.getUid());
        stored.revoke();

        // Build access token
        List<String> privileges = List.copyOf(user.privilegeCodes());
        Instant accessExpiry = now.plus(jwtProperties.accessTokenTtlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(accessExpiry)
                .claim("uid", user.getUid())
                .claim(SecurityConfig.PRIVILEGES_CLAIM, privileges)
                .build();
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();

        long expiresInSeconds = jwtProperties.accessTokenTtlMinutes() * 60;
        return new TokenResponse(accessToken, rawNewRefresh, "Bearer", expiresInSeconds, privileges);
    }

    /**
     * Explicit revoke for {@code POST /api/v1/auth/token/revoke} (CR-10, build-spec §2 #15).
     *
     * <p>Ownership check: the token must belong to the caller ({@code authenticatedUsername}).
     * Unknown and already-revoked tokens both return silently (idempotent, non-enumerating — 204).
     *
     * @throws AccessDeniedException if the token belongs to a different user.
     */
    @Transactional
    public void revoke(String rawRefreshToken, String authenticatedUsername) {
        String hash = sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            // Ownership check: token's user must match JWT subject
            User tokenOwner = userRepository.findByUid(token.getUserUid()).orElse(null);
            if (tokenOwner == null || !tokenOwner.getUsername().equals(authenticatedUsername)) {
                throw new AccessDeniedException("Cannot revoke a token belonging to another user");
            }
            if (!token.isRevoked()) {
                token.revoke();
                auditRecorder.record("iam.RefreshToken", token.getUserUid(), AuditAction.DELETE,
                        authenticatedUsername);
                eventPublisher.publishEvent(new RefreshTokenRevokedEvent(token.getUserUid(), authenticatedUsername));
            }
            // Already revoked → no-op (idempotent)
        });
        // Unknown token → no-op (non-enumerating)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

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

    private void revokeAllLiveTokens(String userUid) {
        refreshTokenRepository.findByUserUidAndRevokedFalse(userUid)
                .forEach(RefreshToken::revoke);
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
