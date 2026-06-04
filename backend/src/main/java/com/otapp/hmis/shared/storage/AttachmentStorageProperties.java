package com.otapp.hmis.shared.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for the local-disk attachment storage (inc-06A C7 / ITEM5).
 *
 * <p>Bound from the {@code hmis.attachments} section of {@code application.yml}:
 * <pre>
 * hmis:
 *   attachments:
 *     base-path: ${HMIS_ATTACHMENTS_PATH:}
 *     max-file-size-bytes: 10485760
 * </pre>
 *
 * <p>If {@code basePath} is blank (the default for local-dev and tests that do not set
 * {@code HMIS_ATTACHMENTS_PATH}), the implementation falls back to a JVM temp-dir
 * subfolder so tests work without any external configuration.
 *
 * <p>{@code maxFileSizeBytes} defaults to {@code 10 485 760} (10 MiB), matching the legacy
 * guard at PatientServiceImpl.java:2842-2844 (lab) and 2940-2942 (radiology).
 *
 * @param basePath         base directory for stored files; blank → temp-dir fallback
 * @param maxFileSizeBytes maximum allowed upload size in bytes (default 10 MiB)
 */
@ConfigurationProperties(prefix = "hmis.attachments")
public record AttachmentStorageProperties(
        String basePath,
        long maxFileSizeBytes) {

    /** 10 MiB — verbatim legacy cap (PatientServiceImpl.java:2842). */
    private static final long DEFAULT_MAX = 10_485_760L;

    /**
     * Compact canonical constructor: apply defaults when values are absent or zero.
     */
    public AttachmentStorageProperties {
        if (maxFileSizeBytes <= 0) {
            maxFileSizeBytes = DEFAULT_MAX;
        }
    }

    /**
     * Resolve the effective base path.
     *
     * <p>If {@code basePath} is blank, returns a deterministic subfolder under the
     * system temp directory so that unit/integration tests work without any config.
     *
     * @return an absolute {@link Path} under which files will be stored
     */
    public Path effectiveBasePath() {
        if (basePath == null || basePath.isBlank()) {
            return Paths.get(System.getProperty("java.io.tmpdir"), "hmis-attachments");
        }
        return Paths.get(basePath);
    }
}
