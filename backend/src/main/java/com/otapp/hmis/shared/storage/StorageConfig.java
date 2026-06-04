package com.otapp.hmis.shared.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link AttachmentStorageProperties} binding (inc-06A C7 / ITEM5).
 *
 * <p>Follows the same pattern as the IAM module's {@code JwtProperties} wiring:
 * a small @{@code Configuration} class carries {@code @EnableConfigurationProperties} so
 * the record is bound by Spring Boot without requiring {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(AttachmentStorageProperties.class)
public class StorageConfig {
    // intentionally empty — the annotation does the work
}
