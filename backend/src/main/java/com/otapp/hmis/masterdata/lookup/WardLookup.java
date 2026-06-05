package com.otapp.hmis.masterdata.lookup;

import java.util.Optional;

/**
 * Cross-module read seam for ward and bed master data (inc-07 SEAM-1, ADR-0008 §1).
 *
 * <p>This interface is the ONLY ward/bed read API that modules outside {@code masterdata}
 * may call. The implementation ({@code WardLookupImpl}) is package-private in
 * {@code masterdata.application}.
 *
 * <p>Precedented by {@link PriceLookup} / {@link ServicePriceResult} — same value-returning
 * read-seam pattern: projections only, no entity type leaks, implementation hidden behind the
 * named interface.
 *
 * <p>Callers (primarily {@code inpatient}) depend on this interface from
 * {@code masterdata :: lookup}; they MUST NEVER import
 * {@code com.otapp.hmis.masterdata.domain} or {@code com.otapp.hmis.masterdata.application}
 * types directly (Spring Modulith named-interface contract, ADR-0008 §1).
 *
 * <p>Legacy citation: bed status EMPTY/WAITING/OCCUPIED — WardBed.java:43 (CR-16).
 * WardType price lookup — WardType.java:39-40. inc-07 SEAM-1 / ADR-0017 ratified.
 */
public interface WardLookup {

    /**
     * Read a {@link WardBedView} by its public uid.
     *
     * @param wardBedUid the ULID of the ward bed
     * @return the bed projection, or {@link Optional#empty()} when no bed with that uid exists
     */
    Optional<WardBedView> findBedByUid(String wardBedUid);

    /**
     * Read a {@link WardTypeView} by its public uid.
     *
     * @param wardTypeUid the ULID of the ward type
     * @return the ward-type projection, or {@link Optional#empty()} when not found
     */
    Optional<WardTypeView> findWardTypeByUid(String wardTypeUid);

    /**
     * Check whether a ward bed with the given uid exists (existence guard without a full read).
     *
     * @param wardBedUid the ULID of the ward bed
     * @return {@code true} if a bed with that uid exists in the system
     */
    boolean wardBedExists(String wardBedUid);
}
