package com.otapp.hmis.iam.application;

/**
 * Formats a raw sequence number into the {@code USR-NNN-NNN} user-code format.
 *
 * <p>Legacy source: {@code com.orbix.api.accessories.Formater.formatSix} (lines 36-52) prefixed
 * with {@code "USR-"}. The legacy implementation zero-pads to 6 digits then inserts a hyphen after
 * position 3, producing e.g. {@code USR-000-001} for seq=1. This class reproduces the format
 * exactly using a DB sequence instead of {@code MAX(id)+1} (CR-06 mechanism modernization).
 *
 * <p>Golden master (from build-spec §B / UserNoFormatterTest):
 * <pre>
 *   format(1)      → USR-000-001
 *   format(2)      → USR-000-002
 *   format(1234)   → USR-001-234
 *   format(999999) → USR-999-999
 * </pre>
 */
public final class UserNoFormatter {

    private UserNoFormatter() {
        // Utility class
    }

    /**
     * Format a positive sequence number as {@code USR-NNN-NNN}.
     *
     * @param seqValue the raw BIGINT from {@code nextval('seq_usr_no')}; must be 1..999999.
     * @return the formatted code, e.g. {@code USR-001-234} for seqValue=1234.
     * @throws IllegalArgumentException if seqValue is out of range.
     */
    public static String format(long seqValue) {
        if (seqValue < 1 || seqValue > 999_999L) {
            throw new IllegalArgumentException("seq_usr_no out of range: " + seqValue);
        }
        // Zero-pad to 6 digits, then insert hyphen after position 3 → "NNN-NNN"
        String padded = String.format("%06d", seqValue);
        String body = padded.substring(0, 3) + "-" + padded.substring(3);
        return "USR-" + body;
    }
}
