package com.otapp.hmis.registration.domain;

/**
 * Lifecycle status of a {@link Visit} (build-spec §1.2, CR-18).
 *
 * <p>Legacy citation: Visit.java:46-47.  Only {@link #PENDING} is written in inc-03.
 * Later increments (admission/discharge) that need additional statuses MUST widen the
 * DB CHECK ({@code ck_visits_status}) via an additive migration — silent relaxation is
 * not permitted (ADR-0008-R2).
 *
 * <p>Stored via {@code @Enumerated(STRING)} as a VARCHAR(20) column.
 */
public enum VisitStatus {

    /**
     * Visit is open/pending — the patient has not yet been seen.
     * Default and sole value written in inc-03.
     */
    PENDING
}
