package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle status for the nurse vitals-capture staging entity {@link PatientVital}
 * (inc-05 C5, PatientVital.java:45).
 *
 * <p>The three values are VALID Java identifiers (no hyphens), so {@code @Enumerated(STRING)}
 * is sufficient — no custom {@code AttributeConverter} is needed. The V23 CHECK constraint
 * explicitly lists {@code 'EMPTY'}, {@code 'SUBMITTED'}, {@code 'ARCHIVED'} — exact match.
 *
 * <p>Lifecycle:
 * <pre>
 *   EMPTY  →  SUBMITTED  →  ARCHIVED
 *             (nurse fills)   (doctor requests — copied into GeneralExamination)
 * </pre>
 *
 * <p>Legacy citation: PatientVital.java:45 (status field); PatientResource.java:1298-1307
 * (auto-create EMPTY), :1321 (submitted worklist), :1340 (request → ARCHIVED).
 */
public enum PatientVitalStatus {
    /** Initial state — vital row created but nurse has not yet submitted readings. */
    EMPTY,

    /** Nurse has submitted the vital readings; ready for the doctor to request. */
    SUBMITTED,

    /** Doctor has requested the vitals — readings copied into GeneralExamination; row closed. */
    ARCHIVED
}
