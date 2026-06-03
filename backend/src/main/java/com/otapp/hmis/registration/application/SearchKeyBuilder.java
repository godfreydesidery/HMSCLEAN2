package com.otapp.hmis.registration.application;

/**
 * Builds the patient {@code searchKey} — byte-verbatim reproduction of the legacy logic
 * (build-spec §2.2, CR-09). Reproduces {@code PatientServiceImpl.createSearchKey} (lines 739-744)
 * followed by {@code Sanitizer.sanitizeString} (Sanitizer.java:11-17), in that order:
 *
 * <pre>
 *   key = no + " " + firstName + " " + middleName + " " + lastName + " " + phoneNo   // plain concat
 *   key = key.trim().replaceAll("\\s+", " ")
 *   key = key.replaceAll("[+^]*#$%&", "")          // intends to strip #$%& but is a NO-OP (see below)
 *   // then Sanitizer.sanitizeString:
 *   key = key.replace("+", " ")
 *   key = key.trim().replaceAll("\\s+", " ")
 *   key = key.replaceAll("[+^]*#$%&", "")
 * </pre>
 *
 * <p>RATIFIED faithful quirks (do NOT "fix" — byte-identical keys are required for any future
 * reconciliation): case is PRESERVED (NOT lowercased — the planning-doc lowercase is DRIFT);
 * the {@code [+^]*#$%&} regex strips only the literal sequence {@code #$%&}; and a {@code null}
 * field is concatenated as the literal {@code "null"} exactly as the legacy plain-{@code +}
 * concatenation does.
 */
public final class SearchKeyBuilder {

    private SearchKeyBuilder() {
        // utility
    }

    /**
     * @param no         the MR number (already assigned)
     * @param firstName  first name
     * @param middleName middle name (nullable — concatenated as "null" if null, per legacy)
     * @param lastName   last name
     * @param phoneNo    phone number (nullable — concatenated as "null" if null, per legacy)
     * @return the sanitized search key, case-preserved
     */
    @SuppressWarnings("java:S5996") // RATIFIED latent bug: the '$' in "[+^]*#$%&" is an anchor, so
    // the regex never matches (a no-op). Reproduced verbatim from legacy for byte-identical keys.
    public static String build(String no, String firstName, String middleName,
                               String lastName, String phoneNo) {
        // createSearchKey (PatientServiceImpl.java:739-744) — plain '+' concat (null -> "null")
        String key = no + " " + firstName + " " + middleName + " " + lastName + " " + phoneNo;
        key = key.trim().replaceAll("\\s+", " ");
        key = key.replaceAll("[+^]*#$%&", "");
        // Sanitizer.sanitizeString (Sanitizer.java:11-17)
        key = key.replace("+", " ");
        key = key.trim().replaceAll("\\s+", " ");
        key = key.replaceAll("[+^]*#$%&", "");
        return key;
    }
}
