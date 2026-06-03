/**
 * Named interface "lookup" — the ONLY types from the {@code masterdata} module that other
 * modules may reference for pricing resolution (Spring Modulith, ADR-0001, ADR-0008,
 * build-spec §2.2).
 *
 * <p>Billing, clinical, and pharmacy increments depend on {@link com.otapp.hmis.masterdata.lookup.PriceLookup}
 * and the projection records in this package; they MUST NEVER import
 * {@code com.otapp.hmis.masterdata.domain} or {@code com.otapp.hmis.masterdata.application}
 * types directly.
 *
 * <p>The implementation ({@code PriceLookupImpl}) is package-private in
 * {@code masterdata.application}.
 */
@org.springframework.modulith.NamedInterface("lookup")
package com.otapp.hmis.masterdata.lookup;
