package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.FileDownload;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;

/**
 * Shared helper for building a SAFE attachment-download {@link ResponseEntity} (inc-06A C7 review
 * SEC-01 fix).
 *
 * <p><strong>Stored-XSS hardening:</strong> uploaded attachment bytes are attacker-influenced
 * content served from the application origin. Serving an uploaded {@code text/html} or
 * {@code image/svg+xml} file {@code Content-Disposition: inline} would let it execute in the
 * user's session (stored XSS). This helper:
 * <ul>
 *   <li>serves only a small allow-list of safe viewable types ({@code application/pdf} and raster
 *       images png/jpeg/gif) with their real content type and {@code inline} disposition — these
 *       are the clinically-useful "view in browser" cases (lab/radiology reports + images);</li>
 *   <li>serves everything else (incl. html, svg, scripts, unknown) as
 *       {@code application/octet-stream} with {@code attachment} disposition so the browser
 *       downloads rather than renders it;</li>
 *   <li>always sets {@code X-Content-Type-Options: nosniff} so the browser cannot MIME-sniff
 *       octet-stream back into an executable type.</li>
 * </ul>
 * The filename in {@code Content-Disposition} is the server-generated opaque storage name
 * (no attacker-controlled path/quotes), so header injection is not a concern.
 */
final class AttachmentDownloadSupport {

    /** Types we will serve inline (safe to render; clinically useful). */
    private static final Set<MediaType> INLINE_SAFE = Set.of(
            MediaType.APPLICATION_PDF,
            MediaType.IMAGE_PNG,
            MediaType.IMAGE_JPEG,
            MediaType.IMAGE_GIF);

    private AttachmentDownloadSupport() {
    }

    /**
     * Build a hardened download response for the given file.
     *
     * @param dl the file bytes + storage filename
     * @return a {@link ResponseEntity} with a safe content type, disposition, and nosniff header
     */
    static ResponseEntity<byte[]> build(FileDownload dl) {
        MediaType detected = MediaTypeFactory.getMediaType(dl.fileName())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        MediaType contentType;
        ContentDisposition disposition;
        if (INLINE_SAFE.contains(detected)) {
            contentType = detected;
            disposition = ContentDisposition.inline().filename(dl.fileName()).build();
        } else {
            // Not on the safe-render allow-list → force download, never inline-render.
            contentType = MediaType.APPLICATION_OCTET_STREAM;
            disposition = ContentDisposition.attachment().filename(dl.fileName()).build();
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(dl.bytes());
    }
}
