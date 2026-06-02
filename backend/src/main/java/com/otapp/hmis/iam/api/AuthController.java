package com.otapp.hmis.iam.api;

import com.otapp.hmis.iam.application.AuthenticationService;
import com.otapp.hmis.iam.application.dto.RefreshRequest;
import com.otapp.hmis.iam.application.dto.TokenRequest;
import com.otapp.hmis.iam.application.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token issuance endpoints (ADR-0006). {@code POST /api/v1/auth/token} (login) and
 * {@code POST /api/v1/auth/token/refresh} (rotation). Both emit a {@code privileges} claim.
 * Constructor injection is Lombok-generated (DIRECTIVE 1).
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
}
