# ADR-0015: File & Attachment (Blob) Storage

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

## Context

The system requires persistent storage for uploaded file bytes linked to clinical orders (LabTest result PDFs/images, Radiology DICOM-adjacent images, Procedure intra-op photos) and potentially to other aggregates in the future (discharge summaries, supplier invoices, HR documents).

**Legacy approach (confirmed by reading `LabTestAttachment.java`, `RadiologyAttachment.java`, and `PatientResource.java`):** The legacy stores only a bare `fileName` string in the DB. Bytes are read from and written to an absolute filesystem path obtained from a `CompanyProfile.publicPath` database field. Download is a raw `GET /patients/download_lab_test_attachment?file_name=...` endpoint that reads the full byte array into memory (`Files.readAllBytes(...)`) and flushes it directly to `HttpServletResponse`. No authentication is checked on the download endpoint (the `@PreAuthorize` annotation is commented out on both download endpoints). The filename is passed directly from the URL query parameter into a `File(fileLocation + fileName)` constructor — a confirmed path-traversal vulnerability. Deletion deletes only the DB row; the file bytes are commented out (orphaned on disk). This approach has no PHI protection, no access control on retrieval, no virus scanning, and no HA story.

**Prior-attempt approach (V41, `AttachmentStorage.java`):** The prior build introduced a `StoragePort`-style `AttachmentStorage` component backed by a configurable local directory (`hmis.attachments.dir`). It corrected path traversal (resolves against the root and checks `startsWith`), sanitizes filenames, enforces a 25 MiB per-file cap, and stores metadata + `storage_key` (relative path `{orderUid}/{attachmentUid}-{filename}`) in an `order_attachment` table. Download is authenticated via `@PreAuthorize("hasAuthority('ENCOUNTER_ACCESS')")`. Deletion removes both the DB row and the bytes. This is a significant improvement. However it has no fault tolerance (a single-node volume), no encryption at rest, no virus scanning, no content-type allowlist, and no horizontal-scaling story — the `FileSystemResource` is served by streaming through the API process.

**Scope expansion needed:** The prior build scoped attachments only to `ClinicalOrder` (LAB_TEST / RADIOLOGY / PROCEDURE). The fresh build must support the same attachment lifecycle for discharge summary documents (DISCH-2 gap), supplier/procurement documents, and eventually HR documents, without bespoke tables per aggregate type.

## Decision

**Use an S3-compatible object store — MinIO self-hosted for on-premise deployments, AWS S3 or compatible for cloud — as the sole byte-storage backend. The PostgreSQL database stores only attachment metadata and a storage key (object name). File bytes never enter the database and are never served by reading into the JVM heap.**

The implementation uses the AWS SDK v2 (software.amazon.awssdk:s3) with a single configurable endpoint, enabling MinIO in development and any S3-compatible endpoint in production without code changes.

## Considered Alternatives

**A. Local filesystem (prior-attempt pattern).** Simple to run; works in a single-node dev setup. Ruled out for production: no replication, no horizontal scaling (two app nodes cannot share a local volume without a network filesystem), no built-in encryption at rest, and backup requires filesystem-level tooling external to the application. Acceptable for local development only.

**B. PostgreSQL large objects / `bytea` columns.** Stores bytes in the DB; eliminates a separate infrastructure component. Ruled out: BLOBs bloat the WAL and backup size, dramatically slow VACUUM, and make the primary database a bottleneck for binary transfers. Clinical radiology images can be tens of megabytes; a PACS-adjacent system cannot rely on the OLTP database as a CDN.

**C. S3-compatible object store (chosen).** MinIO is freely self-hostable, has an identical SDK surface to AWS S3, supports server-side encryption (SSE-S3 / SSE-KMS), bucket versioning, lifecycle policies, and multipart upload. The same application code works against MinIO locally and AWS S3 or Wasabi in production. Signed (pre-signed) URLs can be generated for client-side direct download with a short TTL, removing the need to proxy every byte through the API process. This is the industry-standard pattern for HIPAA/PHI workloads.

## Consequences

**PHI security — encryption at rest.** All buckets are configured with server-side encryption enabled at the bucket policy level (SSE-S3 minimum; SSE-KMS for production). Object names (storage keys) are opaque UUIDs — they carry no patient name, MRN, or diagnosis information. The bucket is private: no public ACL, no unsigned object URLs are ever issued.

**Access via uid + RBAC — never expose raw object names.** The API never returns the `storage_key` in any DTO (analogous to hiding the internal `id`). Clients request a download via the attachment's `uid`: `GET /encounters/attachments/uid/{attachmentUid}/download`. The service layer verifies the caller holds the required privilege (e.g., `LAB_TEST-VIEW` or `RADIOLOGY-VIEW`) and that the attachment belongs to an order the caller is authorised to view before issuing a response. For large files the service generates a short-lived pre-signed URL (TTL: 5 minutes) and redirects the client — the bytes never travel through the Spring process.

**Virus / malware scanning on upload.** A ClamAV sidecar (clamav/clamav Docker image) is co-deployed and exposed via the ClamAV INSTREAM TCP protocol. The `StoragePort.put()` implementation scans the byte stream before writing to the bucket. If ClamAV returns FOUND the upload is rejected with HTTP 422 and a `ProblemDetail` with `type=urn:hmis:error:ATTACHMENT_MALWARE_DETECTED`. The scan adds latency (~100–300 ms for typical files); a 5-second scan timeout rejects oversized or pathological files. ClamAV is the standard open-source choice; it is updated via the sidecar's virus-DB auto-updater. In environments where ClamAV cannot be deployed (e.g., constrained cloud), the scan step is skipped via `hmis.storage.virus-scan.enabled=false` but this must be a conscious operator decision, not a default.

