package com.otapp.hmis.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.otapp.hmis.iam.application.AuthenticationService;
import com.otapp.hmis.iam.config.JwtProperties;
import com.otapp.hmis.iam.domain.RefreshToken;
import com.otapp.hmis.iam.domain.RefreshTokenRepository;
import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.shared.audit.AuditLog;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.InvalidTokenException;
import com.otapp.hmis.shared.error.TokenReuseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Three-way refresh branch unit tests with a fixed {@link Clock} (build-spec §7).
 */
class AuthenticationServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-03T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
    private AuditRecorder auditRecorder;
    private ApplicationEventPublisher eventPublisher;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        jwtEncoder = mock(org.springframework.security.oauth2.jwt.JwtEncoder.class);
        auditRecorder = mock(AuditRecorder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4); // fast for tests
        JwtProperties props = new JwtProperties(
                "test-secret-that-is-long-enough-for-hmac256-signing", "test", 15, 8);

        var mockJwt = mock(org.springframework.security.oauth2.jwt.Jwt.class);
        when(mockJwt.getTokenValue()).thenReturn("mock.access.token");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt);
        when(auditRecorder.record(anyString(), anyString(), any(), anyString()))
                .thenReturn(mock(AuditLog.class));
        when(auditRecorder.record(anyString(), anyString(), any()))
                .thenReturn(mock(AuditLog.class));

        service = new AuthenticationService(
                userRepository, refreshTokenRepository, passwordEncoder,
                jwtEncoder, props, auditRecorder, eventPublisher);
        service.setClock(FIXED_CLOCK);
    }

    // -----------------------------------------------------------------------
    // Branch 1: unknown token → InvalidTokenException
    // -----------------------------------------------------------------------

    @Test
    void refresh_unknownToken_throwsInvalidToken() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("unknown-raw-token"))
                .isInstanceOf(InvalidTokenException.class);
    }

    // -----------------------------------------------------------------------
    // Branch 2: revoked token → TokenReuseException + all live tokens revoked
    // -----------------------------------------------------------------------

    @Test
    void refresh_reuseDetected_throwsTokenReuseException() {
        RefreshToken revoked = new RefreshToken("user-uid-123", "hash", FIXED_NOW.plusSeconds(3600));
        revoked.revoke();
        String reuseHash = AuthenticationService.sha256("already-used-token");

        when(refreshTokenRepository.findByTokenHash(reuseHash)).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByUserUidAndRevokedFalse("user-uid-123"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.refresh("already-used-token"))
                .isInstanceOf(TokenReuseException.class);
    }

    @Test
    void refresh_reuseDetected_revokesAllLiveTokens() {
        RefreshToken liveToken = new RefreshToken(
                "user-uid-456", "other-hash", FIXED_NOW.plusSeconds(3600));
        RefreshToken revokedToken = new RefreshToken(
                "user-uid-456", "revoked-hash", FIXED_NOW.plusSeconds(3600));
        revokedToken.revoke();

        String reuseHash = AuthenticationService.sha256("reused-raw");
        when(refreshTokenRepository.findByTokenHash(reuseHash)).thenReturn(Optional.of(revokedToken));
        when(refreshTokenRepository.findByUserUidAndRevokedFalse("user-uid-456"))
                .thenReturn(List.of(liveToken));

        assertThatThrownBy(() -> service.refresh("reused-raw"))
                .isInstanceOf(TokenReuseException.class);

        assertThat(liveToken.isRevoked()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Branch 3: expired (not yet revoked) → InvalidTokenException
    // -----------------------------------------------------------------------

    @Test
    void refresh_expiredToken_throwsInvalidToken() {
        // Token expired 1 second before FIXED_NOW
        RefreshToken expired = new RefreshToken(
                "user-uid-789", "exp-hash", FIXED_NOW.minusSeconds(1));
        String expHash = AuthenticationService.sha256("expired-raw");
        when(refreshTokenRepository.findByTokenHash(expHash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.refresh("expired-raw"))
                .isInstanceOf(InvalidTokenException.class);
    }

    // -----------------------------------------------------------------------
    // Branch 4: usable → rotate (old token revoked, revokedAt set)
    // -----------------------------------------------------------------------

    @Test
    void refresh_usableToken_revokesOldTokenWithTimestamp() {
        User user = new User("testuser", "$2a$04$hash");
        user.replaceRoles(new HashSet<>());
        setUid(user, "test-uid"); // mocks don't run @PrePersist; issue() needs a non-null uid claim

        String usableRaw = "valid-raw-token";
        String usableHash = AuthenticationService.sha256(usableRaw);
        RefreshToken usable = new RefreshToken("test-uid", usableHash, FIXED_NOW.plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(usableHash)).thenReturn(Optional.of(usable));
        when(userRepository.findByUid("test-uid")).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            setUid(t, "new-token-uid-1234567890"); // simulate JPA @PrePersist uid assignment
            return t;
        });

        service.refresh(usableRaw);

        assertThat(usable.isRevoked()).isTrue();
        assertThat(usable.getRevokedAt()).isNotNull();
    }

    /** Sets the @PrePersist-managed uid on an entity (mocked repos don't trigger JPA lifecycle). */
    private static void setUid(com.otapp.hmis.shared.domain.AuditableEntity entity, String uid) {
        try {
            var uidField = com.otapp.hmis.shared.domain.AuditableEntity.class.getDeclaredField("uid");
            uidField.setAccessible(true);
            uidField.set(entity, uid);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
