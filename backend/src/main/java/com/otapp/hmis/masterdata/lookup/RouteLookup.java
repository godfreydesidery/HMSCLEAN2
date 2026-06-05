package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module check: is a uid a registered, ACTIVE medication administration route?
 *
 * <p>Published as part of {@code masterdata :: lookup} named interface (Spring Modulith,
 * ADR-0008 §1). The implementation is package-private in {@code masterdata.application}.
 *
 * <p>The inpatient module uses this to validate the {@code routeUid} on a
 * {@code MedicationAdministration} (MAR) record: if the route is unknown or inactive, the service
 * rejects with a 422 business-rule error (inc-07 07d, CR-07-MAR). Active is enforced (not just
 * existence) consistent with the honour-active-flag ruling (CR-07-Q9).
 *
 * <p>Net-new — no legacy equivalent (legacy MAR did not exist). inc-07 07d / CR-07-MAR.
 */
public interface RouteLookup {

    /**
     * Return {@code true} if the given uid is a registered AND active administration route.
     *
     * @param routeUid the loose uid of the administration route
     * @return {@code true} if the route exists and is active; {@code false} otherwise
     */
    boolean isActiveRoute(String routeUid);
}
