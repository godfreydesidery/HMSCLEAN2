/**
 * Named interface "api" — the ONLY {@code pharmacy} types other modules may reference (Spring
 * Modulith, ADR-0008). Exposes {@link com.otapp.hmis.pharmacy.api.PharmacyStockCredit} so the
 * {@code inventory} module's store→pharmacy receiving-note (RN) can credit pharmacy stock at receipt
 * (inc-08b chunk 6). The one-directional edge is {@code inventory → pharmacy::api}; there is NO
 * reverse {@code pharmacy → inventory} edge. Implementation is package-private in
 * {@code pharmacy.application}.
 */
@org.springframework.modulith.NamedInterface("api")
package com.otapp.hmis.pharmacy.api;
