package com.otapp.hmis.shared.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Supplies the principal username for {@code @CreatedBy}/{@code @LastModifiedBy} on
 * {@link com.otapp.hmis.shared.domain.AuditableEntity} (ADR-0014 §1). Falls back to
 * {@code SYSTEM} when no security context is present (ADR-0007).
 */
@Configuration
public class JpaAuditingConfig {

    public static final String SYSTEM_ACTOR = "SYSTEM";

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getPrincipal())) {
                return Optional.of(SYSTEM_ACTOR);
            }
            return Optional.of(authentication.getName());
        };
    }
}
