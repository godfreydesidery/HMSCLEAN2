package com.otapp.hmis.masterdata.lookup;

/**
 * Immutable cross-module projection of a {@code WardBed} master row (inc-07 SEAM-1, ADR-0008 §1).
 *
 * <p>This record is the ONLY representation of a {@code WardBed} that modules outside
 * {@code masterdata} may consume. It carries exactly the fields that the inpatient module
 * needs at admission time: the bed status (EMPTY/WAITING/OCCUPIED), active flag, and the
 * loose uid refs to the owning ward and ward type.
 *
 * <p>{@code wardTypeUid} is resolved through the chain
 * {@code WardBed.ward → Ward.wardType → WardType.uid} — confirmed present on the
 * {@code Ward} entity (Ward.java:54-60). No entity type leaks across the module boundary.
 *
 * <p>Precedented by {@link ServicePriceResult} — same pattern: an immutable record projection
 * in the {@code masterdata.lookup} named interface with no domain type exposure.
 *
 * <p>Legacy citation: bed status values EMPTY/WAITING/OCCUPIED — WardBed.java:43 (free-text,
 * CR-16). inc-07 CR-07-Q3 / ADR-0017 ratified.
 *
 * @param uid         the bed's public ULID
 * @param status      free-text status: one of {@code "EMPTY"}, {@code "WAITING"},
 *                    {@code "OCCUPIED"} (WardBed.java:43 / CR-16 — NO enum)
 * @param active      whether the bed is enabled for admission
 * @param wardUid     loose uid of the owning {@code Ward}
 * @param wardTypeUid loose uid of the ward's {@code WardType}
 *                    (resolved via {@code WardBed.ward.wardType})
 */
public record WardBedView(
        String uid,
        String status,
        boolean active,
        String wardUid,
        String wardTypeUid
) {
}
