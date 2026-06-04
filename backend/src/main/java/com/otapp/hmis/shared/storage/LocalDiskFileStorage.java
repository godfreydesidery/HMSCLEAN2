package com.otapp.hmis.shared.storage;

import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Local-disk implementation of {@link FileStoragePort} (inc-06A C7 / ITEM5).
 *
 * <p><strong>Storage layout:</strong> all files are written as flat files directly under
 * the configured {@code hmis.attachments.base-path} directory. The directory is created on
 * first use if absent. No sub-directory sharding is applied (legacy parity: the legacy writes
 * directly under one configured directory, PatientServiceImpl.java:2823-2906).
 *
 * <p><strong>Generated filename scheme (approved deviation from legacy):</strong>
 * {@code <prefix><ownerUid>-<nanoTime>.<ext>}
 * where the extension is lower-cased and derived safely from the client filename.
 * The legacy scheme embeds patient.getNo() (PatientServiceImpl.java:2856 "LT"+id+patientNo+...).
 * That scheme is NOT reproduced here because: (a) the clinical module has no access to
 * patientNo (a registration-module field), and (b) crossing into registration would create
 * a prohibited module dependency (ADR-0008 §3). The opaque name is sufficient — only
 * uniqueness is observable to the client.
 *
 * <p><strong>Path-traversal hardening (approved deviation):</strong> the legacy code performs
 * raw string concatenation (PatientServiceImpl.java:2858 {@code basePath + fileName}).
 * This implementation rejects any filename containing {@code ..}, {@code /}, or {@code \}
 * by throwing {@link InvalidPatientOperationException}. Security-architect-approved.
 *
 * <p><strong>Read-missing:</strong> throws {@link NotFoundException} when the file does not
 * exist on disk (patched to 404 by the global exception handler).
 *
 * <p><strong>Delete:</strong> best-effort; missing file is silently ignored (approved deviation
 * from the legacy non-deletion, documented in {@link FileStoragePort#delete}).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Lab storage:      PatientServiceImpl.java:2823-2906</li>
 *   <li>Radiology storage: PatientServiceImpl.java:2922-2996</li>
 *   <li>10 MiB cap:       PatientServiceImpl.java:2842-2844</li>
 *   <li>Download:         PatientResource.java:5960-6007 (lab), 6093-6140 (radiology)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDiskFileStorage implements FileStoragePort {

    private final AttachmentStorageProperties properties;

    // =========================================================================
    // Store
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Generates a unique storage filename and writes the bytes atomically to disk. The
     * base directory is created if it does not already exist.
     */
    @Override
    public String store(byte[] bytes, String originalFilename, String prefix, String ownerUid) {
        String storageName = generateStorageName(prefix, ownerUid, originalFilename);
        Path basePath = ensureBaseDir();
        Path target = basePath.resolve(storageName);
        // resolve() + normalize() then verify the result is still inside basePath
        target = target.normalize();
        guardInsideBase(target, basePath);
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            log.debug("Stored attachment file: {}", storageName);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write attachment file: " + storageName, e);
        }
        return storageName;
    }

    // =========================================================================
    // Read
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Reads and returns all bytes from the stored file. Throws {@link NotFoundException}
     * (→ HTTP 404) if the file is absent.
     */
    @Override
    public byte[] read(String storageFilename) {
        guardNoTraversal(storageFilename);
        Path basePath = properties.effectiveBasePath();
        Path target   = basePath.resolve(storageFilename).normalize();
        guardInsideBase(target, basePath);
        if (!Files.exists(target)) {
            throw new NotFoundException("Attachment file not found: " + storageFilename);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read attachment file: " + storageFilename, e);
        }
    }

    // =========================================================================
    // Delete (best-effort)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Silently ignores missing files. Path-traversal inputs are still rejected.
     *
     * <p><strong>Approved deviation:</strong> the legacy PatientResource.java:6021-6022
     * does NOT unlink the disk file when deleting an attachment row. Unlinking here prevents
     * orphaned files from accumulating. Security-architect-approved.
     */
    @Override
    public void delete(String storageFilename) {
        guardNoTraversal(storageFilename);
        Path basePath = properties.effectiveBasePath();
        Path target   = basePath.resolve(storageFilename).normalize();
        guardInsideBase(target, basePath);
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.debug("Deleted attachment file: {}", storageFilename);
            } else {
                log.debug("Attachment file not found on disk (best-effort delete): {}",
                        storageFilename);
            }
        } catch (IOException e) {
            // Best-effort: log but do not propagate.
            log.warn("Could not delete attachment file {} (best-effort): {}",
                    storageFilename, e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Generate a globally-unique storage filename.
     *
     * <p>Scheme: {@code <prefix><ownerUid>-<System.nanoTime()>.<ext>}
     * <ul>
     *   <li>{@code prefix} — "LT" or "RAD", passed by the caller.</li>
     *   <li>{@code ownerUid} — the UID of the owning order (embedded for traceability).</li>
     *   <li>{@code nanoTime} — monotonic nanosecond counter (sufficient uniqueness within a
     *       single JVM; combined with ownerUid the collision probability is negligible).</li>
     *   <li>{@code ext} — lower-cased extension derived from {@code originalFilename}; empty
     *       string if no extension is present.</li>
     * </ul>
     *
     * <p>Legacy deviation: the legacy scheme is {@code "LT"+id+patientNo+random+timestamp}
     * (PatientServiceImpl.java:2856). This is NOT reproduced to avoid a cross-module dependency
     * on the registration/patient context. Uniqueness is the only observable property.
     */
    private static String generateStorageName(String prefix, String ownerUid,
                                              String originalFilename) {
        String ext = deriveExtension(originalFilename);
        long nano  = System.nanoTime();
        return prefix + ownerUid + "-" + nano + (ext.isEmpty() ? "" : "." + ext);
    }

    /**
     * Derive a lower-cased extension from the original filename. Returns an empty string if
     * there is no dot, or if the only dot is at position 0 (hidden file like ".gitignore").
     */
    private static String deriveExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        // Strip any leading path separators that a naive caller might include
        String name = originalFilename;
        int lastSep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSep >= 0) {
            name = name.substring(lastSep + 1);
        }
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx <= 0) {
            return "";
        }
        return name.substring(dotIdx + 1).toLowerCase();
    }

    /**
     * Ensure the storage base directory exists, creating it (with parents) if necessary.
     *
     * @return the resolved, existing base directory path
     */
    private Path ensureBaseDir() {
        Path dir = properties.effectiveBasePath();
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("Created attachment storage directory: {}", dir);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Cannot create attachment storage directory: " + dir, e);
            }
        }
        return dir;
    }

    /**
     * Reject filenames containing path-traversal sequences ({@code ..}, {@code /}, {@code \}).
     *
     * <p>Security-architect-approved hardening over the legacy raw concat
     * (PatientServiceImpl.java:2858). The guard is intentionally strict — a legitimate
     * attachment filename is always a plain name (no slashes, no dots-only segments).
     *
     * @throws InvalidPatientOperationException (→ HTTP 422) if the filename is suspicious
     */
    private static void guardNoTraversal(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new InvalidPatientOperationException("Attachment filename must not be blank");
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            // SEC-03: do NOT reflect the raw attacker-controlled filename in the response.
            throw new InvalidPatientOperationException(
                    "Invalid attachment filename (path traversal rejected)");
        }
    }

    /**
     * Verify that the resolved {@code target} path is still inside {@code basePath}, preventing
     * any traversal that slipped through the string check (e.g., encoded sequences).
     *
     * @throws InvalidPatientOperationException (→ HTTP 422) if the target escapes the base dir
     */
    private static void guardInsideBase(Path target, Path basePath) {
        if (!target.startsWith(basePath.normalize())) {
            throw new InvalidPatientOperationException(
                    "Invalid attachment path (outside storage root)");
        }
    }
}
