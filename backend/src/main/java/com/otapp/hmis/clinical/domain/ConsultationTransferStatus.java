package com.otapp.hmis.clinical.domain;

/**
 * Lifecycle status of a {@link ConsultationTransfer} aggregate.
 *
 * <p><strong>EXACT legacy spellings preserved</strong> (11-DECISIONS-RATIFIED §3, inc-05 C3).
 * All three values are valid Java identifiers (no hyphens), so {@code @Enumerated(STRING)}
 * persists them verbatim — no custom converter needed.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>{@link #PENDING}   — created by createConsultationTransfer (PatientServiceImpl.java:2808)</li>
 *   <li>{@link #COMPLETED} — completed when patient re-booked to destination clinic
 *                            (PatientServiceImpl.java:431-435)</li>
 *   <li>{@link #CANCELED}  — canceled by cancelConsultationTransfer, single-L (PatientServiceImpl.java:2821)</li>
 * </ul>
 *
 * <p>DB CHECK in V22: {@code status IN ('PENDING', 'COMPLETED', 'CANCELED')}.
 */
public enum ConsultationTransferStatus {

    /** Transfer raised; patient awaiting re-booking to the destination clinic. */
    PENDING,

    /** Patient was re-booked to the destination clinic; transfer hand-off done. */
    COMPLETED,

    /**
     * Transfer was explicitly canceled. Single-L spelling — EXACT legacy value.
     * (11-DECISIONS-RATIFIED §3: "single-L CANCELED")
     */
    CANCELED
}
