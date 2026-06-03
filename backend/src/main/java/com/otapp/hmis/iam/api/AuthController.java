package com.otapp.hmis.iam.api;

import com.otapp.hmis.iam.application.AuthenticationService;
import com.otapp.hmis.iam.application.dto.RefreshRequest;
import com.otapp.hmis.iam.application.dto.RevokeRequest;
import com.otapp.hmis.iam.application.dto.TokenRequest;
import com.otapp.hmis.iam.application.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token issuance endpoints (ADR-0006). Increment-01 adds {@code POST /token/revoke} (CR-10).
 *
 * <p>{@code /token} and {@code /token/refresh} are in the {@code permitAll} list (SecurityConfig).
 * {@code /token/revoke} is NOT in permitAll — requires a valid Bearer token; ownership check
 * is enforced in {@link AuthenticationService#revoke}. Constructor injection is Lombok-generated.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        return authenticationService.login(request.username(), request.password());
    }

    @PostMapping("/token/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authenticationService.refresh(request.refreshToken());
    }

    /**
     * Revoke a refresh token (CR-10, build-spec §2 endpoint #15).
     *
     * <p>Idempotent: unknown and already-revoked tokens both return 204 (non-enumerating).
     * Cross-user revocation is rejected with 403.
     */
    @PostMapping("/token/revoke")
    public ResponseEntity<Void> revoke(
            @Valid @RequestBody RevokeRequest request,
            Authentication authentication) {
        authenticationService.revoke(request.refreshToken(), authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
