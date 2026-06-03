/**
 * Master / reference data (ADR-0006, ADR-0008). Owns {@code CompanyProfile} and all
 * catalog entities (clinics, wards, inventory, clinical, insurance, pricing).
 *
 * <p>Module dependencies:
 * <ul>
 *   <li>Depends on {@code iam} (via {@code iam::lookup} named interface) for clinician-role
 *       assertions in future affiliation checks (CR-08). No reverse edge.</li>
 *   <li>Exposes {@code masterdata::lookup} named interface ({@code masterdata.lookup} package)
 *       containing {@link com.otapp.hmis.masterdata.lookup.PriceLookup},
 *       {@link com.otapp.hmis.masterdata.lookup.ServiceKind}, and
 *       {@link com.otapp.hmis.masterdata.lookup.ServicePriceResult} for consumption by billing,
 *       clinical, and pharmacy increments (build-spec §2.2, ADR-0008).</li>
 * </ul>
 *
 * <p>{@code allowedDependencies = "iam :: lookup"} — masterdata may call the {@code iam} module's
 * {@code lookup} NAMED INTERFACE only (IamLookupService, ClinicianAffiliationService, UserSummary);
 * it must not reference any other iam type or any other module's internals.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "iam :: lookup")
package com.otapp.hmis.masterdata;
