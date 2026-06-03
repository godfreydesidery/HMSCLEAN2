/**
 * Named interface "lookup" — the ONLY types from the {@code iam} module that other modules
 * may reference (Spring Modulith, ADR-0001, ADR-0008, build-spec §5).
 *
 * <p>Other modules must depend on {@link com.otapp.hmis.iam.lookup.IamLookupService} and the
 * projection records here; they must NEVER import {@code com.otapp.hmis.iam.domain} entities
 * directly (enforced by {@code IamNoEntityLeakArchTest}).
 */
@org.springframework.modulith.NamedInterface("lookup")
package com.otapp.hmis.iam.lookup;
