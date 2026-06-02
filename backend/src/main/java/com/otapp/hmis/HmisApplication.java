package com.otapp.hmis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.modulith.Modulithic;

/**
 * Zana HMIS — Spring Modulith modular monolith entry point.
 *
 * <p>Package root is {@code com.otapp.hmis} (ADR-0014). Each bounded context is a
 * package-level Spring Modulith {@code @ApplicationModule}. Boundaries are mechanically
 * enforced by {@code ApplicationModules.of(HmisApplication.class).verify()} in the test
 * suite (ADR-0001, ADR-0008).
 */
@Modulithic(sharedModules = "shared")
@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class HmisApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmisApplication.class, args);
    }
}