**Content-type and size limits.** Permitted MIME types are defined in `hmis.storage.allowed-content-types` (default: `image/jpeg`, `image/png`, `image/tiff`, `application/pdf`, `application/dicom`). Any other content type is rejected with HTTP 415. Maximum file size is 50 MiB per attachment (raised from the prior attempt's 25 MiB to accommodate DICOM-adjacent radiology images). Spring's multipart limit (`spring.servlet.multipart.max-file-size=50MB`) is set to match.

**DB stores only metadata.** The `attachment` table holds: `id` (hidden `BIGINT` identity), `uid` (ULID, `CHAR(26)`, the public identifier), `owner_kind` (enum: CLINICAL_ORDER / ADMISSION / CLOSURE_PLAN / PROCUREMENT), `owner_uid` (`CHAR(26)`, loose reference — no cross-schema FK), `filename` (sanitized original name), `content_type`, `size_bytes`, `storage_key` (S3 object name, opaque, never serialized to clients), `description`, `virus_scan_status` (PENDING / CLEAN / INFECTED), `uploaded_by_username`, `uploaded_at`. The `storage_key` column is never mapped in any DTO or response body.

**Backup and retention.** The MinIO bucket is configured with versioning enabled. A separate lifecycle policy transitions objects to cheaper storage (e.g., MinIO erasure-coded tier) after 90 days and hard-deletes objects flagged as INFECTED immediately. Application-level deletion marks the DB row deleted and calls `s3Client.deleteObject()` on the storage key. Because versioning is on, MinIO retains a recoverable copy for the configured retention window (default 30 days). For cloud deployments, S3 Object Lock (Governance mode) may be layered on to satisfy medical-record retention law (the ADR does not prescribe a retention period — this is a compliance decision for the deploying facility).

**Serving bytes through the API.** For small files (less than 1 MiB) the API proxies the stream from S3 directly via `ResponseEntity<StreamingResponseBody>` — no heap allocation of the full byte array (correcting the legacy's `Files.readAllBytes()` into memory). For files over 1 MiB the API generates a pre-signed S3 URL valid for 5 minutes and returns `HTTP 302` to the client. The Angular frontend follows the redirect transparently for both download and inline preview.

**Generic `Attachment` aggregate, keyed by `(ownerKind, ownerUid)`.** Rather than the legacy's separate `lab_test_attachments` and `radiology_attachments` tables (two nearly-identical entities with no storage abstraction), and rather than the prior attempt's `order_attachment` table scoped only to `ClinicalOrder`, the fresh build uses a single `attachment` table with a polymorphic `(owner_kind, owner_uid)` key. A `StoragePort` interface (one method: `put`, `get`, `delete`, `presign`) is the sole dependency of the `AttachmentService`; the S3 implementation is injected by Spring. A local filesystem `DevStorageAdapter` is provided for developer mode without MinIO.

## Exact-Process Impact

The legacy process requires that lab result attachments (scanned reports, instrument output PDFs) and radiology attachments (X-ray images, ultrasound images) be uploadable by lab technicians and radiographers respectively, viewable by the ordering clinician within the consultation, and deletable only before the order is VERIFIED. These workflows are preserved exactly. The constraint "cannot delete attachment on a VERIFIED lab test" (present in `PatientResource.java` lines 6021–6023 and mirrored for radiology) is re-encoded as a guard in `AttachmentService.delete()`. The legacy radiology entity also has an inline `attachment` varchar field (a free-text link, confirmed in `Radiology.attachment` read in `PatientResource.java` line 2380) — this is superseded by the structured `Attachment` aggregate in the fresh build; the field is not carried forward.

## Implementation Notes

- **StoragePort interface:** `interface StoragePort { void put(String key, InputStream data, String contentType); InputStream get(String key); void delete(String key); URL presign(String key, Duration ttl); }` Implementations: `S3StorageAdapter` (production), `LocalFilesystemStorageAdapter` (dev, activated by Spring profile).
- **Flyway migration:** One `attachment` table (replacing both legacy tables and the prior attempt's `order_attachment` table). `storage_key` column is `VARCHAR(700)` to accommodate S3 path prefixes (`{env}/{ownerKind}/{ownerUid}/{attachmentUid}`).
- **MinIO deployment:** Docker Compose service `minio` with a named volume. Bucket created by a startup bean (`@PostConstruct` on `StorageConfig`) using the MinIO admin SDK if not already present. Bucket name configurable via `hmis.storage.bucket`.
- **AWS SDK v2 dependency:** `software.amazon.awssdk:s3` (BOM-managed). Use `S3Client` (sync) not `S3AsyncClient` to keep the Spring MVC thread model simple. Pre-signed URLs use `S3Presigner`.
- **ClamAV integration:** `xyz.capybara:clamav4j` or direct socket to `clamd`. Scan happens in `AttachmentService.upload()` before the `StoragePort.put()` call. Infected files result in no object being stored.
- **No legacy file migration:** The fresh build starts empty; there are no legacy attachment bytes to migrate. Existing `lab_test_attachments` and `radiology_attachments` rows in the legacy MySQL DB are reference-only clinical artefacts that the facility must handle via their own archival process.
