package com.otapp.hmis.shared.storage;

/**
 * Storage abstraction for clinical attachment files (inc-06A C7 / ITEM5).
 *
 * <p>Keeps the service layer free of I/O framework specifics. The default implementation is
 * {@link LocalDiskFileStorage} (legacy-parity local-disk, owner-ratified). Object storage
 * (MinIO / S3) is an approved future swap — only this interface changes.
 *
 * <p>Legacy analogue: PatientServiceImpl.java:2823-2906 (lab store), 2922-2996 (radiology
 * store) — direct {@code FileOutputStream} to a configured base directory.
 */
public interface FileStoragePort {

    /**
     * Persist {@code bytes} under a server-generated storage filename derived from
     * {@code originalFilename} and return that filename.
     *
     * <p>The returned filename is an opaque, globally-unique storage key (NOT the original
     * client filename). It is what gets persisted in the {@code fileName} column of the
     * attachment row and passed back to {@link #read} or {@link #delete} later.
     *
     * @param bytes            raw file bytes
     * @param originalFilename the client-supplied filename (used only to derive the extension)
     * @param prefix           short prefix for the generated name ("LT" for lab / "RAD" for
     *                         radiology) — keeps the on-disk file vaguely human-sortable
     * @param ownerUid         the UID of the owning order (lab test uid or radiology uid);
     *                         embedded in the generated filename for traceability
     * @return the generated storage filename (relative, no path separators)
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if
     *         {@code originalFilename} contains path-traversal sequences
     */
    String store(byte[] bytes, String originalFilename, String prefix, String ownerUid);

    /**
     * Read and return the bytes of a previously stored file.
     *
     * @param storageFilename the opaque name returned by {@link #store}
     * @return the file bytes
     * @throws com.otapp.hmis.shared.error.NotFoundException if the file does not exist on disk
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if the filename
     *         contains path-traversal sequences
     */
    byte[] read(String storageFilename);

    /**
     * Delete a previously stored file. This is a best-effort operation — if the file does not
     * exist the call is a no-op (no exception thrown).
     *
     * <p>Approved deviation from legacy (PatientResource.java:6021-6022 does NOT unlink the
     * disk file on attachment-row delete). Unlinking here prevents orphaned files from
     * accumulating on the storage volume. Documented as security-architect-approved deviation.
     *
     * @param storageFilename the opaque name returned by {@link #store}
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if the filename
     *         contains path-traversal sequences
     */
    void delete(String storageFilename);
}
