package com.otapp.hmis.iam.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security 6 OAuth2 Resource Server validating self-issued HS256 JWTs (ADR-0006, ADR-0013).
 *
 * <p>Increment-01 change: wildcard CORS origin pattern replaced with an explicit allow-list
 * from {@link CorsProperties} (build-spec §4 D-1). {@code allowCredentials} is NOT enabled
 * (bearer tokens, not cookies).
 *
 * <p>The signing key is derived from {@code ${JWT_SECRET}} via a {@link SecretKeySpec}; no legacy
 * auth0-style HMAC signing literal exists anywhere (the hardcoded-secret grep gate stays clean).
 * The {@link JwtAuthenticationConverter} maps the {@code privileges} claim to authorities with no
 * prefix, so {@code hasAnyAuthority('ADMIN-ACCESS')} resolves directly.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    /** Claim carrying the array of privilege CODE strings (ADR-0006). Never {@code "roles"}. */
    public static final String PRIVILEGES_CLAIM = "privileges";

    private final JwtProperties jwtProperties;
    private final CorsProperties corsProperties;

    @Bean
    SecretKey jwtSecretKey() {
        byte[] keyBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(jwtSecretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt strength 12 (ADR-0006).
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(PRIVILEGES_CLAIM);
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Explicit allow-list — replaces legacy wildcard (build-spec §4 D-1).
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.addAllowedHeader("*");
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // allowCredentials NOT set — bearer tokens are in Authorization header, not cookies.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/token",
                                "/api/v1/auth/token/refresh",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        // POST /auth/token/revoke is NOT in permitAll — requires authentication.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, ex) -> writeProblem(
                                response, HttpStatus.UNAUTHORIZED, "urn:hmis:error:unauthenticated",
                                "Authentication required"))
                        .accessDeniedHandler((request, response, ex) -> writeProblem(
                                response, HttpStatus.FORBIDDEN, "urn:hmis:error:forbidden", "Access denied")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> writeProblem(
                                response, HttpStatus.UNAUTHORIZED, "urn:hmis:error:unauthenticated",
                                "Authentication required"))
                        .accessDeniedHandler((request, response, e) -> writeProblem(
                                response, HttpStatus.FORBIDDEN, "urn:hmis:error:forbidden", "Access denied")));
        return http.build();
    }

    private static void writeProblem(jakarta.servlet.http.HttpServletResponse response,
                                     HttpStatus status, String type, String detail) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}",
                type, status.getReasonPhrase(), status.value(), detail));
    }
}
