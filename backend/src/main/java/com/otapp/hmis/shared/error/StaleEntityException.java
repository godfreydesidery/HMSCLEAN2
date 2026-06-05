package com.otapp.hmis.shared.error;

/**
 * Thrown when a contended aggregate is observed in an unexpected state by a concurrent writer
 * (inc-07 CR-07-Q3, ADR-0017 ratified). Covers two distinct cases:
 *
 * <ol>
 *   <li><strong>Bed-claim race:</strong> {@code WardBedClaimImpl.claimBed} acquires a
 *       PESSIMISTIC_WRITE lock on the {@code WardBed} master row; the lock-loser re-reads and
 *       finds the bed already WAITING or OCCUPIED (or inactive), so the admission attempt is
 *       rejected. Legacy had no row lock and silently oversold the bed (PatientServiceImpl.java:1703-1711)
 *       — this 409 is the owner-approved deviation (CR-07-Q3).</li>
 *   <li><strong>Optimistic-lock fallback:</strong> any {@code @Version}-annotated aggregate whose
 *       version was bumped by a concurrent writer between read and flush. The
 *       {@link GlobalExceptionHandler} also maps
 *       {@code ObjectOptimisticLockingFailureException} → STALE_ENTITY, but direct application
 *       code that detects staleness at the guard level should throw this typed exception so the
 *       contract is explicit.</li>
 * </ol>
 *
 * <p>Maps to HTTP 409 via {@link ErrorCode#STALE_ENTITY}.
 * The stable {@code urn:hmis:error:stale-entity} type URI lets the Angular client
 * reload-and-retry rather than string-matching a 500 or a generic CONFLICT.
 *
 * <p>Legacy citation: PatientServiceImpl.java:1703-1711 (claimBed guard — exact-process
 * parity; the bed-status guard is reproduced, the missing lock is the net-new hardening).
 * CR-07-Q3 / ADR-0017 ratified.
 */
public class StaleEntityException extends HmisException {

    /**
     * Construct with a detail string describing the concurrency conflict.
     *
     * @param detail human-readable description (e.g. "Bed is no longer EMPTY; reload and retry")
     *               — MUST NOT contain PHI (bed uid is acceptable; patient uid is not)
     */
    public StaleEntityException(String detail) {
        super(ErrorCode.STALE_ENTITY, detail);
    }
}
