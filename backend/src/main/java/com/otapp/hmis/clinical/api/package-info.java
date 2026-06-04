/**
 * Named interface "api" — the ONLY types from the {@code clinical} module that other
 * modules may reference (Spring Modulith, ADR-0022, ADR-0001, ADR-0008).
 *
 * <p>Exposes the consultation booking + open-work lookup contracts that the
 * {@code registration} module calls (ADR-0022 D3/D5). The one-directional dependency
 * edge is {@code registration → clinical::api}; there is NO reverse edge.
 *
 * <p>Implementation is package-private in {@code clinical.application}.
 */
@org.springframework.modulith.NamedInterface("api")
package com.otapp.hmis.clinical.api;
