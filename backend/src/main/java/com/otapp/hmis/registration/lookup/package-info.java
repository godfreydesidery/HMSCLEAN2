/**
 * Registration module — public named interface {@code lookup} (inc-07 07a SEAM-B).
 *
 * <p>The ONLY types that modules outside {@code registration} may import from this module
 * (other than {@code shared}) are the interfaces declared in this package. The implementation
 * is hidden in {@code registration.application} (package-private).
 *
 * <p>Current exports:
 * <ul>
 *   <li>{@link com.otapp.hmis.registration.lookup.PatientStatusLookup} — deceased / existence
 *       query for the inpatient deceased-guard (CR-07-deceased-guard, owner-approved).</li>
 * </ul>
 *
 * <p>Callers depend on {@code registration :: lookup} in their {@code @ApplicationModule}
 * {@code allowedDependencies}. They MUST NOT import any type from
 * {@code com.otapp.hmis.registration.domain} or {@code com.otapp.hmis.registration.application}
 * directly (Spring Modulith named-interface contract, ADR-0008 §1).
 *
 * <p>inc-07 07a SEAM-B; first published interface from the {@code registration} module.
 */
@org.springframework.modulith.NamedInterface("lookup")
package com.otapp.hmis.registration.lookup;
