package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module MUTATING write+lock seam for ward bed status transitions (inc-07 SEAM-1,
 * ADR-0008 §1, CR-07-Q3, ADR-0017 ratified).
 *
 * <p><strong>First-of-kind mutating named-interface contract:</strong> unlike the existing
 * {@link PriceLookup} and {@link WardLookup} read-only seams, this interface performs
 * writes and row-level PESSIMISTIC_WRITE locking. It touches a PHI-adjacent master row
 * ({@code ward_beds}) cross-module. Security-architect review is recommended before
 * exposing additional mutation methods on this interface (CR-07-Q3 / ADR-0017 ratified).
 *
 * <p>The implementation ({@code WardBedClaimImpl}) is package-private in
 * {@code masterdata.application}. Callers depend on this interface from
 * {@code masterdata :: lookup} only.
 *
 * <p>Status literals are free-text strings ({@code "EMPTY"} / {@code "WAITING"} /
 * {@code "OCCUPIED"}) — NO enum, matching legacy WardBed.java:43 (CR-16).
 *
 * <p>Legacy citations: PatientServiceImpl.java:1703-1711 (claimBed guard);
 * doAdmission full-cover branch + PatientBillResource.java:352-365 (occupyBed);
 * get_*_summary frees WardBed (freeBed). inc-07 SEAM-1 / ADR-0017 ratified.
 */
public interface WardBedClaim {

    /**
     * Claim an EMPTY bed for a pending admission: acquires PESSIMISTIC_WRITE on the
     * {@code WardBed} row, re-reads under the lock, and transitions status EMPTY → WAITING.
     *
     * <p>Guard: the bed must be {@code active == true} AND
     * {@code status.equals("EMPTY")}. If the guard fails (bed inactive, or already
     * WAITING/OCCUPIED — another admission won the race) a
     * {@link com.otapp.hmis.shared.error.StaleEntityException} (HTTP 409
     * {@code urn:hmis:error:stale-entity}) is thrown so the caller can reload and retry.
     *
     * <p>The PESSIMISTIC_WRITE lock serialises concurrent bed-claim attempts; the first writer
     * transitions to WAITING and commits; the loser reads WAITING under the lock and is
     * rejected. Net-new hardening over legacy, which had no row lock and silently oversold
     * (CR-07-Q3, ADR-0017 ratified).
     *
     * <p>Legacy citation: PatientServiceImpl.java:1703-1711.
     *
     * @param wardBedUid the ULID of the bed to claim
     * @throws com.otapp.hmis.shared.error.NotFoundException    if no bed with that uid exists
     * @throws com.otapp.hmis.shared.error.StaleEntityException if the bed is not active or
     *         not EMPTY under the lock (409 — caller should reload and retry)
     */
    void claimBed(String wardBedUid);

    /**
     * Activate a claimed bed to OCCUPIED once the admission payment has been received.
     *
     * <p>Transitions status WAITING → OCCUPIED (payment-driven activation). Idempotent if
     * the bed is already OCCUPIED — a second call from a retry path does not raise an error.
     *
     * <p>Legacy citation: doAdmission full-cover branch +
     * PatientBillResource.java:352-365 (payment-side bed activation).
     *
     * @param wardBedUid the ULID of the bed to mark as occupied
     * @throws com.otapp.hmis.shared.error.NotFoundException if no bed with that uid exists
     */
    void occupyBed(String wardBedUid);

    /**
     * Free an occupied or claimed bed on discharge / sign-out: transitions status → EMPTY.
     *
     * <p>Legacy citation: get_discharge_summary / get_referral_summary / get_deceased_summary
     * all free the WardBed on completion.
     *
     * @param wardBedUid the ULID of the bed to free
     * @throws com.otapp.hmis.shared.error.NotFoundException if no bed with that uid exists
     */
    void freeBed(String wardBedUid);
}
