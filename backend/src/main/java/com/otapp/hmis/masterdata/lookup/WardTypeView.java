package com.otapp.hmis.masterdata.lookup;

/**
 * Immutable cross-module projection of a {@code WardType} master row (inc-07 SEAM-1, ADR-0008 §1).
 *
 * <p>This record is the ONLY representation of a {@code WardType} that modules outside
 * {@code masterdata} may consume. It carries exactly the fields that the inpatient module
 * needs at ward-charge billing time: the per-stay cash price and the active flag.
 *
 * <p>Insurance overrides live in {@code service_prices} — the cash price here is the
 * WardType-level charge as per CR-12 / legacy WardType.java:39-40 (no per-ward override
 * in PARITY). Callers that need the insurance price must use {@link PriceLookup} directly.
 *
 * <p>Precedented by {@link ServicePriceResult} — same immutable-record projection pattern.
 *
 * <p>Legacy citation: WardType.java:31-46. inc-07 SEAM-1 / ADR-0017 ratified.
 *
 * @param uid    the ward type's public ULID
 * @param price  cash per-stay ward charge (NUMERIC 19,2; BigDecimal replaces legacy {@code double} —
 *               pre-approved data-type change)
 * @param active whether this ward type is enabled
 */
public record WardTypeView(
        String uid,
        java.math.BigDecimal price,
        boolean active
) {
}
